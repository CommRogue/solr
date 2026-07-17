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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.search.SyntaxError;
import org.junit.jupiter.api.Test;

class BasicQParserTest {

  @Test
  void parseExpandsAliasesIntoShouldBooleanQuery() throws Exception {
    var requestParams = new ModifiableSolrParams().set("f.title.qf", "other_title");
    try (var req = newRequest(requestParams)) {
      req.getContext().put("aliases", Map.of("title", new String[] {"title_en", "title_fr"}));
      var parser = new RecordingBasicQParser(localParams("field", "title"), requestParams, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertIterableEquals(List.of("title_en", "title_fr"), parser.fields());
      assertEquals(2, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en");
      assertClause(booleanQuery.clauses().get(1), "title_fr");
    }
  }

  @Test
  void parseUsesOriginalFieldWhenAliasEntryIsMissing() throws Exception {
    try (var req = newRequest()) {
      var parser = new RecordingBasicQParser(localParams("field", "title"), req);

      var query = parser.parse();

      assertEquals(new TermQuery(new Term("title", "value")), query);
      assertIterableEquals(List.of("title"), parser.fields());
      assertEquals(Map.of(), aliases(req));
    }
  }

  @Test
  void parseBuildsAliasesFromRequestParamsAndCachesThem() throws Exception {
    var requestParams = new ModifiableSolrParams().set("f.title.qf", "title_en title_fr");
    try (var req = newRequest(requestParams)) {
      var parser = new RecordingBasicQParser(localParams("field", "title"), requestParams, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertIterableEquals(List.of("title_en", "title_fr"), parser.fields());
      assertEquals(2, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en");
      assertClause(booleanQuery.clauses().get(1), "title_fr");
      assertArrayEquals(new String[] {"title_en", "title_fr"}, aliases(req).get("title"));
    }
  }

  @Test
  void parseResolvesNestedAliasesFromRequestParams() throws Exception {
    var requestParams =
        new ModifiableSolrParams()
            .set("f.title.qf", "headline summary")
            .set("f.headline.qf", "title_en title_fr")
            .set("f.summary.qf", "body_en title_en");
    try (var req = newRequest(requestParams)) {
      var parser = new RecordingBasicQParser(localParams("field", "title"), requestParams, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertIterableEquals(List.of("title_en", "title_fr", "body_en"), parser.fields());
      assertEquals(3, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en");
      assertClause(booleanQuery.clauses().get(1), "title_fr");
      assertClause(booleanQuery.clauses().get(2), "body_en");
      assertArrayEquals(
          new String[] {"title_en", "title_fr", "body_en"}, aliases(req).get("title"));
      assertArrayEquals(new String[] {"title_en", "title_fr"}, aliases(req).get("headline"));
      assertArrayEquals(new String[] {"body_en", "title_en"}, aliases(req).get("summary"));
    }
  }

  @Test
  void parseReusesCachedAliasesAcrossParserInstances() throws Exception {
    var firstParams = new ModifiableSolrParams().set("f.title.qf", "title_en");
    try (var req = newRequest(firstParams)) {
      var firstParser = new RecordingBasicQParser(localParams("field", "title"), firstParams, req);

      firstParser.parse();

      var secondParams = new ModifiableSolrParams().set("f.title.qf", "title_fr");
      var secondParser =
          new RecordingBasicQParser(localParams("field", "title"), secondParams, req);

      var query = secondParser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertIterableEquals(List.of("title_en"), secondParser.fields());
      assertEquals(1, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en");
      assertArrayEquals(new String[] {"title_en"}, aliases(req).get("title"));
    }
  }

  @Test
  void parseRejectsCyclicAliases() {
    var requestParams =
        new ModifiableSolrParams()
            .set("f.title.qf", "headline")
            .set("f.headline.qf", "summary")
            .set("f.summary.qf", "title");
    try (var req = newRequest(requestParams)) {
      var parser = new RecordingBasicQParser(localParams("field", "title"), requestParams, req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals(
          "Cyclic alias definition detected: title -> headline -> summary -> title",
          error.getMessage());
      assertNull(req.getContext().get("aliases"));
    }
  }

  @Test
  void parseRequiresFieldLocalParam() {
    try (var req = newRequest()) {
      var parser = new RecordingBasicQParser(new ModifiableSolrParams(), req);

      assertThrows(SyntaxError.class, parser::parse);
    }
  }

  private static void assertClause(BooleanClause clause, String field) {
    assertEquals(BooleanClause.Occur.SHOULD, clause.occur());
    assertEquals(new TermQuery(new Term(field, "value")), clause.query());
  }

  private static ModifiableSolrParams localParams(String name, String value) {
    return new ModifiableSolrParams().set(name, value);
  }

  private static SolrQueryRequest newRequest() {
    return newRequest(new ModifiableSolrParams());
  }

  private static SolrQueryRequest newRequest(ModifiableSolrParams params) {
    return new SolrQueryRequestBase(null, params) {};
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String[]> aliases(SolrQueryRequest req) {
    return (Map<String, String[]>) req.getContext().get("aliases");
  }

  private static final class RecordingBasicQParser extends BasicQParser {
    private final List<String> fields = new ArrayList<>();

    private RecordingBasicQParser(ModifiableSolrParams localParams, SolrQueryRequest req) {
      this(localParams, new ModifiableSolrParams(), req);
    }

    private RecordingBasicQParser(
        ModifiableSolrParams localParams, ModifiableSolrParams params, SolrQueryRequest req) {
      super("value", localParams, params, req);
    }

    @Override
    protected Query parseImpl(String field) {
      fields.add(field);
      return new TermQuery(new Term(field, "value"));
    }

    private List<String> fields() {
      return fields;
    }
  }
}
