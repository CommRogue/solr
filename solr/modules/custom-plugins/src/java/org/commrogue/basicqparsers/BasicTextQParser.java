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

import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TextField;
import org.apache.solr.search.SyntaxError;

/**
 * Full-text query parser for tokenized text fields.
 *
 * <h3>Syntax</h3>
 *
 * <pre>{@code {!text field=body}(hello world "exact phrase")}</pre>
 *
 * The query string must be wrapped in parentheses. Unquoted terms are tokenized and matched as
 * {@code SHOULD} clauses; quoted phrases are matched as {@code MUST} clauses (all terms required,
 * in order). Multiple terms/quotes are combined with {@code SHOULD}, so more matching terms score
 * higher.
 *
 * <h3>Field requirements</h3>
 *
 * The field type must extend {@link TextField}, be tokenized, and have a query analyzer configured.
 * Aliases are resolved via the base class.
 */
public class BasicTextQParser extends BasicQParser {
  public BasicTextQParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  protected Query parseImpl(String field) throws SyntaxError {
    var schemaField = getTextSchemaField(field);
    var queryBuilder = new FieldQueryBuilder(schemaField.getType().getQueryAnalyzer());
    var clauses = new ArrayList<Query>();
    for (var part : parseQueryParts()) {
      var clause = queryBuilder.createFieldQuery(field, part.text(), part.quoted());
      if (clause != null) {
        clauses.add(clause);
      }
    }

    if (clauses.isEmpty()) {
      return new BooleanQuery.Builder().build();
    }
    if (clauses.size() == 1) {
      return clauses.get(0);
    }

    var builder = new BooleanQuery.Builder();
    for (var clause : clauses) {
      builder.add(clause, Occur.SHOULD);
    }
    return builder.build();
  }

  private SchemaField getTextSchemaField(String field) throws SyntaxError {
    final SchemaField schemaField;
    try {
      schemaField = req.getSchema().getField(field);
    } catch (SolrException e) {
      throw new SyntaxError("Unknown field: " + field);
    }

    var fieldType = schemaField.getType();
    if (!(fieldType instanceof TextField)
        || !fieldType.isTokenized()
        || fieldType.getQueryAnalyzer() == null) {
      throw new SyntaxError("Field '" + field + "' must use a tokenized text field type");
    }
    return schemaField;
  }

  private List<QueryPart> parseQueryParts() throws SyntaxError {
    var rawQuery = getString();
    var query = rawQuery == null ? null : rawQuery.strip();
    if (query == null
        || query.length() < 2
        || query.charAt(0) != '('
        || query.charAt(query.length() - 1) != ')') {
      throw new SyntaxError("Query must start with '(' and end with ')'");
    }

    var body = query.substring(1, query.length() - 1);
    var parts = new ArrayList<QueryPart>();
    var index = 0;
    while (index < body.length()) {
      while (index < body.length() && Character.isWhitespace(body.charAt(index))) {
        index++;
      }
      if (index >= body.length()) {
        break;
      }

      if (body.charAt(index) == '"') {
        index = parseQuotedPart(body, index, parts);
        continue;
      }

      index = parseUnquotedPart(body, index, parts);
    }

    if (parts.isEmpty()) {
      throw new SyntaxError("Empty query body is not allowed");
    }
    return parts;
  }

  private int parseQuotedPart(String body, int start, List<QueryPart> parts) throws SyntaxError {
    var closingQuote = body.indexOf('"', start + 1);
    if (closingQuote < 0) {
      throw new SyntaxError("Unterminated quoted phrase");
    }

    var phrase = body.substring(start + 1, closingQuote);
    if (phrase.isEmpty()) {
      throw new SyntaxError("Quoted phrases must not be empty");
    }

    if (closingQuote + 1 < body.length()
        && !Character.isWhitespace(body.charAt(closingQuote + 1))) {
      throw new SyntaxError("Expected whitespace after quoted phrase");
    }

    parts.add(new QueryPart(phrase, true));
    return closingQuote + 1;
  }

  private int parseUnquotedPart(String body, int start, List<QueryPart> parts) throws SyntaxError {
    var end = start;
    while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
      if (body.charAt(end) == '"') {
        throw new SyntaxError("Unexpected quote in unquoted term");
      }
      end++;
    }

    parts.add(new QueryPart(body.substring(start, end), false));
    return end;
  }

  private record QueryPart(String text, boolean quoted) {}

  private static final class FieldQueryBuilder extends QueryBuilder {
    private FieldQueryBuilder(Analyzer analyzer) {
      super(analyzer);
    }

    private Query createFieldQuery(String field, String queryText, boolean quoted) {
      return createFieldQuery(
          analyzer, quoted ? Occur.MUST : Occur.SHOULD, field, queryText, quoted, 0);
    }
  }
}
