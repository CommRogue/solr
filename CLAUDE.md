# CLAUDE.md

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

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

This is a **fork of Apache Solr** used to run a customized Solr cluster. It carries two kinds of
change on top of upstream Solr:

1. **Custom plugins** — all of them in `solr/modules/custom-plugins/`.
2. **Modifications to core Solr code** — edits to upstream files (`solr/core`, `solr/solrj`, …).

Everything else in the tree is unmodified Apache Solr and should be treated as third-party code.

### Prefer plugins over core edits

**Implement new functionality as a plugin in `solr/modules/custom-plugins/` whenever possible.**
Only modify core Solr code when the change genuinely cannot be expressed through a Solr extension
point.

This is not a style preference — it is the main cost driver for this repo. The fork is maintained as
a patch series that gets rebased onto each new Solr release (see below). Plugin code is *new files*,
so it never conflicts. Every edit to an upstream file is a conflict to re-resolve on every future
rebase, forever.

When a core edit is unavoidable:

- Keep it minimal and surgical — prefer widening a visibility modifier or adding a hook over
  restructuring code.
- Keep it as its own small, self-contained commit, separate from the plugins commit.
- Consider whether it can be contributed upstream to Apache Solr. A patch that gets merged upstream
  is a patch you never rebase again — the only way to reduce its cost to zero.

## Branch layout

The fork's work lives on branches named **`custom/{TAG}`**, each based on the corresponding upstream
Solr release tag:

| Branch | Based on |
|---|---|
| `custom/9.10.1` | `releases/solr/9.10.1` |
| `custom/main` | `upstream/main` (currently 11.0.0-SNAPSHOT) |

Each branch is a small stack of commits on top of its base. `upstream` is the `apache/solr` remote;
`origin` is the fork.

**Rebase, never merge.** The 9.x line is not an ancestor of `main` — the two diverged in January 2022
and are sibling lines of development. Only a clean, rebasable commit stack can be retargeted from one
to the other; a merge history cannot. Moving the fork to a new release:

```bash
git fetch upstream --tags
git switch -c custom/<new> custom/<old>
git rebase --onto releases/solr/<new> $(git merge-base custom/<old> upstream/main) custom/<new>
```

`--onto` is mandatory here: it separates *which* commits to replay (everything after the fork point)
from *where* they land. A plain `git rebase releases/solr/<tag>` would use the tag as both, selecting
thousands of upstream commits. Enable `git config rerere.enabled true` so each conflict resolution is
remembered across rebases.

`solr/modules/custom-plugins/README.md` documents this in more detail, including the differences
between bases (9.x has no dependency locking and needs Java 11; `main` requires lock files and Java
21) and the one-line `settings.gradle` trap described below.

## The custom-plugins module

`solr/modules/custom-plugins/` is a **single Solr module** — one Gradle subproject holding all the
plugins, plus the Docker/compose setup for running a cluster on the custom image.

New plugins are added by dropping a class into `src/java/` and registering it in a configset's
`solrconfig.xml`. **No build changes are needed** — new classes land in the same module jar, which is
already packaged and already on the classpath. Only new third-party dependencies require touching
`build.gradle`.

Note that this repo rewires the Java source layout for all projects (`gradle/java/folder-layout.gradle`):
sources are in **`src/java/`** and tests in **`src/test/`**, *not* `src/main/java`.

### How it reaches the Docker image

Nothing here is bespoke — the module rides Solr's existing machinery, which is worth understanding
before changing any of it:

- `gradle/solr/packaging.gradle` gives every project whose path starts with `:solr:modules:` a
  `packaging` configuration and an `assemblePackaging` task, collecting the module jar plus its
  non-Solr runtime dependencies.
- `solr/packaging/build.gradle` iterates the children of `:solr:modules` and sweeps them into the
  **full** distribution tarball at `modules/<name>/lib/`.
- `solr/docker/build.gradle`'s `dockerBuild` pipes that tarball into `docker build -` and builds using
  the Dockerfile *inside* the tarball, which is essentially `FROM $BASE_IMAGE` + `COPY / /opt/`.

So the plugin jar ends up at `/opt/solr/modules/custom-plugins/lib/` in the image, and **core Solr
modifications need no wiring at all** — they compile into the jars the distribution is built from.

The single point of build wiring is one `include` line in the root `settings.gradle`. It is
deliberately placed at the **end** of the include list, away from the churn-prone module list that
upstream keeps editing. **This line is the only upstream file the plugins touch, and a rebase can
silently drop it** — resolving that conflict in upstream's favour discards it, the rebase reports
success, and the plugins vanish from the build with no error. After any rebase:

```bash
grep custom-plugins settings.gradle
```

### Loading plugins at runtime

Plugins are loaded via **`SOLR_MODULES=custom-plugins`** (already set in the compose file), which puts
`modules/custom-plugins/lib/*.jar` on Solr's shared classloader. Solr fails to start if a named module
directory is missing, so a healthy node proves the jar made it into the image.

Do **not** use `<lib dir="..."/>` in `solrconfig.xml`. It still works on 9.x but was removed in Solr 11
(logged and ignored), so relying on it breaks the fork the next time it is rebased forward. Plugins are
still *registered* in `solrconfig.xml` the normal way (`<searchComponent>`, `<requestHandler>`, …).

## Commands

The build requires a JDK matching the base branch (Java 11+ on 9.x; Java 21–23 on `main`). If the
default JDK is wrong, set `JAVA_HOME` for the command.

```bash
# Build + test the plugins module
./gradlew :solr:modules:custom-plugins:test

# A single test class, or a method, or a package
./gradlew -p solr/modules/custom-plugins test --tests EchoSearchComponentTest
./gradlew -p solr/core test --tests "org.apache.solr.index.*"
./gradlew -p solr/core test --tests TestDemo -Ptests.iters=5   # repeat, for flaky tests

# Auto-format (spotless/google-java-format). Wildcard imports are a hard error.
./gradlew tidy

# Full validation: rat (licenses), forbiddenApis, javadoc, ecjLint, source patterns, spotless
./gradlew precommit
```

RAT (license-header validation) **only scans files tracked by git**, so `git add` new files before
running it or it will pass without seeing them. Every new `.java`/`.gradle`/`.sh`/`.yml`/`.md` file
needs the ASF license header.

### Docker image and cluster

```bash
# Builds Solr from source (core mods + plugins), packages the full dist, tags an image
./gradlew :solr:modules:custom-plugins:dockerBuild -Psolr.docker.imageName=myorg/solr:dev

# 2 Solr nodes + 1 ZooKeeper, creates a 'test' collection
cd solr/modules/custom-plugins/docker && docker compose up -d
```

Do not build the slim distribution (`-Psolr.docker.dist=slim`): only the **full** distribution — the
default — contains `modules/`, so the plugins would be silently absent.

Keep the image name in sync with `SOLR_IMAGE` in `solr/modules/custom-plugins/docker/.env`.
