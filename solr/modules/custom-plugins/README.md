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


| What upstream Solr does | Why it breaks | What `build.gradle` does |
|---|---|---|
| `-proc:none` tree-wide (`gradle/java/javac.gradle`) | The code is Lombok-annotated; every generated getter and constructor would silently vanish | Drops the flag, puts Lombok on the annotation processor path |
| Compiles at `--release 11` | The code uses records and switch expressions | Compiles this module at `--release 17` |
| `rat` | The plugins carry no ASF license header | **Not run** for this module |
| `ecjLint` | ECJ has no Lombok support, so it sees the un-generated code and errors on every generated getter | **Not run** for this module |
| `renderJavadoc` | The plugins have no `package-info.java`, which the missing-doclet requires | **Not run** for this module |
| spotless (google-java-format, no wildcard imports) | Upstream is palantir-formatted and uses wildcard imports | Excludes `**/org/commrogue/indexanalyzer/**` — the one check that *can* be scoped, so our own code stays formatted |


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

    <searchComponent name="matched_queries" class="org.commrogue.namedqueries.MatchedQueriesComponent"/>

The index-analyzer plugin
------------------------
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

The basic query parsers
-----------------------
`src/java/org/commrogue/basicqparsers/**` adds three query parsers, registered in the configset's
`solrconfig.xml`:

    <queryParser name="basic_exact" class="org.commrogue.basicqparsers.BasicExactQParserPlugin"/>
    <queryParser name="basic_range" class="org.commrogue.basicqparsers.BasicRangeQParserPlugin"/>
    <queryParser name="basic_text"  class="org.commrogue.basicqparsers.BasicTextQParserPlugin"/>

All three take a required `field` local param:

| Parser | Query | Field types |
|---|---|---|
| `basic_exact` | `{!basic_exact field=sku value=ABC-123}` | non-tokenized text, numeric, date |
| `basic_range` | `{!basic_range field=price gte=10 lt=100}` | numeric, date |
| `basic_text` | `{!basic_text field=title}(hello "two words")` | tokenized text |

`basic_exact` passes `value` straight to the field type, so it is never analyzed — the whole string
must match. `basic_range` takes any of `gt`/`gte`/`lt`/`lte` (at least one; `gt` and `gte` are mutually
exclusive, as are `lt` and `lte`). `basic_text` requires the query be parenthesised: bare terms become
`SHOULD` clauses and `"quoted phrases"` become `MUST` ones, all OR'd together, so a document matching
more of them scores higher.

### Field aliases

A request param `f.<alias>.qf=fieldA fieldB` makes `<alias>` usable as `field` in any of the three
parsers; the query then fans out into a `SHOULD` over each target. Aliases may point at other aliases
(resolved recursively, cycles rejected) and are cached per request.

    q={!basic_text field=title}(bonjour)&f.title.qf=title_en title_fr

Note this shadows the real `title` field: once `title` is an alias, it is the alias that is queried.

The attribute-routing field
---------------------------
`src/java/org/commrogue/routingfield/**` adds `AttributeRoutingTextField`, a proxy field that holds
nothing itself: it analyzes the query text, looks at the **token types** the analyzer emitted, and
builds the query against a *different*, real field chosen by those types. Unlike everything else here
it is registered in the **schema**, not `solrconfig.xml`:

    <fieldType name="routed_proxy" class="org.commrogue.routingfield.AttributeRoutingTextField"
               indexed="false" stored="false"
               defaultField="title" routes="&lt;NUM&gt;=sku,&lt;EMAIL&gt;=email">
      <analyzer>
        <tokenizer class="solr.ClassicTokenizerFactory"/>
        <filter class="solr.LowerCaseFilterFactory"/>
      </analyzer>
    </fieldType>

    <field name="routed" type="routed_proxy"/>

`routes` is a comma-separated list of `tokenType=fieldName`; the **first** token whose type has a
route decides the field, and a query with no routable token goes to `defaultField`. Both args are
required, and every target is resolved against the schema at core load, so a typo is a startup error
rather than a query that quietly matches nothing. The token types are whatever the analyzer produces
— `ClassicTokenizer` above is a convenient one because it types its tokens out of the box
(`<ALPHANUM>`, `<NUM>`, `<EMAIL>`, `<HOST>`, …).

    q=routed:user@example.com     ->  email:user@example.com
    q=routed:ab-123               ->  sku:ab-123
    q=routed:hello                ->  title:hello        (no route matched)

Fields of this type **must** be `indexed="false" stored="false"`, and it is not a style rule:
`SolrQueryParserBase` only delegates to a field type when the field is not (tokenized *and* indexed),
and a `TextField` is always tokenized — so `indexed=false` is exactly what makes Solr call the field
type at all. An indexed field of this type would be analyzed against itself and never route. The type
rejects such a field at schema load.

The multi-analysis catchall
---------------------------
`src/java/org/commrogue/multianalysis/**` adds a catchall field whose **index analysis is chosen per
value**. One multivalued field can hold a stemmed copy of one field and a verbatim copy of another,
each analyzed exactly as it would have been in its source field — so a single field can be searched
in place of many, without flattening them all through one analyzer.

It is two plugins working as a pair. An update processor copies source fields into the catchall,
prefixing each copy with a fixed-width tag naming the field it came from:

    <updateRequestProcessorChain name="multi-analysis-copy">
      <processor class="org.commrogue.multianalysis.MultiAnalysisCopyFieldUpdateProcessor$Factory">
        <arr name="rules">
          <lst>
            <str name="source">title</str>
            <str name="target">catchall</str>
            <str name="useFieldAnalyzer">title</str>
          </lst>
        </arr>
      </processor>
      <processor class="solr.RunUpdateProcessorFactory"/>
    </updateRequestProcessorChain>

and a field type in the **schema** reads those tags back, dispatching each value to the index analyzer
of the field its tag names:

    <fieldType name="catchall_multi" class="org.commrogue.multianalysis.MultiAnalysisTextField">
      <analyzer type="query">
        <tokenizer class="solr.WhitespaceTokenizerFactory"/>
        <filter class="solr.LowerCaseFilterFactory"/>
      </analyzer>
    </fieldType>

    <field name="catchall" type="catchall_multi" indexed="true" stored="false" multiValued="true"/>

A stored value is `[16-char tag][payload]`, the tag being the source field's name right-padded with
spaces. The tag is consumed off the reader before analysis, so it never reaches the index as a term.

It has to be an update chain rather than a Solr `copyField` because the value written is not the
source value but the tagged one. `useFieldAnalyzer` need not equal `source` — it names whichever
field's analyzer should be applied.

Points worth knowing:

- **Declare a query analyzer on the type.** Queries carry no tag, so query-time analysis is ordinary
  and single-analyzer. Without an `<analyzer type="query">`, `FieldTypePluginLoader` never sets a
  multi-term analyzer and wildcard queries against the field will NPE.
- **Field names must fit in 16 characters** to be usable as a tag. Longer schema fields are simply not
  addressable; a *rule* that names one is a startup error, not a silent drop.
- **The catchall is not highlightable.** Offsets are relative to the payload, i.e. shifted by the tag
  width against the stored value. Immaterial for a catchall, which is `stored="false"`.
- The index analyzer is built in `inform()`, not `init()`: `IndexSchema` loads field *types* before
  *fields*, so `schema.getFields()` is still empty when a field type initializes. This is the same
  reason `AttributeRoutingTextField` resolves its routes there.

Named queries
-------------
`src/java/org/commrogue/namedqueries/**` lets a query clause be given a name, and then reports which
named clause did what. A name is attached with the `name` local param, on any parser, at any nesting
depth:

    q={!bool should='{!field f=title name=t1 v=hello}' should='{!field f=body name=b1 v=world}'}

Two consumers read those names, and both are off unless asked for:

**`hl.matchedQueries=true`** augments the pre-tag of each highlighted region with a
`data-matched-queries` attribute naming the queries whose terms produced it:

    <em data-matched-queries='[{"name":"t1","original":"title:hello","analyzed":"hello"}]'>hello</em>

`original` is the matched query's `toString()`; `analyzed` is the post-analysis term or phrase that
matched — for a stemmed field these differ from the text they highlight, which is the point. Regions
produced by no named query keep the plain pre-tag. Attribution is term-based, not positional: a
region lists every named query containing that term, even if only one of them matched there. It
requires the unified highlighter (`hl.method=unified`, the default) and this registration:

    <searchComponent name="highlight" class="solr.HighlightComponent">
      <highlighting class="org.commrogue.namedqueries.MatchedQueriesUnifiedHighlighter"/>
    </searchComponent>

**`matched_queries=true`** (or `mq=true`) adds `matched_queries_per_hit` (unique key → names that
matched it) and `matched_queries_summary` (name → the keys it matched) to the response. Register it
as a last-component:

    <searchComponent name="matched_queries" class="org.commrogue.namedqueries.MatchedQueriesComponent"/>

`MatchedQueriesComponent` is a copy of the one upstream Solr already has on `main`; on the eventual
rebase to a `main` base, drop this copy for the upstream class.

### The one core edit

`name=` itself has no extension point — every parser and every nesting level must honour it — so it
is wired into `QParser.getQuery()`, which wraps the query in Lucene's `NamedMatches` (for the
component) and records it in `NamedQueries` (for the highlighter, which needs the queries themselves,
not their matches — and a named `fq` never reaches the highlight query at all). That hunk is copied
from upstream `main`, where this feature already lives, so it should mostly dissolve on the move to a
`main` base. `NamedQueries` is a new file, so it costs nothing at rebase time. Everything else here is
a plugin.

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
