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

package org.commrogue.basicqparsers;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrException;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the three parsers through a real Solr core, which is the one thing the unit tests cannot
 * do: it proves they are registered in {@code solrconfig.xml}, load from the module jar, and return
 * the right documents.
 */
public class BasicQParsersIntegrationTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");

    assertU(
        adoc(
            "id", "1",
            "sku", "ABC-123",
            "title", "hello two words",
            "title_en", "hello world",
            "title_fr", "bonjour monde",
            "price", "15",
            "published_at", "2024-06-01T00:00:00Z"));
    assertU(
        adoc(
            "id", "2",
            "sku", "XYZ-999",
            "title", "goodbye",
            "price", "12",
            "published_at", "2023-01-01T00:00:00Z"));
    assertU(
        adoc(
            "id", "3",
            "sku", "DEF-456",
            "title", "hello there",
            "price", "50",
            "published_at", "2025-01-01T00:00:00Z"));
    assertU(commit());
  }

  @Test
  public void testExactMatchesWholeStringOnly() {
    assertQ(
        req("q", "{!basic_exact field=sku value=ABC-123}"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");

    // Not analyzed and not tokenized: a prefix of the value must not match.
    assertQ(req("q", "{!basic_exact field=sku value=ABC}"), "//result[@numFound='0']");
  }

  @Test
  public void testExactOnNumericField() {
    assertQ(
        req("q", "{!basic_exact field=price value=15}"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");
  }

  @Test
  public void testRangeBoundsAreInclusiveOnlyWhenAsked() {
    // price: doc2=12, doc1=15, doc3=50
    assertQ(req("q", "{!basic_range field=price gte=10 lt=20}"), "//result[@numFound='2']");
    assertQ(
        req("q", "{!basic_range field=price gt=15}"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='3']");
    assertQ(req("q", "{!basic_range field=price gte=15}"), "//result[@numFound='2']");
  }

  @Test
  public void testRangeOnDateField() {
    assertQ(
        req("q", "{!basic_range field=published_at gt=2024-01-01T00:00:00Z}"),
        "//result[@numFound='2']");
  }

  @Test
  public void testTextTokenizesTermsAndRequiresWholePhrase() {
    // 'hello' is a SHOULD clause, so doc3 ("hello there") matches too -- but doc1 also satisfies
    // the "two words" phrase, so it scores higher and sorts first.
    assertQ(
        req("q", "{!basic_text field=title}(hello \"two words\")"),
        "//result[@numFound='2']",
        "//result/doc[1]/str[@name='id'][.='1']");

    // The phrase is a MUST: its terms must all be present, in order.
    assertQ(req("q", "{!basic_text field=title}(\"words two\")"), "//result[@numFound='0']");
  }

  @Test
  public void testAliasFansOutAcrossFields() {
    // 'bonjour' lives in title_fr, never in title -- so without the alias there is nothing to find.
    assertQ(req("q", "{!basic_text field=title}(bonjour)"), "//result[@numFound='0']");

    // /aliased-select defaults f.title.qf to "title_en title_fr", which fans the query out.
    assertQ(
        req("qt", "/aliased-select", "q", "{!basic_text field=title}(bonjour)"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");
  }

  @Test
  public void testSyntaxErrorsAreBadRequests() {
    assertQEx(
        "missing 'field' should be rejected",
        req("q", "{!basic_exact value=ABC-123}"),
        SolrException.ErrorCode.BAD_REQUEST);
    assertQEx(
        "missing 'value' should be rejected",
        req("q", "{!basic_exact field=sku}"),
        SolrException.ErrorCode.BAD_REQUEST);
    assertQEx(
        "a range needs at least one bound",
        req("q", "{!basic_range field=price}"),
        SolrException.ErrorCode.BAD_REQUEST);
    assertQEx(
        "basic_text needs a tokenized field",
        req("q", "{!basic_text field=sku}(hello)"),
        SolrException.ErrorCode.BAD_REQUEST);
  }
}
