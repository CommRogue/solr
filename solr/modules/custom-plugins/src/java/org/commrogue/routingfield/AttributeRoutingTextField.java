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

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;
import org.apache.solr.common.SolrException;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaAware;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TextField;
import org.apache.solr.search.QParser;

/**
 * "Proxy" field, which routes queries to different underlying fields, based on token types
 * encountered in the analyzed query-time TokenStream.
 *
 * <p>Configuration keys:
 *
 * <ul>
 *   <li>{@code routes}: Comma-separated list of {@code tokenType=fieldName} entries.
 *   <li>{@code defaultField}: Fallback field used when no route matches.
 * </ul>
 *
 * <p>Fields of this type must be {@code indexed="false" stored="false"} — see {@link
 * #checkSchemaField}.
 */
public class AttributeRoutingTextField extends TextField implements SchemaAware {
  private Map<String, String> routesByType = Map.of();
  private String defaultField;

  @Override
  protected void init(IndexSchema schema, Map<String, String> args) {
    super.init(schema, args);
    defaultField =
        Optional.ofNullable(args.remove("defaultField"))
            .orElseThrow(
                () ->
                    new SolrException(
                        SolrException.ErrorCode.SERVER_ERROR,
                        "Missing defaultField argument for AttributeRoutingTextField"));
    routesByType =
        parseRoutes(
            Optional.ofNullable(args.remove("routes"))
                .orElseThrow(
                    () ->
                        new SolrException(
                            SolrException.ErrorCode.SERVER_ERROR,
                            "Missing routes argument for AttributeRoutingTextField")));
  }

  /**
   * Routes are plain names at init() time — the schema's fields are not loaded yet, so this is the
   * earliest point they can be resolved. Without this, a typo'd target is a query against a field
   * that does not exist: no error, no matches.
   */
  @Override
  public void inform(IndexSchema schema) {
    routesByType.forEach(
        (type, field) -> {
          if (schema.getFieldOrNull(field) == null) {
            throw new SolrException(
                SolrException.ErrorCode.SERVER_ERROR,
                "Route '" + type + "=" + field + "' targets a field that is not in the schema");
          }
        });

    if (schema.getFieldOrNull(defaultField) == null) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "defaultField '" + defaultField + "' is not in the schema");
    }
  }

  @Override
  public Query getFieldQuery(QParser parser, SchemaField field, String externalVal) {
    Analyzer analyzer =
        Optional.ofNullable(getQueryAnalyzer())
            .orElseThrow(
                () ->
                    new SolrException(
                        SolrException.ErrorCode.SERVER_ERROR,
                        "AttributeRoutingTextField requires a query analyzer"));

    RoutingQueryBuilder queryBuilder =
        new RoutingQueryBuilder(analyzer, routesByType, defaultField);
    queryBuilder.setEnableGraphQueries(getEnableGraphQueries());
    queryBuilder.setAutoGenerateMultiTermSynonymsPhraseQuery(getAutoGeneratePhraseQueries());

    return queryBuilder.createPhraseQuery(field.getName(), externalVal);
  }

  /**
   * {@code indexed=false} is what makes the routing happen at all, not just a tidiness rule: {@link
   * org.apache.solr.parser.SolrQueryParserBase#getFieldQuery} only delegates to a field type when
   * the field is not (tokenized {@code &&} indexed), and TextField is always tokenized. An indexed
   * field of this type would be analyzed against itself and never reach {@link #getFieldQuery}.
   */
  @Override
  public void checkSchemaField(final SchemaField field) {
    super.checkSchemaField(field);
    if (field.indexed() || field.stored()) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "AttributeRoutingTextField fields must be indexed=false and stored=false: "
              + field.getName());
    }
  }

  private static Map<String, String> parseRoutes(String routesArg) {
    Map<String, String> routes =
        Arrays.stream(routesArg.split(","))
            .map(String::trim)
            .filter(Predicate.not(String::isEmpty))
            .map(
                route -> {
                  String[] parts = route.split("=", 2);
                  if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new SolrException(
                        SolrException.ErrorCode.SERVER_ERROR,
                        "Invalid route definition '"
                            + route
                            + "'. Expected a format of 'tokenType=fieldName'");
                  }
                  return Map.entry(parts[0].trim(), parts[1].trim());
                })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    if (routes.isEmpty()) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "At least one route must be configured in 'routes'");
    }

    return routes;
  }

  private static final class RoutingQueryBuilder extends QueryBuilder {
    private final Map<String, String> routesByType;
    private final String defaultField;

    private RoutingQueryBuilder(
        Analyzer analyzer, Map<String, String> routesByType, String defaultField) {
      super(analyzer);
      this.routesByType = routesByType;
      this.defaultField = defaultField;
    }

    /**
     * Copied from {@link QueryBuilder#createFieldQuery(TokenStream, BooleanClause.Occur, String,
     * boolean, int)} with modifications to route to the correct field based on token type.
     *
     * <p>Note the use of {@link CachingTokenFilter} to allow the multiple passes over the token
     * stream within the two phases, without having to re-analyze the input text multiple times.
     */
    @Override
    protected Query createFieldQuery(
        TokenStream source,
        BooleanClause.Occur operator,
        String field,
        boolean quoted,
        int phraseSlop) {
      assert operator == BooleanClause.Occur.SHOULD || operator == BooleanClause.Occur.MUST;

      try (CachingTokenFilter stream = new CachingTokenFilter(source)) {
        TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);
        PositionIncrementAttribute posIncAtt =
            stream.addAttribute(PositionIncrementAttribute.class);
        PositionLengthAttribute posLenAtt = stream.addAttribute(PositionLengthAttribute.class);
        TypeAttribute typeAtt = stream.addAttribute(TypeAttribute.class);

        if (termAtt == null) {
          return null;
        }

        int numTokens = 0;
        int positionCount = 0;
        boolean hasSynonyms = false;
        boolean isGraph = false;
        // The first token whose type has a route decides the field; null until one does.
        String routedField = null;

        // 1st phase from the original implementation
        stream.reset();
        while (stream.incrementToken()) {
          numTokens++;
          int positionIncrement = posIncAtt.getPositionIncrement();
          if (positionIncrement != 0) {
            positionCount += positionIncrement;
          } else {
            hasSynonyms = true;
          }

          int positionLength = posLenAtt.getPositionLength();
          if (getEnableGraphQueries() && positionLength > 1) {
            isGraph = true;
          }

          if (routedField == null) {
            routedField = routesByType.get(typeAtt.type());
          }
        }

        if (routedField == null) {
          routedField = defaultField;
        }

        // 2nd phase from the original implementation
        if (numTokens == 0) {
          return null;
        } else if (numTokens == 1) {
          return analyzeTerm(routedField, stream);
        } else if (isGraph) {
          if (quoted) {
            return analyzeGraphPhrase(stream, routedField, phraseSlop);
          }
          return analyzeGraphBoolean(routedField, stream, operator);
        } else if (quoted && positionCount > 1) {
          if (hasSynonyms) {
            return analyzeMultiPhrase(routedField, stream, phraseSlop);
          }
          return analyzePhrase(routedField, stream, phraseSlop);
        } else {
          if (positionCount == 1) {
            return analyzeBoolean(routedField, stream);
          }
          return analyzeMultiBoolean(routedField, stream, operator);
        }
      } catch (IOException e) {
        throw new RuntimeException("Error analyzing query text", e);
      }
    }
  }
}
