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

class BasicExactQParserTest {
  @Test
  void parseBuildsExactQueryForNonTokenizedTextField() throws Exception {
    var fieldType = new RecordingFieldType(null, true, false);
    var schema = schema(Map.of("sku", new SchemaField("sku", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(
              null,
              localParams("field", "sku", "value", "ABC-123"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      assertEquals(markerQuery("sku", "ABC-123"), query);
      assertEquals(List.of(new FieldInvocation("sku", "ABC-123")), fieldType.invocations());
    }
  }

  @Test
  void parseBuildsExactQueryForNumericField() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.INTEGER, false, false);
    var schema = schema(Map.of("price", new SchemaField("price", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(
              null, localParams("field", "price", "value", "10"), new ModifiableSolrParams(), req);

      var query = parser.parse();

      assertEquals(markerQuery("price", "10"), query);
      assertEquals(List.of(new FieldInvocation("price", "10")), fieldType.invocations());
    }
  }

  @Test
  void parseBuildsExactQueryForDateField() throws Exception {
    var fieldType = new RecordingFieldType(NumberType.DATE, false, false);
    var schema = schema(Map.of("published_at", new SchemaField("published_at", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(
              null,
              localParams("field", "published_at", "value", "2024-01-01T00:00:00Z"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      assertEquals(markerQuery("published_at", "2024-01-01T00:00:00Z"), query);
      assertEquals(
          List.of(new FieldInvocation("published_at", "2024-01-01T00:00:00Z")),
          fieldType.invocations());
    }
  }

  @Test
  void parseExpandsAliasesIntoExactQueries() throws Exception {
    var titleEn = new RecordingFieldType(null, true, false);
    var titleFr = new RecordingFieldType(null, true, false);
    var schema =
        schema(
            Map.of(
                "title_en", new SchemaField("title_en", titleEn),
                "title_fr", new SchemaField("title_fr", titleFr)));
    var params = new ModifiableSolrParams().set("f.title.qf", "title_en title_fr");
    try (var req = newRequest(schema, params)) {
      var parser =
          new BasicExactQParser(null, localParams("field", "title", "value", "solr"), params, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertEquals(2, booleanQuery.clauses().size());
      assertClause(booleanQuery.clauses().get(0), "title_en", "solr");
      assertClause(booleanQuery.clauses().get(1), "title_fr", "solr");
      assertEquals(List.of(new FieldInvocation("title_en", "solr")), titleEn.invocations());
      assertEquals(List.of(new FieldInvocation("title_fr", "solr")), titleFr.invocations());
    }
  }

  @Test
  void parsePassesEmptyValueThroughToFieldQuery() throws Exception {
    var fieldType = new RecordingFieldType(null, true, false);
    var schema = schema(Map.of("sku", new SchemaField("sku", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(
              null, localParams("field", "sku", "value", ""), new ModifiableSolrParams(), req);

      var query = parser.parse();

      assertEquals(markerQuery("sku", ""), query);
      assertEquals(List.of(new FieldInvocation("sku", "")), fieldType.invocations());
    }
  }

  @Test
  void parseRequiresValueLocalParam() {
    var fieldType = new RecordingFieldType(null, true, false);
    var schema = schema(Map.of("sku", new SchemaField("sku", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(null, localParams("field", "sku"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Missing required local param: value", error.getMessage());
    }
  }

  @Test
  void parseRejectsUnknownFields() {
    try (var req = newRequest(schema(Map.of()))) {
      var parser =
          new BasicExactQParser(
              null, localParams("field", "missing", "value", "1"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Unknown field: missing", error.getMessage());
    }
  }

  @Test
  void parseRejectsTokenizedTextFields() {
    var fieldType = new RecordingFieldType(null, true, true);
    var schema = schema(Map.of("title", new SchemaField("title", fieldType)));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicExactQParser(
              null,
              localParams("field", "title", "value", "solr"),
              new ModifiableSolrParams(),
              req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals(
          "Field 'title' must use a non-tokenized text, numeric, or date field type",
          error.getMessage());
    }
  }

  @Test
  void pluginCreatesBasicExactParser() {
    var schema =
        schema(Map.of("sku", new SchemaField("sku", new RecordingFieldType(null, true, false))));
    try (var req = newRequest(schema)) {
      var plugin = new BasicExactQParserPlugin();

      assertEquals("basic_exact", BasicExactQParserPlugin.NAME);
      assertInstanceOf(
          BasicExactQParser.class,
          plugin.createParser(
              null,
              localParams("field", "sku", "value", "ABC-123"),
              new ModifiableSolrParams(),
              req));
    }
  }

  private static void assertClause(BooleanClause clause, String field, String value) {
    assertEquals(BooleanClause.Occur.SHOULD, clause.occur());
    assertEquals(markerQuery(field, value), clause.query());
  }

  private static Query markerQuery(String field, String value) {
    return new TermQuery(new Term(field, String.valueOf(value)));
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

  private record FieldInvocation(String field, String value) {}

  private static final class RecordingFieldType extends PrimitiveFieldType {
    private final NumberType numberType;
    private final boolean utf8Field;
    private final boolean tokenized;
    private final List<FieldInvocation> invocations = new ArrayList<>();

    private RecordingFieldType(NumberType numberType, boolean utf8Field, boolean tokenized) {
      this.numberType = numberType;
      this.utf8Field = utf8Field;
      this.tokenized = tokenized;
    }

    @Override
    public NumberType getNumberType() {
      return numberType;
    }

    @Override
    public boolean isUtf8Field() {
      return utf8Field;
    }

    @Override
    public boolean isTokenized() {
      return tokenized;
    }

    @Override
    public Query getFieldQuery(QParser parser, SchemaField field, String externalVal) {
      invocations.add(new FieldInvocation(field.getName(), externalVal));
      return markerQuery(field.getName(), externalVal);
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

    private List<FieldInvocation> invocations() {
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
