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

Apache Solr Custom Plugins
==========================

Introduction
------------
Custom Solr plugins for this deployment. This is a single Solr module — one Gradle subproject holding
all of the plugins:

    custom-plugins/
      build.gradle    <- java-library; also hosts the dockerBuild task
      src/java/       <- plugin sources
      src/test/       <- tests
      docker/         <- compose file for a SolrCloud cluster on the custom image

Adding a plugin
---------------
Add the class under `src/java/` and register it in your configset's `solrconfig.xml`. That's it — no
build changes: new classes land in the same module jar, which is already packaged and already on the
classpath.

Sources go in `src/java/` and tests in `src/test/` — **not** `src/main/java`; the repo rewires the
source layout in `gradle/java/folder-layout.gradle`.

New third-party dependencies are the one case that needs more: add them to `build.gradle`, and on a
`main`-based branch also refresh the lock file (see "Rebasing onto a Solr release" below — 9.x has no
dependency locking and this task does not exist there):

    ./gradlew :solr:modules:custom-plugins:resolveAndLockAll --write-locks

How the packaging works
-----------------------
This project is a direct child of `:solr:modules`, which is all it takes. `gradle/solr/packaging.gradle`
gives every project under that path a `packaging` configuration and an `assemblePackaging` task that
collects the module jar plus its non-Solr runtime dependencies; `solr/packaging/build.gradle` then
iterates the children of `:solr:modules` and sweeps them into the full distribution.

So the plugins land at `modules/custom-plugins/lib/` in the distribution and
`/opt/solr/modules/custom-plugins/lib/` in the Docker image, with no packaging or Docker
configuration of our own. The only build wiring is the one `include` line in the root
`settings.gradle`.

Building the Docker image
-------------------------
This builds Solr from source (including any modifications to core Solr in this fork), packages the
full distribution, and tags a Docker image:

    ./gradlew :solr:modules:custom-plugins:dockerBuild -Psolr.docker.imageName=myorg/solr:dev

Keep the image name in sync with `SOLR_IMAGE` in `docker/.env`.

Do not build the slim distribution (`-Psolr.docker.dist=slim`): only the full distribution — the
default — contains `modules/`.

On a `main`-based branch, if you add or change dependencies you must regenerate the lock file or the
build will fail (9.x branches have no dependency locking — nothing to do there):

    ./gradlew :solr:modules:custom-plugins:resolveAndLockAll --write-locks

Running a SolrCloud cluster
---------------------------
`docker/docker-compose.yml` starts 2 Solr nodes against 1 ZooKeeper and creates a `test` collection:

    cd docker && docker compose up -d

Solr is then on http://localhost:8981 and http://localhost:8982.

Loading the plugins at runtime
------------------------------
Set `SOLR_MODULES=custom-plugins` — the compose file already does. That puts
`modules/custom-plugins/lib/*.jar` on Solr's shared classloader. Solr refuses to start if the module
directory is missing, so a node that comes up healthy proves the jar made it into the image.

Use `SOLR_MODULES` rather than `<lib dir="..."/>` in `solrconfig.xml`: the `<lib>` directive still
works on 9.x but was removed in Solr 11 (logged and ignored), so relying on it means the fork breaks
the next time it is rebased forward. Plugins are still *registered* in `solrconfig.xml` the usual
way, e.g.:

    <searchComponent name="echo" class="org.apache.solr.custom.EchoSearchComponent"/>

`EchoSearchComponent` is a placeholder that echoes `"custom-plugins":"loaded"` into the response, so
the pipeline can be smoke-tested end to end. Delete it once real plugins land here.

Rebasing onto a Solr release
---------------------------
This fork is a **patch series on top of an upstream base**: the plugins above, plus whatever
modifications to core Solr this deployment needs. Moving to a new Solr release means replaying that
series onto a new base.

**Rebase, never merge.** Merging upstream into the fork tangles the history and — critically — leaves
you unable to retarget onto a *different line of development*. The 9.x line is not an ancestor of
`main`; it is a sibling that diverged in January 2022. Only a clean commit stack is portable to an
arbitrary base.

Keep the stack small and each commit one logical change: one commit for this `custom-plugins`
subtree (new files, essentially conflict-free), then one commit per core modification.

### Moving to a new base

    git fetch upstream --tags
    git switch -c custom/<new-version> custom/<old-version>
    git rebase --onto releases/solr/<new-version> \
        $(git merge-base custom/<old-version> upstream/main) \
        custom/<new-version>

`git rebase --onto` takes three arguments with three *different* jobs:

| Argument | Role |
|---|---|
| `--onto releases/solr/<new-version>` | where the commits land — the new base |
| `$(git merge-base ...)` | where to **cut** — replay only commits after this point |
| `custom/<new-version>` | the branch to rewrite |

The middle argument is only a selector: it picks the range `<cut>..<branch>`, i.e. exactly the
commits added since the fork point. `--onto` is **not optional** here. Plain
`git rebase releases/solr/<tag>` would use the tag as both the cut point and the destination, and
since `main` and 9.x diverged in 2022, that selects several *thousand* upstream commits and tries to
replay all of Apache Solr's main-line development onto the release tag.

Keep one branch per base (`custom/main`, `custom/9.10.1`, ...); the differences below mean no single
branch can serve both lines.

### Check `settings.gradle` first, every time

The plugins are new files and cannot conflict — with one exception. The `include` line in the root
`settings.gradle` is the **only** upstream file this subtree touches, and it is the one thing a
rebase can silently drop: resolving that conflict in upstream's favour discards the line, the rebase
reports success, and the plugins become invisible to Gradle. They are not built, not packaged, and
not in the image, with no error at build time.

So after every rebase, before anything else:

    grep custom-plugins settings.gradle

If it is missing, re-add it (it lives at the end of the include list to keep it clear of upstream's
churn):

    include "solr:modules:custom-plugins"

The failure is at least loud at runtime: Solr refuses to start when a name in `SOLR_MODULES` has no
matching module directory, so the cluster will not come up rather than quietly serving without your
plugins.

### Turn on rerere

    git config rerere.enabled true

You will replay the same commits onto every new release. `rerere` memorizes each conflict resolution
and replays it automatically, so each core-code conflict is solved roughly once rather than once per
release. This is the single highest-value setting for maintaining this fork.

### What differs between bases

The plugin layout itself is portable — 9.x and `main` share both the `:solr:modules:` path-prefix
convention and the direct-children packaging sweep that this container project works around. What
does change:

| | 9.x | `main` |
|---|---|---|
| Dependency locking | **none** — `resolveAndLockAll` does not exist | required; a stale/missing `gradle.lockfile` fails the build |
| Minimum Java | 11 | 21 |
| `<lib dir="..."/>` in solrconfig.xml | works | removed — logged and ignored |

The `gradle.lockfile`s are deliberately kept in the tree on *all* branches: they are inert on 9.x
(nothing reads them) and mandatory on `main`. Deleting them on a 9.x branch would only create a
divergence to re-resolve on every rebase. Regenerate them whenever you land back on `main`.

Plugin code must compile at this base's minimum Java level — Java 11 on 9.x means no records, no
newer syntax.

### After rebasing

Confirm the whole pipeline still works on the new base:

    ./gradlew :solr:modules:custom-plugins:test
    ./gradlew :solr:modules:custom-plugins:dockerBuild -Psolr.docker.imageName=myorg/solr:dev
    cd docker && docker compose up -d

A node that comes up healthy proves the plugins were packaged and loaded, since Solr refuses to start
if a name in `SOLR_MODULES` has no matching module directory.

### The real lever: keep the core diff small

All of the rebase cost lives in the core modifications; the plugins are new files and cost nothing.
So every core edit avoided is tax you stop paying forever:

- **Upstream what you can.** A patch merged into Apache Solr is a patch you never rebase again — the
  only strategy that reduces the work to zero.
- **Prefer an extension point over a core edit.** Anything expressible as a plugin moves from the
  conflict-prone zone to the conflict-free one.
- If a core edit exists only to *expose* something (widen a visibility modifier, add a hook), keep it
  as a tiny standalone commit. Those rebase almost silently, and are the easiest to upstream.
