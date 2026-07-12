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

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.solr.metrics.MetricsMap;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.search.CacheRegenerator;
import org.apache.solr.search.CancellableCollector;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrCacheBase;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.IOFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SolrCache} that behaves exactly like Solr's {@link
 * org.apache.solr.search.CaffeineCache}, but additionally counts how many times each individual key
 * was hit, so the cache can report its hottest keys.
 *
 * <p>Every value is wrapped in a {@link CachedValue} carrying a hit counter. Tracking therefore
 * spans exactly the cache's live contents: when Caffeine evicts an entry its counter is discarded
 * with it, and the key re-enters with a hit count of 1. The hottest {@code topKeys} entries (10 by
 * default) are exposed by {@link TopKeysRequestHandler}, not through the metrics registry — the
 * metrics this cache registers are the same ones {@code CaffeineCache} registers, and nothing more.
 *
 * <pre>{@code
 * <filterCache class="org.commrogue.topkeys.TopKeysTrackingCache" size="512" topKeys="100"/>
 * }</pre>
 *
 * <p><b>This class is a fork of {@code org.apache.solr.search.CaffeineCache} as of commit {@code
 * a7a4765ef6f}, not a subclass of it.</b> Delegation is not an option: hit counts have to live on
 * the cached values themselves, and {@code CaffeineCache} exposes neither its backing {@link Cache}
 * nor the values it stores. The cost is that this file must be maintained by hand — on every rebase
 * onto a new Solr release, diff it against {@code CaffeineCache} and replay whatever changed there.
 * Keep the delta small for exactly that reason.
 */
public class TopKeysTrackingCache<K, V> extends SolrCacheBase
    implements SolrCache<K, V>,
        Accountable,
        RemovalListener<K, TopKeysTrackingCache.CachedValue<V>> {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static final String TOP_KEYS_PARAM = "topKeys";
  static final String KEY_MAX_LEN_PARAM = "keyMaxLength";

  /** Key under which {@link TopKeysRequestHandler} returns the snapshot. */
  static final String TOP_KEYS_RESPONSE_KEY = "topKeys";

  private static final long BASE_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(TopKeysTrackingCache.class)
          + RamUsageEstimator.shallowSizeOfInstance(CacheStats.class)
          + 2 * RamUsageEstimator.shallowSizeOfInstance(LongAdder.class);

  private static final long RAM_BYTES_PER_FUTURE =
      RamUsageEstimator.shallowSizeOfInstance(CompletableFuture.class);

  private Executor executor;

  private CacheStats priorStats;
  private long priorHits;
  private long priorInserts;
  private long priorLookups;

  private String description = "Key-tracking Caffeine Cache";
  private LongAdder hits;
  private LongAdder inserts;
  private LongAdder lookups;
  private Cache<K, CachedValue<V>> cache;
  private AsyncCache<K, CachedValue<V>> asyncCache;
  private long warmupTime;
  private int maxSize;
  private long maxRamBytes;
  private int initialSize;
  private int maxIdleTimeSec;
  private boolean cleanupThread;
  private boolean async;

  private MetricsMap cacheMap;
  private SolrMetricsContext solrMetricsContext;

  private long initialRamBytes = 0;
  private final LongAdder ramBytes = new LongAdder();

  private int topKeysLimit = 10;
  private int keyMaxLength = 1024;

  public TopKeysTrackingCache() {
    this.priorStats = CacheStats.empty();
  }

  @Override
  public Object init(Map<String, String> args, Object persistence, CacheRegenerator regenerator) {
    super.init(args, regenerator);
    topKeysLimit = parsePositiveInt(args.get(TOP_KEYS_PARAM), TOP_KEYS_PARAM, topKeysLimit);
    keyMaxLength = parsePositiveInt(args.get(KEY_MAX_LEN_PARAM), KEY_MAX_LEN_PARAM, keyMaxLength);
    String str = args.get(SIZE_PARAM);
    maxSize = (str == null) ? 1024 : Integer.parseInt(str);
    str = args.get(INITIAL_SIZE_PARAM);
    initialSize = Math.min((str == null) ? 1024 : Integer.parseInt(str), maxSize);
    str = args.get(MAX_IDLE_TIME_PARAM);
    if (str == null) {
      maxIdleTimeSec = -1;
    } else {
      maxIdleTimeSec = Integer.parseInt(str);
    }
    str = args.get(MAX_RAM_MB_PARAM);
    int maxRamMB = str == null ? -1 : Double.valueOf(str).intValue();
    maxRamBytes = maxRamMB < 0 ? Long.MAX_VALUE : maxRamMB * 1024L * 1024L;
    cleanupThread = Boolean.parseBoolean(args.get(CLEANUP_THREAD_PARAM));
    async = Boolean.parseBoolean(args.getOrDefault(ASYNC_PARAM, "true"));
    if (async) {
      // We record futures in the map to decrease bucket-lock contention, but need computation
      // handled in same thread
      executor = Runnable::run;
    } else if (cleanupThread) {
      executor = ForkJoinPool.commonPool();
    } else {
      executor = Runnable::run;
    }

    description = generateDescription(maxSize, initialSize);

    cache = buildCache(null);
    hits = new LongAdder();
    inserts = new LongAdder();
    lookups = new LongAdder();

    initialRamBytes =
        RamUsageEstimator.shallowSizeOfInstance(cache.getClass())
            + RamUsageEstimator.shallowSizeOfInstance(executor.getClass())
            + RamUsageEstimator.sizeOfObject(description);

    return persistence;
  }

  /** A bad value in solrconfig.xml degrades tracking; it must not stop the core from coming up. */
  private static int parsePositiveInt(String value, String param, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // fall through to the warning below
    }
    log.warn("Invalid {} value '{}', using default {}", param, value, defaultValue);
    return defaultValue;
  }

  private Cache<K, CachedValue<V>> buildCache(Cache<K, CachedValue<V>> prev) {
    Caffeine<K, CachedValue<V>> builder =
        Caffeine.newBuilder()
            .initialCapacity(initialSize)
            .executor(executor)
            .removalListener(this)
            .recordStats();
    if (maxIdleTimeSec > 0) {
      builder.expireAfterAccess(Duration.ofSeconds(maxIdleTimeSec));
    }
    if (maxRamBytes != Long.MAX_VALUE) {
      builder.maximumWeight(maxRamBytes);
      builder.weigher(
          (k, v) -> (int) (RamUsageEstimator.sizeOfObject(k) + RamUsageEstimator.sizeOfObject(v)));
    } else {
      builder.maximumSize(maxSize);
    }
    Cache<K, CachedValue<V>> newCache;
    if (async) {
      asyncCache = builder.buildAsync();
      newCache = asyncCache.synchronous();
    } else {
      newCache = builder.build();
    }
    if (prev != null) {
      newCache.putAll(prev.asMap());
    }
    return newCache;
  }

  @Override
  public void onRemoval(K key, CachedValue<V> value, RemovalCause cause) {
    ramBytes.add(
        -(RamUsageEstimator.sizeOfObject(key, RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED)
            + RamUsageEstimator.sizeOfObject(value, RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED)
            + RamUsageEstimator.LINKED_HASHTABLE_RAM_BYTES_PER_ENTRY));
    if (async) {
      ramBytes.add(-RAM_BYTES_PER_FUTURE);
    }
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + initialRamBytes + ramBytes.sum();
  }

  @Override
  public V get(K key) {
    CachedValue<V> cachedValue = cache.getIfPresent(key);
    if (cachedValue == null) {
      return null;
    }
    cachedValue.recordHit();
    return cachedValue.value();
  }

  private V computeAsync(K key, IOFunction<? super K, ? extends V> mappingFunction)
      throws IOException {
    CompletableFuture<CachedValue<V>> future = new CompletableFuture<>();
    CompletableFuture<CachedValue<V>> result = asyncCache.asMap().putIfAbsent(key, future);
    lookups.increment();
    if (result != null) {
      try {
        // Another thread is already working on this computation, wait for them to finish
        CachedValue<V> cachedValue = result.join();
        hits.increment();
        if (cachedValue == null) {
          return null;
        }
        cachedValue.recordHit();
        return cachedValue.value();
      } catch (CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          // Computation had an IOException, likely index problems, so fail this result too
          throw (IOException) cause;
        }
        if (cause instanceof CancellableCollector.QueryCancelledException) {
          // The reserved slot that we were waiting for got cancelled, so we will compute directly
          // If we go back to waiting for a new cache result then that can lead to thread starvation
          // Should we record a cache miss here?
          return mappingFunction.apply(key);
        }
        throw e;
      }
    }
    try {
      // We reserved the slot, so we do the work
      V value = mappingFunction.apply(key);
      // A null value cannot be wrapped; completing with null evicts the reserved slot, exactly as
      // CaffeineCache relies on, and there is then nothing to count or to charge ram bytes for.
      CachedValue<V> cachedValue = value == null ? null : new CachedValue<>(value);
      future.complete(cachedValue); // This will update the weight and expiration
      if (cachedValue == null) {
        return null;
      }
      cachedValue.recordHit();
      recordRamBytes(key, cachedValue);
      inserts.increment();
      return value;
    } catch (Error | RuntimeException | IOException e) {
      // TimeExceeded exception is runtime and will bubble up from here
      future.completeExceptionally(e); // This will remove the future from the cache
      throw e;
    }
  }

  @Override
  public V computeIfAbsent(K key, IOFunction<? super K, ? extends V> mappingFunction)
      throws IOException {
    if (async) {
      return computeAsync(key, mappingFunction);
    }

    try {
      CachedValue<V> cachedValue =
          cache.get(
              key,
              k -> {
                V value;
                try {
                  value = mappingFunction.apply(k);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
                if (value == null) {
                  return null;
                }
                CachedValue<V> computed = new CachedValue<>(value);
                recordRamBytes(key, computed);
                inserts.increment();
                return computed;
              });
      if (cachedValue == null) {
        return null;
      }
      cachedValue.recordHit();
      return cachedValue.value();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  @Override
  public V put(K key, V val) {
    inserts.increment();
    CachedValue<V> cachedValue = new CachedValue<>(val);
    CachedValue<V> old = cache.asMap().put(key, cachedValue);
    // ramBytes decrement for `old` happens via #onRemoval.
    //
    // NOTE: CaffeineCache guards this on `val != old` to work around a behavior in the Caffeine
    //  library: where there is reference equality between `val` and `old`, caffeine does _not_
    //  invoke RemovalListener, so the entry is not decremented for the replaced value (hence it
    //  does not need to increment ram bytes for the entry either). Here the wrapper is freshly
    //  allocated on every put, so it can never be reference-equal to `old` and the guard is always
    //  true -- keep the unconditional call, and keep this note, so the divergence is not mistaken
    //  for a dropped fix (SOLR-17597) on the next rebase.
    recordRamBytes(key, cachedValue);
    return old == null ? null : old.value();
  }

  /**
   * Update the estimate of used memory.
   *
   * <p>NOTE: old value (in the event of replacement) adjusts {@link #ramBytes} via {@link
   * #onRemoval(Object, CachedValue, RemovalCause)}
   *
   * @param key the cache key
   * @param newValue the new cached value to increment estimate
   */
  private void recordRamBytes(K key, CachedValue<V> newValue) {
    ramBytes.add(
        RamUsageEstimator.sizeOfObject(newValue, RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED));
    ramBytes.add(
        RamUsageEstimator.sizeOfObject(key, RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED));
    ramBytes.add(RamUsageEstimator.LINKED_HASHTABLE_RAM_BYTES_PER_ENTRY);
    if (async) ramBytes.add(RAM_BYTES_PER_FUTURE);
  }

  @Override
  public V remove(K key) {
    // ramBytes adjustment happens via #onRemoval
    CachedValue<V> removed = cache.asMap().remove(key);
    return removed == null ? null : removed.value();
  }

  @Override
  public void clear() {
    cache.invalidateAll();
    ramBytes.reset();
  }

  @Override
  public int size() {
    return cache.asMap().size();
  }

  @Override
  public void close() throws IOException {
    SolrCache.super.close();
    cache.invalidateAll();
    cache.cleanUp();
    if (executor instanceof ExecutorService) {
      ((ExecutorService) executor).shutdownNow();
    }
    ramBytes.reset();
  }

  @Override
  public int getMaxSize() {
    return maxSize;
  }

  @Override
  public void setMaxSize(int maxSize) {
    if (this.maxSize == maxSize) {
      return;
    }
    Optional<Eviction<K, CachedValue<V>>> evictionOpt = cache.policy().eviction();
    if (evictionOpt.isPresent()) {
      Eviction<K, CachedValue<V>> eviction = evictionOpt.get();
      eviction.setMaximum(maxSize);
      this.maxSize = maxSize;
      initialSize = Math.min(1024, this.maxSize);
      description = generateDescription(this.maxSize, initialSize);
      cache.cleanUp();
    }
  }

  @Override
  public int getMaxRamMB() {
    return maxRamBytes != Long.MAX_VALUE ? (int) (maxRamBytes / 1024L / 1024L) : -1;
  }

  @Override
  public void setMaxRamMB(int maxRamMB) {
    long newMaxRamBytes = maxRamMB < 0 ? Long.MAX_VALUE : maxRamMB * 1024L * 1024L;
    if (newMaxRamBytes != maxRamBytes) {
      maxRamBytes = newMaxRamBytes;
      Optional<Eviction<K, CachedValue<V>>> evictionOpt = cache.policy().eviction();
      if (evictionOpt.isPresent()) {
        Eviction<K, CachedValue<V>> eviction = evictionOpt.get();
        if (!eviction.isWeighted()) {
          // rebuild cache using weigher
          cache = buildCache(cache);
          return;
        } else if (maxRamBytes == Long.MAX_VALUE) {
          // rebuild cache using maxSize
          cache = buildCache(cache);
          return;
        }
        eviction.setMaximum(newMaxRamBytes);
        description = generateDescription(this.maxSize, initialSize);
        cache.cleanUp();
      }
    }
  }

  @Override
  public void warm(SolrIndexSearcher searcher, SolrCache<K, V> old) {
    if (regenerator == null) {
      return;
    }

    long warmingStartTime = System.nanoTime();
    Map<K, CachedValue<V>> hottest = Collections.emptyMap();
    TopKeysTrackingCache<K, V> other = (TopKeysTrackingCache<K, V>) old;

    // warm entries
    if (isAutowarmingOn()) {
      int size = autowarm.getWarmCount(other.cache.asMap().size());
      hottest =
          other.cache.policy().eviction().map(p -> p.hottest(size)).orElse(Collections.emptyMap());
    }

    for (Entry<K, CachedValue<V>> entry : hottest.entrySet()) {
      try {
        boolean continueRegen =
            regenerator.regenerateItem(
                searcher, this, old, entry.getKey(), entry.getValue().value());
        if (!continueRegen) {
          break;
        }
      } catch (Exception e) {
        log.error("Error during auto-warming of key: {}", entry.getKey(), e);
      }
    }

    hits.reset();
    inserts.reset();
    lookups.reset();
    CacheStats oldStats = other.cache.stats();
    priorStats = oldStats.plus(other.priorStats);
    priorHits = oldStats.hitCount() + other.hits.sum() + other.priorHits;
    priorInserts = other.inserts.sum() + other.priorInserts;
    priorLookups = oldStats.requestCount() + other.lookups.sum() + other.priorLookups;
    warmupTime =
        TimeUnit.MILLISECONDS.convert(System.nanoTime() - warmingStartTime, TimeUnit.NANOSECONDS);
  }

  /** Returns the description of this cache. */
  private String generateDescription(int limit, int initialSize) {
    return String.format(
        Locale.ROOT,
        "Key-tracking Caffeine Cache(maxSize=%d, initialSize=%d, topKeys=%d%s)",
        limit,
        initialSize,
        topKeysLimit,
        isAutowarmingOn() ? (", " + getAutowarmDescription()) : "");
  }

  @Override
  public boolean isRecursionSupported() {
    return async;
  }

  //////////////////////// Key tracking //////////////////////

  /**
   * The {@code topKeys} entries with the most hits, most-hit first, each numbered from 1. Ties
   * break on the key's string form so that the order is stable rather than dependent on hash
   * iteration.
   *
   * <p>This is a snapshot of a live cache taken without locking it: entries may be evicted or hit
   * while it is being built, so treat the counts as a close estimate, not a consistent instant.
   */
  public List<TopKey<K>> topKeysSnapshot() {
    List<TopKey<K>> hottest =
        cache.asMap().entrySet().stream()
            .map(
                entry ->
                    new TopKey<>(
                        0,
                        entry.getKey(),
                        entry.getValue().getHits(),
                        RamUsageEstimator.sizeOfObject(entry.getKey())
                            + RamUsageEstimator.sizeOfObject(entry.getValue()),
                        entry.getValue().value().getClass()))
            .sorted(
                Comparator.comparingLong(TopKey<K>::hits)
                    .reversed()
                    .thenComparing(topKey -> Objects.toString(topKey.key())))
            .limit(topKeysLimit)
            .toList();

    List<TopKey<K>> numbered = new ArrayList<>(hottest.size());
    for (int i = 0; i < hottest.size(); i++) {
      numbered.add(hottest.get(i).withIndex(i + 1));
    }
    return numbered;
  }

  /** Cap on the length of the key's string form, as rendered by {@link TopKeysRequestHandler}. */
  int getKeyMaxLength() {
    return keyMaxLength;
  }

  /**
   * One entry of a {@link #topKeysSnapshot()}. {@code index} is 1-based; 0 means "not ranked yet".
   */
  public record TopKey<K>(int index, K key, long hits, long sizeBytes, Class<?> valueType) {

    TopKey<K> withIndex(int index) {
      return new TopKey<>(index, key, hits, sizeBytes, valueType);
    }

    public String typeName() {
      return valueType == null ? Object.class.getName() : valueType.getName();
    }
  }

  /** A cached value plus the number of times it has been served. */
  public static final class CachedValue<V> implements Accountable {
    private final V value;
    private final LongAdder hits = new LongAdder();

    CachedValue(V value) {
      this.value = value;
    }

    V value() {
      return value;
    }

    long getHits() {
      return hits.sum();
    }

    void recordHit() {
      hits.increment();
    }

    @Override
    public long ramBytesUsed() {
      return RamUsageEstimator.sizeOfObject(value)
          + RamUsageEstimator.shallowSizeOfInstance(CachedValue.class)
          + RamUsageEstimator.shallowSizeOfInstance(LongAdder.class);
    }

    @Override
    public Collection<Accountable> getChildResources() {
      if (value instanceof Accountable accountable) {
        return accountable.getChildResources();
      }
      return Accountable.super.getChildResources();
    }
  }

  //////////////////////// SolrInfoBean methods //////////////////////

  @Override
  public String getName() {
    return TopKeysTrackingCache.class.getName();
  }

  @Override
  public String getDescription() {
    return description;
  }

  // for unit tests only
  MetricsMap getMetricsMap() {
    return cacheMap;
  }

  @Override
  public SolrMetricsContext getSolrMetricsContext() {
    return solrMetricsContext;
  }

  @Override
  public String toString() {
    return name() + (cacheMap != null ? cacheMap.getValue().toString() : "");
  }

  @Override
  public void initializeMetrics(SolrMetricsContext parentContext, String scope) {
    solrMetricsContext = parentContext.getChildContext(this);
    cacheMap =
        new MetricsMap(
            map -> {
              if (cache != null) {
                CacheStats stats = cache.stats();
                long hitCount = stats.hitCount() + hits.sum();
                long insertCount = inserts.sum();
                long lookupCount = stats.requestCount() + lookups.sum();

                map.put(LOOKUPS_PARAM, lookupCount);
                map.put(HITS_PARAM, hitCount);
                map.put(HIT_RATIO_PARAM, hitRate(hitCount, lookupCount));
                map.put(INSERTS_PARAM, insertCount);
                map.put(EVICTIONS_PARAM, stats.evictionCount());
                map.put(SIZE_PARAM, cache.asMap().size());
                map.put("warmupTime", warmupTime);
                map.put(RAM_BYTES_USED_PARAM, ramBytesUsed());
                map.put(MAX_RAM_MB_PARAM, getMaxRamMB());

                CacheStats cumulativeStats = priorStats.plus(stats);
                long cumLookups = priorLookups + lookupCount;
                long cumHits = priorHits + hitCount;
                map.put("cumulative_lookups", cumLookups);
                map.put("cumulative_hits", cumHits);
                map.put("cumulative_hitratio", hitRate(cumHits, cumLookups));
                map.put("cumulative_inserts", priorInserts + insertCount);
                map.put("cumulative_evictions", cumulativeStats.evictionCount());
              }
            });
    solrMetricsContext.gauge(cacheMap, true, scope, getCategory().toString());
  }

  private static double hitRate(long hitCount, long lookupCount) {
    return lookupCount == 0 ? 1.0 : (double) hitCount / lookupCount;
  }
}
