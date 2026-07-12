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
package org.apache.solr.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.lucene.search.Query;
import org.apache.solr.request.SolrQueryRequest;

/**
 * The queries of a request that carried a {@code name=} local param, recorded as they are parsed.
 *
 * <p>{@link org.apache.lucene.search.NamedMatches} already names a query <em>within</em> the query
 * tree, which is enough to report per-document matches after the search. It is not enough for
 * consumers that need the named queries themselves rather than their matches — highlighting, for
 * one, must relate a name to the terms it can produce, and a named query in an {@code fq} never
 * reaches the highlight query at all. So {@link QParser#getQuery()} also records them here, under
 * the request context, where any downstream component can find them.
 */
public class NamedQueries {

  /** The local param that names a query, e.g. {@code {!field f=title name=t1 v=hello}}. */
  public static final String NAME = "name";

  private static final String CONTEXT_KEY = "solr.namedQueries";

  private NamedQueries() {}

  /** Records a named query. The last registration of a name wins. */
  @SuppressWarnings("unchecked")
  public static void register(SolrQueryRequest req, String name, Query query) {
    ((Map<String, Query>)
            req.getContext().computeIfAbsent(CONTEXT_KEY, k -> new LinkedHashMap<String, Query>()))
        .put(name, query);
  }

  /** The queries recorded by {@link #register}, in the order they were parsed. */
  @SuppressWarnings("unchecked")
  public static Map<String, Query> get(SolrQueryRequest req) {
    Map<String, Query> named = (Map<String, Query>) req.getContext().get(CONTEXT_KEY);
    return named == null ? Collections.emptyMap() : named;
  }
}
