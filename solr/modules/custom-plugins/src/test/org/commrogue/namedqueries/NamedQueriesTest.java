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
package org.commrogue.namedqueries;

import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives both consumers of the {@code name=} local param through a real Solr core: the
 * hl.matchedQueries highlighter and the matched-queries component. Between them they cover the
 * whole path -- {@code name=} recorded by the core QParser hook, then read back by each plugin.
 */
public class NamedQueriesTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");

    assertU(
        adoc(
            "id", "1",
            "text", "second document",
            "text_stemmed", "Walked in The Park"));
    assertU(adoc("id", "2", "text", "another document"));
    assertU(commit());
  }

  /** Asserts the response contains the snippet, escaped the way Solr writes XML char data. */
  private static void assertContains(String response, String snippet) {
    String escaped = snippet.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    assertTrue(
        "expected:\n" + snippet + "\n\nin response:\n" + response, response.contains(escaped));
  }

  private static String hlQuery(String... params) throws Exception {
    return h.query(req(params, "hl", "true", "hl.method", "unified", "hl.matchedQueries", "true"));
  }

  // ---- hl.matchedQueries -------------------------------------------------

  /** A stemmed phrase: the analyzed terms differ from the text they highlight. */
  @Test
  public void testPhraseWithStemming() throws Exception {
    String response =
        hlQuery(
            "q", "{!field f=text_stemmed name=id_1 v='Walking in The Park'}",
            "hl.fl", "text_stemmed");
    assertContains(response, "\"name\":\"id_1\"");
    assertContains(response, "\"analyzed\":\"walk in the park\"");
    assertContains(response, "data-matched-queries=");
  }

  /** A named clause is attributed; an unnamed one alongside it keeps the plain pre-tag. */
  @Test
  public void testNamedAndUnnamedSubQueries() throws Exception {
    String response =
        hlQuery(
            "q",
            "{!bool must='{!field f=text name=one v=document}'"
                + " should='{!field f=text v=second}'}",
            "hl.fl",
            "text");
    assertContains(
        response,
        "<em data-matched-queries='[{\"name\":\"one\",\"original\":\"text:document\","
            + "\"analyzed\":\"document\"}]'>document</em>");
    assertContains(response, "<em>second</em>");
  }

  /** One term shared by two named queries is attributed to both. */
  @Test
  public void testTermSharedByTwoNamedQueries() throws Exception {
    String response =
        hlQuery(
            "q",
            "{!bool must='{!field f=text name=one v=document}'"
                + " should='{!field f=text name=two v=document}'}",
            "hl.fl",
            "text");
    assertContains(response, "{\"name\":\"one\",\"original\":\"text:document\"");
    assertContains(response, "{\"name\":\"two\",\"original\":\"text:document\"");
  }

  /** Off by default: the stock highlighter output is untouched. */
  @Test
  public void testDisabledByDefault() throws Exception {
    String response =
        h.query(
            req(
                "q", "{!field f=text name=one v=document}",
                "hl", "true",
                "hl.method", "unified",
                "hl.fl", "text"));
    assertContains(response, "<em>document</em>");
    assertFalse(response.contains("data-matched-queries"));
  }

  // ---- matched-queries component -----------------------------------------

  /** Doc 1 matches both named clauses; doc 2 only the 'doc' one. */
  @Test
  public void testMatchedQueriesComponent() {
    assertQ(
        req(
            "q",
            "{!bool should='{!field f=text name=doc v=document}'"
                + " should='{!field f=text name=sec v=second}'}",
            "matched_queries",
            "true"),
        "//lst[@name='matched_queries_per_hit']/arr[@name='1']/str[.='doc']",
        "//lst[@name='matched_queries_per_hit']/arr[@name='1']/str[.='sec']",
        "//lst[@name='matched_queries_per_hit']/arr[@name='2']/str[.='doc']",
        "count(//lst[@name='matched_queries_per_hit']/arr[@name='2']/str)=1",
        "//lst[@name='matched_queries_summary']/arr[@name='doc']/str[.='1']",
        "//lst[@name='matched_queries_summary']/arr[@name='doc']/str[.='2']",
        "count(//lst[@name='matched_queries_summary']/arr[@name='sec']/str)=1");
  }

  /** Off by default: no matched-queries sections in the response. */
  @Test
  public void testComponentDisabledByDefault() {
    assertQ(
        req("q", "{!field f=text name=doc v=document}"),
        "count(//lst[@name='matched_queries_per_hit'])=0",
        "count(//lst[@name='matched_queries_summary'])=0");
  }
}
