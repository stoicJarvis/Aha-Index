# Aha-Index architecture

This is the design the implementation guides follow. If a step and this document disagree, this document wins, and you fix the step before writing code.

## Problem

For `(tenant, user, query)` return at most 10 documents that:

- belong to that tenant
- the user can read, either by a direct allow-grant or by membership in a group that has an allow-grant
- are the best BM25 matches for the query among those documents

Invariants:

1. A document the user cannot read does not appear. Missing grants are denial. There is no deny-rule in v1.
2. A grant, revoke, or membership change that has committed in Postgres is visible to search within 30 seconds.
3. Under normal load, server-side p99 is at most 100ms, including the case where one index replica is slow and the other is healthy.
4. When the freshest replica is more than 30 seconds behind the log, search returns 503. It does not serve a stale allow-set in order to keep latency.

"Never leak" and "a replica may lag for a few seconds" are both requirements. A bitmap that has not yet consumed a revoke is not allowed to answer alone. Each index replica confirms the final candidate ids with one Postgres visibility query that reads the current `acl_entries` and `group_memberships` rows. The bitmap keeps that query to a handful of primary keys. The log keeps grants searchable within 30 seconds. The watermark turns a stuck consumer into a 503.

## Stack

| Piece | Choice | Pinned in the guides |
| --- | --- | --- |
| Language | Java 21 | Temurin 21, Gradle wrapper 8.11.1 |
| HTTP | `com.sun.net.httpserver.HttpServer` | No Spring, no Javalin |
| Index | Hand-built in-memory postings, BM25, roaring bitmaps | `org.roaringbitmap:RoaringBitmap:1.6.29` |
| System of record | PostgreSQL 16 | `postgres:16` |
| Change capture | Debezium Postgres connector 3.7.0.Final | `pgoutput`, transaction metadata on |
| Log | Kafka in KRaft mode, the image shipped with that Debezium release | `kafka-clients:4.3.1` |
| Confirm path | JDBC visibility query over the candidate ids | PostgreSQL JDBC 42.7.x |
| Query cache | Caffeine, query-token cache only | `com.github.ben-manes.caffeine:caffeine:3.2.0` |
| Schema | Postgres 16, schema `search`, RLS on tenant tables, app role `aha_app` | [implementation/00-before-you-write-code.md](implementation/00-before-you-write-code.md) section 0.7 |
| Checks | `psql`, `curl`, `EXPLAIN`, benchmark mains | No JUnit, jqwik, or Testcontainers |
| UI (last) | Next.js, TypeScript, Tailwind, shadcn/ui | Phase 5 only |

### Why this runtime

RoaringBitmap, the Kafka client, and Debezium's connector are JVM libraries. The index, the applier, and the change-capture process share one memory model.

Publication is single-writer, many-readers. The applier builds a snapshot and stores it in a `volatile` field. A reader that loads that field happens-before the writes that built it. `RoaringBitmap` mutation is single-threaded. Query code clones or reads a bitmap that has already been published. It does not call `add` on a bitmap another thread can see.

Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational` on Java 21) keeps pauses out of the 100ms budget. Pause time is not allocation rate. The scorer uses primitive postings, a reused size-10 heap, and roaring containers. Virtual threads run the phase-1 JDBC server and blocking HTTP calls in the coordinator. The scorer itself runs on a small platform-thread pool: a CPU-bound intersection does not get faster by adding virtual threads.

External HTTP is JSON (Jackson 2.21.2). Replica fanout is JSON as well, until a benchmark shows encoding of a 10-hit response above 5ms. Only then do you add a length-prefixed binary frame, in its own commit. Ten small hits are not the latency risk. A slow replica is.

### Why the index is hand-built

You implement the dictionary, sorted postings, galloping intersection, BM25, and the visibility bitmap so the commit history shows each of those decisions. Elasticsearch document-level security post-filters after top-k. That is the phase-1 failure mode, not the product. If the corpus later outgrows RAM, the `IndexSnapshot` interface is what a Lucene directory would sit behind. You do not take that step in this build.

### Why Postgres is the source of truth

Writers insert documents, grants, revokes, and memberships only into Postgres, in one transaction per API call. Search nodes learn about those commits from the WAL. They do not accept a permission RPC that mutates the bitmap directly. A direct bitmap write would race the log and create a second source of truth.

Logical decoding emits commits in commit order. Debezium turns them into events with an LSN. The applier applies one database transaction as one published snapshot.

### Why a bitmap plus a point check

Per tenant the index stores:

- `userGrants[userId] -> RoaringBitmap` of dense doc ids
- `groupGrants[groupId] -> RoaringBitmap`
- `memberships[userId] -> int[]` of group ids (small; a bitmap is unnecessary)
- `liveDocs -> RoaringBitmap`

At query time the candidate set is:

```text
visible = clone(userGrants[user])
visible.or(groupGrants[g]) for each group g the user belongs to
visible.and(liveDocs)
```

Clone before `or`. An in-place `or` into the published user bitmap permanently grants that user every document in the group.

The bitmap is the acceleration structure. It can be a few seconds behind a revoke. Before hits are returned, the replica calls `VisibilityStore.retainVisible(tenant, user, orderedCandidateIds)` which runs the same SQL predicate as phase 1, restricted to those ids:

```sql
SELECT d.doc_id
FROM documents d
WHERE d.tenant_id = ?
  AND d.doc_id = ANY (?)
  AND <visible predicate for this user>
```

Drop any id the database does not return. Walk candidates in score order and keep the first 10 that survive. Batch size is 30, at most 5 batches. If Postgres cannot answer, return 503. A bitmap-only answer while Postgres is down would bring back the leak window.

This query is a primary-key lookup of at most 30 ids, not a sequential scan of the corpus. That is the difference from phase 1. Record both times in `Server-Timing` (`index` and `authz`).

Grants are the opposite problem. A grant the consumer has not applied yet will not be in the bitmap, so the document will not be a candidate, and the point check never sees it. The watermark covers that direction: if `now - lastAppliedEventTime > 30s`, the replica reports itself unready and the coordinator returns 503.

### Log shape

Debezium's Postgres connector writes one topic per table, plus `aha.transaction` when `provide.transaction.metadata=true`. Those topics are not ordered against each other unless a single consumer reads all of them and reassembles transactions.

For this build:

- One Kafka partition on every captured topic. A single consumer thread then sees a total order per topic, and the transaction id stitches topics together.
- Both index replicas use **different** consumer group ids (`index-replica-a`, `index-replica-b`). The same group would give the only partition to one replica.
- On every process start, and on every partition assignment, seek to the beginning and rebuild memory from the log. In-memory state dies on restart. A committed offset with empty memory would skip a revoke. Replay is safe because grant, revoke, upsert, and delete are absolute operations.
- Snapshot events (`op=r`) have no transaction wrapper. Apply them immediately.
- Streaming events carry `transaction.id` and `transaction.total_order`. Buffer them until the matching `END` marker arrives with `event_count`. Then apply that buffer in `total_order`, build one snapshot, and publish it with one volatile write.
- `ready` stays false until a poll returns no records after startup replay. Search on that replica returns 503 while `ready` is false.

Do not also JDBC-load the corpus and then replay Debezium's snapshot. Replaying a snapshot read after a later delete resurrects the row. The JDBC loader from phase 2 remains for the `--bootstrap=jdbc` developer switch. The compose stack uses `--bootstrap=log`.

### Coordinator

The browser and the load generator talk only to the coordinator.

- Pick the replica with the lowest EWMA latency among replicas that are not ejected.
- Send the search there.
- If it has not returned by `min(20ms, that replica's recent p95)`, send one backup to the other replica.
- Return the first success. Cancel the other call.
- Search is read-only, so a hedge is safe. `POST /v1/documents` and `POST /v1/acl` go to Postgres through the coordinator and are never hedged.
- Coalesce identical in-flight searches only when they share tenant, user, normalized query, target replica, and that replica's advertised `aclEpoch`.
- If more than 5% of requests in a 60-second window needed the hedge, and the window has at least 20 requests, eject the slow replica from the primary set for 30 seconds. It remains eligible as a hedge target.
- Internal deadline is 80ms. `Server-Timing` reports `index`, `authz`, `hedge`, and `lag`.

EWMA uses `alpha = 0.2`. The p95 is a 64-sample ring buffer: copy, sort, take index `floor(0.95 * (n - 1))`.

### Identity

`AUTH_MODE=demo` (the default in this build) reads `X-Tenant-Id` and `X-User-Id`. `PrincipalResolver` is the only class allowed to do that. `AUTH_MODE=token` ignores those headers. You will not implement real SSO. You will implement the branch, and a `curl` with only those headers against a token-mode server returns 401.

### Response shape (stable from phase 1)

```json
{
  "hits": [{ "docId": "d-eng-1", "title": "...", "snippet": "...", "score": 1.23 }],
  "mode": "naive",
  "replica": "oracle",
  "hedged": false,
  "aclEpoch": "0",
  "watermarkLagMs": 0
}
```

`mode` is `naive` on the oracle process and `indexed` on a replica. The field names do not change in later phases. Add fields only at the end of the JSON objects.

### Processes and ports

| Process | Port |
| --- | --- |
| Postgres | 54329 on the host, 5432 in the network |
| Kafka | 19092 on the host |
| Debezium Connect | 18083 on the host |
| Oracle HTTP | 18480 |
| Index replica A | 18481 |
| Index replica B | 18482 |
| Coordinator | 18483 |
| Web (phase 5) | 43123 |

### Frontend, after the backend SLO

The UI is a harness. It switches tenant and user, searches, grants, revokes, shows propagation delay, and plots server time against a 100ms line. It is built only after `v0.4-slo`. Until then, every check is `curl`, `psql`, and a benchmark main.

## Build phases

```text
feature/phase-1-naive-oracle     -> tag v0.1-naive
feature/phase-2-bitmap-prefilter -> tag v0.2-prefilter
feature/phase-3-cdc-freshness    -> tag v0.3-freshness
feature/phase-4-hedged-slo       -> tag v0.4-slo
feature/web-search-console       -> tag v0.5-console
```

Phase 1 is a correct, slow oracle: load every document the user may read, match tokens in the same `Tokenizer` the index will use, rank with a simple count, return 10. Phase 2 adds postings, BM25, and bitmaps, and you compare a search against the oracle with `curl`. Phase 3 replaces the startup table-scan bootstrap with the log. Phase 4 adds the second replica, hedging, coalescing, fault injection, and a load generator that prints the p99. Phase 5 is the UI.

## References to read at the start of each phase

Phase 1 and 2:

- Robertson and Zaragoza, *The Probabilistic Relevance Framework: BM25 and Beyond*.
- Zobel and Moffat, *Inverted Files for Text Search Engines* (ACM Computing Surveys).
- Lemire et al., *Consistently faster and smaller compressed bitmaps with Roaring*.
- Google, *Zanzibar: Google’s Consistent, Global Authorization System* (USENIX ATC 2019). The LSN watermark is a single-datacenter zookie with a 30 second bound.
- Elasticsearch Guide, document-level security. Read it so you can recognize post-filter-after-top-k.
- Broder et al., *Efficient Query Evaluation using a Two-Level Retrieval Process* (WAND). Read it. Do not implement WAND in this build.

Phase 3:

- Kleppmann, *Designing Data-Intensive Applications*, replication and derived-data chapters.
- PostgreSQL docs: logical decoding, `wal_level`, replication slots.
- Debezium Postgres connector docs, especially transaction metadata and `snapshot.mode`.
- Kafka docs: consumer groups, delivery semantics, offset commit.
- Jay Kreps, *The Log*.

Phase 4:

- Dean and Barroso, *The Tail at Scale*.
- gRPC hedging design notes.
- Marc Brooker, timeouts, retries, and hedging (AWS Builder's Library).
- Java 21 ZGC docs and JEP 439.
- Goetz et al., *Java Concurrency in Practice*, the chapter on safe publication. The `volatile` snapshot is that pattern.

Phase 5:

- MDN: `AbortController`, `Server-Timing`.
- Debounce the value that triggers `fetch`, not the controlled input the person is typing.
