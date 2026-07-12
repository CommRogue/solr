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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.NamedQueries;

/**
 * A {@link UnifiedSolrHighlighter} that can relate each highlighted region back to the {@code
 * name=} query that produced it.
 *
 * <p>With {@code hl.matchedQueries=true}, the pre-tag of every highlighted region is augmented with
 * a {@code data-matched-queries} attribute naming the queries whose terms produced it. See {@link
 * MatchedQueriesPassageFormatter} for the shape of that attribute.
 *
 * <p>Register it in {@code solrconfig.xml} in place of the stock highlighter:
 *
 * <pre>{@code
 * <searchComponent name="highlight" class="solr.HighlightComponent">
 *   <highlighting class="org.commrogue.namedqueries.MatchedQueriesUnifiedHighlighter"/>
 * </searchComponent>
 * }</pre>
 *
 * <p>It behaves exactly like the stock unified highlighter when {@code hl.matchedQueries} is off,
 * which it is by default.
 */
public class MatchedQueriesUnifiedHighlighter extends UnifiedSolrHighlighter {

  /**
   * Boolean, per-field. Augments highlight pre-tags with the named queries that produced them.
   * Defaults to false.
   *
   * <p>Not in {@link HighlightParams} because that lives in solrj, and a fork-local param is not
   * worth an edit to an upstream file.
   */
  public static final String MATCHED_QUERIES = HighlightParams.HIGHLIGHT + ".matchedQueries";

  @Override
  protected UnifiedHighlighter getHighlighter(SolrQueryRequest req) {
    return new MatchedQueriesHighlighter(req);
  }

  /**
   * The stock {@link SolrExtendedUnifiedHighlighter}, but handing out a {@link
   * MatchedQueriesPassageFormatter} for fields that any named query can actually produce a
   * highlight in.
   */
  protected static class MatchedQueriesHighlighter extends SolrExtendedUnifiedHighlighter {

    private final SolrQueryRequest req;

    public MatchedQueriesHighlighter(SolrQueryRequest req) {
      super(req);
      this.req = req;
    }

    @Override
    protected PassageFormatter getFormatter(String fieldName) {
      // Same params the stock highlighter reads; we cannot reuse its formatter because
      // DefaultPassageFormatter does not expose the tags it was built with.
      String preTag =
          params.getFieldParam(
              fieldName,
              HighlightParams.TAG_PRE,
              params.getFieldParam(fieldName, HighlightParams.SIMPLE_PRE, "<em>"));
      String postTag =
          params.getFieldParam(
              fieldName,
              HighlightParams.TAG_POST,
              params.getFieldParam(fieldName, HighlightParams.SIMPLE_POST, "</em>"));
      String ellipsis =
          params.getFieldParam(fieldName, HighlightParams.TAG_ELLIPSIS, SNIPPET_SEPARATOR);
      String encoder = params.getFieldParam(fieldName, HighlightParams.ENCODER, "simple");
      boolean escape = "html".equals(encoder);

      if (params.getFieldBool(fieldName, MATCHED_QUERIES, false)) {
        Map<String, Map<String, String>> termToNamedQueries = buildTermToNamedQueries(fieldName);
        if (!termToNamedQueries.isEmpty()) {
          return new MatchedQueriesPassageFormatter(
              preTag, postTag, ellipsis, escape, termToNamedQueries);
        }
      }
      return new DefaultPassageFormatter(preTag, postTag, ellipsis, escape);
    }

    /**
     * Maps each post-analysis term of every named query — and, for multi-term queries, the
     * space-joined phrase, which is the form {@link
     * org.apache.lucene.search.uhighlight.UnifiedHighlighter.HighlightFlag#WEIGHT_MATCHES} mode
     * reports a phrase match as — to {@code name -> matched query}, restricted to the terms this
     * field's {@link #getFieldMatcher(String)} accepts.
     *
     * <p>Attribution is therefore term-based, not positional: a highlight lists every named query
     * containing that term or phrase, even if only one of them matched at that position.
     */
    protected Map<String, Map<String, String>> buildTermToNamedQueries(String fieldName) {
      Map<String, Query> namedQueries = NamedQueries.get(req);
      if (namedQueries.isEmpty()) {
        return Map.of();
      }
      Predicate<String> fieldMatcher = getFieldMatcher(fieldName);
      Map<String, Map<String, String>> termToNamedQueries = new HashMap<>();
      namedQueries.forEach(
          (name, namedQuery) ->
              namedQuery.visit(
                  new QueryVisitor() {
                    @Override
                    public boolean acceptField(String field) {
                      return fieldMatcher.test(field);
                    }

                    @Override
                    public void consumeTerms(Query query, Term... terms) {
                      StringBuilder joined = new StringBuilder();
                      for (Term term : terms) {
                        termToNamedQueries
                            .computeIfAbsent(term.text(), k -> new LinkedHashMap<>())
                            .putIfAbsent(name, namedQuery.toString());
                        if (joined.length() > 0) {
                          joined.append(' ');
                        }
                        joined.append(term.text());
                      }
                      if (terms.length > 1) {
                        termToNamedQueries
                            .computeIfAbsent(joined.toString(), k -> new LinkedHashMap<>())
                            .putIfAbsent(name, namedQuery.toString());
                      }
                    }
                  }));
      return termToNamedQueries;
    }
  }
}
