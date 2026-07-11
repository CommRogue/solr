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
 * Range query parser for numeric and date fields.
 *
 * <h3>Syntax</h3>
 *
 * <pre>{@code {!range field=price gte=10 lt=100}}</pre>
 *
 * Supports four optional bounds:
 *
 * <ul>
 *   <li>{@code gt} — greater than (exclusive)
 *   <li>{@code gte} — greater than or equal (inclusive)
 *   <li>{@code lt} — less than (exclusive)
 *   <li>{@code lte} — less than or equal (inclusive)
 * </ul>
 *
 * At least one bound is required. {@code gt} and {@code gte} are mutually exclusive; likewise
 * {@code lt} and {@code lte}.
 *
 * <h3>Field requirements</h3>
 *
 * Numeric ({@code TrieIntField}, {@code TrieLongField}, {@code TrieFloatField}, {@code
 * TrieDoubleField}) or date ({@code TrieDateField}, {@code DatePointField}) field types. Aliases
 * are resolved via the base class.
 */
public class BasicRangeQParser extends BasicQParser {
  public BasicRangeQParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  protected Query parseImpl(String field) throws SyntaxError {
    var gt = localParams == null ? null : localParams.get("gt");
    var gte = localParams == null ? null : localParams.get("gte");
    var lt = localParams == null ? null : localParams.get("lt");
    var lte = localParams == null ? null : localParams.get("lte");

    if (gt == null && gte == null && lt == null && lte == null) {
      throw new SyntaxError("At least one of gt, gte, lt, or lte must be specified");
    }
    if (gt != null && gte != null) {
      throw new SyntaxError("Only one of gt or gte may be specified");
    }
    if (lt != null && lte != null) {
      throw new SyntaxError("Only one of lt or lte may be specified");
    }

    var schemaField = getSchemaField(field);
    var numberType = schemaField.getType().getNumberType();
    if (numberType == null) {
      throw new SyntaxError("Field '" + field + "' must use a numeric or date field type");
    }

    var lower = gte != null ? gte : gt;
    var upper = lte != null ? lte : lt;
    var lowerInclusive = gte != null;
    var upperInclusive = lte != null;
    return schemaField
        .getType()
        .getRangeQuery(this, schemaField, lower, upper, lowerInclusive, upperInclusive);
  }

  private SchemaField getSchemaField(String field) throws SyntaxError {
    try {
      return req.getSchema().getField(field);
    } catch (SolrException e) {
      throw new SyntaxError("Unknown field: " + field);
    }
  }
}
