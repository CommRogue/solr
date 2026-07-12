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

package org.commrogue.routingfield;

import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the routing field type through a real Solr core: the 'routed' field holds nothing, and
 * every query against it has to come back with documents matched on sku, email or title.
 *
 * <p>See {@code schema.xml}: {@code routes="<NUM>=sku,<EMAIL>=email"}, {@code defaultField=title}.
 */
public class AttributeRoutingTextFieldTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");

    // ClassicTokenizer types 'ab-123' as <NUM> (digits joined by punctuation) but a bare '12345' as
    // <ALPHANUM>, so the sku values here are what the <NUM> route actually fires on.
    assertU(
        adoc(
            "id", "1",
            "sku", "ab-123",
            "email", "user@example.com",
            "title", "hello two words"));
    // The same sku text, but in the title. A <NUM> query must route to sku and must not find this
    // one -- this is the assertion the original, never-firing routing guard fails.
    assertU(
        adoc(
            "id", "2",
            "sku", "xy-999",
            "email", "other@example.com",
            "title", "hello ab-123"));
    assertU(commit());
  }

  @Test
  public void testNumericTokenRoutesToSku() {
    assertQ(
        req("q", "routed:ab-123"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");
  }

  @Test
  public void testEmailTokenRoutesToEmail() {
    assertQ(
        req("q", "routed:user@example.com"),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");
  }

  @Test
  public void testUnroutedTokenFallsBackToDefaultField() {
    assertQ(req("q", "routed:hello"), "//result[@numFound='2']");
  }

  @Test
  public void testPhraseOnDefaultField() {
    assertQ(
        req("q", "routed:\"hello two words\""),
        "//result[@numFound='1']",
        "//result/doc/str[@name='id'][.='1']");

    // A phrase, not a bag of terms: the same words out of order must not match.
    assertQ(req("q", "routed:\"two hello words\""), "//result[@numFound='0']");
  }

  @Test
  public void testProxyFieldItselfHoldsNothing() {
    // Nothing was ever indexed into 'routed' -- the hits above are all on the routed-to fields.
    assertQ(req("q", "*:*", "fl", "routed"), "//result[@numFound='2']", "count(//arr)=0");
  }
}
