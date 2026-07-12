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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.commrogue.topkeys.TopKeysTrackingCache.TopKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code async} splits the cache into two genuinely different code paths -- {@code computeAsync}
 * against the {@link com.github.benmanes.caffeine.cache.AsyncCache}, versus {@code cache.get}
 * against the synchronous one -- and only the first is used by default. Every test here therefore
 * runs against both.
 */
class TopKeysTrackingCacheTest {

  private static TopKeysTrackingCache<String, String> newCache(boolean async, int topKeys) {
    TopKeysTrackingCache<String, String> cache = new TopKeysTrackingCache<>();
    Map<String, String> args = new HashMap<>();
    args.put("name", "testCache");
    args.put("async", Boolean.toString(async));
    args.put(TopKeysTrackingCache.TOP_KEYS_PARAM, Integer.toString(topKeys));
    cache.init(args, null, null);
    return cache;
  }

  private static Map<String, TopKey<String>> byKey(TopKeysTrackingCache<String, String> cache) {
    return cache.topKeysSnapshot().stream()
        .collect(Collectors.toMap(TopKey::key, Function.identity()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countsAHitPerServedRead(boolean async) throws Exception {
    TopKeysTrackingCache<String, String> cache = newCache(async, 10);

    // A put alone is not a hit: nothing has been served yet.
    cache.put("cold", "value");
    assertEquals(0, byKey(cache).get("cold").hits());

    cache.put("hot", "value");
    assertEquals("value", cache.get("hot"));
    assertEquals("value", cache.get("hot"));
    // computeIfAbsent serves the cached value, so it counts too.
    assertEquals("value", cache.computeIfAbsent("hot", key -> "recomputed"));
    assertEquals(3, byKey(cache).get("hot").hits());

    // A miss is not a hit against any key, and does not create one.
    assertNull(cache.get("absent"));
    assertNull(byKey(cache).get("absent"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countsTheInsertingComputeAsAHit(boolean async) throws Exception {
    TopKeysTrackingCache<String, String> cache = newCache(async, 10);

    assertEquals("computed", cache.computeIfAbsent("key", k -> "computed"));

    // The compute that populated the entry served a value for it, so the key starts at 1 rather
    // than 0 -- otherwise a key computed once and never re-read would be indistinguishable from
    // one that was put and never read at all.
    assertEquals(1, byKey(cache).get("key").hits());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void forgetsKeysThatLeaveTheCache(boolean async) {
    TopKeysTrackingCache<String, String> cache = newCache(async, 10);
    cache.put("key", "value");
    cache.get("key");
    assertEquals(1, byKey(cache).get("key").hits());

    assertEquals("value", cache.remove("key"));
    assertNull(byKey(cache).get("key"));

    // Re-entering the cache restarts the count rather than resuming it: tracking spans exactly the
    // cache's live contents.
    cache.put("key", "value");
    cache.get("key");
    assertEquals(1, byKey(cache).get("key").hits());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rankingIsHottestFirstAndCappedAtTopKeys(boolean async) {
    TopKeysTrackingCache<String, String> cache = newCache(async, 3);
    Map<String, Integer> readsPerKey =
        Map.of("first", 5, "second", 4, "third", 3, "fourth", 2, "fifth", 1);
    readsPerKey.forEach(
        (key, reads) -> {
          cache.put(key, "value");
          for (int i = 0; i < reads; i++) {
            cache.get(key);
          }
        });

    List<TopKey<String>> top = cache.topKeysSnapshot();

    assertEquals(3, top.size(), "snapshot must be capped at topKeys, not report the whole cache");
    assertEquals(List.of("first", "second", "third"), top.stream().map(TopKey::key).toList());
    assertEquals(List.of(5L, 4L, 3L), top.stream().map(TopKey::hits).toList());
    // Ranks are 1-based and dense.
    assertEquals(List.of(1, 2, 3), top.stream().map(TopKey::index).toList());

    for (TopKey<String> topKey : top) {
      assertEquals(String.class, topKey.valueType());
      assertEquals(String.class.getName(), topKey.typeName());
      assertTrue(topKey.sizeBytes() > 0, "every entry should be charged some memory");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void tiesBreakOnTheKeySoTheOrderIsStable(boolean async) {
    TopKeysTrackingCache<String, String> cache = newCache(async, 10);
    for (String key : List.of("b", "c", "a")) {
      cache.put(key, "value");
      cache.get(key);
    }

    // All three are tied on hits; without a tie-break the order would follow hash iteration.
    assertEquals(
        List.of("a", "b", "c"), cache.topKeysSnapshot().stream().map(TopKey::key).toList());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nullComputeIsNotCachedAndNotCounted(boolean async) throws Exception {
    TopKeysTrackingCache<String, String> cache = newCache(async, 10);

    assertNull(cache.computeIfAbsent("key", k -> null));

    assertEquals(0, cache.size());
    assertTrue(cache.topKeysSnapshot().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void badConfigFallsBackToTheDefaultInsteadOfFailingTheCore(boolean async) {
    TopKeysTrackingCache<String, String> cache = new TopKeysTrackingCache<>();
    Map<String, String> args = new HashMap<>();
    args.put("name", "testCache");
    args.put("async", Boolean.toString(async));
    args.put(TopKeysTrackingCache.TOP_KEYS_PARAM, "not-a-number");
    args.put(TopKeysTrackingCache.KEY_MAX_LEN_PARAM, "-1");
    cache.init(args, null, null);

    for (int i = 0; i < 20; i++) {
      cache.put("key" + i, "value");
    }
    assertEquals(10, cache.topKeysSnapshot().size(), "should fall back to the default topKeys");
    assertEquals(1024, cache.getKeyMaxLength(), "should fall back to the default keyMaxLength");
  }
}
