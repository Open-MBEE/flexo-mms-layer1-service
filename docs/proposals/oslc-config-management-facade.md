# Proposal: an OSLC Configuration Management provider on Flexo MMS

**Status:** proposal
**Specs:** [OSLC Core 3.0](https://docs.oasis-open-projects.org/oslc-op/core/v3.0/os/oslc-core.html),
[OSLC Configuration Management 1.0](https://docs.oasis-open-projects.org/oslc-op/config/v1.0/os/config-resources.html),
[OSLC Query 3.0](https://docs.oasis-open-projects.org/oslc-op/query/v3.0/os/oslc-query.html),
[OSLC Tracked Resource Set 3.0](https://docs.oasis-open-projects.org/oslc-op/trs/v3.0/os/tracked-resource-set.html),
[OSLC Requirements Management 2.1](https://docs.oasis-open-projects.org/oslc-op/rm/v2.1/os/requirements-management-spec.html)

## Why OSLC

Flexo MMS already *is* most of an OSLC Configuration Management provider. OSLC is RDF end to end,
its Core 3.0 discovery and resource protocol are built on the W3C Linked Data Platform that
`server/LinkedDataPlatform.kt` implements, and its configuration-management vocabulary describes the
same objects `mor-graph:Metadata` holds:

| OSLC Configuration Management | Flexo MMS |
|---|---|
| `oslc_config:Component` — a unit of configuration | `mms:Repo` |
| `oslc_config:Stream` — mutable configuration | `mms:Branch` |
| `oslc_config:Baseline` — immutable configuration | `mms:Lock` |
| `oslc_config:ChangeSet` — a set of changes that `oslc_config:overrides` a stream and is delivered to it | a short-lived `mms:Branch` cut from the stream; delivery is a diff applied as a commit (see *Change sets*) |
| Global configuration — a configuration whose `oslc_config:contribution`s are configurations of other components | `mms:Collection`, whose `mms:collects` already accept branches, locks and scratches across repos (`routes/CollectionGraphResolution.kt`) |
| Version resource (`oslc_config:VersionResource`, `dcterms:isVersionOf` concept URI) | the state of an element IRI in a commit's model graph |
| `oslc_config:selections` — the version resources selected by a configuration | the set of subjects in the configuration's model graph |
| `oslc_config:derivedFrom`, `oslc_config:previousBaseline` | `mms:commit` / `mms:parent+` |
| `oslc:ServiceProvider` | `mms:Org` |
| `oslc:ServiceProviderCatalog` | the Flexo MMS instance |

The payoff is the tool ecosystem: IBM DOORS Next, Jama Connect, Polarion, Siemens Teamcenter,
PTC Windchill and codebeamer consume and expose OSLC, and *configuration-enabled* OSLC — where a
link from a requirement to a model element is resolved in a chosen baseline rather than "latest" —
is the capability those integrations most often lack on the model side. A SysML v2 model served by
Flexo through the SysML v2 API service becomes linkable from a requirement baseline in DOORS Next
without either tool importing the other's data.

## Shape of the solution

Follow the pattern established by the SysML v2 API service: a separate façade service
(`flexo-mms-oslc`) that translates OSLC requests onto the Layer 1 API, holds no state of its own,
and forwards the caller's bearer token. Layer 1 gains a small number of primitives that the façade
needs and that are useful independently of OSLC; those are listed under *Layer 1 changes* and are
the part of this proposal that lands in this repository.

```
OSLC consumer (DOORS Next, Jama, an OSLC client library)
        │  OSLC Core 3.0 / Config Mgmt 1.0 / Query 3.0 / TRS 3.0
        ▼
flexo-mms-oslc (façade: discovery, vocabulary mapping, OSLC Query → SPARQL, TRS feed)
        │  LDP / GSP / SPARQL, bearer JWT (unchanged)
        ▼
flexo-mms-layer1-service (+ commit reads, Memento, deliver-by-diff, commit feed)
        │
        ▼
quad-store
```

## Discovery (OSLC Core 3.0 §5)

| OSLC resource | URI in the façade | Backed by |
|---|---|---|
| `oslc:ServiceProviderCatalog` | `/oslc/catalog` | `GET /orgs` |
| `oslc:ServiceProvider` (one per org) | `/oslc/orgs/{orgId}` | `GET /orgs/{orgId}` |
| `oslc:Service` for `oslc_config:` domain | inside the provider | — |
| `oslc:CreationFactory` for components | `/oslc/orgs/{orgId}/components` | `POST /orgs/{orgId}/repos` |
| `oslc:QueryCapability` for components, configurations, version resources | `/oslc/orgs/{orgId}/components?oslc.where=…` etc. | SPARQL on `/orgs/{orgId}/repos/{repoId}/query` and the repo metadata graph |
| `oslc:ResourceShape`s for the above | `/oslc/shapes/{name}` | static |

Every façade response carries `OSLC-Core-Version: 3.0` and the Core 3.0 discovery `Link`
headers (`rel="http://open-services.net/ns/core#serviceProvider"` etc.) so that clients
discovering by LDP containment and clients discovering by the 2.0 catalog both work.

Authentication is the bearer JWT Layer 1 already requires. OSLC Core 3.0 mandates OAuth 2.0 /
OpenID Connect for interactive consumers; the façade delegates to the Flexo MMS Auth service for
the authorization-code flow and hands the resulting JWT to Layer 1 unchanged. No new authorization
model is introduced: whatever the token may read or write through Layer 1 it may read or write
through OSLC.

## Components, streams and baselines

### Component

```
GET /oslc/orgs/{orgId}/components/{repoId}

<…/components/{repoId}> a oslc_config:Component ;
    dcterms:title "…" ;                      # the repo's dct:title
    oslc_config:configurations <…/components/{repoId}/configurations> ;
    oslc_config:streams   <…/components/{repoId}/streams> ;     # creation factory
    oslc_config:baselines <…/components/{repoId}/baselines> .
```

`POST …/components` (creation factory) → `POST /orgs/{orgId}/repos` with the title mapped to
`dcterms:title`. The component's default stream is the repo's initial branch.

### Stream

```
GET /oslc/orgs/{orgId}/components/{repoId}/streams/{branchId}

<…/streams/{branchId}> a oslc_config:Stream, oslc_config:Configuration ;
    dcterms:title "…" ;
    oslc_config:component <…/components/{repoId}> ;
    oslc_config:derivedFrom <…/baselines/{lockId}> ;   # when the branch was cut from a lock
    oslc_config:selections <…/streams/{branchId}/selections> ;
    oslc_config:baselines  <…/streams/{branchId}/baselines> .   # creation factory
```

`POST …/streams` → `POST /orgs/{orgId}/repos/{repoId}/branches` with `mms:ref` or `mms:commit`
set from `oslc_config:derivedFrom`.

### Baseline

`POST …/streams/{branchId}/baselines` → `POST /orgs/{orgId}/repos/{repoId}/locks` with
`mms:ref morb:` (the stream's current commit). The response is

```
<…/baselines/{lockId}> a oslc_config:Baseline, oslc_config:Configuration ;
    oslc_config:baselineOfStream <…/streams/{branchId}> ;
    oslc_config:previousBaseline <…/baselines/{prevLockId}> ;
    oslc_config:committed "…"^^xsd:dateTime ;                    # lock's mms:created
    oslc_config:committer <user> ;                               # mms:createdBy
    oslc_config:selections <…/baselines/{lockId}/selections> .
```

`previousBaseline` is the most recent lock whose commit is an ancestor (`mms:parent+`) of this
lock's commit on the same branch. Baselines are immutable, which Layer 1 already enforces
(`otherwiseNotAllowed("locks")` on the lock graph route).

### Change sets

OSLC change sets are the one concept without a one-to-one counterpart. A change set is created
against a stream, accumulates edits in isolation, and is *delivered* — applied to the stream as a
unit — or discarded. Scratches are isolated but unversioned and untied to a branch, so they are
not the right primitive. The mapping is:

| OSLC | Layer 1 |
|---|---|
| `POST …/streams/{branchId}/changesets` | `POST /branches` cut from the stream's current commit, carrying `oslc_config:overrides <stream branch>` in its own metadata (see *Layer 1 changes — change-set metadata*) |
| edits in the change-set context | commits on the change-set branch |
| `oslc_config:overrides` | the originating stream |
| deliver | see *Layer 1 changes — deliver by diff* |
| discard | `DELETE /branches/{changeSetId}` |

## Configuration context (Config Mgmt 1.0 §5)

OSLC clients select a configuration with the `Configuration-Context` request header or the
`oslc_config.context` query parameter, and then dereference *concept* URIs; the provider is
responsible for returning the version that is selected in that configuration.

A concept URI for a Flexo-hosted element is `/oslc/orgs/{orgId}/components/{repoId}/resources/{id}`,
where `{id}` is the element's identifier within the model graph (for SysML v2 models, the element
`@id`; in general, a repo-configured IRI template). Resolution:

| `Configuration-Context` | Layer 1 read |
|---|---|
| a stream | `…/branches/{branchId}/query` — `describe <element>` |
| a baseline | `…/locks/{lockId}/query` |
| a global configuration | `…/collections/{collectionId}/query` |
| absent | the component's default stream |

The response carries the version resource's identity so that links captured by consumers are
stable across time:

```
<…/resources/{id}?oslc_config.context=…/baselines/{lockId}> a oslc_config:VersionResource ;
    dcterms:isVersionOf <…/resources/{id}> ;
    oslc_config:versionId "{commitId}" ;
    … element triples …
```

A version resource URI is a concept URI qualified by the *commit* the context resolves to, not by
the stream, so the same URI keeps returning the same triples after the stream moves on. Layer 1
serves these via the commit read routes introduced by the
[Memento proposal](memento-datetime-negotiation.md); the two proposals are designed together.

### Selections

`GET …/{configuration}/selections` returns an `oslc_config:Selections` resource listing the version
resources the configuration selects. For a Flexo graph that is every distinct subject, which can be
large; the façade pages it (`oslc.paging=true`, `oslc.pageSize`) with a SPARQL `select distinct ?s`
plus `offset`/`limit` against the configuration's query endpoint.

## Global configurations

A global configuration aggregates configurations of several components — a system baseline is the
requirements baseline in one component plus the architecture baseline in another. Collections
already model this: `mms:collects` accepts locks (baselines) and branches (streams) from any repo
in the org, and `…/collections/{collectionId}/query` federates over their graphs.

| OSLC | Layer 1 |
|---|---|
| `oslc_config:Configuration` with `oslc_config:contribution`s | `mms:Collection` |
| `oslc_config:Contribution` → `oslc_config:configuration` | one `mms:collects` target |
| global baseline (all contributions are baselines) | a collection whose targets are all locks |
| global stream | a collection with at least one branch target |

The façade refuses to baseline a global stream until each contributed stream has been baselined
(creating those baselines on request), which is the behaviour OSLC consumers expect.

## OSLC Query → SPARQL

The query capabilities accept OSLC Query 3.0 syntax and compile it to SPARQL against the
appropriate Layer 1 query endpoint. The translation is mechanical:

| OSLC Query | SPARQL |
|---|---|
| `oslc.prefix=dcterms=<…>` | `prefix dcterms: <…>` |
| `oslc.where=dcterms:title="Power"` | `?s dcterms:title "Power"` |
| `oslc.where=oslc_rm:validatedBy{dcterms:title="TC-1"}` | nested triple pattern on a bound object |
| comparison operators `< <= > >= !=` | `filter(?v … )` |
| `oslc.where=dcterms:created>"2026-01-01T00:00:00Z"^^xsd:dateTime` | typed literal filter |
| `oslc.searchTerms="pump"` | `filter(contains(lcase(str(?text)), "pump"))` over `dcterms:title`/`dcterms:description`; a full-text index is a later optimisation |
| `oslc.select=dcterms:title,oslc_rm:satisfiedBy{dcterms:title}` | `construct` of the selected properties, one hop deep |
| `oslc.orderBy=-dcterms:modified` | `order by desc(?v)` |
| `oslc.paging`, `oslc.pageSize` | `limit`/`offset`; `oslc:nextPage` in the response |

`Configuration-Context` selects the endpoint (branch, lock or collection) exactly as for
resource reads, so a query capability is configuration-aware for free.

## Tracked Resource Set

TRS lets consumers (an LQE/ELM index, a search service, a report engine) mirror a provider without
polling every resource. It is a *base* (the current set of resource URIs) plus a *change log*
(ordered `trs:Creation`/`trs:Modification`/`trs:Deletion` events with a monotonically increasing
`trs:order`). Layer 1's commit graph is already this change log: each `mms:Commit` records
`mms:submitted`, `mms:parent` and, through `mms:data`/`mms:insGraph`/`mms:delGraph`, exactly which
triples changed.

```
GET /oslc/orgs/{orgId}/components/{repoId}/streams/{branchId}/trs

<…/trs> a trs:TrackedResourceSet ;
    trs:base <…/trs/base> ;
    trs:changeLog [ a trs:ChangeLog ;
        trs:change [ a trs:Modification ; trs:changed <…/resources/{id}> ;
                     trs:order 41 ; trs:changedTime "…"^^xsd:dateTime ] ;
        trs:previous <…/trs/changelog?before={commitId}> ] .
```

The façade derives events from each commit's insert/delete graphs (subjects appearing only in
`mms:insGraph` are creations, only in `mms:delGraph` deletions, in both modifications) and uses the
commit's position on the branch as `trs:order`. This needs one addition to Layer 1: a read route
for a commit's delta (see below), because today `mms:insGraph`/`mms:delGraph` are internal.

## OSLC domain vocabularies (RM, QM, CM, AM)

Configuration management is the enabling layer; the domain vocabularies ride on top with no
additional Layer 1 work. A Flexo repo whose model uses `oslc_rm:Requirement`,
`oslc_qm:TestCase` or `oslc_am:Resource` as (or alongside) its element types is served by the
same façade under the corresponding `oslc:Service` domain, and the SysML v2 API service's
requirement and verification-case elements can be *projected* to `oslc_rm:` / `oslc_qm:` classes
by a CONSTRUCT-based view when a consumer asks for them, without changing the stored model. Link
properties (`oslc_rm:satisfiedBy`, `oslc_rm:validatedBy`, `oslc_qm:validatesRequirement`) are ordinary
triples whose objects are concept URIs in another provider; the façade resolves them in the caller's
configuration context per Config Mgmt 1.0.

## Layer 1 changes

Everything above the line is façade work. These are the pieces this repository would add; each is
small, and each is useful outside OSLC.

### 1. Commit reads and Memento

`GET …/commits/{commitId}/graph` and `POST …/commits/{commitId}/query`, plus `Accept-Datetime` on
branch routes, are specified in the [Memento proposal](memento-datetime-negotiation.md). Version
resource URIs and baseline reads depend on the commit routes; TimeMaps give the façade a
component's history without walking `/commits` itself.

### 2. Commit delta read

```
GET /orgs/{orgId}/repos/{repoId}/commits/{commitId}/delta
Accept: application/trig
```

Returns the commit's insert and delete graphs as two named graphs (`<…/commits/{commitId}/ins>`,
`<…/commits/{commitId}/del>`) in TriG or N-Quads. Read access is repo read access, as for the
commit itself. This is the primitive the TRS change log is built from, and it is independently
useful to any client that wants to see "what changed in this commit" without creating a diff
resource between two arbitrary refs.

### 3. Deliver by diff

Delivery of a change set is: compute the change set's net effect relative to where it started,
and apply that as one commit to the target stream, failing if the stream moved in a way that
conflicts.

```
POST /orgs/{orgId}/repos/{repoId}/branches/{targetBranchId}/deliver
Content-Type: text/turtle
If-Match: "<target branch etag>"

<> mms:source morb-changeset: .          # the change-set branch
```

Semantics:

1. Compute `diff(base, head)` where `base` is the commit the change-set branch was cut from and
   `head` is its current commit — exactly what `POST /diffs` (`routes/ldp/DiffCreate.kt`) produces.
2. If the target's current commit is `base`, apply the diff as a single SPARQL update to the target
   (through the ordinary commit path in `routes/Model.kt`, so the result is a normal `mms:Commit`
   whose `mms:message` names the delivered change set).
3. Otherwise compute `diff(base, targetHead)` as well. Delivery proceeds when the two diffs touch
   disjoint subjects; it is refused with `409 Conflict` and a body listing the overlapping subjects
   when they do not. Three-way merge at the triple level is a later refinement; subject-level
   disjointness is the rule the OSLC consumer can reason about.
4. The `If-Match` precondition is honoured as on any branch write (`Layer1Context.checkPreconditions`).

The route is a general "merge one branch into another when it is safe" operation and fills the
`POST` slot that `crudModel()` currently leaves commented out.

### 4. Change-set metadata

The façade needs to remember that a branch is a change set of a given stream. Branch creation
already keeps caller-supplied triples about the branch (`filterIncomingStatements` in
`routes/ldp/BranchWrite.kt`); `Sanitizer.kt` rejects predicates in the `rdf:`, `rdfs:`, `owl:`,
`sh:` and `mms:` namespaces and passes everything else through. So the façade records
`oslc_config:overrides <stream branch IRI>` on the change-set branch itself, in the OSLC vocabulary,
and no Layer 1 change is needed. The `mms:` namespace stays closed to callers; this proposal does
not add an `mms:changeSetOf` term.

### 5. Commit feed (optional, replaces TRS polling)

A Linked Data Notifications inbox or webhook fired per commit lets the TRS change log be pushed to
consumers rather than polled. Not required for correctness; listed because the TRS feed is the
first consumer that would use it.

## Access control

No new policy scopes. OSLC components, streams and baselines map to repos, branches and locks,
which already have `mms:scope`d policies (`routes/Policies.kt`, `AccessControl.kt`); the façade's
own routes simply propagate Layer 1's `403`/`404`. `FLEXO_MMS_GLOMAR_RESPONSE` behaviour (`404`
instead of `403` for resources the caller may not know exist) is preserved by passing the status
through unchanged. Global configurations inherit the collection's policy.

## Testing

- Façade: contract tests using the OSLC Test Suite for Config Mgmt plus recorded DOORS Next /
  Jama request logs (both products' OSLC clients are well documented); run against the same
  docker-compose stack the SysML v2 API service uses.
- Layer 1: Kotest suites, in the style of `src/test/kotlin`, for the commit delta route (contents
  equal the commit's `mms:insGraph`/`mms:delGraph`), for deliver-by-diff (fast-forward, disjoint,
  overlapping → `409`, stale `If-Match` → `412`) and for an `oslc_config:overrides` triple
  surviving branch creation and appearing on `GET /branches/{branchId}`.

## Sequencing

1. Layer 1: Memento proposal (commit reads, TimeGate, TimeMap).
2. Layer 1: commit delta route.
3. Façade: discovery, components/streams/baselines, configuration context, OSLC Query. This is
   already enough for DOORS Next to link to Flexo-hosted elements in a chosen baseline.
4. Façade: TRS.
5. Layer 1: deliver-by-diff; façade: change sets.
6. Façade: global configurations over collections.

## Non-goals

- Acting as an OSLC *consumer* (storing links to remote requirements with their previews). That is
  a client-side concern; models already hold outbound IRIs.
- Delegated UI dialogs (selection/creation) — useful for interactive consumers, but a web-UI
  concern that belongs with a dashboard, not with this service.
- OSLC 2.0 OAuth 1.0a. Consumers that cannot do OAuth 2.0 / OIDC are out of scope.
