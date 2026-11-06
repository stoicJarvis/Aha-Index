# Phase 2 — Inverted index and bitmap pre-filter

Branch: `feature/phase-2-bitmap-prefilter`

Tag: `v0.2-prefilter`

Cut the branch from `main` only after `v0.1-naive` exists.

```bash
git checkout main
git checkout -b feature/phase-2-bitmap-prefilter
```

Phase 2 loads the index from Postgres at startup. A grant committed after startup is invisible to the bitmap until restart. The Postgres candidate check from `DocumentStore.retainVisible` still drops a revoke immediately, because it reads current rows. New grants wait for phase 3. Say that in the module's package javadoc so you do not "fix" it with a cache invalidator.

Add to `settings.gradle.kts`:

```kotlin
include("index")
```

## Commit 1 — `chore: add the index module`

`index/build.gradle.kts`:

```kotlin
plugins {
    java
    application
}

application {
    mainClass.set("io.aha.index.idx.IndexMain")
}

dependencies {
    implementation(project(":analysis"))
    implementation(project(":api"))
    implementation(project(":oracle"))
    implementation(libs.roaring)
    implementation(libs.slf4j.api)
    implementation(libs.logback)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
}
```

Package is `io.aha.index.idx` because `io.aha.index.index` is an unreadable stutter. The module directory stays `index/`.

`IndexMain` prints `index module booted` and exits 0. One test asserts the main method returns without throwing. You will replace the body in the HTTP commit.

ZGC lives in `deploy/run-index.sh`, not in the Gradle application plugin, so tests do not require it:

```bash
#!/usr/bin/env bash
set -euo pipefail
export PORT="${PORT:-18481}"
export BOOTSTRAP="${BOOTSTRAP:-jdbc}"
exec java -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m \
  -jar index/build/libs/index-0.1.0-SNAPSHOT.jar
```

The jar name follows the application plugin only if you build a fat jar. `installDist` is easier and matches phase 1. Use `./gradlew :index:installDist` and point the script at `index/build/install/index/bin/index`. Pass JVM flags with `JAVA_OPTS`.

Commit:

```text
chore: add the index module

Why:
The index is a separate process from the oracle. It may depend on
the oracle's VisibilityStore. The oracle must not depend on the index.

Verify:
./gradlew :index:test
```

## Commit 2 — `feat: assign dense doc ids and store document records`

`io.aha.index.idx.DocTable`

- `int assign(String externalId)` — if the id exists, return the existing int. Otherwise append. The first id is 0.
- `String external(int internal)`
- `int internal(String external)` throws `IllegalArgumentException` if unknown.
- `void put(int internal, String title, String body, List<String> tokens, int tokenCount)`
- Getters for those fields.
- `int docCount()`
- Assignment order is the call order. The loader calls `assign` in `ORDER BY doc_id ASC`, so internal order matches external lexicographic order for a full rebuild. Do not renumber. Do not reuse an id after delete in this build. A deleted id is removed from `liveDocs` and stays reserved.

`DocTableTest`: assign `b` then `a`. Internal ids are 0 and 1. Assign `b` again and get 0. This is the opposite of sorted order on purpose, so the test locks "call order", and the loader test later locks "the loader calls in sorted order".

Commit:

```text
feat: assign dense doc ids and store document records

Why:
Roaring bitmaps key on 32-bit integers. External ids stay strings
at the HTTP boundary and become dense ints once, in this table.

Verify:
./gradlew :index:test --tests io.aha.index.idx.DocTableTest
```

## Commit 3 — `feat: build sorted postings lists`

`io.aha.index.idx.Postings`

- `void add(String term, int docId)` — insert `docId` into that term's sorted `int[]`. Binary search. If it is already present, return. Grow the array by doubling. Keep a parallel `size`.
- `void remove(String term, int docId)` — binary search, `System.arraycopy` the tail down, decrement size. Missing doc id is a no-op.
- `IntCursor cursor(String term)` — a view of the current array and length. The cursor does not allocate an `Integer`.
- `int docFreq(String term)` — the length.
- `int[] raw(String term)` for tests.

A cursor exposes `boolean next()`, `int docId()`, and `advanceTo(int target)` which moves to the first doc id greater than or equal to `target`. `advanceTo` is galloping: from the current index, step 1, 2, 4, 8… until the value exceeds the target or the list ends, then binary search inside that window. If you skip galloping and binary search the whole array every time, leave a `// TODO gallop` and do not claim the commit done. The test `AdvanceToGallops` uses a list of even ids `0,2,4,...,20000` and `advanceTo(15001)` and asserts the landed id is `15002`.

Commit:

```text
feat: build sorted postings lists

Why:
Intersection and BM25 both need document frequency and a sorted
doc-id sequence. Galloping advanceTo is the primitive the query
walk uses when one list is much shorter than the other.

Verify:
./gradlew :index:test --tests io.aha.index.idx.PostingsTest
```

## Commit 4 — `feat: score with BM25 into a fixed top-10 heap`

`io.aha.index.idx.Bm25`

Constants: `k1 = 1.2`, `b = 0.75`.

```text
idf(term) = ln(1 + (N - df + 0.5) / (df + 0.5))
norm      = 1 - b + b * (dl / avgdl)
weight    = idf * (tf * (k1 + 1)) / (tf + k1 * norm)
```

`N` is `liveDocs` cardinality, not the high-water mark of assigned ids. `dl` is the document's token count (sum of term frequencies, not the distinct term count). `avgdl` is the mean `dl` over live documents. `df` is `postings.docFreq(term)`. If `df` is 0 or `N` is 0, the weight is 0. If `idf` is negative, use 0. `avgdl` of 0 is treated as 1 to avoid a divide by zero on an empty corpus.

`io.aha.index.idx.TopK`

- Capacity 10.
- Min-heap on the worst of the current winners. `java.util.PriorityQueue` with a comparator: lower score is worse; if scores differ by less than `1e-12`, the larger internal doc id is worse. The head of the min-heap is the first candidate to evict.
- `offer(int docId, double score)` does nothing when the heap is full and the new pair is worse than or equal to the head.
- `List<Scored> drain()` returns best-first (highest score, then smallest doc id).

Do not use `PriorityQueue` as the returned order. Drain by polling into a list and reversing, or sort the ten at the end. Ten items. Clarity beats a clever heap drain.

`Bm25Test` worked example. Three live docs, `avgdl = 4`, `N = 3`, every doc contains `quarterly` once, `df = 3`, the scored doc has `dl = 4`, `tf = 1`.

```text
idf = ln(1 + 0.5/3.5) = ln(1.142857142857143) = 0.13353139262452263
norm = 1
weight = idf
```

Assert `Math.abs(weight - 0.13353139262452263) < 1e-12`.

Second case: a term that appears in all documents still has a positive Lucene-style idf with this formula (`0.5` in the numerator). Do not switch to `log(N/df)`, which would be 0 and would make the canary tests order-unstable.

`TopKTest`: offer 20 docs with scores equal to their id. Drain returns internal ids 19,18,…,10.

Commit:

```text
feat: score with BM25 into a fixed top-10 heap

Why:
Phase 1's count score is the oracle of who matches, not the ranker
the SLO demo shows. BM25 is deterministic here: fixed k1 and b,
doc-id tie break, and an idf that stays positive when a term is common.

Verify:
./gradlew :index:test --tests io.aha.index.idx.Bm25Test --tests io.aha.index.idx.TopKTest
```

## Commit 5 — `feat: publish index snapshots through a volatile field`

`io.aha.index.idx.IndexSnapshot` is a final class. Fields are private and not mutated after the constructor returns. It holds:

- `DocTable docs` — treat as frozen after publish. The next writer clones or builds a new table.
- `Postings postings`
- `RoaringBitmap liveDocs`
- `Map<String, RoaringBitmap> userGrants` — unmodifiable map of bitmaps that will not be mutated
- `Map<String, RoaringBitmap> groupGrants`
- `Map<String, int[]> memberships` — user id to group ids, arrays not shared with the builder
- `double avgdl`
- `long appliedLsn` — 0 in this phase
- `long appliedAtMillis` — 0 in this phase
- `boolean ready`

`SnapshotPublisher`:

```java
public final class SnapshotPublisher {
    private volatile IndexSnapshot current = IndexSnapshot.empty();

    public IndexSnapshot current() { return current; }

    public void publish(IndexSnapshot next) { this.current = next; }
}
```

That is the whole concurrency protocol. One writer thread calls `publish`. Any number of readers call `current` and then only read. There is no lock on the query path.

`SnapshotPublisherTest`: a reader thread loops for 100ms loading `current()` and reading `liveDocs.getCardinality()` while the writer publishes snapshots with cardinality 0,1,2,…,1000. The reader never throws. Cardinality is whatever snapshot it loaded, and it is allowed to skip values. It is not allowed to observe a negative or a value above 1000. This test will not fail on a correct `volatile` publish. It fails if you publish a snapshot and then keep mutating its bitmap. So the test's writer must mutate only unpublished builders. Add a comment in the test that says that.

Commit:

```text
feat: publish index snapshots through a volatile field

Why:
Query threads have to see either the previous snapshot or the next
one, never a bitmap mid-or. Volatile publication is the protocol.
RoaringBitmap writes stay on the builder side of publish.

Verify:
./gradlew :index:test --tests io.aha.index.idx.SnapshotPublisherTest
```

## Commit 6 — `feat: encode allow-grants as roaring bitmaps`

Builder `io.aha.index.idx.IndexBuilder`:

- `addDocument(externalId, title, body)` tokenizes with `Tokenizer`, assigns an id, fills postings, adds the id to `liveDocs`.
- `grantUser(externalDocId, userId)`, `grantGroup(externalDocId, groupId)`.
- `addMembership(userId, groupId)`.
- `deleteDocument(externalDocId)` removes the id from `liveDocs`, from every posting term it contributed (you stored the token list on the doc record), and from every grant bitmap that contains it. Track `Map<Integer, List<Principal>> grantsByDoc` so delete does not scan every bitmap. A second delete is a no-op.
- `IndexSnapshot build(long appliedLsn, long appliedAtMillis, boolean ready)` computes `avgdl` from live docs, wraps maps in `Map.copyOf`, clones every bitmap (`bitmap.clone()`), clones membership arrays, and returns a snapshot the builder may continue to modify afterwards without the snapshot changing. Test this: grant a user on the builder after `build()`, assert the published snapshot's bitmap does not contain the new doc.

`Visibility` helper, pure function of a snapshot:

```java
public static RoaringBitmap visible(IndexSnapshot snap, String userId) {
    RoaringBitmap out = new RoaringBitmap();
    RoaringBitmap direct = snap.userGrants().get(userId);
    if (direct != null) {
        out.or(direct);
    }
    int[] groups = snap.memberships().getOrDefault(userId, new int[0]);
    // memberships stored as group ids are awkward if group ids are strings.
}
```

Store memberships as `Map<String, String[]>` of group id strings, not ints. The group grant map is keyed by group id string. Correct the field list in commit 5 if you already used `int[]`. The snapshot field is `Map<String, String[]> memberships`.

```java
RoaringBitmap out = new RoaringBitmap();
RoaringBitmap direct = snap.userGrants().get(userId);
if (direct != null) out.or(direct);
for (String groupId : snap.memberships().getOrDefault(userId, EMPTY)) {
    RoaringBitmap g = snap.groupGrants().get(groupId);
    if (g != null) out.or(g);
}
out.and(snap.liveDocs());
return out;
```

`out` is a new bitmap. `direct.or(g)` would mutate the published user bitmap. The test `VisibleDoesNotMutatePublishedGrants` calls `visible` and then asserts the stored user bitmap cardinality is unchanged.

Commit:

```text
feat: encode allow-grants as roaring bitmaps

Why:
A query has to intersect matches with the documents this user can
read without scanning ACL rows. The bitmap is that set. Cloning
on publish and OR-ing into a fresh bitmap are what keep a query
from permanently granting the user every document in their groups.

Verify:
./gradlew :index:test --tests io.aha.index.idx.IndexBuilderTest
```

## Commit 7 — `feat: intersect postings with the visibility bitmap before scoring`

`io.aha.index.idx.QueryEngine.search(IndexSnapshot snap, String userId, String query)`:

1. Tokenize. Empty tokens throw `NoTokensException`.
2. If `!snap.ready()`, throw `IndexNotReadyException`. The HTTP layer maps it to 503.
3. `RoaringBitmap visible = Visibility.visible(snap, userId)`.
4. If `visible.isEmpty()`, return an empty list.
5. Drop query tokens whose postings are null. If any query token has `docFreq == 0`, return empty. Conjunction.
6. Order the remaining tokens by `docFreq` ascending.
7. Build the candidate id list with the intersection below.
8. For each candidate that `visible.contains(id)`, compute BM25 across all query tokens and offer to `TopK`.
9. Drain top 10. Map internal ids back to external ids, titles, snippets. Snippet uses the same 60/100 window as phase 1.

Intersection, given the rarest term's cursor and the other terms, and `visible`:

```text
candidates = empty list of int
rarest = cursor of the lowest-df term
if visible.getCardinality() < rarest.docFreq:
    for docId in visible:
        if every query term's postings contain docId:
            append docId
else:
    for docId in rarest:
        if !visible.contains(docId): continue
        if every other term contains docId: append docId
```

`contains` on a sorted posting list is binary search via `advanceTo` on a fresh cursor, or a dedicated `Postings.contains(term, docId)`. Implement `contains` with binary search so the visible-driven loop does not allocate a cursor per term per doc. For the rarest-driven loop, a cursor per other term that you `advanceTo` monotonically is faster and is what you should write: the doc ids only increase, so each other cursor only moves forward.

```text
otherCursors = one cursor per non-rarest term, positioned at the start
for docId in rarest:
    if !visible.contains(docId): continue
    ok = true
    for c in otherCursors:
        c.advanceTo(docId)
        if c exhausted or c.docId() != docId:
            ok = false
            break
    if ok: append docId
```

Do not reset other cursors inside the loop.

BM25 `tf` is the term frequency stored at index time. You need it. A postings entry is not only a doc id. Change `Postings` in this commit (or a fixup commit if commit 3 is already too far to amend) so each term maps to parallel `int[] docIds` and `int[] tfs`, same length, same order. `add` takes `(term, docId, tf)`. `remove` removes both. `DocTable` already has the token list; the loader passes `termFreq`.

If you notice this only now, that is what this commit is for. Update `PostingsTest` and `Bm25` to read `tf` from the posting, not from a rescan of the body. Rescanning the body is correct and slower. Do the parallel array. A comment in `Postings` should say the two arrays stay the same length.

Scoring a candidate:

```text
score = 0
for term in queryTokens:
    tf = postings.tf(term, docId)   // 0 if absent, but intersection means it is present
    score += bm25.weight(tf, docLen, avgdl, df, N)
```

`QueryEngineTest` builds a snapshot in memory, no Postgres:

- Three docs, user `ada` granted on doc 0 and doc 2, not doc 1.
- All three contain `quarterly`.
- Search returns external ids of doc 0 and doc 2 only.
- A second test: 100 docs contain `quarterly`, the user can read one of them, the result has size 1. This is the pre-filter test. Count how many docs you scored (add a package-visible counter or return it on a test subclass). Assert `scored == 1`, not 100.

Commit:

```text
feat: intersect postings with the visibility bitmap before scoring

Why:
Post-filtering a top-10 taken from the whole corpus drops authorized
hits when the user can see very little of the index. The bitmap
intersection happens before BM25, and the scored-doc counter locks
that order.

Verify:
./gradlew :index:test --tests io.aha.index.idx.QueryEngineTest
```

## Commit 8 — `feat: load a snapshot from Postgres and confirm candidates`

`io.aha.index.idx.JdbcBootstrap`:

1. Open a connection, `setAutoCommit(false)`, `setTransactionIsolation(REPEATABLE_READ)`.
2. Read documents `ORDER BY tenant_id, doc_id`. You are building one snapshot that contains every tenant. Namespace the external key as `tenantId + "\u0000" + docId` inside `DocTable`, or build `Map<String, IndexSnapshot>` keyed by tenant. Prefer **one `IndexBuilder` per tenant** stored in `Map<String, IndexBuilder>`, and a published `Map<String, IndexSnapshot>`. Change `SnapshotPublisher` to hold `volatile Map<String, IndexSnapshot>` or a small `CorpusSnapshot` that contains the map. Do this now, before the HTTP handler, even if it means adjusting commit 5's type. A single global bitmap with colliding doc ids across tenants is a cross-tenant leak.
3. Read `acl_entries` and `group_memberships` in the same transaction.
4. Commit the JDBC transaction.
5. `publish` with `ready=true`, `appliedLsn=0`.

`QueryEngine` gains `Connection confirmConn` and after `TopK` collects a ranked candidate list of up to 30 external ids per batch:

1. Take the BM25 order of all intersected candidates. If there are more than 150, keep the best 150 by the heap of size 150 (a second `TopK` with capacity 150, or reuse `TopK` with a constructor argument). 150 is 5 batches of 30.
2. Walk that list in order, in slices of 30.
3. `documentStore.retainVisible(conn, tenant, user, slice)`.
4. Append survivors until you have 10 or you run out.
5. SQLException from the confirm call becomes `IndexNotReadyException`. The handler returns 503. It does not return the unconfirmed list.

`JdbcBootstrapTest` + `ConfirmedSearchTest` on Testcontainers with the canary seed:

- Ada `quarterly` returns `d-ada-1`, `d-eng-1`, `d-shared` in BM25 order. Compute the expected order with `ReferenceBm25` described below, not by calling production `Bm25`.
- Revoke ada's direct grant on `d-ada-1` in Postgres **without** rebuilding the index. Search again. `d-ada-1` is absent. This is the confirm path. If you skip `retainVisible`, this test fails.
- Insert a new document granted to ada **without** rebuilding. Search. It is absent. That is expected in phase 2. The test is named `GrantIsInvisibleUntilReload` and its assertion is absence. Phase 3 deletes this behavior. Leave the test, and in phase 3 change it to assert presence within the freshness bound. A comment on the test says so.
- Canary is absent for ada and present for ivan.
- `scored` for ada on `quarterly` is far below the number of acme documents. Assert `scored < 10` given the seed (ada can see 3 matching docs). The fillers are not scored.

`ReferenceBm25` lives in `index/src/test/.../ReferenceBm25.java`. Type the formula again from the architecture doc. Do not call `io.aha.index.idx.Bm25`. The test builds tf from `Tokenizer` over the seeded title and body of the oracle's visible, matching documents, computes scores, sorts, and compares external ids to the engine. A bug in production BM25 then fails the test instead of passing twice.

Ranking may differ from phase 1's count score. Do not assert phase-1 order against the index. Assert reference BM25 order, and assert the set of engine hits is a subset of `DocumentStore.visibleDocuments` filtered by the tokenizer match rule.

Commit:

```text
feat: load a snapshot from Postgres and confirm candidates

Why:
The bitmap can be stale after a revoke until the process restarts.
retainVisible on the candidate ids is the check that makes a revoke
disappear immediately. A grant still waits for a reload; the test
locks that limitation so phase 3 has something to remove.

Verify:
./gradlew :index:test --tests io.aha.index.idx.ConfirmedSearchTest
```

## Commit 9 — `feat: serve the index on port 18481`

`IndexServer` mirrors `OracleServer` routes that are reads:

- `GET /v1/health` returns `mode=indexed`, `ready`, `appliedLsn`, `lagMs`. In this phase `appliedLsn` is 0 and `lagMs` is 0 when ready.
- `GET /v1/search` same JSON as the oracle with `mode=indexed` and `replica` from env `REPLICA_ID` default `index-a`.
- `GET /internal/search` is the same handler. The coordinator will use it in phase 4. Keeping one handler avoids a second parser.
- No write routes on this process. ACL writes stay on the oracle port until the coordinator exists. Document that in the class javadoc.

Startup: migrate is not this process's job. It assumes the schema and the seed already exist. `BOOTSTRAP=jdbc` runs `JdbcBootstrap`. Any other value logs an error and exits 2 in this phase. Phase 3 adds `log`.

`IndexHttpTest` starts Postgres via Testcontainers, runs the seeder, starts the index server on port 0, and repeats the Ada / Ivan canary assertions over HTTP.

`deploy/run-index.sh` uses `PORT=18481` and `BOOTSTRAP=jdbc`.

Commit:

```text
feat: serve the index on port 18481

Why:
The process boundary is real from this phase on. The oracle remains
on 18480 as the writer and the differential oracle. The index serves
reads from the snapshot.

Verify:
./gradlew :index:test --tests io.aha.index.idx.IndexHttpTest
```

## Commit 10 — `docs: record post-filter cost against pre-filter cost`

Extend the benchmark seeder idea. A main `SelectivityBenchmark`:

1. Tenant `bench`, user `sparse` in group `readers`, user `broad` in group `everyone`.
2. 20_000 documents containing `quarterly`, ids `b-00000` upward.
3. Grant `everyone` on all of them. Grant `readers` on 20 of them (0.1%).
4. Time 100 searches for each user through `QueryEngine` with confirm disabled via a package flag `confirm=false` for this benchmark only, so you measure intersection rather than Postgres. The flag defaults true in the server. The benchmark calls the engine in-process.
5. Also time a post-filter implementation that lives **only in the benchmark class**: score all docs that contain the term, take top 50, then drop those whose bitmap bit is off, and if you have fewer than 10, the benchmark records `shortfall`. For the sparse user the post-filter shortfall is the lesson: the global top 50 are almost all unreadable.

Write `docs/benchmarks/phase2.md` with both users, p50 and p99, `scored` count, and the shortfall. State that confirm was off for this measurement and on in the server.

Commit:

```text
docs: record post-filter cost against pre-filter cost

Why:
The sparse user is the case where ranking the whole corpus and then
dropping unauthorized hits returns the wrong set and does more work.
The numbers belong next to the phase-1 baseline.

Verify:
SelectivityBenchmark main, docs/benchmarks/phase2.md filled in
```

## Merge

```bash
./gradlew test
git checkout main
git merge --ff-only feature/phase-2-bitmap-prefilter
git tag -a v0.2-prefilter -m "In-memory postings, BM25, roaring pre-filter, Postgres confirm"
```

Exit bar:

- Ada's canary is absent through the index HTTP port.
- Revoking a grant in Postgres removes it from the next index search without a restart.
- A new grant does not appear until restart, and a test says so.
- `scored` stays tiny for the sparse user.
- No `web/` directory.
