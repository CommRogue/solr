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

New third-party dependencies are the one case that needs more. Solr pins every version centrally with
[palantir consistent-versions](https://github.com/palantir/gradle-consistent-versions), so a version
in `build.gradle` is rejected — declare the dependency *without* one and put the version in the root
`versions.props`. Then regenerate the locks and register the jar's license:

    ./gradlew --write-locks    # refreshes versions.lock and the per-module gradle.lockfile
    ./gradlew updateLicenses   # writes solr/licenses/<jar>.sha1

`updateLicenses` only writes the checksum. Every jar also needs a `solr/licenses/<name>-LICENSE-<TYPE>.txt`
(and, for `ASL`, a `-NOTICE.txt`) or `validateJarLicenses` fails — the text is usually inside the jar
itself. Note `updateLicenses` prunes checksums it thinks are unused, so check `git status` afterwards
for deletions you did not intend.

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

The base image is `eclipse-temurin:21-jre-jammy`. Solr 9.x's own default is `17-jre-jammy`, so this
module's `build.gradle` seeds the `solr.docker.baseImage` project property that
`solr/docker/build.gradle` reads — done here rather than by editing that upstream file, which would be
a conflict on every rebase. An explicit `-Psolr.docker.baseImage=...`, `-D`, or
`SOLR_DOCKER_BASE_IMAGE` still overrides it.

Keep the image name in sync with `SOLR_IMAGE` in `docker/.env`.

Do not build the slim distribution (`-Psolr.docker.dist=slim`): only the full distribution — the
default — contains `modules/`.

If you add or change dependencies you must regenerate the locks or the build will fail — see
"Adding a plugin" above.

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

The index-analyzer plugin
------------------------
`src/java/org/commrogue/indexanalyzer/**` is vendored from
[solr-index-analyzer](https://github.com/jd252387/solr-index-analyzer).
It adds a request handler that reports how many bytes each field consumes in postings, DocValues,
points, term vectors, stored fields and kNN vectors, broken down per Lucene file extension.

Register it in the configset's `solrconfig.xml` and reload the collection:

    <requestHandler name="/index-analysis"
                    class="org.commrogue.indexanalyzer.IndexAnalyzerRequestHandler"
                    startup="lazy"/>

Then:

    curl "http://localhost:8981/solr/test/index-analysis?analysis=all"

`analysis` takes any comma-separated mix of `invertedIndex`, `docValues`, `pointValues`,
`termVectors`, `knnVectors`, `storedFields`, or `all`; omitting it returns an empty analysis block.
Each component has its own `*AnalysisMode` parameter trading accuracy for I/O — the structural
defaults read codec metadata, the `instrumented` modes read the actual data and are expensive on a
large index. The upstream README documents every parameter.

### It is vendored, so the build bends around it

The sources are otherwise kept **unmodified from upstream**, so that pulling in a new version stays a
copy rather than a re-port. Solr's build defaults are hostile to them on four counts, all handled in
`build.gradle` and all scoped to this module:

| What upstream Solr does | Why it breaks | What `build.gradle` does |
|---|---|---|
| `-proc:none` tree-wide (`gradle/java/javac.gradle`) | The code is Lombok-annotated; every generated getter and constructor would silently vanish | Drops the flag, puts Lombok on the annotation processor path |
| Compiles at `--release 11` | The code uses records and switch expressions | Compiles this module at `--release 17` |
| `rat` | The vendored sources carry no ASF license header | **Not run** for this module |
| `ecjLint` | ECJ has no Lombok support, so it sees the un-generated code and errors on every generated getter | **Not run** for this module |
| `renderJavadoc` | The vendored packages have no `package-info.java`, which the missing-doclet requires | **Not run** for this module |
| spotless (google-java-format, no wildcard imports) | Upstream is palantir-formatted and uses wildcard imports | Excludes `**/org/commrogue/indexanalyzer/**` — the one check that *can* be scoped, so our own code stays formatted |

#### The one edit we do make: the package

Upstream lives at `org.commrogue.*`, sprawled across the root of that namespace. Here it is moved down
into **`org.commrogue.indexanalyzer.*`**, so the namespace has room for the other plugins
(`org.commrogue.basicqparsers`, ...). That is the *only* change to the vendored sources — but it means
a re-pull is no longer a plain copy. **Re-vendoring a new version is: copy the tree in, then re-apply
the rename**, which is one `sed` over the copied files:

    sed -i -E 's/\borg\.commrogue\b/org.commrogue.indexanalyzer/g' \
        $(find src -path '*/org/commrogue/indexanalyzer/*' -name '*.java')

Nothing enforces this: forget it and the module simply fails to compile, which is a loud enough
failure to be fine.

Three consequences worth knowing:

- **Building this module needs JDK 17+**, not the JDK 11 the rest of 9.x accepts. Java 17 is safe as a
  *target* because the image this fork ships runs `eclipse-temurin:21-jre-jammy`; if the base image is
  ever moved back to a Java 11 runtime, the plugin will fail to load with `UnsupportedClassVersionError`.
- **Lombok is compile-time only** (`compileOnly` + `annotationProcessor`), so it is not packaged and
  is not on Solr's classpath at runtime.
- **`rat`, `ecjLint` and `renderJavadoc` are off for the whole module**, not just the vendored tree —
  they cannot be scoped to part of a source set. Code we write here is still formatted by spotless and
  covered by tests, but it is not license-checked, lint-checked or javadoc-checked. If that becomes a
  problem, the fix is to move the vendored tree into its own subproject and re-enable them here.

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
| Minimum Java | 11 (but this module needs 17 — see above) | 21 |
| `<lib dir="..."/>` in solrconfig.xml | works | removed — logged and ignored |

Both lines lock dependencies, so `versions.props`, `versions.lock`, the per-module `gradle.lockfile`
and `solr/licenses/` all have to stay in step on either base; a stale lock fails the build.

Plugin code must compile at this base's minimum Java level — Java 11 on 9.x — **except** where
`build.gradle` overrides it, as it does for this module (see "The index-analyzer plugin" above).

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
