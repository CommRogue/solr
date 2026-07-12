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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.AttributeImpl;

/**
 * Index-time analyzer that picks a different delegate analyzer for every value, from a fixed-width
 * tag on the front of the value.
 *
 * <p>Each value must be {@code [16-char tag][payload]}, where the tag is an analyzer name
 * right-padded with spaces (see {@link #encodeAnalyzerTag}). The tag is consumed here and only the
 * payload reaches the delegate. {@link MultiAnalysisCopyFieldUpdateProcessor} is what writes the
 * tags.
 *
 * <p>ponytail: because the tag is stripped from the reader, token offsets are relative to the
 * payload, i.e. shifted by {@link #ANALYZER_TAG_LENGTH} against the stored value. A field of this
 * type is therefore not usable for highlighting -- fine for a catchall, which is {@code
 * stored="false"}. Storing an offset gap would mean rewriting offsets in a filter.
 */
public final class MultiAnalysisAnalyzer extends Analyzer {
  public static final int ANALYZER_TAG_LENGTH = 16;

  /**
   * Dispatch happens per value, so components can never be reused: the delegate is only known once
   * the tag has been read off the reader. Returning null here is what forces {@link
   * #createComponents} to run for every value.
   *
   * <p>This survives being wrapped by the schema: {@code DelegatingAnalyzerWrapper} consults the
   * <em>wrapped</em> analyzer's reuse strategy, not its own.
   */
  private static final ReuseStrategy NO_REUSE =
      new ReuseStrategy() {
        @Override
        public TokenStreamComponents getReusableComponents(Analyzer analyzer, String fieldName) {
          return null;
        }

        @Override
        public void setReusableComponents(
            Analyzer analyzer, String fieldName, TokenStreamComponents components) {
          // Intentionally empty -- nothing is reusable.
        }
      };

  private final Map<String, Analyzer> analyzersByName;

  public MultiAnalysisAnalyzer(Map<String, Analyzer> analyzersByName) {
    super(NO_REUSE);
    Objects.requireNonNull(analyzersByName, "analyzersByName must not be null");
    if (analyzersByName.isEmpty()) {
      throw new IllegalArgumentException("At least one named analyzer must be configured");
    }

    Map<String, Analyzer> copy = new LinkedHashMap<>();
    analyzersByName.forEach(
        (analyzerName, analyzer) -> {
          validateAnalyzerName(analyzerName);
          if (analyzer == null) {
            throw new IllegalArgumentException("Analyzer '" + analyzerName + "' is null");
          }
          copy.put(analyzerName, analyzer);
        });
    this.analyzersByName = Map.copyOf(copy);
  }

  Map<String, Analyzer> getAnalyzersByName() {
    return analyzersByName;
  }

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    DispatchingTokenStream tokenStream = new DispatchingTokenStream(fieldName);
    return new TokenStreamComponents(
        reader -> {
          try {
            tokenStream.setTaggedInput(reader);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        tokenStream);
  }

  /**
   * A name has to survive a round trip through a fixed-width, space-padded tag, so it may not be
   * blank, may not carry outer whitespace, and may not exceed {@link #ANALYZER_TAG_LENGTH}.
   */
  public static void validateAnalyzerName(String analyzerName) {
    if (analyzerName == null || analyzerName.isBlank()) {
      throw new IllegalArgumentException("Analyzer name must not be null or blank");
    }
    if (!analyzerName.equals(analyzerName.strip())) {
      throw new IllegalArgumentException(
          "Analyzer name must not have leading or trailing whitespace: '" + analyzerName + "'");
    }
    if (analyzerName.length() > ANALYZER_TAG_LENGTH) {
      throw new IllegalArgumentException(
          "Analyzer name '"
              + analyzerName
              + "' exceeds the maximum of "
              + ANALYZER_TAG_LENGTH
              + " characters");
    }
  }

  /** Right-pads an analyzer name with spaces into the fixed-width tag that prefixes a value. */
  public static String encodeAnalyzerTag(String analyzerName) {
    validateAnalyzerName(analyzerName);
    return analyzerName + " ".repeat(ANALYZER_TAG_LENGTH - analyzerName.length());
  }

  private static String readAnalyzerName(Reader reader) throws IOException {
    char[] tag = new char[ANALYZER_TAG_LENGTH];
    int offset = 0;
    while (offset < ANALYZER_TAG_LENGTH) {
      int read = reader.read(tag, offset, ANALYZER_TAG_LENGTH - offset);
      if (read == -1) {
        throw new IllegalArgumentException(
            "Value is shorter than the required analyzer tag length of " + ANALYZER_TAG_LENGTH);
      }
      offset += read;
    }

    String analyzerName = new String(tag).stripTrailing();
    if (analyzerName.isEmpty()) {
      throw new IllegalArgumentException("Analyzer tag is blank");
    }
    return analyzerName;
  }

  /**
   * Reads the tag off the reader, then hands the rest of it to the analyzer the tag names and
   * mirrors that analyzer's token stream.
   *
   * <p>The delegate is driven through {@link Analyzer#tokenStream}, not through its components
   * directly, so its char filters still see the payload.
   */
  private final class DispatchingTokenStream extends TokenStream {
    private final String fieldName;
    private TokenStream delegate;

    private DispatchingTokenStream(String fieldName) {
      this.fieldName = fieldName;
    }

    void setTaggedInput(Reader reader) throws IOException {
      closeDelegate();

      String analyzerName = readAnalyzerName(reader);
      Analyzer analyzer = analyzersByName.get(analyzerName);
      if (analyzer == null) {
        throw new IllegalArgumentException(
            "No analyzer configured for tag '"
                + analyzerName
                + "'. Known tags: "
                + analyzersByName.keySet());
      }

      delegate = analyzer.tokenStream(fieldName, reader);

      // The consumer reads attributes off *this* stream, so it has to carry the delegate's.
      Iterator<AttributeImpl> attributes = delegate.getAttributeImplsIterator();
      while (attributes.hasNext()) {
        addAttributeImpl(attributes.next());
      }
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      requireDelegate().reset();
    }

    @Override
    public boolean incrementToken() throws IOException {
      return requireDelegate().incrementToken();
    }

    @Override
    public void end() throws IOException {
      if (delegate != null) {
        delegate.end();
      }
    }

    @Override
    public void close() throws IOException {
      try {
        closeDelegate();
      } finally {
        super.close();
      }
    }

    private TokenStream requireDelegate() {
      if (delegate == null) {
        throw new IllegalStateException("setReader() was never called, so there is no delegate");
      }
      return delegate;
    }

    private void closeDelegate() throws IOException {
      if (delegate != null) {
        delegate.close();
        delegate = null;
      }
    }
  }
}
