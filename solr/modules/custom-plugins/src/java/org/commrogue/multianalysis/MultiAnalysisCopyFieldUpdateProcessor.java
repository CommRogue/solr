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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

/**
 * Copies source field values into a catchall field, tagging each copy with the name of the field it
 * came from so that a {@link MultiAnalysisTextField} target can analyze it with that field's index
 * analyzer.
 *
 * <p>This is a plain copy, not a Solr {@code copyField}: it has to run in an update chain because
 * the value it writes is not the source value, but the tagged value.
 *
 * <pre>
 * &lt;updateRequestProcessorChain name="multi-analysis-copy"&gt;
 *   &lt;processor class="org.commrogue.multianalysis.MultiAnalysisCopyFieldUpdateProcessor$Factory"&gt;
 *     &lt;arr name="rules"&gt;
 *       &lt;lst&gt;
 *         &lt;str name="source"&gt;title&lt;/str&gt;
 *         &lt;str name="target"&gt;catchall&lt;/str&gt;
 *         &lt;str name="useFieldAnalyzer"&gt;title&lt;/str&gt;
 *       &lt;/lst&gt;
 *     &lt;/arr&gt;
 *   &lt;/processor&gt;
 *   &lt;processor class="solr.RunUpdateProcessorFactory"/&gt;
 * &lt;/updateRequestProcessorChain&gt;
 * </pre>
 */
public class MultiAnalysisCopyFieldUpdateProcessor extends UpdateRequestProcessor {
  private final List<CopyRule> rules;

  MultiAnalysisCopyFieldUpdateProcessor(List<CopyRule> rules, UpdateRequestProcessor next) {
    super(next);
    this.rules = rules;
  }

  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    SolrInputDocument document = cmd.getSolrInputDocument();

    if (document != null) {
      for (CopyRule rule : rules) {
        SolrInputField sourceField = document.getField(rule.sourceField());
        if (sourceField == null) {
          continue;
        }

        Collection<Object> sourceValues = sourceField.getValues();
        if (sourceValues == null) {
          continue;
        }

        // Snapshot: a rule may copy a field onto itself, and addField would then mutate the
        // collection being iterated.
        for (Object sourceValue : new ArrayList<>(sourceValues)) {
          if (sourceValue == null) {
            continue;
          }
          document.addField(rule.targetField(), rule.encodedAnalyzerTag() + sourceValue);
        }
      }
    }

    super.processAdd(cmd);
  }

  /** One {@code <lst>} of the {@code rules} array, with the tag already encoded. */
  record CopyRule(String sourceField, String targetField, String encodedAnalyzerTag) {}

  /**
   * Solr entry point: {@code
   * org.commrogue.multianalysis.MultiAnalysisCopyFieldUpdateProcessor$Factory}.
   */
  public static class Factory extends UpdateRequestProcessorFactory {
    private List<CopyRule> rules = List.of();

    @Override
    public void init(NamedList<?> args) {
      // Bad config fails core startup rather than silently dropping values at index time.
      if (!(args.get("rules") instanceof List<?> rawRules) || rawRules.isEmpty()) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Expected at least one copy rule in <arr name=\"rules\">");
      }

      List<CopyRule> parsed = new ArrayList<>();
      for (Object rawRule : rawRules) {
        if (!(rawRule instanceof NamedList<?> rule)) {
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "Each copy rule must be an <lst>, but got: " + rawRule);
        }
        parsed.add(parseRule(rule));
      }

      this.rules = List.copyOf(parsed);
    }

    @Override
    public UpdateRequestProcessor getInstance(
        SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
      return new MultiAnalysisCopyFieldUpdateProcessor(rules, next);
    }

    private static CopyRule parseRule(NamedList<?> rule) {
      String source = requiredString(rule, "source");
      String target = requiredString(rule, "target");
      String analyzerField = requiredString(rule, "useFieldAnalyzer");

      try {
        return new CopyRule(source, target, MultiAnalysisAnalyzer.encodeAnalyzerTag(analyzerField));
      } catch (IllegalArgumentException e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Invalid useFieldAnalyzer for copy rule [" + source + " -> " + target + "]",
            e);
      }
    }

    private static String requiredString(NamedList<?> rule, String key) {
      Object value = rule.get(key);
      String text = value == null ? "" : value.toString().trim();
      if (text.isEmpty()) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR, "Copy rule is missing required '" + key + "'");
      }
      return text;
    }
  }
}
