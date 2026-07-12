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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.junit.jupiter.api.Test;

/** Rule parsing and the tagging of copied values. */
class MultiAnalysisCopyFieldUpdateProcessorTest {

  @Test
  void tagsEveryCopiedValueWithItsSourceFieldAndAppendsToTheTarget() throws Exception {
    UpdateRequestProcessor processor =
        processorFor(List.of(rule("title", "catchall", "title"), rule("body", "catchall", "body")));

    SolrInputDocument document = new SolrInputDocument();
    document.addField("title", "Hello");
    document.addField("title", "World");
    document.addField("body", "Alpha");
    document.addField("catchall", "existing");

    processor.processAdd(addCommand(document));

    assertEquals(
        List.of("existing", tag("title") + "Hello", tag("title") + "World", tag("body") + "Alpha"),
        valuesOf(document, "catchall"));
  }

  @Test
  void leavesTheTargetAloneWhenNoSourceFieldIsPresent() throws Exception {
    UpdateRequestProcessor processor = processorFor(List.of(rule("title", "catchall", "title")));

    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "1");

    processor.processAdd(addCommand(document));

    assertNull(document.getField("catchall"));
  }

  @Test
  void rejectsAnAnalyzerNameTooLongToBeTagged() {
    assertThrows(
        SolrException.class,
        () -> processorFor(List.of(rule("title", "catchall", "a-seventeen-char-name"))));
  }

  @Test
  void rejectsARuleMissingARequiredKey() {
    NamedList<Object> incomplete = new NamedList<>();
    incomplete.add("source", "title");
    incomplete.add("target", "catchall");

    assertThrows(SolrException.class, () -> processorFor(List.of(incomplete)));
  }

  @Test
  void rejectsAConfigWithNoRules() {
    MultiAnalysisCopyFieldUpdateProcessor.Factory factory =
        new MultiAnalysisCopyFieldUpdateProcessor.Factory();

    assertThrows(SolrException.class, () -> factory.init(new NamedList<>()));
  }

  private static String tag(String analyzerName) {
    return MultiAnalysisAnalyzer.encodeAnalyzerTag(analyzerName);
  }

  private static UpdateRequestProcessor processorFor(List<NamedList<Object>> rules) {
    NamedList<Object> args = new NamedList<>();
    args.add("rules", rules);

    MultiAnalysisCopyFieldUpdateProcessor.Factory factory =
        new MultiAnalysisCopyFieldUpdateProcessor.Factory();
    factory.init(args);
    return factory.getInstance(null, null, null);
  }

  private static NamedList<Object> rule(String source, String target, String useFieldAnalyzer) {
    NamedList<Object> rule = new NamedList<>();
    rule.add("source", source);
    rule.add("target", target);
    rule.add("useFieldAnalyzer", useFieldAnalyzer);
    return rule;
  }

  private static AddUpdateCommand addCommand(SolrInputDocument document) {
    AddUpdateCommand command = new AddUpdateCommand(null);
    command.solrDoc = document;
    return command;
  }

  private static List<String> valuesOf(SolrInputDocument document, String fieldName) {
    List<String> values = new ArrayList<>();
    if (document.getField(fieldName) != null) {
      document.getField(fieldName).getValues().forEach(value -> values.add(String.valueOf(value)));
    }
    return values;
  }
}
