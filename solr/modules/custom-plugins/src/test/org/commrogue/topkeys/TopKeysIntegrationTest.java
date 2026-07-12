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

package org.commrogue.topkeys;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrException;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the cache through a real Solr core, which is the one thing the unit tests cannot do: it
 * proves the cache is instantiable as the {@code filterCache} from {@code solrconfig.xml}, that it
 * counts real {@code fq} traffic, and that the handler finds it in the core's info registry.
 */
public class TopKeysIntegrationTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");

    assertU(adoc("id", "1", "sku", "AAA", "title", "hello"));
    assertU(adoc("id", "2", "sku", "BBB", "title", "hello"));
    assertU(adoc("id", "3", "sku", "CCC", "title", "goodbye"));
    assertU(commit());

    // Everything below shares one searcher, and so one filterCache -- do not commit past this
    // point, or the cache (and its counts) is replaced along with the searcher.
    //
    // Each repetition uses a different q so that the queryResultCache misses every time and the
    // filter actually gets consulted; only the fq is held constant. The three filters are used a
    // different number of times, so the expected ranking is unambiguous.
    query("sku:AAA", "id:1", "id:2", "id:3");
    query("sku:BBB", "id:1", "id:2");
    query("sku:CCC", "id:1");
  }

  private static void query(String fq, String... queries) {
    for (String q : queries) {
      assertQ(req("q", q, "fq", fq));
    }
  }

  @Test
  public void testRanksFilterQueriesByHowOftenTheyWereUsed() {
    assertQ(
        req("qt", "/topkeys", "cacheName", "filterCache"),
        "count(//arr[@name='topKeys']/lst)=3",
        // Hottest first, numbered from 1, with the filter query itself as the key.
        "//arr[@name='topKeys']/lst[1]/int[@name='index'][.=1]",
        "//arr[@name='topKeys']/lst[1]/str[@name='key'][.='sku:AAA']",
        "//arr[@name='topKeys']/lst[1]/long[@name='hits'][.=3]",
        "//arr[@name='topKeys']/lst[2]/int[@name='index'][.=2]",
        "//arr[@name='topKeys']/lst[2]/str[@name='key'][.='sku:BBB']",
        "//arr[@name='topKeys']/lst[2]/long[@name='hits'][.=2]",
        "//arr[@name='topKeys']/lst[3]/int[@name='index'][.=3]",
        "//arr[@name='topKeys']/lst[3]/str[@name='key'][.='sku:CCC']",
        "//arr[@name='topKeys']/lst[3]/long[@name='hits'][.=1]",
        // The cached values really are the filterCache's DocSets, and they are charged some memory.
        "//arr[@name='topKeys']/lst[1]/str[@name='type'][contains(.,'DocSet')]",
        "//arr[@name='topKeys']/lst[1]/long[@name='size'][.>0]");
  }

  @Test
  public void testListsTheTrackingCachesWhenNoCacheIsNamed() {
    assertQ(
        req("qt", "/topkeys"),
        "count(//arr[@name='trackingCaches']/str)=1",
        "//arr[@name='trackingCaches']/str[.='filterCache']",
        // Without a cacheName there is nothing to report, only the menu.
        "count(//arr[@name='topKeys'])=0");
  }

  @Test
  public void testRejectsACacheThatIsNotTracking() {
    // queryResultCache exists in this core, but it is a stock CaffeineCache with no hit tracking,
    // so it must be rejected rather than reported as empty.
    assertQEx(
        "queryResultCache does not track keys",
        req("qt", "/topkeys", "cacheName", "queryResultCache"),
        SolrException.ErrorCode.BAD_REQUEST);

    assertQEx(
        "no such cache",
        req("qt", "/topkeys", "cacheName", "nonexistentCache"),
        SolrException.ErrorCode.BAD_REQUEST);
  }
}
