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
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortField.Type;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.util.Version;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.TextResponseWriter;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.PrimitiveFieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TextField;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.uninverting.UninvertingReader;
import org.junit.jupiter.api.Test;

class BasicTextQParserTest {
  @Test
  void parseBuildsSingleWordQuery() throws Exception {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(hello)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var query = parser.parse();

      assertEquals(expectedFieldQuery(analyzer, "title", "hello", false), query);
    }
  }

  @Test
  void parseBuildsShouldQueryForWordsAndPhrases() throws Exception {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(hello \"two words\" world)",
              localParams("field", "title"),
              new ModifiableSolrParams(),
              req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertEquals(3, booleanQuery.clauses().size());
      assertClause(
          booleanQuery.clauses().get(0), expectedFieldQuery(analyzer, "title", "hello", false));
      assertClause(
          booleanQuery.clauses().get(1), expectedFieldQuery(analyzer, "title", "two words", true));
      assertClause(
          booleanQuery.clauses().get(2), expectedFieldQuery(analyzer, "title", "world", false));
      assertInstanceOf(PhraseQuery.class, booleanQuery.clauses().get(1).query());
    }
  }

  @Test
  void parseExpandsAliasesIntoTextQueries() throws Exception {
    var analyzer = new WhitespaceAnalyzer();
    var schema =
        schema(
            Map.of(
                "title_en", new SchemaField("title_en", new TestTextFieldType(analyzer)),
                "title_fr", new SchemaField("title_fr", new TestTextFieldType(analyzer))));
    var params = new ModifiableSolrParams().set("f.title.qf", "title_en title_fr");
    try (var req = newRequest(schema, params)) {
      var parser = new BasicTextQParser("(hello)", localParams("field", "title"), params, req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertEquals(2, booleanQuery.clauses().size());
      assertClause(
          booleanQuery.clauses().get(0), expectedFieldQuery(analyzer, "title_en", "hello", false));
      assertClause(
          booleanQuery.clauses().get(1), expectedFieldQuery(analyzer, "title_fr", "hello", false));
    }
  }

  @Test
  void parseSkipsAnalyzedAwayClausesAndReturnsSingleRemainingQuery() throws Exception {
    var analyzer = new StopwordAnalyzer("the");
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(keep the)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var query = parser.parse();

      assertEquals(expectedFieldQuery(analyzer, "title", "keep", false), query);
    }
  }

  @Test
  void parseReturnsEmptyBooleanQueryWhenAllClausesAnalyzeAway() throws Exception {
    var analyzer = new StopwordAnalyzer("the", "and");
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(the and)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var query = parser.parse();

      var booleanQuery = assertInstanceOf(BooleanQuery.class, query);
      assertEquals(0, booleanQuery.clauses().size());
    }
  }

  @Test
  void parseRejectsUnknownFields() {
    try (var req = newRequest(schema(Map.of()))) {
      var parser =
          new BasicTextQParser(
              "(hello)", localParams("field", "missing"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Unknown field: missing", error.getMessage());
    }
  }

  @Test
  void parseRejectsNonTextFields() {
    var schema = schema(Map.of("title", new SchemaField("title", new TestNonTextFieldType())));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(hello)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Field 'title' must use a tokenized text field type", error.getMessage());
    }
  }

  @Test
  void parseRejectsNonTokenizedTextFields() {
    var schema =
        schema(
            Map.of(
                "title",
                new SchemaField("title", new TestTextFieldType(new WhitespaceAnalyzer(), false))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(hello)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Field 'title' must use a tokenized text field type", error.getMessage());
    }
  }

  @Test
  void parseRejectsMissingOuterParentheses() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "hello", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Query must start with '(' and end with ')'", error.getMessage());
    }
  }

  @Test
  void parseRejectsEmptyBody() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "()", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Empty query body is not allowed", error.getMessage());
    }
  }

  @Test
  void parseRejectsEmptyQuotedPhrases() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(\"\")", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Quoted phrases must not be empty", error.getMessage());
    }
  }

  @Test
  void parseRejectsUnterminatedQuotedPhrases() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(\"hello)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Unterminated quoted phrase", error.getMessage());
    }
  }

  @Test
  void parseRejectsMissingWhitespaceAfterQuotedPhrases() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(\"hello\"world)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Expected whitespace after quoted phrase", error.getMessage());
    }
  }

  @Test
  void parseRejectsUnexpectedQuotesInUnquotedTerms() {
    var analyzer = new WhitespaceAnalyzer();
    var schema = schema(Map.of("title", new SchemaField("title", new TestTextFieldType(analyzer))));
    try (var req = newRequest(schema)) {
      var parser =
          new BasicTextQParser(
              "(hel\"lo)", localParams("field", "title"), new ModifiableSolrParams(), req);

      var error = assertThrows(SyntaxError.class, parser::parse);

      assertEquals("Unexpected quote in unquoted term", error.getMessage());
    }
  }

  @Test
  void pluginCreatesBasicTextParser() {
    var schema =
        schema(
            Map.of(
                "title",
                new SchemaField("title", new TestTextFieldType(new WhitespaceAnalyzer()))));
    try (var req = newRequest(schema)) {
      var plugin = new BasicTextQParserPlugin();

      assertEquals("basic_text", BasicTextQParserPlugin.NAME);
      assertInstanceOf(
          BasicTextQParser.class,
          plugin.createParser(
              "(hello)", localParams("field", "title"), new ModifiableSolrParams(), req));
    }
  }

  private static void assertClause(BooleanClause clause, Query expectedQuery) {
    assertEquals(BooleanClause.Occur.SHOULD, clause.occur());
    assertEquals(expectedQuery, clause.query());
  }

  private static Query expectedFieldQuery(
      Analyzer analyzer, String field, String queryText, boolean quoted) {
    return new TestQueryBuilder(analyzer).createFieldQuery(field, queryText, quoted);
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

  private static final class TestTextFieldType extends TextField {
    private final Analyzer queryAnalyzer;
    private final boolean tokenized;

    private TestTextFieldType(Analyzer queryAnalyzer) {
      this(queryAnalyzer, true);
    }

    private TestTextFieldType(Analyzer queryAnalyzer, boolean tokenized) {
      this.queryAnalyzer = queryAnalyzer;
      this.tokenized = tokenized;
    }

    @Override
    public Analyzer getQueryAnalyzer() {
      return queryAnalyzer;
    }

    @Override
    public boolean isTokenized() {
      return tokenized;
    }
  }

  private static final class TestNonTextFieldType extends PrimitiveFieldType {
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
  }

  private static final class StopwordAnalyzer extends Analyzer {
    private final CharArraySet stopwords;

    private StopwordAnalyzer(String... stopwords) {
      this.stopwords = new CharArraySet(Arrays.asList(stopwords), false);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
      var tokenizer = new WhitespaceTokenizer();
      return new TokenStreamComponents(tokenizer, new StopFilter(tokenizer, stopwords));
    }
  }

  private static final class TestQueryBuilder extends QueryBuilder {
    private TestQueryBuilder(Analyzer analyzer) {
      super(analyzer);
    }

    private Query createFieldQuery(String field, String queryText, boolean quoted) {
      return createFieldQuery(
          analyzer,
          quoted ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD,
          field,
          queryText,
          quoted,
          0);
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
