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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SyntaxError;

/**
 * Shared base for all basic query parsers.
 *
 * <p>Every subclass is invoked as {@code {!name field=F ...}} where {@code name} is the parser's
 * plugin name and {@code field} is required.
 *
 * <h3>Aliases</h3>
 *
 * <p>Request parameters of the form {@code f.&lt;alias&gt;.qf=fieldA fieldB} define field aliases.
 * When {@code field} matches an alias, the parser fans out into a {@code BooleanQuery} of {@code
 * SHOULD} clauses, calling {@link #parseImpl(String)} once per resolved field.
 *
 * <p>Aliases may target other aliases (recursive resolution with cycle detection) and are cached in
 * the request context so each alias is resolved only once per request.
 *
 * <p>Example: {@code f.colors.qf=red green blue} — querying {@code field=colors} runs the parser
 * against {@code red}, {@code green}, and {@code blue}.
 *
 * <h3>Subclass contract</h3>
 *
 * <p>Subclasses implement {@link #parseImpl(String)} — validate the field type against the schema,
 * build the Lucene {@link Query}, and return it.
 */
public abstract class BasicQParser extends QParser {
  protected final String ALIASES_CONTEXT_KEY = "aliases";

  protected BasicQParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  public Query parse() throws SyntaxError {
    var field = localParams == null ? null : localParams.get("field");
    if (field == null) {
      throw new SyntaxError("Missing required local param: field");
    }

    var aliases = getOrCreateAliases();
    var aliasedFields = aliases.get(field);
    if (aliasedFields == null) {
      return parseImpl(field);
    }

    var builder = new BooleanQuery.Builder();
    for (var aliasedField : aliasedFields) {
      builder.add(parseImpl(aliasedField), Occur.SHOULD);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, String[]> getOrCreateAliases() throws SyntaxError {
    var aliases = (Map<String, String[]>) req.getContext().get(ALIASES_CONTEXT_KEY);
    if (aliases != null) {
      return aliases;
    }

    aliases = buildAliasesFromParams();
    req.getContext().put(ALIASES_CONTEXT_KEY, aliases);
    return aliases;
  }

  private Map<String, String[]> buildAliasesFromParams() throws SyntaxError {
    var aliasDefinitions = new LinkedHashMap<String, List<String>>();
    var parameterNames = params.getParameterNamesIterator();
    while (parameterNames.hasNext()) {
      var parameterName = parameterNames.next();
      if (!parameterName.startsWith("f.") || !parameterName.endsWith(".qf")) {
        continue;
      }

      var alias = parameterName.substring(2, parameterName.length() - 3);
      var targets = splitAliasTargets(params.getParams(parameterName));
      if (targets.isEmpty()) {
        throw new SyntaxError("Alias '" + alias + "' must target at least one field or alias");
      }
      aliasDefinitions.put(alias, targets);
    }

    var resolvedAliases = new LinkedHashMap<String, String[]>();
    var resolutionPath = new ArrayDeque<String>();
    var resolvingAliases = new LinkedHashSet<String>();
    for (var alias : aliasDefinitions.keySet()) {
      resolveAlias(alias, aliasDefinitions, resolvedAliases, resolutionPath, resolvingAliases);
    }
    return resolvedAliases;
  }

  private List<String> splitAliasTargets(String[] parameterValues) {
    var targets = new ArrayList<String>();
    if (parameterValues == null) {
      return targets;
    }

    for (var parameterValue : parameterValues) {
      if (parameterValue == null || parameterValue.isBlank()) {
        continue;
      }
      for (var token : parameterValue.trim().split("\\s+")) {
        if (!token.isBlank()) {
          targets.add(token);
        }
      }
    }
    return targets;
  }

  private String[] resolveAlias(
      String alias,
      Map<String, List<String>> aliasDefinitions,
      Map<String, String[]> resolvedAliases,
      Deque<String> resolutionPath,
      Set<String> resolvingAliases)
      throws SyntaxError {
    var resolvedAlias = resolvedAliases.get(alias);
    if (resolvedAlias != null) {
      return resolvedAlias;
    }

    if (!resolvingAliases.add(alias)) {
      throw cyclicAliasError(alias, resolutionPath);
    }

    resolutionPath.addLast(alias);
    try {
      var targets = aliasDefinitions.get(alias);
      var resolvedTargets = new LinkedHashSet<String>();
      for (var target : targets) {
        if (aliasDefinitions.containsKey(target)) {
          resolvedTargets.addAll(
              Arrays.asList(
                  resolveAlias(
                      target,
                      aliasDefinitions,
                      resolvedAliases,
                      resolutionPath,
                      resolvingAliases)));
          continue;
        }
        resolvedTargets.add(target);
      }

      if (resolvedTargets.isEmpty()) {
        throw new SyntaxError("Alias '" + alias + "' must target at least one field or alias");
      }

      resolvedAlias = resolvedTargets.toArray(String[]::new);
      resolvedAliases.put(alias, resolvedAlias);
      return resolvedAlias;
    } finally {
      resolutionPath.removeLast();
      resolvingAliases.remove(alias);
    }
  }

  private SyntaxError cyclicAliasError(String alias, Deque<String> resolutionPath) {
    var cycle = new ArrayList<String>();
    var capturing = false;
    for (var pathAlias : resolutionPath) {
      if (!capturing && pathAlias.equals(alias)) {
        capturing = true;
      }
      if (capturing) {
        cycle.add(pathAlias);
      }
    }
    cycle.add(alias);
    return new SyntaxError("Cyclic alias definition detected: " + String.join(" -> ", cycle));
  }

  protected abstract Query parseImpl(String field) throws SyntaxError;
}
