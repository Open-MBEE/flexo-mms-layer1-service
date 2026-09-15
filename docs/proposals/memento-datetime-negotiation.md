# Proposal: Memento datetime negotiation for branches

**Status:** proposal
**Spec:** [RFC 7089 — HTTP Framework for Time-Based Access to Resource States (Memento)](https://www.rfc-editor.org/rfc/rfc7089)

## Problem

Every state a branch has ever been in is recoverable today: each commit records `mms:submitted`
and `mms:parent` in `mor-graph:Metadata`, and `materializeModelGraph()`
(`routes/ldp/GraphMaterialization.kt`) can rebuild the model graph of any commit by replaying
`mms:patch` deltas from the nearest snapshot. But the only way a client can *read* a past state is
to know a commit ID, create a lock on it (`POST /orgs/{orgId}/repos/{repoId}/locks` with
`mms:commit`), and then read or query the lock. Clients that only know a point in time — "what did
the model say when the review board met on 2026-03-04?" — have to walk `/commits` themselves and
pick a commit, and they leave a lock behind for every question they ask.

Memento is the standard answer to this. A client sends `Accept-Datetime` to an ever-changing
resource (the *Original Resource*, RFC 7089 §1.1) and is redirected to the representation that was
current at that instant (a *Memento*). Branch graphs and branch query endpoints are Original
Resources; commits are Mementos. Nothing about the data model needs to change.

## Proposed behaviour

### Original Resources (the branch)

| Route (existing) | Role |
|---|---|
| `GET/HEAD /orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph` | Original Resource (GSP read) |
| `POST /orgs/{orgId}/repos/{repoId}/branches/{branchId}/query` | Original Resource (SPARQL query) |

Both gain two things:

1. A `Link` header advertising the TimeGate and TimeMap (RFC 7089 §2.2.1):

   ```
   Link: <…/branches/{branchId}/graph>; rel="timegate",
         <…/branches/{branchId}/timemap>; rel="timemap"; type="application/link-format"
   ```

2. Datetime negotiation. When the request carries `Accept-Datetime: <HTTP-date>`, the branch route
   acts as its own TimeGate (RFC 7089 pattern 1.1, §4.1.1):

   ```
   GET /orgs/o/repos/r/branches/master/graph
   Accept-Datetime: Wed, 04 Mar 2026 15:00:00 GMT

   302 Found
   Location: /orgs/o/repos/r/commits/{commitId}/graph
   Vary: accept-datetime
   Link: <…/branches/master/graph>; rel="original",
         <…/branches/master/timemap>; rel="timemap"; type="application/link-format"
   ```

   For `/query`, the negotiation is identical but the redirect target is
   `/commits/{commitId}/query`; because the request is a POST the response is `307 Temporary
   Redirect` so the client resubmits the same query body.

### Mementos (the commit)

Two new read-only routes expose a commit's model state directly, without requiring the client to
create a lock:

| Route (new) | Protocol | Handler |
|---|---|---|
| `GET/HEAD /orgs/{orgId}/repos/{repoId}/commits/{commitId}/graph` | GSP read | `readModel(RefType.COMMIT, …)` |
| `POST /orgs/{orgId}/repos/{repoId}/commits/{commitId}/query/{inspect?}` | SPARQL query | `processAndSubmitUserQuery(…)` against the commit's model graph |

Each response carries the Memento headers (RFC 7089 §2.1.1, §2.2.2):

```
Memento-Datetime: Wed, 04 Mar 2026 14:52:31 GMT      ← the commit's mms:submitted
Link: <…/branches/master/graph>; rel="original timegate",
      <…/branches/master/timemap>; rel="timemap"; type="application/link-format",
      <…/commits/{parentId}/graph>; rel="prev memento"; datetime="…",
      <…/commits/{childId}/graph>;  rel="next memento"; datetime="…"
```

`rel="original"` is the branch whose history contains the commit. A commit reachable from several
branches (before a fork point) is a Memento of each of them; the `original` link is chosen from the
branch the client was redirected from when that is known (recorded in the redirect's query string,
see *Ambiguity of "original"* below) and otherwise from the branch with the shortest `mms:parent+`
path.

Mementos are immutable, so commit routes return `Cache-Control: public, max-age=31536000, immutable`
and an `ETag` equal to the commit's `mms:etag`.

### TimeMap (the branch history)

| Route (new) | Body |
|---|---|
| `GET /orgs/{orgId}/repos/{repoId}/branches/{branchId}/timemap` | `application/link-format` (RFC 7089 §5) |

```
<…/branches/master/graph>; rel="original",
<…/branches/master/timemap>; rel="self"; type="application/link-format";
    from="Tue, 03 Feb 2026 09:00:00 GMT"; until="Wed, 04 Mar 2026 14:52:31 GMT",
<…/commits/c1/graph>; rel="first memento"; datetime="Tue, 03 Feb 2026 09:00:00 GMT",
<…/commits/c2/graph>; rel="memento"; datetime="…",
<…/commits/c9/graph>; rel="last memento"; datetime="Wed, 04 Mar 2026 14:52:31 GMT"
```

The TimeMap is the branch's `mms:parent+` chain ordered by `mms:submitted`. It is paginated with
`rel="timemap"` continuation links (RFC 7089 §5) once the chain exceeds a configurable page size.
A JSON-LD variant is offered via `Accept` for clients that already speak the RDF side of the API.

## Resolution algorithm

Given a branch and an `Accept-Datetime` value `t`:

```sparql
select ?commit ?submitted where {
    graph mor-graph:Metadata {
        morb: mms:commit ?head .
        ?head mms:parent* ?commit .
        ?commit mms:submitted ?submitted .
        filter(?submitted <= ?_acceptDatetime)
    }
} order by desc(?submitted) limit 1
```

- The Memento is the most recent commit on the branch's ancestry whose `mms:submitted` is not after
  `t` (RFC 7089 §4.5.3: "the Memento with a datetime closest to, but not after").
- If `t` precedes the root commit, the root commit is returned (the earliest known state) — the
  RFC allows either the first Memento or `406`; the first Memento is more useful to model clients.
- If `t` is later than the head commit, the redirect targets the head commit, not the branch: the
  client asked for a fixed state and a fixed state is what it receives.
- A malformed `Accept-Datetime` is a `400`, not a silent fall-through to the current state.

`mms:parent*` over a long chain is the same traversal `materializeModelGraph()` already performs
for lock creation, so its cost is known and bounded by the repo's history length. Deployments that
need faster TimeGates can add an `mms:submitted` index at the quad-store or let the TimeMap route
serve a cached page.

## Serving a commit's graph

`readModel()` (`routes/gsp/ModelRead.kt`) resolves a ref to a target graph IRI via
`checkModelQueryConditions()`. A new `RefType.COMMIT` branch does the following:

1. Check the commit exists and the caller may read the repo (`COMMIT_QUERY_CONDITIONS`, mirroring
   `LOCK_QUERY_CONDITIONS`; access is read access on the repo, since a commit has no policy scope
   of its own).
2. Look for an existing snapshot: `?lock mms:commit <commit> ; mms:snapshot/mms:graph ?graph`. The
   head commit of every branch has one (`routes/Model.kt` creates `mor-lock:Commit.<txn>` on each
   commit and `cleanupPreviousCommitLock()` removes only the *previous* one), and every
   user-created lock has one.
3. Otherwise call `materializeModelGraph(commitIri, "mor-graph:Model.<txn>")`, which replays
   patches from the nearest snapshotted ancestor. Register the result as a snapshot on a
   `mor-lock:Memento.<commitId>` lock so the next reader of the same commit hits step 2.

Materialized memento graphs are a cache, not user data. They are reclaimed by a sweep that drops
`mor-lock:Memento.*` locks not read within a configurable TTL (`FLEXO_MMS_MEMENTO_TTL_HOURS`,
default 168) — the same "orphan reclaim" posture `cleanupPreviousCommitLock()` takes for commit
locks — and the reclaim is skipped when a user lock or collection also references the snapshot.

## Access control

Reading a Memento is reading the repo at an earlier point; the check is the same repo-level read
permission the branch route enforces. This is deliberately not a per-commit or per-lock policy:
Memento clients never create resources, so there is nothing for the `AutoLockOwner` policy pattern
to attach to. A deployment that wants some history hidden already has that tool — squash the
history (`POST /orgs/{orgId}/repos/{repoId}/squash`), which removes the intermediate commits.

## Interaction with existing features

- **Locks are unaffected.** A lock remains the way to *name* and *protect* a state; a Memento is an
  anonymous read of one. Lock routes may also emit `Memento-Datetime` (from the commit's
  `mms:submitted`) so that a lock URI is a valid Memento in its own right.
- **Preconditions.** `If-Match`/`If-None-Match` on a commit route compare against the commit's
  `mms:etag`, which never changes, so `If-None-Match` short-circuits to `304` for any repeat read.
- **Collections** are out of scope for the first iteration: a collection spans repos whose
  histories are not aligned, so "the collection at time `t`" requires resolving one commit per
  member; that is a natural follow-up once single-branch negotiation is in place.
- **OSLC.** The [OSLC Configuration Management façade](oslc-config-management-facade.md) uses
  `/commits/{commitId}/graph` for baseline reads and TimeMaps to populate a component's history, so
  this proposal is a prerequisite for that one.

## Implementation sketch

| Change | Where |
|---|---|
| `RefType.COMMIT`, `COMMIT_QUERY_CONDITIONS` | `routes/gsp/ModelRead.kt`, `Conditions.kt` |
| `commit()` path-param parser | `Layer1Context.kt` (alongside `branch()`, `lock()`) |
| `crudModel()`: add `graphStoreProtocol("…/commits/{commitId}/graph")` read-only | `routes/Model.kt` |
| `queryCommit()`: `sparqlQuery("…/commits/{commitId}/query/{inspect?}")` | `routes/sparql/CommitQuery.kt` (new), registered in `server/Routing.kt` |
| `Accept-Datetime` parsing, TimeGate resolution, redirect | `server/Memento.kt` (new); invoked from the branch `graph`/`query` handlers before condition checks |
| `timemap` route | `routes/Branches.kt` |
| Memento/Link/Vary headers | a `Layer1Context` helper next to the existing `ETag` header handling |
| Memento snapshot registration + TTL sweep | `routes/ldp/GraphMaterialization.kt`, a scheduled job in `Application.kt` |
| `FLEXO_MMS_MEMENTO_TTL_HOURS`, `FLEXO_MMS_TIMEMAP_PAGE_SIZE` | `application.conf.example`, `docs/index.rst` |
| OpenAPI | `openapi/openapi.yaml`: new paths, `Accept-Datetime` parameter on branch graph/query |

Tests (Kotest, against the docker-compose quad-store like the existing suites): commit graph read
equals the lock graph for the same commit; TimeGate picks the correct commit for a datetime between
two commits, before the root, and after the head; `307` preserves the query body; TimeMap lists
every commit in order with correct `first`/`last`; memento snapshot is reused on second read and
reclaimed after TTL; unauthenticated read of a commit graph is refused exactly as the branch graph is.

## Ambiguity of "original"

A commit before a fork belongs to more than one branch. RFC 7089 permits a Memento to have one
`rel="original"` link, so the redirect from a TimeGate appends `?original=<branchId>` to the
`Location`, and the commit route echoes that branch in its `Link` header. A direct request to a
commit route without the hint falls back to the nearest branch by parent-path length. This keeps
commit URIs canonical (one URI per state) while still letting a Memento client navigate back to
the branch it came from.

## Non-goals

- **Writes at a datetime.** `Accept-Datetime` is ignored on `PUT`/`POST /update`; time travel is
  read-only. Writing to the past is a branch (`POST /branches` with `mms:commit`), and already exists.
- **Sub-second precision.** `Accept-Datetime` is an HTTP-date (second resolution). Two commits
  submitted within the same second resolve to the later one, which is consistent with the ordering
  the TimeMap shows.
- **Cross-repo datetime negotiation** (collections), as noted above.
