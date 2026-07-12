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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.util.Utils;

/**
 * A {@link DefaultPassageFormatter} that augments the pre-tag of each highlighted region with a
 * {@code data-matched-queries} attribute relating the highlight to the {@code name=} queries whose
 * post-analysis terms produced it.
 *
 * <p>The attribute value is a JSON array of {@code {"name":..., "original":..., "analyzed":...}}
 * objects, where {@code original} is the matched query's {@code toString()} and {@code analyzed} is
 * the post-analysis term or phrase that matched. For example:
 *
 * <pre>{@code
 * <em data-matched-queries='[{"name":"t1","original":"title:hello","analyzed":"hello"}]'>hello</em>
 * }</pre>
 *
 * <p>Regions produced by no named query keep the plain pre-tag.
 */
public class MatchedQueriesPassageFormatter extends DefaultPassageFormatter {

  /** term/phrase text (post-analysis) -&gt; (query name -&gt; matched query) */
  private final Map<String, Map<String, String>> termToNamedQueries;

  public MatchedQueriesPassageFormatter(
      String preTag,
      String postTag,
      String ellipsis,
      boolean escape,
      Map<String, Map<String, String>> termToNamedQueries) {
    super(preTag, postTag, ellipsis, escape);
    this.termToNamedQueries = termToNamedQueries;
  }

  // The passage-stitching loop of DefaultPassageFormatter.format, except that the pre-tag of each
  // highlighted region is augmented with the named queries of that region's match terms.
  @Override
  public String format(Passage[] passages, String content) {
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    for (Passage passage : passages) {
      // don't add ellipsis if its the first one, or if its connected.
      if (sb.length() > 0 && passage.getStartOffset() != pos) {
        sb.append(ellipsis);
      }
      pos = passage.getStartOffset();
      for (int i = 0; i < passage.getNumMatches(); i++) {
        int start = passage.getMatchStarts()[i];
        assert start >= pos && start < passage.getEndOffset();
        // append content before this start
        append(sb, content, pos, start);

        List<Map<String, String>> entries = new ArrayList<>();
        addMatchedQueryEntries(entries, passage.getMatchTerms()[i]);
        int end = passage.getMatchEnds()[i];
        assert end > start;
        // It's possible to have overlapping terms.
        //   Look ahead to expand 'end' past all overlapping.
        //   Only take new end if it is larger than current end.
        while (i + 1 < passage.getNumMatches() && passage.getMatchStarts()[i + 1] < end) {
          addMatchedQueryEntries(entries, passage.getMatchTerms()[i + 1]);
          end = Math.max(end, passage.getMatchEnds()[++i]);
        }
        end = Math.min(end, passage.getEndOffset()); // in case match straddles past passage

        sb.append(entries.isEmpty() ? preTag : augmentPreTag(entries));
        append(sb, content, start, end);
        sb.append(postTag);

        pos = end;
      }
      // its possible a "term" from the analyzer could span a sentence boundary.
      append(sb, content, pos, Math.max(pos, passage.getEndOffset()));
      pos = passage.getEndOffset();
    }
    return sb.toString();
  }

  private void addMatchedQueryEntries(List<Map<String, String>> entries, BytesRef matchTerm) {
    String analyzed = matchTerm.utf8ToString();
    Map<String, String> named = termToNamedQueries.get(analyzed);
    if (named == null) {
      return;
    }
    named.forEach(
        (name, original) -> {
          // dedupe by name across merged overlapping matches
          if (entries.stream().anyMatch(entry -> name.equals(entry.get("name")))) {
            return;
          }
          Map<String, String> entry = new LinkedHashMap<>();
          entry.put("name", name);
          entry.put("original", original);
          entry.put("analyzed", analyzed);
          entries.add(entry);
        });
  }

  private String augmentPreTag(List<Map<String, String>> entries) {
    // the attribute is single-quoted, so ' must go, and & with it to keep the escaping reversible
    String json = Utils.toJSONString(entries, -1).replace("&", "&amp;").replace("'", "&#x27;");
    String attr = " data-matched-queries='" + json + "'";
    // inject as an attribute if the pre-tag looks like a tag, else append after it
    int insertAt = preTag.endsWith(">") ? preTag.length() - 1 : preTag.length();
    return new StringBuilder(preTag).insert(insertAt, attr).toString();
  }
}
