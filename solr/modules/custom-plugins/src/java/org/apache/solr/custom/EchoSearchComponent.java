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

package org.apache.solr.custom;

import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;

/**
 * A trivial example plugin that echoes a marker into the response, proving this module was loaded
 * from the Docker image's {@code modules/custom-plugins/lib} directory.
 *
 * <p>Register it in a configset's {@code solrconfig.xml}:
 *
 * <pre>
 * &lt;searchComponent name="echo" class="org.apache.solr.custom.EchoSearchComponent"/&gt;
 * </pre>
 *
 * Delete this class once you have real plugins here.
 */
public class EchoSearchComponent extends SearchComponent {

  /** The response key under which this component reports that it ran. */
  public static final String RESPONSE_KEY = "custom-plugins";

  @Override
  public void prepare(ResponseBuilder rb) {
    rb.rsp.add(RESPONSE_KEY, "loaded");
  }

  @Override
  public void process(ResponseBuilder rb) {
    // Nothing to do: this component only advertises its own presence.
  }

  @Override
  public String getDescription() {
    return "Echoes a marker confirming the custom-plugins module is on the classpath";
  }
}
