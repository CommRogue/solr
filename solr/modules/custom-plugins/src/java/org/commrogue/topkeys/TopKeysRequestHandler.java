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

package org.commrogue.topkeys;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrInfoBean;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.commrogue.topkeys.TopKeysTrackingCache.TopKey;

/**
 * Reports the hottest keys of a {@link TopKeysTrackingCache} in the core handling the request.
 *
 * <pre>{@code
 * <requestHandler name="/topkeys" class="org.commrogue.topkeys.TopKeysRequestHandler"/>
 * }</pre>
 *
 * <p>{@code /topkeys?cacheName=filterCache} returns the snapshot; {@code /topkeys} with no {@code
 * cacheName} lists the caches in this core that can be asked.
 *
 * <p>Caches are resolved through {@link org.apache.solr.core.SolrCore#getInfoRegistry()}, which
 * {@link org.apache.solr.search.SolrIndexSearcher#register()} populates with every cache the
 * searcher holds, keyed by its configured name. Note that {@code SolrIndexSearcher.getCache(name)}
 * is <em>not</em> an alternative: it only sees user caches ({@code <cache name="..."/>}), and would
 * miss the built-in {@code filterCache} and {@code queryResultCache}.
 */
public class TopKeysRequestHandler extends RequestHandlerBase {

  static final String CACHE_NAME_PARAM = "cacheName";
  static final String TRACKING_CACHES_RESPONSE_KEY = "trackingCaches";

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) {
    Map<String, SolrInfoBean> infoRegistry = req.getCore().getInfoRegistry();
    String cacheName = req.getParams().get(CACHE_NAME_PARAM);

    if (cacheName == null) {
      rsp.add(TRACKING_CACHES_RESPONSE_KEY, trackingCacheNames(infoRegistry));
      return;
    }

    if (!(infoRegistry.get(cacheName) instanceof TopKeysTrackingCache<?, ?> cache)) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "No TopKeysTrackingCache named '"
              + cacheName
              + "' in this core. Tracking caches: "
              + trackingCacheNames(infoRegistry));
    }

    rsp.add(
        TopKeysTrackingCache.TOP_KEYS_RESPONSE_KEY,
        cache.topKeysSnapshot().stream()
            .map(topKey -> describe(topKey, cache.getKeyMaxLength()))
            .toList());
  }

  private static List<String> trackingCacheNames(Map<String, SolrInfoBean> infoRegistry) {
    return infoRegistry.entrySet().stream()
        .filter(entry -> entry.getValue() instanceof TopKeysTrackingCache)
        .map(Map.Entry::getKey)
        .sorted()
        .collect(Collectors.toList());
  }

  private static SimpleOrderedMap<Object> describe(TopKey<?> topKey, int keyMaxLength) {
    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    entry.add("index", topKey.index());
    entry.add("key", truncate(String.valueOf(topKey.key()), keyMaxLength));
    entry.add("hits", topKey.hits());
    entry.add("size", topKey.sizeBytes());
    entry.add("type", topKey.typeName());
    return entry;
  }

  /** Cache keys are queries, and a query has no bound on its length. */
  private static String truncate(String key, int keyMaxLength) {
    return key.length() <= keyMaxLength ? key : key.substring(0, keyMaxLength) + "...";
  }

  @Override
  public String getDescription() {
    return "Reports the most-hit keys of a TopKeysTrackingCache in this core. "
        + "Pass 'cacheName' to select a cache; omit it to list the caches that can be asked.";
  }

  @Override
  public PermissionNameProvider.Name getPermissionName(AuthorizationContext request) {
    return PermissionNameProvider.Name.READ_PERM;
  }
}
