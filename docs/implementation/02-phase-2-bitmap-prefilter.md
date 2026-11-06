# Phase 2 — Inverted index and bitmap pre-filter

Branch: `feature/phase-2-bitmap-prefilter`

Tag: `v0.2-prefilter`

Cut the branch from `main` only after `v0.1-naive` exists.

```bash
git checkout main
git checkout -b feature/phase-2-bitmap-prefilter
```

This phase stays one Spring Boot application, on port 18480. There is still no `index/` Gradle module and no second port. The index is a package, `io.aha.index.idx`, inside the app from phase 1. RoaringBitmap is added to `build.gradle.kts` in the commit that first imports it.

This phase has no test sources. Where a check names expected ids or a score, confirm it with `curl` or `psql` against Compose. `./gradlew compileJava` is the compile check.

Every commit opens with what it changes and why that change waits until this phase.

Phase 2 loads the index from Postgres at startup. A grant committed after startup is invisible to the bitmap until restart. The Postgres candidate check from `DocumentStore.retainVisible` still drops a revoke immediately, because it reads current rows. New grants wait for phase 3. Say that on the package so a cache invalidator is not added to hide it.

The search route keeps the phase-1 SQL implementation available. Indexed search is what `GET /v1/search` returns once this phase is done, and the SQL path remains the check you curl against.

## Commit 1 — `feat: assign dense doc ids and store document records`

**What.** `DocTable` assigns a dense `int` the first time it sees an external id, in call order, and stores title, body length, and token count under that int.

**Why.** The next commit stores postings by document id. A `HashSet` of strings is the wrong key for that. This commit only assigns the ids. RoaringBitmap is not a dependency yet, nothing new listens on a port, and the application class does not change. Package `io.aha.index.idx` exists because this class needs a home. `io.aha.index.index` would stutter. The loader in the Postgres commit calls `assign` in external-id order, so the dense id follows `docId` ascending.

**Significance.** Every bitmap, posting, and score in the rest of the index is an int from this table. Reassigning ids on restart, or recycling an id after a delete, makes a revoke land on the wrong document.

`io.aha.index.idx.DocTable`

- `int assign(String externalId)` — if the id exists, return the existing int. Otherwise append. The first id is 0.
- `String external(int internal)`
- `int internal(String external)` throws `IllegalArgumentException` if unknown.
- `void put(int internal, String title, String body, List<String> tokens, int tokenCount)`
- Getters for those fields.
- `int docCount()`
- Assignment order is the call order. The loader calls `assign` in `ORDER BY doc_id ASC`, so internal order matches external lexicographic order for a full rebuild. Do not renumber. Do not reuse an id after delete in this build. A deleted id is removed from `liveDocs` and stays reserved.

Assign `b` then `a`. Internal ids are 0 and 1. Assign `b` again and get 0. Assignment follows call order. The loader later calls in sorted external-id order, which is a separate rule.

Commit:

```text
feat: assign dense doc ids and store document records

Why:
Roaring bitmaps key on 32-bit integers. External ids stay strings
at the HTTP boundary and become dense ints once, in this table.

Verify:
./gradlew compileJava
```

## Commit 2 — `feat: build sorted postings lists`

**What.** For each term, a sorted array of dense doc ids plus a parallel array of term frequencies. A cursor walks that array with `next` and galloping `advanceTo`.

**Why.** Conjunction is an intersection of sorted lists, rarest term first. Galloping finds the next candidate without scanning every id when one list is much longer than the other. The frequency sits beside the id so scoring does not rescan the body.

**Significance.** Commit 6 intersects these lists with the visibility bitmap. A posting list that is unsorted, or whose frequency array has a different length, returns the wrong document or the right document with a silent score.

`io.aha.index.idx.Postings`

- `void add(String term, int docId)` — insert `docId` into that term's sorted `int[]`. Binary search. If it is already present, return. Grow the array by doubling. Keep a parallel `size`.
- `void remove(String term, int docId)` — binary search, `System.arraycopy` the tail down, decrement size. Missing doc id is a no-op.
- `IntCursor cursor(String term)` — a view of the current array and length. The cursor does not allocate an `Integer`.
- `int docFreq(String term)` — the length.
- `int[] raw(String term)` so a caller can see the doc ids in order.

A cursor exposes `boolean next()`, `int docId()`, and `advanceTo(int target)` which moves to the first doc id greater than or equal to `target`. `advanceTo` is galloping: from the current index, step 1, 2, 4, 8… until the value exceeds the target or the list ends, then binary search inside that window. If you skip galloping and binary search the whole array every time, leave a `// TODO gallop` and do not claim the commit done. On a list of even ids `0,2,4,...,20000`, `advanceTo(15001)` lands on `15002`.

Commit:

```text
feat: build sorted postings lists

Why:
Intersection and BM25 both need document frequency and a sorted
doc-id sequence. Galloping advanceTo is the primitive the query
walk uses when one list is much shorter than the other.

Verify:
./gradlew compileJava
```

## Commit 3 — `feat: score with BM25 into a fixed top-10 heap`

**What.** BM25 with `k1 = 1.2` and `b = 0.75`, and a heap that keeps ten `(docId, score)` pairs. Tie-break is the dense id ascending.

**Why.** The phase-1 score was a term count, good enough to prove who can see a document and too crude to be the product rank. BM25 is the score the service promises. A fixed heap of ten means ranking does not allocate a scored object per candidate.

**Significance.** The curl comparison with the oracle is on ids and visibility. The order inside those ids is this formula. A second implementation of BM25 that calls the production class cannot catch a bug in it, which is why the reference calculation is typed again from the architecture note.

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

Worked example. Three live docs, `avgdl = 4`, `N = 3`, every doc contains `quarterly` once, `df = 3`, the scored doc has `dl = 4`, `tf = 1`.

```text
idf = ln(1 + 0.5/3.5) = ln(1.142857142857143) = 0.13353139262452263
norm = 1
weight = idf
```

Assert `Math.abs(weight - 0.13353139262452263) < 1e-12`.

Second case: a term that appears in all documents still has a positive Lucene-style idf with this formula (`0.5` in the numerator). `log(N/df)` would be 0 and would make the canary's order depend on tie-break alone.

Top-k check: offer 20 docs with scores equal to their id. Drain returns internal ids 19,18,…,10.

Commit:

```text
feat: score with BM25 into a fixed top-10 heap

Why:
Phase 1's count score is the oracle of who matches, not the ranker
the SLO demo shows. BM25 is deterministic here: fixed k1 and b,
doc-id tie break, and an idf that stays positive when a term is common.

Verify:
./gradlew compileJava
```

## Commit 4 — `feat: publish index snapshots through a volatile field`

**What.** An immutable `IndexSnapshot`, and a publisher that stores it in a `volatile` field. Readers load that field. The writer mutates only a builder that has not been published.

**Why.** Publication is single-writer, many-readers. A reader that loads the volatile field happens-before every write the builder finished before the store. Roaring bitmaps are not safe to mutate while another thread reads them, so the snapshot clones bitmaps before it becomes visible. This is the first commit that imports RoaringBitmap, so the dependency is added here:

```kotlin
implementation("org.roaringbitmap:RoaringBitmap:1.6.29")
```

**Significance.** Phase 3's applier has nowhere to publish a new generation except this field. If query threads take a lock, or if a published bitmap is still being `or`-ed, the freshness path and the latency path fight over the same object.

`io.aha.index.idx.IndexSnapshot` is a final class. Fields are private and not mutated after the constructor returns. It holds:

- `DocTable docs` — treat as frozen after publish. The next writer clones or builds a new table.
- `Postings postings`
- `RoaringBitmap liveDocs`
- `Map<String, RoaringBitmap> userGrants` — unmodifiable map of bitmaps that will not be mutated
- `Map<String, RoaringBitmap> groupGrants`
- `Map<String, int[]> memberships` — user id to group ids, arrays not shared with the builder
- `double avgdl`
- `boolean ready`

`appliedLsn` and `appliedAtMillis` are added in phase 3, when a log event has an LSN to store. This phase has no log.

`SnapshotPublisher`:

```java
public final class SnapshotPublisher {
    private volatile IndexSnapshot current = IndexSnapshot.empty();

    public IndexSnapshot current() { return current; }

    public void publish(IndexSnapshot next) { this.current = next; }
}
```

That is the whole concurrency protocol. One writer thread calls `publish`. Any number of readers call `current` and then only read. There is no lock on the query path.

A reader thread may loop, loading `current()` and reading `liveDocs.getCardinality()`, while the writer publishes snapshots with cardinality 0,1,2,…,1000. The reader sees a cardinality from a published snapshot: any value from 0 through 1000, possibly skipping values. Publish only finished builders. A bitmap that is still being mutated is not stored in the volatile field.

Commit:

```text
feat: publish index snapshots through a volatile field

Why:
Query threads have to see either the previous snapshot or the next
one, never a bitmap mid-or. Volatile publication is the protocol.
RoaringBitmap writes stay on the builder side of publish.

Verify:
./gradlew compileJava
```

## Commit 5 — `feat: encode allow-grants as roaring bitmaps`

**What.** Per tenant, a user bitmap, a group bitmap, a small membership array, and a live-doc bitmap. The visible set is a clone of the user bitmap, OR-ed with each group bitmap, AND-ed with live docs.

**Why.** Enterprise ACLs are sparse: many documents, fewer groups, users who inherit through groups. Roaring keeps sparse sets in arrays and dense sets in bitsets, and the intersection never materializes a Java `Integer` per document. The clone matters: an in-place OR into the published user bitmap permanently grants that user every document in the group.

**Significance.** This is the acceleration structure, not the authority. It can lag a revoke by seconds. Commit 7's Postgres confirm exists because a bitmap-only answer would bring that lag back as a leak.

Builder `io.aha.index.idx.IndexBuilder`:

- `addDocument(externalId, title, body)` tokenizes with `Tokenizer`, assigns an id, fills postings, adds the id to `liveDocs`.
- `grantUser(externalDocId, userId)`, `grantGroup(externalDocId, groupId)`.
- `addMembership(userId, groupId)`.
- `deleteDocument(externalDocId)` removes the id from `liveDocs`, from every posting term it contributed (you stored the token list on the doc record), and from every grant bitmap that contains it. Track `Map<Integer, List<Principal>> grantsByDoc` so delete does not scan every bitmap. A second delete is a no-op.
- `IndexSnapshot build(boolean ready)` computes `avgdl` from live docs, wraps maps in `Map.copyOf`, clones every bitmap (`bitmap.clone()`), clones membership arrays, and returns a snapshot the builder may continue to modify afterwards without the snapshot changing. Grant a user on the builder after `build()`. The published snapshot's bitmap does not contain the new doc.

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

Store memberships as `Map<String, String[]>` of group id strings, not ints. The group grant map is keyed by group id string. Correct the field list in commit 4 if you already used `int[]`. The snapshot field is `Map<String, String[]> memberships`.

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

`out` is a new bitmap. `direct.or(g)` would mutate the published user bitmap. After `visible` returns, the stored user bitmap cardinality is unchanged.

Commit:

```text
feat: encode allow-grants as roaring bitmaps

Why:
A query has to intersect matches with the documents this user can
read without scanning ACL rows. The bitmap is that set. Cloning
on publish and OR-ing into a fresh bitmap are what keep a query
from permanently granting the user every document in their groups.

Verify:
./gradlew compileJava
```

## Commit 6 — `feat: intersect postings with the visibility bitmap before scoring`

**What.** Order query terms by document frequency, rarest first. Intersect postings. Intersect the survivor set with the visibility bitmap as soon as that bitmap is more selective than the next term. Score survivors into the size-10 heap.

**Why.** Phase 1 filtered after collecting matches, so cost tracked the whole corpus. Pre-filter cost tracks the documents the user can read. Scoring a hundred hits and keeping one is the bug this commit removes, and the scored-document count is how you see that it happened.

**Significance.** The phase-2 benchmark's story is this order of operations. A post-filter that looks correct on Ada's three documents still falls over when the unauthorized corpus grows, which is the case commit 9 measures.

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

BM25 `tf` is the term frequency stored at index time. You need it. A postings entry is not only a doc id. Change `Postings` in this commit (or a fixup commit if commit 2 is already too far to amend) so each term maps to parallel `int[] docIds` and `int[] tfs`, same length, same order. `add` takes `(term, docId, tf)`. `remove` removes both. `DocTable` already has the token list; the loader passes `termFreq`.

If you notice this only now, that is what this commit is for. Update postings and `Bm25` to read `tf` from the posting, not from a rescan of the body. Rescanning the body is correct and slower. Do the parallel array. A comment in `Postings` should say the two arrays stay the same length.

Scoring a candidate:

```text
score = 0
for term in queryTokens:
    tf = postings.tf(term, docId)   // 0 if absent, but intersection means it is present
    score += bm25.weight(tf, docLen, avgdl, df, N)
```

Build a snapshot in memory, with no Postgres:

- Three docs, user `ada` granted on doc 0 and doc 2, not doc 1.
- All three contain `quarterly`.
- Search returns external ids of doc 0 and doc 2 only.
- A second case: 100 docs contain `quarterly`, the user can read one of them, the result has size 1. Expose how many docs were scored. That count is 1.

Commit:

```text
feat: intersect postings with the visibility bitmap before scoring

Why:
Post-filtering a top-10 taken from the whole corpus drops authorized
hits when the user can see very little of the index. The bitmap
intersection happens before BM25, and the scored-doc counter locks
that order.

Verify:
./gradlew compileJava
```

## Commit 7 — `feat: load a snapshot from Postgres and confirm candidates`

**What.** At startup, one repeatable-read transaction loads documents, grants, and memberships into a snapshot per tenant. After BM25, `retainVisible` confirms candidate ids in batches of 30, at most five batches. A SQLException becomes 503, and the unconfirmed list is not returned.

**Why.** The bitmap can be stale after a revoke until the process restarts. The confirm query is a primary-key lookup of a handful of ids, which is the difference from phase 1's sequential scan. A grant that arrives after startup is absent until reload. That limit is written in the class Javadoc so phase 3 has a behavior to remove, and so nobody "fixes" it with a cache flush in this phase.

**Significance.** One global bitmap with colliding doc ids across tenants is a cross-tenant leak, which is why the snapshot is keyed by tenant. Skipping `retainVisible` makes the index faster and wrong the moment a revoke commits.

`io.aha.index.idx.JdbcBootstrap`:

1. Open a connection, `setAutoCommit(false)`, `setTransactionIsolation(REPEATABLE_READ)`.
2. Read documents `ORDER BY tenant_id, doc_id`. You are building one snapshot that contains every tenant. Namespace the external key as `tenantId + "\u0000" + docId` inside `DocTable`, or build `Map<String, IndexSnapshot>` keyed by tenant. Prefer **one `IndexBuilder` per tenant** stored in `Map<String, IndexBuilder>`, and a published `Map<String, IndexSnapshot>`. Change `SnapshotPublisher` to hold `volatile Map<String, IndexSnapshot>` or a small `CorpusSnapshot` that contains the map. Do this now, before the HTTP handler, even if it means adjusting commit 4's type. A single global bitmap with colliding doc ids across tenants is a cross-tenant leak.
3. Read `acl_entries` and `group_memberships` in the same transaction.
4. Commit the JDBC transaction.
5. `publish` with `ready=true`.

`QueryEngine` gains `Connection confirmConn` and after `TopK` collects a ranked candidate list of up to 30 external ids per batch:

1. Take the BM25 order of all intersected candidates. If there are more than 150, keep the best 150 by the heap of size 150 (a second `TopK` with capacity 150, or reuse `TopK` with a constructor argument). 150 is 5 batches of 30.
2. Walk that list in order, in slices of 30.
3. `documentStore.retainVisible(conn, tenant, user, slice)`.
4. Append survivors until you have 10 or you run out.
5. SQLException from the confirm call becomes `IndexNotReadyException`. The handler returns 503. It does not return the unconfirmed list.

Against Compose, with the canary seed loaded and the index bootstrapped from JDBC:

- Ada `quarterly` returns `d-ada-1`, `d-eng-1`, `d-shared` in BM25 order. Compute the expected order with a scratch class `ReferenceBm25` that you run once from `main` and then delete, or by hand from the formula in the architecture doc. It calls `Tokenizer` and does not call `io.aha.index.idx.Bm25`.
- Revoke ada's direct grant on `d-ada-1` in Postgres without rebuilding the index. Search again. `d-ada-1` is absent. That is the confirm path.
- Insert a new document granted to ada without rebuilding. Search. It is absent. Phase 3 is what makes a new grant visible without a restart. Say that in the class Javadoc.
- The canary is absent for ada and present for ivan.
- `scored` for ada on `quarterly` is under 10. Ada can see 3 matching docs. The fillers are not scored.

Ranking may differ from phase 1's count score. Compare the engine's external ids with the reference BM25 order. The set of hits is a subset of `DocumentStore.visibleDocuments` filtered by the tokenizer match rule.

Commit:

```text
feat: load a snapshot from Postgres and confirm candidates

Why:
The bitmap can be stale after a revoke until the process restarts.
retainVisible on the candidate ids is the check that makes a revoke
disappear immediately. A grant still waits for a reload. The class
Javadoc records that limitation so phase 3 has something to remove.

Verify:
./gradlew compileJava
```

## Commit 8 — `feat: serve indexed search from the same application`

**What.** `GET /v1/search` on port 18480 now answers from the snapshot and returns `mode=indexed`. `GET /v1/search?engine=sql` still runs the phase-1 scan, so you can compare the two hit lists on one process. Writes stay on the same routes they already have. Startup bootstraps the snapshot from JDBC after the existing migration runner.

**Why.** A second port would be a second process, and nothing in this phase needs one. The comparison you want is two engines, not two listeners. A route that only a future coordinator would call is not added here. Phase 4's coordinator calls this same `GET /v1/search`.

**Significance.** `engine=sql` is the check that the index did not change who is visible. Removing it to "simplify" the controller removes the only way to see a wrong bitmap without a second app.

- `GET /v1/health` adds `ready`. Lag and LSN arrive with the log in phase 3.
- `GET /v1/search` returns `mode=indexed`. `replica` stays `oracle`, the one process. `index-a` and `index-b` are names for the two processes phase 4 starts.
- `GET /v1/search?engine=sql` is the phase-1 path.

Repeat the Ada and Ivan curls from phase 1 against `127.0.0.1:18480`. Then repeat them with `engine=sql` and confirm the doc ids match.

Commit:

```text
feat: serve indexed search from the same application

Why:
The comparison is two engines on the port you already have.
A second process waits until phase 4 needs two copies.

Verify:
./gradlew compileJava
```

## Commit 9 — `docs: record post-filter cost against pre-filter cost`

**What.** `SelectivityBenchmark` times a user who can read 0.1% of 20,000 documents against a user who can read all of them, and writes `docs/benchmarks/phase2.md`. Confirm is off for this measurement and on in the server. A post-filter that lives only in the benchmark scores the global top and then drops unauthorized hits.

**Why.** The sparse user is the case where ranking the whole corpus and then dropping unauthorized hits returns the wrong set and does more work. The numbers belong next to the phase-1 baseline so the lesson is a measurement, not a claim.

**Significance.** Phase 4's p99 is believable only if this page already shows that cost follows the visible corpus. A benchmark that leaves confirm on is still useful, and it is a different number; the page says which one you measured.

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
./gradlew assemble
git checkout main
git merge --ff-only feature/phase-2-bitmap-prefilter
git tag -a v0.2-prefilter -m "In-memory postings, BM25, roaring pre-filter, Postgres confirm"
```

Exit bar:

- Ada's canary is absent through the index HTTP port.
- Revoking a grant in Postgres removes it from the next index search without a restart.
- A new grant does not appear until restart. The class Javadoc says so.
- `scored` stays tiny for the sparse user.
- No `web/` directory.
