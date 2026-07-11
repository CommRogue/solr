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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortField.Type;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Version;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.TextResponseWriter;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.NumberType;
import org.apache.solr.schema.PrimitiveFieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.uninverting.UninvertingReader;
import org.junit.jupiter.api.Test;

class BasicRangeQParserTest {
  @Test
  void parseBuildsInclusiveNumericRangeQuery() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.INTEGER);
    var schema = schema(Map.of("price", new SchemaField("price", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null,
              localParams("field", "price", "gte", "10", "lte", "20"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      assertEquals(markerQuery("price", "10", "20", true, true), query);
      assertEquals(
          List.of(new RangeInvocation("price", "10", "20", true, true)), fieldType.invocations());
    }
  }

  @Test
  void parseBuildsExclusiveDateRangeQuery() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.DATE);
    var schema = schema(Map.of("published_at", new SchemaField("published_at", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null,
              localParams(
                  "field",
                  "published_at",
                  "gt",
                  "2024-01-01T00:00:00Z",
                  "lt",
                  "2024-12-31T23:59:59Z"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      assertEquals(
          markerQuery("published_at", "2024-01-01T00:00:00Z", "2024-12-31T23:59:59Z", false, false),
          query);
      assertEquals(
          List.of(
              new RangeInvocation(
                  "published_at", "2024-01-01T00:00:00Z", "2024-12-31T23:59:59Z", false, false)),
          fieldType.invocations());
    }
  }

  @Test
  void parseBuildsLowerBoundOnlyRangeQuery() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.LONG);
    var schema = schema(Map.of("timestamp", new SchemaField("timestamp", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null,
              localParams("field", "timestamp", "gte", "100"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      assertEquals(markerQuery("timestamp", "100", null, true, false), query);
      assertEquals(
          List.of(new RangeInvocation("timestamp", "100", null, true, false)),
          fieldType.invocations());
    }
  }

  @Test
  void parseBuildsUpperBoundOnlyRangeQuery() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.DOUBLE);
    var schema = schema(Map.of("rating", new SchemaField("rating", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null, localParams("field", "rating", "lt", "4.5"), new ModifiableSolrParams(), req);

      var query = parser.parse();

      assertEquals(markerQuery("rating", null, "4.5", false, false), query);
      assertEquals(
          List.of(new RangeInvocation("rating", null, "4.5", false, false)),
          fieldType.invocations());
    }
  }

  @Test
  void parseRequiresAtLeastOneBound() {
    var fieldType = new RecordingFieldType(NumberType.FLOAT);
    var schema = schema(Map.of("score", new SchemaField("score", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null, localParams("field", "score"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("At least one of gt, gte, lt, or lte must be specified", error.getMessage());
    }
  }

  @Test
  void parseRejectsConflictingLowerBounds() {
    var fieldType = new RecordingFieldType(NumberType.INTEGER);
    var schema = schema(Map.of("price", new SchemaField("price", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null,
              localParams("field", "price", "gt", "10", "gte", "11"),
              new ModifiableSolrParams(),
              req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Only one of gt or gte may be specified", error.getMessage());
    }
  }

  @Test
  void parseRejectsConflictingUpperBounds() {
    var fieldType = new RecordingFieldType(NumberType.INTEGER);
    var schema = schema(Map.of("price", new SchemaField("price", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null,
              localParams("field", "price", "lt", "19", "lte", "20"),
              new ModifiableSolrParams(),
              req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Only one of lt or lte may be specified", error.getMessage());
    }
  }

  @Test
  void parseRejectsUnsupportedFieldTypes() {
    var fieldType = new RecordingFieldType(null);
    var schema = schema(Map.of("title", new SchemaField("title", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null, localParams("field", "title", "gte", "a"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Field 'title' must use a numeric or date field type", error.getMessage());
    }
  }

  @Test
  void parseRejectsUnknownFields() {
    var schema = schema(Map.of());
    try (var req = newRequest(schema)) {
      var parser =
          new BasicRangeQParser(
              null, localParams("field", "missing", "gte", "1"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Unknown field: missing", error.getMessage());
    }
  }

  @Test
  void parseExpandsAliasesIntoRangeQueries() throws Exception {
    var titleEn = new RecordingFieldType(NumberType.INTEGER);
    var titleFr = new RecordingFieldType(NumberType.INTEGER);
    var schema =
        schema(
            Map.of(
                "title_en", new SchemaField("title_en", titleEn),
                "title_fr", new SchemaField("title_fr", titleFr)));
    var params = new ModifiableSolrParams().set("f.price.qf", "title_en title_fr");
    try (var req = newRequest(schema, params)) {
      var parser =
          new BasicRangeQParser(
              null, localParams("field", "price", "gt", "10", "lte", "20"), params, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertEquals(2, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en", "10", "20", false, true);
      assertClause(booleanQuery.clauses().get(1), "title_fr", "10", "20", false, true);
      assertEquals(
          List.of(new RangeInvocation("title_en", "10", "20", false, true)), titleEn.invocations());
      assertEquals(
          List.of(new RangeInvocation("title_fr", "10", "20", false, true)), titleFr.invocations());
    }
  }

  @Test
  void pluginCreatesBasicRangeParser() {
    var plugin = new BasicRangeQParserPlugin();
    try (var req =
        newRequest(
            schema(
                Map.of(
                    "price",
                    new SchemaField("price", new RecordingFieldType(NumberType.INTEGER)))))) {

      assertEquals("basic_range", BasicRangeQParserPlugin.NAME);
      assertInstanceOf(
          BasicRangeQParser.class,
          plugin.createParser(
              null, localParams("field", "price", "gte", "10"), new ModifiableSolrParams(), req));
    }
  }

  private static void assertClause(
      BooleanClause clause,
      String field,
      String lower,
      String upper,
      boolean lowerInclusive,
      boolean upperInclusive) {
    assertEquals(BooleanClause.Occur.SHOULD, clause.getOccur());
    assertEquals(
        markerQuery(field, lower, upper, lowerInclusive, upperInclusive), clause.getQuery());
  }

  private static Query markerQuery(
      String field, String lower, String upper, boolean lowerInclusive, boolean upperInclusive) {
    return new TermQuery(
        new Term(field, lower + "|" + upper + "|" + lowerInclusive + "|" + upperInclusive));
  }

  private static ModifiableSolrParams localParams(String... pairs) {
    var params = new ModifiableSolrParams();
    for (var i = 0; i < pairs.length; i += 2) {
      params.set(pairs[i], pairs[i + 1]);
    }
    return params;
  }

  private static TestIndexSchema schema(Map<String, SchemaField> fields) {
    return new TestIndexSchema(fields);
  }

  private static SolrQueryRequest newRequest(TestIndexSchema schema) {
    return newRequest(schema, new ModifiableSolrParams());
  }

  private static SolrQueryRequest newRequest(TestIndexSchema schema, ModifiableSolrParams params) {
    return new TestSolrQueryRequest(schema, params);
  }

  private record RangeInvocation(
      String field, String lower, String upper, boolean lowerInclusive, boolean upperInclusive) {}

  private static final class RecordingFieldType extends PrimitiveFieldType {
    private final NumberType numberType;
    private final List<RangeInvocation> invocations = new ArrayList<>();

    private RecordingFieldType(NumberType numberType) {
      this.numberType = numberType;
    }

    @Override
    public NumberType getNumberType() {
      return numberType;
    }

    @Override
    public Query getRangeQuery(
        QParser parser,
        SchemaField field,
        String min,
        String max,
        boolean minInclusive,
        boolean maxInclusive) {
      invocations.add(new RangeInvocation(field.getName(), min, max, minInclusive, maxInclusive));
      return markerQuery(field.getName(), min, max, minInclusive, maxInclusive);
    }

    @Override
    public UninvertingReader.Type getUninversionType(SchemaField sf) {
      return null;
    }

    @Override
    public void write(TextResponseWriter writer, String name, IndexableField f) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SortField getSortField(SchemaField field, boolean reverse) {
      return new SortField(field.getName(), Type.STRING, reverse);
    }

    private List<RangeInvocation> invocations() {
      return invocations;
    }
  }

  private static final class TestSolrQueryRequest extends SolrQueryRequestBase {
    private TestSolrQueryRequest(IndexSchema schema, ModifiableSolrParams params) {
      super(null, params);
      this.schema = schema;
    }
  }

  private static final class TestIndexSchema extends IndexSchema {
    private static final SolrResourceLoader RESOURCE_LOADER = new SolrResourceLoader(Path.of("."));

    private final Map<String, SchemaField> fields;

    private TestIndexSchema(Map<String, SchemaField> fields) {
      super(Version.LATEST, RESOURCE_LOADER, new Properties());
      this.fields = fields;
    }

    @Override
    public SchemaField getField(String fieldName) {
      var field = fields.get(fieldName);
      if (field == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown field");
      }
      return field;
    }
  }
}
