<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Porting `custom/9.10.1` → Solr 10.0.0 (`custom/10.0.0`)

Date: 2026-07-17 (updated 2026-07-19)
Repo: `D:\JavaStuff\solr` (fork `CommRogue/solr`)
**Status: validated and pushed to `origin/custom/10.0.0`. Open items are in
"What's left to check" at the bottom.**

## Background

The fork's `custom/9.10.1` branch contains 15 custom commits (author: Guy Stern)
on top of Apache Solr 9.10.1. Goal: replay those commits onto the Solr **10.0.0
release tag** so the same functionality exists on Solr 10.

- 9.10.1 base commit: `c135e6335c7` (2 commits after the v9.10.1 CHANGELOG commit)
- New base: tag `releases/solr/10.0.0` = commit `6c6c48a6f78`
  (deliberately the *tag*, not `branch_10_0`, so the base is pinned to the exact
  release; moving to `branch_10_0` later is a simple
  `git rebase apache/branch_10_0 custom/10.0.0`)

## Setup steps performed

1. Added remote `apache` → `https://github.com/apache/solr.git`
2. `git fetch apache branch_10_0 --no-tags` (initially; later switched plan)
3. `git fetch apache tag releases/solr/10.0.0 --no-tags`
4. Created branch from the tag (initially named `custom/10.0`, renamed to
   `custom/10.0.0` afterwards)
5. Set repo-local git identity: `Lior Bekman <liorbek2006@gmail.com>`
   (needed as committer; original commits keep Guy Stern as author)
6. `git cherry-pick 9cd0999b193^..custom/9.10.1` — all 15 commits, oldest first

## The 15 cherry-picked commits (original → new hash)

| Original | New | Subject |
|---|---|---|
| 9cd0999b193 | 30b6432292a | Initial scaffold |
| 2151b79a4ef | e16bd613428 | Move the vendored index-analyzer into org.commrogue.indexanalyzer |
| 3e4c9f1cfc9 | 66b81bda90b | Add the basic query parsers plugin |
| d700bd65a84 | 776ed68ba44 | Remove EchoSearchComponent and package-info.java from custom plugins |
| 34df03f9479 | 2ba3369b4d1 | Add an optional node-level Lucene query cache |
| 48d0ae64a86 | 5fb0f272e95 | update |
| f9b97e74f8e | 1edd12c0a6d | Add the name= local param for queries |
| e7e2c0660fc | 0051ca15684 | Add hl.matchedQueries and the matched-queries component |
| 1a440a7db4c | 9159a414330 | top keys |
| 59925d96330 | 0c7ad2e2a04 | update readme |
| d81249bd9c4 | c7f0d5b9bfd | updates |
| 3620c73e2f9 | 91cae447d48 | Add node-wide segment-level Lucene query cache configuration |
| 7ed9329fb8e | 90f4e541b6b | Fix coordinator on request handlers other than /select |
| e3ffd0d491f | 4577541fd28 | Add additional packages to Dockerfile for enhanced functionality |
| 21e8643ade4 | b2303480d95 | Enable position increments in RoutingQueryBuilder |

## Conflicts hit and how each was resolved

### 1. `versions.props` / `versions.lock` (3 commits: e16bd613428, 66b81bda90b, c7f0d5b9bfd)
- **What happened:** Solr 10 deleted both files — the old
  palantir-consistent-versions dependency system was replaced by a Gradle
  version catalog (`gradle/libs.versions.toml`).
- **Resolution:** kept the files deleted (`git rm`).
- **Follow-up required:** the 9.10 commits registered these versions in
  `versions.props`, which now must be ported to `gradle/libs.versions.toml`:
  - `org.junit.jupiter:* = 5.10.2`
  - `org.junit.vintage:junit-vintage-engine = 5.10.2`
  - `org.projectlombok:lombok = 1.18.38`
  (Solr 10 may already provide JUnit 5 — check the catalog before adding.)

### 2. `solr/licenses/opentest4j-NOTICE.txt` (add/add, commit e16bd613428)
- **What happened:** both sides added the file — opentest4j is now an upstream
  Solr 10 dependency.
- **Resolution:** kept Solr 10's version (`git checkout --ours`).

### 3. `changelog/unreleased/node-level-lucene-query-cache.yml` (commit 2ba3369b4d1)
- **What happened:** the 10.0.0 release tree emptied `changelog/unreleased/`;
  git's rename detection tried to place the new entry under `changelog/v9.10.1/`
  which doesn't exist on this branch.
- **Resolution:** recreated `changelog/unreleased/` and put the entry there,
  matching the original commit's intent. (A later commit renames it to
  `segment-query-cache.yml` — that applied cleanly afterwards.)

### 4. `SolrXmlConfig.java` (commit 2ba3369b4d1)
- **What happened:** the hunk adding the `queryCacheMaxRam` / `queryCacheCount`
  solr.xml cases also carried the neighboring 9.x `transientCacheSize` case —
  but Solr 10 removed transient-core support entirely
  (`NodeConfig.setTransientCacheSize` no longer exists).
- **Resolution:** kept the two new query-cache cases, dropped `transientCacheSize`.
- Verified `parseMemoryBytes`, `setQueryCacheMaxRamBytes`,
  `DEFAULT_QUERY_CACHE_COUNT` all auto-merged into place.

### 5. `CoordinatorHttpSolrCall.java` (commit 90f4e541b6b)
- **What happened:** import-block collision — Solr 10 switched
  `javax.servlet` → `jakarta.servlet` imports where the commit added
  `import java.io.IOException;`.
- **Resolution:** kept both (jakarta imports + IOException).
- Verified the `writeResponse(SolrQueryResponse, QueryResponseWriter, Method)`
  override still matches Solr 10's `HttpSolrCall` signature exactly, and
  `CoreContainer.getHostName()` still exists.

### 6. `solr/docker/templates/Dockerfile.body.template` (commit 4577541fd28)
- **What happened:** both sides edited the apt-get line. Solr 10 added `curl`
  and renamed `netcat` → `netcat-openbsd`. The commit added debug/ops packages
  including `openjdk-17-jdk-headless` (Solr 9.x ran Java 17).
- **Resolution:** merged both lists, and bumped the JDK package to
  `openjdk-25-jdk-headless` — Solr 10's image base is
  `eclipse-temurin:25-jre-noble` (Java 25), and JDK attach tools
  (jstack/jmap/jcmd) must match the running JVM's major version.
  Confirmed `openjdk-25-jdk-headless` exists for Ubuntu Noble
  (version 25.0.3+9-2~24.04.2, updates/security pockets).

## Result

- `custom/10.0.0` = `releases/solr/10.0.0` + the 15 commits.
- Working tree clean; `git diff custom/9.10.1 custom/10.0.0 -- solr/modules/custom-plugins`
  is empty (module content byte-identical to the 9.10.1 branch).

## Known risks / follow-ups (not yet done)

1. **Build system port:** `custom-plugins/build.gradle` was written for the
   9.x build (versions.props / palantir). Needs adaptation to the Solr 10
   version catalog. JUnit5/Lombok versions need a home (see conflict #1).
2. **Lucene major version bump:** the `org.commrogue.indexanalyzer` package
   reads low-level Lucene index internals (BlockTree terms dictionary,
   postings, doc values, stored fields, term vectors, KNN vectors). Lucene 10/11
   changed several of these APIs — compile will reveal the damage.
3. **License checker:** the branch added `junit-jupiter-5.10.2.jar.sha1` etc.
   under `solr/licenses/`. If Solr 10 already ships different JUnit 5 versions,
   these are duplicates/stale and the license validation task will complain.
4. **Solr 10 API drift** in touched core files beyond what conflicts revealed
   (SolrIndexSearcher, QParser, NamedQueries, SolrCore, CoreContainer hooks) —
   auto-merged cleanly at the text level, but semantic drift only shows at
   compile/test time.

## Verification plan

1. `gradlew :solr:modules:custom-plugins:compileJava` (+ `compileTestJava`) —
   surface API drift. Fix what breaks.
2. `gradlew :solr:core:compileJava` — the core hooks (query cache, NamedQueries,
   coordinator fix).
3. Module unit tests: `gradlew :solr:modules:custom-plugins:test`
4. Core tests around touched code:
   - `TestSegmentQueryCache` (node-level query cache)
   - `TestSolrXml` (new solr.xml attributes)
5. Integration tests inside the module (BasicQParsersIntegrationTest,
   MultiAnalysisIntegrationTest, TopKeysIntegrationTest, NamedQueriesTest).
6. Runtime smoke test: build the docker image / run `bin/solr` with the module
   enabled, index a few docs, exercise each plugin (query parsers,
   hl.matchedQueries, top-keys handler, index-analyzer handler) by hand.
7. Coordinator behavior needs a SolrCloud setup with a coordinator node to
   verify the non-/select handler fix end-to-end.

## Post-cherry-pick work

### Branch renamed
`custom/10.0` → `custom/10.0.0` (`git branch -m`); the stale upstream tracking
(`apache/branch_10_0`) was unset since the branch is now based on the tag.

### Build system port (working-tree changes, to be committed after compile passes)

**`gradle/libs.versions.toml`** — three additions, keeping alphabetical order:
- `[versions]` `projectlombok-lombok = "1.18.38"`
- `[libraries]` `projectlombok-lombok = { module = "org.projectlombok:lombok", ... }`
- `[libraries]` `junit-vintage-engine = { module = "org.junit.vintage:junit-vintage-engine", version.ref = "junit-jupiter" }`
  (rides the existing `junit-jupiter = "5.13.4"` version — newer than the 5.10.2
  the 9.10 branch pinned, matching what Solr 10 already ships)

**`solr/modules/custom-plugins/build.gradle`** — ported to Solr 10 conventions:
- Dependencies switched from versionless GAV strings (which relied on the
  removed versions.props/palantir system) to version-catalog aliases
  (`libs.apache.lucene.core`, …), plus `implementation platform(project(':platform'))`
  as every Solr 10 module declares.
- JUnit test deps simplified: aggregate `libs.junit.jupiter` (carries api,
  params, engine) + `libs.junit.vintage.engine` + `libs.junit.junit`.
- The JavaCompile override now only removes `-proc:none` (still needed for
  Lombok). The 9.x `--release 11 → 17` bump is gone: Solr 10's floor is
  already release 21, and keeping a 17 override would *break* the build
  (module classes at release 17 can't read solr-core's 21 classfiles).
  The explicit `java { source/targetCompatibility 17 }` block was dropped too.
- The `solr.docker.baseImage` override (Temurin 21 jammy) was removed —
  Solr 10's own default is `eclipse-temurin:25-jre-noble`, which is what the
  Dockerfile's `openjdk-25-jdk-headless` addition expects.
- `useJUnitPlatform()`, the rat/ecjLint/javadoc disables and the spotless
  hold-out for the vendored index-analyzer tree are all still valid on
  Solr 10 and kept unchanged (verified `defaults-tests.gradle` still pins
  `useJUnit()`, and javac.gradle still injects `-proc:none`).

**Known remaining build item:** Solr 10 uses per-project Gradle dependency
locking (`gradle.lockfile` in every module dir) — `custom-plugins` doesn't
have one yet and will need `--write-locks` once resolution succeeds.

## Compile results

### Round 1 — FAILED in `:solr:core:compileJava`
`CoreContainer.initializeSegmentQueryCacheMetrics` used the 9.x metrics API:
`SolrMetricsContext.gauge(supplier, true, name, category, scope)` — that method
no longer exists. **Solr 10 replaced the whole Dropwizard-style metrics API with
OpenTelemetry** (`SolrMetricsContext` now deals in `ObservableLongMeasurement`s
and `batchCallback`s; see the new `org.apache.solr.metrics.otel` package).

**Fix applied** (modeled on how Solr 10's own `CaffeineCache.initializeMetrics`
does it): the six gauges became
- `solr_node_segment_query_cache_lookups` counter with `result=hit|miss` attributes
- `solr_node_segment_query_cache_ops` counter with `ops=inserts|evictions` attributes
- `solr_node_segment_query_cache_size` gauge
- `solr_node_segment_query_cache_ram_used` gauge (unit: bytes)
all recorded in one `batchCallback` with `category=CACHE, name=segmentQueryCache`
attributes. The callback is auto-registered in the context's closeables, so it is
cleaned up with the CoreContainer's metrics context — no explicit close needed.
(A separate metric-name prefix was chosen instead of reusing CaffeineCache's
`solr_node_cache_*` names because OTel dislikes same-name instruments with
different descriptions.)

**Note for whoever consumes these metrics:** the metric names/shape changed
from the 9.10 fork (`CACHE.segmentQueryCache.hits` etc. → the OTel names above).
Dashboards/alerts will need updating.

### Round 2 — FAILED in `:solr:modules:custom-plugins:compileJava` (33 errors)

Two buckets, both fixed:

**a) `TopKeysTrackingCache` (topkeys)** — same metrics migration as CoreContainer:
`MetricsMap` no longer exists and `SolrMetricProducer.initializeMetrics` changed
signature to `(SolrMetricsContext, Attributes)`. Since this class is a fork of
CaffeineCache, it was re-ported 1:1 against Solr 10's CaffeineCache: metric name
prefix `solr_topkeys_cache` (`_lookups`/`_ops`/`_size`/`_ram_used`/`_warmup_time`),
batch callback held in a `toClose` field closed in `close()`. The
`getMetricsMap()` test-only hook was removed; per-entry hit-ratio values are no
longer published (consumers can derive them from hit/miss counters).

**b) `indexanalyzer` (vendored) — Lucene 9→10 API drift**, all fixed:
- `Directory.openChecksumInput(name, IOContext)` lost the IOContext param (6 sites).
- `CompoundFormat.getCompoundReader(...)` lost the IOContext param (1 site).
- `SegmentReader.document(int, visitor)` removed → `storedFields().document(...)`
  (hoisted out of per-doc loops); same for the handler test's `document(0)`.
- `StoredFieldVisitor.binaryField(FieldInfo, DataInput, int)` →
  `binaryField(FieldInfo, StoredFieldDataInput)`.
- `FieldInfos.hasVectors()` → `hasTermVectors()`;
  `SegmentReader.getTermVectors(doc)` → `termVectors().get(doc)`.
- KNN: `Float/ByteVectorValues.nextDoc()/vectorValue()` (doc-iterator style)
  → `KnnVectorValues.DocIndexIterator` + `vectorValue(it.index())` (ordinal style).
- `Utils.getBlockTermState`: `Lucene912PostingsFormat` moved to backward-codecs;
  added the two new generations `Lucene103PostingsFormat` (core, current) and
  `Lucene101PostingsFormat` (backward-codecs) to the instanceof chain.
- **`BlockSkippingTermsAnalyzer` rewritten for the Lucene103 BlockTree format**
  (`org.apache.lucene.codecs.lucene103.blocktree`). The `.tmd` layout changed:
  codec header names/versions updated, per-field stats order changed
  (sumTotalTermFreq now unconditional, sumDocFreq conditional), and the
  FST-based RootCode + FST metadata were replaced by three plain VLongs
  (`indexStart`, `rootFP`, `indexEnd`).
  **Functional caveat:** the new metadata carries no `.tim` file pointer at all
  (`rootFP` points inside the `.tip` trie slice), so per-field dictionary (.tim)
  size attribution is impossible in block-skipping mode — it now reports 0,
  documented in the class javadoc. In exchange, per-field `.tip` size is now
  exact (`indexEnd - indexStart`) instead of a delta approximation.
  INSTRUMENTED mode remains the accurate option for .tim.

### Round 3 — module main compiles; test compile failures (10 errors)
- Lucene 10 made `BooleanClause` a record: `getOccur()`→`occur()`,
  `getQuery()`→`query()` (9 sites in the basicqparsers tests).
- `SegmentReader.document(0)` → `storedFields().document(0)` (handler test).

### Round 4 — module tests compile; core test failures (5 errors)
- `SolrQuery` moved: `org.apache.solr.client.solrj.SolrQuery` →
  `org.apache.solr.client.solrj.request.SolrQuery` (TestSegmentQueryCache).
- Solr 10 finished its File→Path migration: `ExternalPaths.SERVER_HOME` is now a
  `Path` (TestSolrXml), and `copyMinConf` takes a `Path` (TestSegmentQueryCache).

### Round 5 — **BUILD SUCCESSFUL**: module main+test and core main+test all compile.

## Test runs

### Module tests — first attempt FAILED (all executors crashed)
`JUnitException: OutputDirectoryProvider not available; probably due to
unaligned versions of the junit-platform-engine and junit-platform-launcher
jars`. Gradle injects its own (older) platform-launcher when none is declared,
clashing with platform-engine 1.13.4 from the jupiter aggregate.
**Fix:** added `junit-platform = "1.13.4"` + `junit-platform-launcher` to the
catalog (comment: keep in sync with junit-jupiter) and declared it
`testRuntimeOnly` in the module.

### Module tests — PASS ✅
`:solr:modules:custom-plugins:test` — **97 tests, all pass** (44s), including
the integration suites (MultiAnalysis, TopKeys, AttributeRouting, all four
basic query parsers) and `IndexAnalyzerRequestHandlerTest`, which indexes real
documents and runs the analyzer over genuine Lucene 10.3 segments — i.e. the
rewritten lucene103 `.tmd` parsing works against the real format.

### Core tests — PASS ✅
`:solr:core:test --tests TestSegmentQueryCache --tests TestSolrXml` —
**42 tests pass, 1 skipped.** The segment query cache works end-to-end on
Solr 10 (real cores, real queries, hit-count assertions), and the new solr.xml
attributes parse correctly.

## Final state of the branch

`custom/10.0.0` = tag `releases/solr/10.0.0` + 18 commits:
- the 15 original cherry-picked commits (author Guy Stern), then three port commits:
- `e4811f75821` Port the custom-plugins build to the Solr 10 build system
  (+ generated `solr/modules/custom-plugins/gradle.lockfile`)
- `d1967067a5f` Port cache metrics to Solr 10's OpenTelemetry metrics API
- `cae73a2cef0` Port the index-analyzer and tests to Lucene 10 / Solr 10 APIs

Verified: module main+test compile, core main+test compile, **97/97 module
tests pass, 42/42 core tests pass (1 skipped)**. Nothing pushed.

## Runtime verification (2026-07-17, all PASSED ✅)

### Docker image
`./gradlew :solr:modules:custom-plugins:dockerBuild -Psolr.docker.imageName=myorg/solr:dev`
→ built successfully on base `eclipse-temurin:25-jre-noble`, Solr
10.0.0-SNAPSHOT, full distribution — the modified Dockerfile package set
(incl. `openjdk-25-jdk-headless`) installs cleanly on noble.

**Windows gotcha found and fixed:** the extensionless scripts in
`solr/docker/scripts/` checked out with CRLF (core.autocrlf=true, no
.gitattributes rule), so the first image failed at container start
(`init-var-solr: cannot execute: required file not found` — CRLF shebang).
Fixed the working tree, added `.gitattributes` rules (`*.sh`, `solr/bin/solr`,
`solr/docker/scripts/*` → eol=lf), committed as `4f4d0ca1ca8`, rebuilt.

### SolrCloud cluster + coordinator test
Used the module's docker-compose (ZK + solr1 + solr2 + auto-created `test`
collection, 2 shards × 2 replicas) plus a scratchpad overlay adding a
`coordinator` service (`-Dsolr.node.roles=coordinator:on,data:off`, host port
8980). Indexed 5 documents. Results:

| Test | Result |
|---|---|
| `/select` via coordinator (baseline) | ✅ numFound=5 |
| **`/query` via coordinator (the fix)** | ✅ numFound=5, docs returned — pre-fix this path was refused by `getCoreByCollection` |
| `debug=track` via coordinator | ✅ `requestCoordinatorNode` key present in track section (both /select and /query) |
| `debug=track` direct to data node | ✅ no `requestCoordinatorNode` key (correct) |
| Coordinator hosts no data | ✅ only core is `.sys.COORDINATOR-COLL-test_core` (synthetic) |
| Filtered query via coordinator (`cat_s:event`) | ✅ numFound=3 (correct) |

**Known quirk (pre-existing, not a port regression):** the
`requestCoordinatorNode` *value* is an empty string in this setup —
`CoreContainer.getHostName()` returns `cfg.getNodeName()` captured at startup,
which is empty unless solr.xml/host is set; the actual node name is computed
later by ZkController. A more reliable source would be
`getZkController().getNodeName()`. Same behavior existed on the 9.10 branch.

(Ports at the time: 8980=coordinator, 8981=solr1, 8982=solr2 — since remapped,
see below. Stop the cluster with:
`docker compose --project-directory solr/modules/custom-plugins/docker -p solr-custom down -v`)

## Remaining verification ideas (superseded — see "What's left to check" below)

(The list that used to live here is folded into "What's left to check" below;
the license-sha1 item is done.)

## 2026-07-18/19: precommit green, ports remapped, branch pushed

- `./gradlew precommit` **passes**. It caught two real issues, both fixed and
  folded into their stack commits via `git commit --fixup` + non-interactive
  `git rebase --autosquash` (final tree hash verified identical before/after
  the rewrite):
  - stale JUnit 5.10.2 sha1 files under `solr/licenses/` — Solr 10 resolves
    Jupiter 5.13.4; regenerated with `./gradlew updateLicenses`, folded into
    "Move the vendored index-analyzer…" (the commit that added them);
  - spotless drift in `CoordinatorHttpSolrCall.java` and
    `BasicRangeQParserTest.java` (the 10.x google-java-format is newer than
    9.x's) — fixed with `./gradlew tidy`, folded into their commits.
- Additional core test run: `TestCoordinatorRole` passes alongside
  `TestSolrXml` / `TestSegmentQueryCache` (51 passed, 1 skipped total).
- The compose cluster now maps **solr1→8983, solr2→8984** (own commit; README
  updated). Docker e2e re-verified on the new ports.
- **Pushed:** `custom/10.0.0` (20 commits on the release tag) →
  `origin` = `CommRogue/solr`. Note: git/gh on this machine authenticate as
  `bekman121`, which initially lacked write access to the fork.

## What's left to check

Ranked by importance:

1. **Automated test for the coordinator fix.** The fix was verified by hand
   (see the coordinator table above) but has no test of its own — and it
   touches ~29 upstream lines in `CoordinatorHttpSolrCall` that must survive
   every future rebase. Write a fork-owned test class as a **new file** under
   `solr/core/src/test` (new files never conflict on rebase; don't edit
   upstream `TestCoordinatorRole`). Cover a non-`/select` handler through a
   coordinator node.
2. **Per-plugin HTTP smoke on a live cluster.** The compose `test` collection
   uses `_default`, so the Docker e2e never registered or invoked any plugin.
   Upload a configset that registers them
   (`solr/modules/custom-plugins/src/test-files/solr/collection1/conf` has
   everything), create a 2-shard collection, and hit each once: index-analysis
   handler, `hl.matchedQueries` + `name=` queries, basic qparsers, top-keys,
   and index into the multianalysis/routing field types. Two shards also
   exercise `MatchedQueriesComponent`'s distributed merge, which single-core
   unit tests cannot.
3. **Observe the segment query cache actually caching.** In the Docker e2e all
   counters read 0 — Lucene's `UsageTrackingQueryCachingPolicy` skips tiny
   segments. Index ~50k docs, repeat a few `fq` queries, confirm
   `inserts`/`hits` move at `/solr/admin/metrics?wt=prometheus`. The cache
   needs BOTH `-Dsolr.segmentQueryCache.enabled=true` AND
   `-Dsolr.segmentQueryCache.maxRam=<size>` (e.g. `64m`); enabled without
   maxRam logs a warning and stays off.
4. **Seed-randomization repeats.** Solr tests randomize per seed; one green
   run is one sample. `-Ptests.iters=5` on `TestSegmentQueryCache` and the
   plugins suite.
5. **One wide net over `org.apache.solr.search`.** The fork touches `QParser`
   and `SolrIndexSearcher`; the targeted tests can't catch interactions:
   `./gradlew -p solr/core test --tests "org.apache.solr.search.*"`
   (long, one-time).
6. **Only if the production upgrade from 9.10.1 is in-place:** boot the 10.0.0
   image against a copy of a 9.10.1 data volume and confirm the cores load.
7. **Docs are stale for the 10.x base:** `CLAUDE.md` and the module README
   still document 9.x locking (`versions.props`, `writeVersionsLocks`,
   `:verifyLocks` — none exist here). On 10.x: Gradle built-in dependency
   locking (validated implicitly at resolution, updated with `--write-locks`)
   and `./gradlew updateLicenses` for the `solr/licenses` sha1 files.
8. **Metrics consumers (operational):** dashboards scraping the 9.10 names
   (`CACHE.segmentQueryCache.*`, top-keys MetricsMap) must move to the OTel
   names (see the metrics-port section above).
9. **Optional code improvement:** coordinator `writeResponse` could use
   `getZkController().getNodeName()` instead of `getHostName()` so
   `requestCoordinatorNode` carries a real value (pre-existing quirk, see
   above).

### Windows / environment gotchas for whoever picks this up

- Quote gradle `-P` args in PowerShell (`"-Psolr.docker.imageName=..."`) —
  otherwise the dot splits the token.
- ZooKeeper state survives compose restarts (the container is reused), so the
  one-shot `init` service fails benignly with "Collection 'test' already
  exists" on every restart after the first.