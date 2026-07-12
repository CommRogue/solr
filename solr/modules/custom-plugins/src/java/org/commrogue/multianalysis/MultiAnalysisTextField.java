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

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.solr.common.SolrException;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaAware;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TextField;

/**
 * Catchall field type whose index analysis is chosen per value: every value carries a tag naming
 * the schema field whose index analyzer should analyze it.
 *
 * <p>So one multivalued catchall field can hold a stemmed copy of one field and a verbatim copy of
 * another, each analyzed exactly as it would have been in its source field. {@link
 * MultiAnalysisCopyFieldUpdateProcessor} writes the tags; {@link MultiAnalysisAnalyzer} reads them.
 *
 * <p>Query-time analysis is ordinary and single-analyzer -- queries carry no tag. Declare an {@code
 * <analyzer type="query">} on the type: without one, {@code FieldTypePluginLoader} never sets a
 * multi-term analyzer, and wildcard queries against the field will NPE.
 */
public class MultiAnalysisTextField extends TextField implements SchemaAware {

  /**
   * The analyzer map can only be built here, not in {@code init()}: {@code
   * IndexSchema.readSchema()} loads field types before it loads fields, so {@code
   * schema.getFields()} is still empty when a field type initializes. {@code inform()} runs after
   * fields are loaded and -- importantly -- before {@code refreshAnalyzers()} snapshots every
   * field's index analyzer into the schema-wide cache, so the analyzer set here is the one that
   * actually gets used.
   */
  @Override
  public void inform(IndexSchema schema) {
    Map<String, Analyzer> analyzersByName = new LinkedHashMap<>();

    for (SchemaField field : schema.getFields().values()) {
      FieldType fieldType = field.getType();

      // Skip this type and any sibling catchall, so a catchall analyzer never contains another one.
      if (fieldType instanceof MultiAnalysisTextField) {
        continue;
      }

      // A field whose name does not fit in a tag can never be referenced by one, so it is simply
      // not
      // addressable rather than fatal -- a rule that does name it still fails loudly, at startup,
      // in
      // MultiAnalysisCopyFieldUpdateProcessor.Factory.
      if (field.getName().length() > MultiAnalysisAnalyzer.ANALYZER_TAG_LENGTH) {
        continue;
      }

      analyzersByName.put(field.getName(), fieldType.getIndexAnalyzer());
    }

    if (analyzersByName.isEmpty()) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "FieldType '"
              + getTypeName()
              + "' resolved no field index analyzers from the schema; it has nothing to dispatch to");
    }

    setIndexAnalyzer(new MultiAnalysisAnalyzer(analyzersByName));
  }
}
