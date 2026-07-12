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

package org.commrogue.multianalysis;

import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the whole feature through a real core: the update chain tags the copies, and the catchall
 * field type resolves each tag back to that field's index analyzer.
 *
 * <p>Booting the core at all is half the test. The analyzer map can only be built once the schema's
 * fields are loaded, which is why {@link MultiAnalysisTextField} is {@code SchemaAware}; built in
 * {@code init()} instead, it would come out empty and fail the core here.
 */
public class MultiAnalysisIntegrationTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");

    // Same text in both fields. 'title' is whitespace+lowercase, 'text_stemmed' adds a Porter
    // stemmer, so the two copies of it in 'catchall' must come out as different terms.
    updateJ(
        jsonAdd(sdoc("id", "1", "title", "Walking Parks", "text_stemmed", "Walking Parks")),
        params("update.chain", "multi-analysis-copy"));

    // The control: only the unstemmed source, so only the unstemmed copy reaches catchall.
    updateJ(
        jsonAdd(sdoc("id", "2", "title", "Walking Parks")),
        params("update.chain", "multi-analysis-copy"));

    assertU(commit());
  }

  /**
   * The point of the whole thing: one field, one value's worth of text, two analyzers. The query
   * analyzer does not stem, so 'walk' can only match a copy that was stemmed at index time, and
   * 'walking' only one that was not. Both matching on doc 1 means both analyzers really ran; a
   * dispatch that silently fell back to one analyzer fails exactly one of these.
   */
  @Test
  public void analyzesEachCopyWithItsSourceFieldsAnalyzer() {
    assertQ(
        "'walk' matches only the Porter-stemmed copy, so only the doc that had text_stemmed",
        req("q", "catchall:walk", "fl", "id", "sort", "id asc"),
        "//result[@numFound='1']",
        "//str[@name='id'][.='1']");

    assertQ(
        "'walking' matches the unstemmed copy, which both docs have via title",
        req("q", "catchall:walking", "fl", "id", "sort", "id asc"),
        "//result[@numFound='2']");
  }

  /** The tag is consumed off the reader, so it must never reach the index as a term. */
  @Test
  public void doesNotIndexTheAnalyzerTag() {
    assertQ(req("q", "catchall:title"), "//result[@numFound='0']");
    assertQ(req("q", "catchall:text_stemmed"), "//result[@numFound='0']");
  }

  /** Without the chain there are no tagged values, so nothing lands in the catchall. */
  @Test
  public void copiesOnlyThroughTheChain() throws Exception {
    updateJ(jsonAdd(sdoc("id", "3", "title", "Walking Parks")), null);
    assertU(commit());

    assertQ(req("q", "catchall:walking", "fl", "id"), "//result[@numFound='2']");
    assertQ(req("q", "id:3"), "//result[@numFound='1']");
  }
}
