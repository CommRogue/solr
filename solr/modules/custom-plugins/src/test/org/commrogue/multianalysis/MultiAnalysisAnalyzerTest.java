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

package org.commrogue.multianalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

/** The tag-to-analyzer dispatch, in isolation from the schema. */
class MultiAnalysisAnalyzerTest {

  @Test
  void dispatchesToTheAnalyzerNamedByTheTag() throws Exception {
    Map<String, Analyzer> analyzers = new LinkedHashMap<>();
    analyzers.put("kw", new KeywordAnalyzer());
    analyzers.put("ws", new WhitespaceAnalyzer());

    MultiAnalysisAnalyzer analyzer = new MultiAnalysisAnalyzer(analyzers);

    // Same text, same field, same analyzer instance -- only the tag differs.
    assertEquals(List.of("Hello World"), tokens(analyzer, "kw", "Hello World"));
    assertEquals(List.of("Hello", "World"), tokens(analyzer, "ws", "Hello World"));
  }

  /** Values are dispatched independently, so a reused analyzer must not leak the last delegate. */
  @Test
  void dispatchesEachValueIndependently() throws Exception {
    Map<String, Analyzer> analyzers = new LinkedHashMap<>();
    analyzers.put("kw", new KeywordAnalyzer());
    analyzers.put("ws", new WhitespaceAnalyzer());

    MultiAnalysisAnalyzer analyzer = new MultiAnalysisAnalyzer(analyzers);

    assertEquals(List.of("a b"), tokens(analyzer, "kw", "a b"));
    assertEquals(List.of("a", "b"), tokens(analyzer, "ws", "a b"));
    assertEquals(List.of("a b"), tokens(analyzer, "kw", "a b"));
  }

  @Test
  void rejectsAnUnknownTag() {
    MultiAnalysisAnalyzer analyzer = new MultiAnalysisAnalyzer(Map.of("kw", new KeywordAnalyzer()));

    assertThrows(IllegalArgumentException.class, () -> tokens(analyzer, "missing", "value"));
  }

  @Test
  void rejectsAValueTooShortToCarryATag() {
    MultiAnalysisAnalyzer analyzer = new MultiAnalysisAnalyzer(Map.of("kw", new KeywordAnalyzer()));

    assertThrows(IllegalArgumentException.class, () -> tokensOf(analyzer, "too-short"));
  }

  @Test
  void encodesATagAsAFixedWidthRightPaddedPrefix() {
    assertEquals("kw              ", MultiAnalysisAnalyzer.encodeAnalyzerTag("kw"));
    assertEquals(
        MultiAnalysisAnalyzer.ANALYZER_TAG_LENGTH,
        MultiAnalysisAnalyzer.encodeAnalyzerTag("abc").length());
  }

  @Test
  void rejectsNamesThatCannotSurviveATagRoundTrip() {
    // Too long to fit, and so not distinguishable from a truncated name.
    assertThrows(
        IllegalArgumentException.class,
        () -> MultiAnalysisAnalyzer.encodeAnalyzerTag("12345678901234567"));
    // Trailing space is what the padding uses, so it cannot also be part of a name.
    assertThrows(
        IllegalArgumentException.class, () -> MultiAnalysisAnalyzer.encodeAnalyzerTag("kw "));
    assertThrows(IllegalArgumentException.class, () -> MultiAnalysisAnalyzer.encodeAnalyzerTag(""));
  }

  @Test
  void rejectsAnEmptyAnalyzerMap() {
    assertThrows(IllegalArgumentException.class, () -> new MultiAnalysisAnalyzer(Map.of()));
  }

  private static List<String> tokens(MultiAnalysisAnalyzer analyzer, String tag, String payload)
      throws IOException {
    return tokensOf(analyzer, MultiAnalysisAnalyzer.encodeAnalyzerTag(tag) + payload);
  }

  private static List<String> tokensOf(Analyzer analyzer, String value) throws IOException {
    List<String> terms = new ArrayList<>();
    try (TokenStream tokenStream = analyzer.tokenStream("catchall", value)) {
      CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
      tokenStream.reset();
      while (tokenStream.incrementToken()) {
        terms.add(term.toString());
      }
      tokenStream.end();
    }
    return terms;
  }
}
