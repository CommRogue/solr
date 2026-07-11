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

import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.SyntaxError;

/**
 * Exact-match query parser for non-tokenized text, numeric, and date fields.
 *
 * <h3>Syntax</h3>
 *
 * <pre>{@code {!exact field=sku value=ABC-123}}</pre>
 *
 * The value is passed directly to the field type's {@code getFieldQuery}, so it is <em>not</em>
 * analyzed or tokenized — the entire string must match exactly. For numeric and date fields, the
 * value is parsed according to the field type's native format.
 *
 * <h3>Field requirements</h3>
 *
 * Non-tokenized {@code Utf8Field} (e.g. {@code StrField}), numeric, or date field types. Aliases
 * are resolved via the base class.
 */
public class BasicExactQParser extends BasicQParser {
  public BasicExactQParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  protected Query parseImpl(String field) throws SyntaxError {
    var value = localParams == null ? null : localParams.get("value");
    if (value == null) {
      throw new SyntaxError("Missing required local param: value");
    }

    var schemaField = getSchemaField(field);
    var fieldType = schemaField.getType();
    var isSupportedTextField = fieldType.isUtf8Field() && !fieldType.isTokenized();
    if (fieldType.getNumberType() == null && !isSupportedTextField) {
      throw new SyntaxError(
          "Field '" + field + "' must use a non-tokenized text, numeric, or date field type");
    }

    return fieldType.getFieldQuery(this, schemaField, value);
  }

  private SchemaField getSchemaField(String field) throws SyntaxError {
    try {
      return req.getSchema().getField(field);
    } catch (SolrException e) {
      throw new SyntaxError("Unknown field: " + field);
    }
  }
}
