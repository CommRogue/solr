/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search;

import static org.apache.solr.core.CoreContainer.ALLOW_PATHS_SYSPROP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.LRUQueryCache;
import org.apache.lucene.search.QueryCachingPolicy;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.apache.solr.util.RefCounted;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Tests the node-level Lucene segment query cache, enabled via {@code enableSegmentQueryCache} plus
 * {@code segmentQueryCacheMaxRam} in solr.xml: one {@link LRUQueryCache} shared by all cores, with
 * a per-core {@link QueryCachingPolicy} that survives searcher reopens.
 */
public class TestSegmentQueryCache extends SolrTestCaseJ4 {

  @ClassRule
  public static EmbeddedSolrServerTestRule solrTestRule = new EmbeddedSolrServerTestRule();

  @BeforeClass
  public static void setupSolrHome() throws Exception {
    Path home = createTempDir("home");
    Path configSet = createTempDir("configSet");
    System.setProperty(ALLOW_PATHS_SYSPROP, configSet.toAbsolutePath().toString());
    Files.writeString(
        home.resolve("solr.xml"),
        "<solr>\n"
            + "  <str name=\"allowPaths\">${"
            + ALLOW_PATHS_SYSPROP
            + ":}</str>\n"
            + "  <bool name=\"enableSegmentQueryCache\">true</bool>\n"
            + "  <str name=\"segmentQueryCacheMaxRam\">1m</str>\n"
            + "</solr>");

    solrTestRule.startSolr(home);

    copyMinConf(configSet.toFile());
    solrTestRule.newCollection("core1").withConfigSet(configSet.toString()).create();
    solrTestRule.newCollection("core2").withConfigSet(configSet.toString()).create();
    solrTestRule.newCollection("core3").withConfigSet(configSet.toString()).create();
  }

  @Test
  public void testCacheSharedAcrossCoresWithPerCorePolicy() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    LRUQueryCache segmentCache = cc.getSegmentQueryCache();
    assertNotNull("cache should be enabled via enableSegmentQueryCache", segmentCache);
    try (SolrCore core1 = cc.getCore("core1");
        SolrCore core2 = cc.getCore("core2")) {
      assertNotSame(
          "each core has its own caching policy",
          core1.getSegmentQueryCachingPolicy(),
          core2.getSegmentQueryCachingPolicy());
      RefCounted<SolrIndexSearcher> s1 = core1.getSearcher();
      RefCounted<SolrIndexSearcher> s2 = core2.getSearcher();
      try {
        assertSame(segmentCache, s1.get().getQueryCache());
        assertSame(segmentCache, s2.get().getQueryCache());
        assertSame(core1.getSegmentQueryCachingPolicy(), s1.get().getQueryCachingPolicy());
        assertSame(core2.getSegmentQueryCachingPolicy(), s2.get().getQueryCachingPolicy());
      } finally {
        s1.decref();
        s2.decref();
      }
    }
  }

  @Test
  public void testPolicySurvivesSearcherReopen() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    SolrClient client = solrTestRule.getSolrClient("core1");
    try (SolrCore core = cc.getCore("core1")) {
      QueryCachingPolicy policy = core.getSegmentQueryCachingPolicy();
      SolrIndexSearcher before;
      RefCounted<SolrIndexSearcher> ref = core.getSearcher();
      try {
        before = ref.get();
        assertSame(policy, before.getQueryCachingPolicy());
      } finally {
        ref.decref();
      }

      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", "1");
      client.add(doc);
      client.commit();

      ref = core.getSearcher();
      try {
        assertNotSame("commit should have opened a new searcher", before, ref.get());
        assertSame(policy, ref.get().getQueryCachingPolicy());
        assertSame(cc.getSegmentQueryCache(), ref.get().getQueryCache());
      } finally {
        ref.decref();
      }
    }
  }

  @Test
  public void testRealtimeSearcherHasCache() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    try (SolrCore core = cc.getCore("core2")) {
      RefCounted<SolrIndexSearcher> rt = core.getRealtimeSearcher();
      try {
        assertSame(cc.getSegmentQueryCache(), rt.get().getQueryCache());
      } finally {
        rt.decref();
      }
    }
  }

  /**
   * The wiring tests above only prove the cache is attached. This one proves it actually caches a
   * filter query end-to-end, and pins the two thresholds that make it do nothing if you get them
   * wrong: Lucene's {@code LRUQueryCache(maxSize, maxRamBytesUsed)} only caches segments of at
   * least 10k docs (MinSegmentSizePredicate), and {@link
   * org.apache.lucene.search.UsageTrackingQueryCachingPolicy} only caches a query once it has been
   * seen several times.
   */
  @Test
  public void testFilterQueryIsActuallyCached() throws Exception {
    CoreContainer cc = solrTestRule.getCoreContainer();
    SolrClient client = solrTestRule.getSolrClient("core3");

    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < 12_000; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", Integer.toString(i));
      doc.setField("cat_s", (i % 2 == 0) ? "even" : "odd");
      docs.add(doc);
    }
    client.add(docs);
    client.commit();
    // Force one big segment: the test framework randomizes the merge policy, and a segment under
    // 10k docs is never cached.
    client.optimize();

    try (SolrCore core = cc.getCore("core3")) {
      RefCounted<SolrIndexSearcher> ref = core.getSearcher();
      try {
        List<LeafReaderContext> leaves = ref.get().getIndexReader().leaves();
        assertEquals("expected a single segment after optimize", 1, leaves.size());
        assertTrue(
            "segment must clear MinSegmentSizePredicate's 10k-doc floor to be cacheable",
            leaves.get(0).reader().maxDoc() >= 10_000);
      } finally {
        ref.decref();
      }
    }

    LRUQueryCache segmentCache = cc.getSegmentQueryCache();
    long hitsBefore = segmentCache.getHitCount();

    // A prefix query, not a plain term query: UsageTrackingQueryCachingPolicy never caches
    // TermQuery ("already plenty fast"), so a single-term fq would never produce an entry.
    SolrQuery q = new SolrQuery("*:*").addFilterQuery("cat_s:ev*");
    for (int i = 0; i < 25; i++) {
      assertEquals(6000L, client.query(q).getResults().getNumFound());
    }

    assertTrue(
        "filter query should have been cached, cacheSize=" + segmentCache.getCacheSize(),
        segmentCache.getCacheSize() > 0);
    assertTrue(
        "cached filter should have been reused, hits=" + segmentCache.getHitCount(),
        segmentCache.getHitCount() > hitsBefore);
  }

  @Test
  public void testDisabledByDefault() throws Exception {
    assertNoSegmentQueryCache("<solr/>");
  }

  /** The size alone must not enable the cache; the flag gates it. */
  @Test
  public void testMaxRamWithoutFlagStaysDisabled() throws Exception {
    assertNoSegmentQueryCache(
        "<solr><str name=\"segmentQueryCacheMaxRam\">1m</str></solr>",
        "<solr><bool name=\"enableSegmentQueryCache\">false</bool>"
            + "<str name=\"segmentQueryCacheMaxRam\">1m</str></solr>",
        // ... and the flag alone must not either, with no size to give the cache.
        "<solr><bool name=\"enableSegmentQueryCache\">true</bool></solr>");
  }

  private void assertNoSegmentQueryCache(String... solrXmls) throws Exception {
    for (String solrXml : solrXmls) {
      CoreContainer cc = new CoreContainer(SolrXmlConfig.fromString(createTempDir(), solrXml));
      try {
        cc.load();
        assertNull(solrXml, cc.getSegmentQueryCache());
      } finally {
        cc.shutdown();
      }
    }
  }
}
