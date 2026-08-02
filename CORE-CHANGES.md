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

Core Solr modifications
=======================

This fork carries two kinds of change on top of Apache Solr. The plugins — all new files under
`solr/modules/custom-plugins/` — are documented in
[`solr/modules/custom-plugins/README.md`](solr/modules/custom-plugins/README.md). **This file
documents the other kind: edits to upstream Solr source.**

They are kept separate because they cost differently. A plugin is a new file and never conflicts. An
edit to an upstream file is a conflict to re-resolve on every rebase, forever — so every entry below
records *why it could not be a plugin*, and whether it could be upstreamed (a patch merged into
Apache Solr is a patch you never rebase again).

The current base is `releases/solr/9.10.1`; the fork point is `c135e6335c7`. The complete,
authoritative core diff is therefore:

    git diff c135e6335c7..HEAD -- . ':(exclude)solr/modules/custom-plugins'

At a glance
-----------

| # | Change | Upstream files edited | New files | Upstreamable? |
|---|---|---|---|---|
| 1 | [Segment-level query cache](#1-node-wide-segment-level-lucene-query-cache) | `CoreContainer`, `NodeConfig`, `SolrXmlConfig`, `SolrCore`, `SolrIndexSearcher`, `solr.xml`, `TestSolrXml`, `configuring-solr-xml.adoc` | `TestSegmentQueryCache`, changelog entry | Yes — strong candidate |
| 2 | [In-progress backup status](#2-in-progress-backup-status-on-replicationcommanddetails) | `SnapShooter`, `TestSnapshotCoreBackup` | — | Yes — bug fix, one file |
| 3 | [Coordinator fixes](#3-coordinator-node-fixes) | `CoordinatorHttpSolrCall` | — | Partly |
| 4 | [`name=` local param](#4-name-local-param-for-queries) | `QParser` | `NamedQueries` | Already upstream on `main` |
| 5 | [Docker debug packages](#5-docker-image-extra-packages) | `Dockerfile.body.template` | — | No — deployment-specific |
| 6 | [Build & dependency wiring](#6-build-and-dependency-wiring) | `settings.gradle`, `versions.props`, `versions.lock` | `solr/licenses/*`, `CLAUDE.md`, this file | No — fork bookkeeping |

Sections are ordered by rebase cost, largest first.


1. Node-wide segment-level Lucene query cache
---------------------------------------------
Commits `34df03f9479` (initial) and `3620c73e2f9` (reworked to the current shape).

An optional `LRUQueryCache` owned by the node and shared by every core, sitting alongside — not
replacing — the per-core `filterCache`. Its entries are **per segment**, so they stay valid across
commits for segments that did not change, with no warm-up. The `filterCache` caches results for the
whole index and is thrown away on every commit.

This is the largest core edit: five upstream Java files, because Solr has no extension point for the
Lucene-level query cache. `SolrIndexSearcher` hardcodes `setQueryCache(null)`, and nothing in
`solr.xml` or `solrconfig.xml` can reach it.

### What each file does

| File | Change |
|---|---|
| `solr/core/.../core/CoreContainer.java` | Holds the shared `LRUQueryCache` (`getSegmentQueryCache()`, null when disabled). Built during `load()` only when `enableSegmentQueryCache` is true **and** `segmentQueryCacheMaxRamBytes > 0` — the flag alone logs a warning and leaves the cache off. Also registers six metrics gauges under `CACHE/segmentQueryCache`: `hits`, `misses`, `inserts`, `evictions`, `size`, `ramBytesUsed`. |
| `solr/core/.../core/NodeConfig.java` | Three fields, their getters, three builder setters, and `DEFAULT_SEGMENT_QUERY_CACHE_COUNT = 10_000`. |
| `solr/core/.../core/SolrXmlConfig.java` | Three new `case` labels in `fillSolrSection`, plus package-private `parseMemoryBytes()`. |
| `solr/core/.../core/SolrCore.java` | One `UsageTrackingQueryCachingPolicy` per core (`getSegmentQueryCachingPolicy()`). Per-core rather than per-node so query usage history survives searcher reopens — the same shape Elasticsearch uses per shard. |
| `solr/core/.../search/SolrIndexSearcher.java` | Replaces the unconditional `setQueryCache(null)` with the node cache plus the core's policy. Still falls back to `null` when disabled — Lucene's *static default* query cache must never be inherited. Attached even for realtime searchers: it is a shared singleton, so there is no per-searcher cost. |
| `solr/server/solr/solr.xml` | The three sysprop-backed config entries. |
| `solr/solr-ref-guide/.../configuring-solr-xml.adoc` | Reference documentation for all three. |
| `changelog/unreleased/segment-query-cache.yml` | Changelog entry. |

`NodeConfig`'s constructor gained three parameters. That is the likeliest conflict in this whole
feature — upstream edits that constructor regularly.

### Configuration

    <bool name="enableSegmentQueryCache">${solr.segmentQueryCache.enabled:false}</bool>
    <str  name="segmentQueryCacheMaxRam">${solr.segmentQueryCache.maxRam:}</str>
    <int  name="segmentQueryCacheCount">${solr.segmentQueryCache.count:10000}</int>

`segmentQueryCacheMaxRam` is parsed by `SolrXmlConfig.parseMemoryBytes()` and accepts a percentage of
max heap (`10%`), a suffixed size (`512k`, `512m`, `1g`), or plain bytes. Empty or `0` means
disabled, regardless of the flag.

### Two Lucene behaviours worth knowing

`LRUQueryCache` deliberately caches far less than a `filterCache` does, which surprises people
watching the hit count stay at zero:

- **Only *costly* filters are cached** — range, prefix/wildcard, and other multi-term queries. A
  plain single-term `fq=field:value` is never cached, on the grounds that it is already fast.
- **Only segments of at least 10,000 documents are cached**, so small cores may never populate it.

Frequently used filter queries can end up in both caches, so revisit `filterCache` sizing when
turning this on.

### Tests

- `solr/core/src/test/.../search/TestSegmentQueryCache.java` (new) — six tests covering sharing
  across cores with a per-core policy, policy survival across searcher reopen, realtime searchers,
  that a filter query is actually cached, disabled-by-default, and max-ram-without-flag.
- `TestSolrXml` — `testParseMemoryBytes`, `testSegmentQueryCacheConfig`, and
  `testSegmentQueryCacheInShippedSolrXml`, the last of which parses the `solr.xml` we actually ship
  so a typo in it is caught here rather than on a live node.

### Upstreaming

The best candidate in this file. It is a self-contained feature with reference docs, a changelog
entry and tests already written to upstream standards.


2. In-progress backup status on `/replication?command=details`
--------------------------------------------------------------
Commit `757b3e0350b`. One upstream file: `solr/core/.../handler/SnapShooter.java`.

**The problem.** The `backup` key in the `command=details` response only appeared once a backup had
*finished*. Worse, until then it still held the **previous** backup's `"status": "success"` — so
anything polling right after issuing `command=backup` read "already done" and stopped.

**The fix.** The plumbing already existed and was simply only used once. `createSnapAsync` takes a
`Consumer<NamedList<?>>` that `ReplicationHandler` wires to its `volatile snapShootDetails` field,
which `getReplicationDetails` already publishes — it was just called a single time, at the end of the
snapshot. That consumer is now kept in a `volatile progressListener` field and emitted through as the
copy loop advances. **`ReplicationHandler` is unchanged**, which is what keeps this edit to one file.

Three shapes are reported:

| `status` | When | Extra keys |
|---|---|---|
| `waiting for commit` (`WAITING_FOR_COMMIT_STATUS`) | Published **synchronously**, before the worker thread starts | none |
| `running` (`RUNNING_STATUS`) | After each `backupRepo.copyFileFrom` | `fileCount`, `finishedFileCount` |
| `success` / exception payload | Unchanged from upstream | unchanged |

The synchronous publish is the part that matters: it is what makes a stale `success` unreadable as
the new backup's result. It carries no file counts because the index commit — and with it the list of
files to copy — has not been resolved yet. All three carry `startTime`, `directoryName`, and
`snapshotName`; a null `snapshotName` is omitted rather than reported, matching how
`CoreSnapshotResponse` renders a completed snapshot via `putIfNotNull`.

Test: `TestSnapshotCoreBackup#testBackupReportsProgressWhileRunning`.

**Why not a plugin.** `SnapShooter` is instantiated directly by `ReplicationHandler` and the backup
APIs; there is no factory or hook to substitute one.

**Upstreaming.** Good candidate — a bug fix in a single file, with a test, no API change, purely
additive to the response.


3. Coordinator node fixes
-------------------------
Commit `7ed9329fb8e`. One upstream file: `solr/core/.../servlet/CoordinatorHttpSolrCall.java`. Two
unrelated changes that happen to live in the same class:

- **`getCoreByCollection`** drops the `if (!path.endsWith("/select")) return null;` guard, so
  coordinator mode works for request handlers other than `/select`. It also gains a null/blank
  `collectionName` guard before touching the field, avoiding an NPE on requests that carry no
  collection.
- **A new `writeResponse` override** adds `requestCoordinatorNode` (the node's host name) to the
  `debug`/`track` section of the response, making it explicit which node coordinated the request.
  Only present when `debug=track` was requested.

No test. The override is indented four-space rather than google-java-format — `./gradlew tidy` will
reformat it, which is fine but will show up as an unrelated-looking diff.

**Upstreaming.** The `/select` restriction removal looks like a genuine upstream bug fix and is worth
proposing. The `requestCoordinatorNode` debug field is deployment-specific and probably stays here.


4. `name=` local param for queries
----------------------------------
Commits `f9b97e74f8e` and `e7e2c0660fc`. One upstream file edited —
`solr/core/.../search/QParser.java` — plus one new file, `solr/core/.../search/NamedQueries.java`.

A `name=` local param on any parser at any nesting depth, wired into `QParser.getQuery()`: the query
is wrapped in Lucene's `NamedMatches` and recorded in `NamedQueries` under the request context. It has
to be a core edit because every parser and every nesting level must honour it — there is no extension
point that sees them all.

Two details that a rebase can quietly break:

- The wrap **must** come before the `extendedQuery()` handling further down `getQuery()`.
  `NamedMatches` is not an `ExtendedQuery`, so wrapping first is what lets `extendedQuery()` put a
  `WrappedQuery` (carrying the cache/cost settings) on the outside.
- The hunk is copied from upstream `main`, where this feature already lives, so it should mostly
  dissolve on a move to a `main` base. `NamedQueries.java` is a new file and costs nothing.

The consumers — `hl.matchedQueries` and the `matched_queries` component — are plugins. See
**"Named queries"** and **"The one core edit"** in
[`solr/modules/custom-plugins/README.md`](solr/modules/custom-plugins/README.md) for the full story,
including the registration snippets and response formats.


5. Docker image extra packages
------------------------------
Commit `e3ffd0d491f`. One line in `solr/docker/templates/Dockerfile.body.template`, appended to the
existing `apt-get install`:

    openjdk-17-jdk-headless dnsutils iputils-ping net-tools vim nano less moreutils
    openssh-client tcpdump strace tmux

Debugging convenience for a running cluster. `openjdk-17-jdk-headless` is the significant one: the
base image is a **JRE**, so without it there is no `jstack`, `jcmd` or `jmap` to point at a wedged
node.

This inflates the image noticeably and is a one-line conflict on every rebase (upstream edits that
package list). It is not upstreamable and is not meant to be — if image size ever matters more than
convenience, this is the first thing to drop.


6. Build and dependency wiring
------------------------------
Not code, but it conflicts, and one part of it breaks *silently*:

- **`settings.gradle`** — the single `include "solr:modules:custom-plugins"` line, deliberately at
  the end of the include list to stay clear of upstream's churn. A rebase can drop it with no error
  at all: the build then succeeds without the plugins. Run `grep custom-plugins settings.gradle`
  after every rebase — see **"Check `settings.gradle` first, every time"** in the
  [plugins README](solr/modules/custom-plugins/README.md).
- **`versions.props`** — adds `org.projectlombok:lombok=1.18.38`, `org.junit.jupiter:*=5.10.2` and
  `org.junit.vintage:junit-vintage-engine=5.10.2` for the plugins module. `versions.lock` is
  regenerated to match; a stale lock fails `:verifyLocks`.
- **`solr/licenses/`** — the corresponding checksums and license texts: seven `.sha1` files for the
  JUnit 5 artifacts plus `lombok-1.18.38.jar.sha1` and `opentest4j-1.3.0.jar.sha1`, and the license
  texts `lombok-LICENSE-MIT.txt`, `opentest4j-LICENSE-ASL.txt`, `opentest4j-NOTICE.txt`.
- **`CLAUDE.md`** and **this file** — ours, at the repo root, new files.


Keeping this file honest
------------------------
This is an inventory, and an inventory rots. After every rebase — and whenever a core edit lands —
regenerate the real diff and reconcile it against the table above:

    git diff --stat c135e6335c7..HEAD -- . ':(exclude)solr/modules/custom-plugins'

Every file in that output should be accounted for by a section here. Anything unexplained is either
an undocumented core edit or an accident from a bad conflict resolution, and both are worth finding.
