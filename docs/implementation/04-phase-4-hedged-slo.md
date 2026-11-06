# Phase 4 — p99 under 100ms with one slow replica

Branch: `feature/phase-4-hedged-slo`

Tag: `v0.4-slo`

```bash
git checkout main
git checkout -b feature/phase-4-hedged-slo
```

Phase 3's index is correct and fresh, inside one process. One stalled search still stalls the caller, and hedging needs a second copy. This phase starts that second copy from the same jar. A new Gradle project appears only when the program is different: the coordinator, in the commit that proxies, and the load generator, in the commit that measures. The tokenizer, the visibility SQL, and the index classes stay in the jar both replicas run. The browser still does not exist. This phase has no test sources.

Every commit opens with what it changes, why that change stands alone, and the significance for the phases after it.

## Commit 1 — `feat: run a second index replica in its own consumer group`

**What.** The same Boot jar, started three times. One process keeps the write routes on port 18480 and does not consume the log (`ROLE=writer`). Two processes consume the log, `index-a` on 18481 and `index-b` on 18482, each with its own consumer group. There is no new Gradle module.

**Why.** Hedging needs two complete copies. A shared consumer group with one partition delivers the log to only one of them, and the other sits empty. The copies are the same program with different environment, so a second source tree would only duplicate the classes phase 3 already runs. ZGC is set here, on the two long-running index JVMs, because this is the phase that measures pauses. The writer stays on 18480 so grants still have one place to land.

**Significance.** The coordinator in the rest of this phase assumes both index ports answer the same Ada query. A group id copied from one service to the other looks healthy in Compose and fails the moment the backup is used.

`deploy/Dockerfile.index` packages the jar `./gradlew bootJar` already produces:

```dockerfile
FROM eclipse-temurin:21-jre
COPY build/libs/aha-index-0.1.0-SNAPSHOT.jar /opt/index.jar
ENTRYPOINT ["java", "-jar", "/opt/index.jar"]
```

The writer stays a host process: `ROLE=writer`, `BOOTSTRAP=jdbc`, port 18480. It serves `POST /v1/documents`, `POST /v1/acl`, and `POST /v1/memberships`. It does not start `LogConsumer`.

Both index services:

- `ROLE=index`
- `BOOTSTRAP=log`
- `KAFKA_BOOTSTRAP=kafka:9092`
- `PGHOST=postgres`, `PGPORT=5432`, same user and database
- `JAVA_TOOL_OPTIONS=-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m`

They differ:

| Service | `REPLICA_ID` | `CONSUMER_GROUP` | host port |
| --- | --- | --- | --- |
| index-a | index-a | index-replica-a | 18481 |
| index-b | index-b | index-replica-b | 18482 |

Same group id would assign the single partition to one of them. The other would sit idle and every hedge would hit an empty replica. A comment in the compose file states that the two `CONSUMER_GROUP` values differ. `GET /v1/search` on an index process returns `replica` from `REPLICA_ID`. The writer is no longer the search path.

Bring both up. `deploy/freshness.sh` still targets `index-a`. Hit `index-b` with the same curl and expect the same Ada ids.

Commit:

```text
feat: run a second index replica in its own consumer group

Why:
Hedging needs two complete copies of the index. A shared consumer
group with one partition feeds only one copy, so the groups are
different. The compose comment records that.

Verify:
both health endpoints ready=true and the same Ada search ids
```

## Commit 2 — `chore: add the coordinator module`

**What.** A new Gradle project, `:coordinator`, on port 18483. It proxies search to `index-a` and proxies writes to the writer on 18480. One attempt. No hedge yet. `gradle/libs.versions.toml` is created in this commit so the two Boot builds share the Spring Boot version.

**Why.** Clients need one address, and that address must not be an index replica. The coordinator is a different program, which is why it is a second project. It forwards the response body, so it does not need the index's classes, a shared JSON jar, or the tokenizer. Introducing it as a straight proxy makes the hedge commit a behavior change on its own. Writes go to the writer and are not retried, because a hedged grant would race the row that is the source of truth. The load generator is not created here. It arrives in the commit that runs it.

**Significance.** The browser in phase 5 talks only to this port. A coordinator that depends on the index project would compile the replica's internals into the edge and hide the network hop the SLO is about.

`settings.gradle.kts` includes `coordinator` and nothing else.

`coordinator/build.gradle.kts` applies the Spring Boot plugin and depends on `spring-boot-starter-web` only. `CoordinatorApplication` listens on port `18483`.

`SearchController` proxies `GET /v1/search` to `index-a` only, using `java.net.http.HttpClient`. Timeouts: connect 50ms, request 80ms. Forward the principal headers and `X-Request-Id`. Return the upstream body and status. Do not pass `engine=sql`. `GET /v1/health` returns the coordinator's own status plus the upstream status code. `DemoPrincipalFilter` is copied into this project with the same rules. The coordinator does not depend on the index sources.

This commit is a dumb proxy. Hedging comes next. Point the coordinator at the Compose index and confirm a search body comes back with status 200.

Writes are not proxied to an index replica. `POST /v1/documents`, `POST /v1/acl`, and `POST /v1/memberships` go to the writer, `WRITER_URL` default `http://127.0.0.1:18480`. They are a single attempt. A hedge target is never called for `POST /v1/acl`.

Commit:

```text
chore: add the coordinator module

Why:
Clients need one address. This commit introduces that process as a
straight proxy so the hedge commit is a behavior change on its own.
Writes go to the writer on 18480 and are not retried.

Verify:
./gradlew :coordinator:compileJava
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ada' 'http://127.0.0.1:18483/v1/search?q=quarterly'
```

## Commit 3 — `feat: track replica latency with an EWMA and a p95 ring`

**What.** `ReplicaStats` records successful latency samples. EWMA uses alpha 0.2 and starts at 20ms. p95 is a 64-sample ring, and until eight samples exist it returns 20ms. The primary is the lowest EWMA among replicas that are not ejected, with a lexicographic tie-break.

**Why.** The hedge delay and the primary choice need a number that moves with the replica and forgets a single spike. EWMA is that number. p95 is the backup timer. The tie-break makes the same inputs pick the same replica, so a demo is explainable.

**Significance.** Commit 4's hedge timer and commit 6's ejection both read this object. A primary chosen by error rate would keep a replica that is up and slow, which is the failure mode in the spec.

`io.aha.index.coord.ReplicaStats`:

- `void record(String replicaId, long elapsedMs, boolean success)`
- `double ewmaMillis(String replicaId)` starts at 20. Alpha `0.2`. Update only on success. `ewma = 0.2 * sample + 0.8 * ewma`.
- `long p95Millis(String replicaId)` from a ring of 64 successful samples. Until there are 8 samples, return 20. Otherwise copy the occupied slots, `Arrays.sort`, index `floor(0.95 * (n - 1))`.
- `String primary(List<String> ids, Set<String> ejected)` — lowest EWMA among ids not in `ejected`. Tie-break lexicographic id, so the same inputs always pick the same replica.
- `String backup(String primary, List<String> ids)` — the other id. Two replicas only. If you pass three, pick the lowest EWMA among the rest. Do not build a general load balancer past that.

Worked checks for `ReplicaStats`:

- Record 100ms, then 100ms. EWMA moves from 20 toward 100. After one sample: `0.2*100+0.8*20 = 36`. Assert that.
- Fill 64 samples with 10 and one with 500 before the window wraps. p95 is 10 while the 500 has aged out of the 64. A simpler assertion: 20 samples of 10 and 1 sample of 400, with n=21, index `floor(0.95*20)=19` on the sorted array. Sorted is twenty 10s then 400 if you only have 21 values of which 20 are 10. Index 19 is 10. Change the mix so index 19 is the high value: 2 low and 19 high is the wrong lesson. Assert the exact index formula with a hand-computed array of `1,2,3,...,21`. p95 index `floor(0.95*20)=19`, value `20` because the series is 1-based values equal to the rank. Sorted values are 1..21, index 19 is 20. Assert `p95 == 20`.

Commit:

```text
feat: track replica latency with an EWMA and a p95 ring

Why:
The hedge delay and the primary choice are functions of recent
latency. A fixed 20ms hedge on a replica whose healthy p95 is 4ms
fires too often. A fixed hedge on a replica whose p95 is 40ms
fires too late. The ring is there so the delay is measured.

Verify:
./gradlew :coordinator:compileJava
```

## Commit 4 — `feat: hedge the backup replica at the primary p95`

**What.** Send the search to the primary. If it has not returned by `min(20ms, that replica's p95)`, send one backup to the other replica. Return the first success. Cancel the loser. The internal deadline is 80ms.

**Why.** Search is read-only, so a duplicate attempt is safe. One extra request is the cap: a second backup would amplify a brownout. The 20ms cap stops a poisoned p95 from waiting out the whole SLO before the backup is even sent.

**Significance.** This is the mechanism behind "one copy of the index is slow and p99 still holds." Writes stay single-attempt. Copying this pattern onto `POST /v1/acl` would apply a grant twice.

`io.aha.index.coord.HedgedSearch`:

```java
public Response search(SearchCall call, Deadline deadline)
```

`Deadline` is `long startNanos` plus `80_000_000` ns. Use `System.nanoTime()`, not the wall clock, for the budget. The watermark SLO still uses the wall clock inside the replica.

Algorithm:

1. `primary = stats.primary(replicas, ejected)`.
2. Start the call on a virtual thread. The call uses `HttpClient.sendAsync`.
3. `hedgeDelayMs = min(20, max(1, stats.p95Millis(primary)))`.
4. `primaryFuture.get(hedgeDelayMs, MILLISECONDS)`. On success, record latency, return. On timeout, continue. On a completed 503 from the primary, start the backup immediately. On a completed 400, return the 400. Do not hedge a client error.
5. Start one backup if a different replica exists and the deadline has at least 5ms left.
6. `CompletableFuture.anyOf` the still-running calls, with the remaining budget as the outer timeout.
7. Cancel the loser: `future.cancel(true)` and close the body. Record the winner's latency against the winner's id. Record nothing for a cancelled loser (you do not know its service time).
8. If the winner is 503 and the other also finished 503, return 503. If one is 200, return 200.
9. If the budget expires, return 503 `INDEX_UNAVAILABLE` when every observed failure was 503, otherwise 502 `UPSTREAM_FAILED`.
10. At most one backup. There is no third try.

With two local stub processes started from a small `main` you delete after the check. Those stubs can be JDK `HttpServer` instances. They are stand-ins for a slow replica, not the service:

- Primary sleeps 300ms, backup returns in 5ms. Hedge delay is forced by seeding p95 with samples of 10. The client observes a 200 from the backup's body and the elapsed time is under 150ms.
- Primary returns 200 in 5ms. Assert the backup's request count stays 0. Give the backup a counter incremented at the start of its handler.
- Primary returns 400. Backup's counter stays 0.
- Both sleep 2 seconds and the deadline is 80ms. The caller returns 502 or 503 in under 500ms. Two seconds means cancellation is broken.

The stub primary's sleep must be interruptible (`Thread.sleep` in the handler). Cancelling the HTTP future does not abort the server-side sleep. The check is that the caller returns. The server-side sleep may continue. Leave that thread alone.

Commit:

```text
feat: hedge the backup replica at the primary p95

Why:
One slow copy of the index is the SLO's failure mode. A single
backup request, delayed by the primary's own p95 and capped at
20ms, is the tail-tolerant pattern. Client errors are not hedged.
Writes are not on this path.

Verify:
./gradlew :coordinator:compileJava
```

## Commit 5 — `feat: coalesce in-flight searches that share a replica and an epoch`

**What.** `SingleFlight` joins identical searches that are already in flight. The key is tenant, user, normalized query, target replica, and that replica's advertised `aclEpoch`.

**Why.** Two callers asking the same question at the same moment should share one replica call. Two callers in different epochs must not share a future: a post-revoke caller would otherwise receive a pre-revoke result that was already started.

**Significance.** Coalescing is a latency tool with a security key. Dropping `aclEpoch` from the key is a leak that still looks like a cache hit in the logs.

`io.aha.index.coord.SingleFlight<K, V>`:

- `V doOrJoin(K key, Callable<V> call)`
- The map is `ConcurrentHashMap<K, CompletableFuture<V>>`.
- `compute` to insert a future if absent. The winner runs `call` on the calling thread (the virtual thread of the HTTP handler). Waiters block on `future.join()` with the same deadline as the caller; if you cannot share the deadline easily, each waiter uses its own deadline and a timeout on `get`. A timed-out waiter does not cancel the shared call if another waiter remains. The last waiter's timeout cancels.
- `whenComplete` removes the key.
- Exceptions complete the future exceptionally. Every waiter sees them.

Key:

```text
tenant + "\0" + user + "\0" + normalizedQuery + "\0" + replicaId + "\0" + aclEpoch
```

`normalizedQuery` is the query tokens joined by a single space, not the raw string. `aclEpoch` is the `aclEpoch` field you expect the replica to serve. You do not know the epoch until the replica answers, which is too late for the key. Use the last epoch you observed from that replica's health or from its previous response, stored in `ReplicaStats`. If you have never seen one, use `"unknown"`. Two callers that both see `"unknown"` may share. After a response, store `response.aclEpoch`. A caller that arrives with a newer stored epoch does not join the old flight.

The replica must actually return a changing epoch. Set `aclEpoch` on the index health and search response to `Long.toString(appliedLsn)`. Phase 2 left it as `"0"` on the JDBC path. The log path publishes `appliedLsn`. Thread it into `SearchResponse`.

Single-flight check:

- Two threads call `doOrJoin` with the same key. The callable increments an atomic counter and sleeps 50ms. Assert the counter is 1 and both threads receive the same value.
- Two keys that differ by epoch run the callable twice.

Wire it inside the coordinator **after** primary selection and **before** the hedge. The backup is a second call with a different replica id in the key, so it does not join the primary's flight. Do not coalesce across replicas.

Commit:

```text
feat: coalesce in-flight searches that share a replica and an epoch

Why:
A burst of identical queries would otherwise run the intersection
once per request on the same replica. The epoch is in the key so a
search that started before a revoke does not satisfy a caller who
arrived after the replica advertised a newer LSN.

Verify:
./gradlew :coordinator:compileJava
```

## Commit 6 — `feat: eject a replica that is alive and slow`

**What.** Over a 60-second window of at least 20 requests, if more than 5% needed the hedge, the slow replica leaves the primary set for 30 seconds. It remains eligible as a hedge target.

**Why.** Ejection uses latency, not errors. A replica that is up and slow is the case the spec names. Keeping it as a backup means a failure of the fast replica still has somewhere to go. Returning it after 30 seconds gives a recovered replica a way back.

**Significance.** Without ejection, the hedge fires on every request and the extra load becomes the outage. The load-generator page in commit 10 should show the primary move to the fast replica while p99 stays under the line.

Inside `ReplicaStats`, a window of 60 seconds:

- `void recordAttempt(String primaryId, boolean hedgeFired, long nowMillis)`
- When `attempts >= 20` and `hedges / attempts > 0.05` over the last 60 seconds, `eject(primaryId, nowMillis + 30_000)`.
- `boolean isEjected(id, now)` is true while `now < ejectedUntil`.
- An ejected replica is skipped by `primary(...)` and is still eligible as `backup(...)`.

Feed 20 attempts with 2 hedges (10%). The replica is ejected. Feed 20 attempts with 1 hedge (5%). The condition is `> 0.05`, so 5% leaves it in the primary set. Advance the clock 31 seconds. It is back in the primary set.

The coordinator records an attempt when a search returns, `hedgeFired` true when the backup was started.

Commit:

```text
feat: eject a replica that is alive and slow

Why:
A replica that always needs a hedge is still taking a primary slot
and adding tail latency. Ejection lasts 30 seconds and is based on
the hedge rate, which is the signal the SLO actually cares about.
The ejected replica remains a hedge target so a single healthy
replica is not a single point of failure.

Verify:
./gradlew :coordinator:compileJava
```

## Commit 7 — `feat: inject delay on one replica`

**What.** `POST /admin/faults` sleeps the index handler for `delayMs` when `FAULTS_ENABLED=true`. The route is 404 when the flag is off. `GET` returns the current delay.

**Why.** The SLO sentence is "even when one index replica is slow." A toggle makes that slowness repeatable without killing a process or depending on a garbage-collection pause. The flag defaults off so an ordinary start cannot be paused by an anonymous POST.

**Significance.** Commit 10 and the phase-5 switch both call this route. A delay that is compiled into the scorer with no flag would ship inside the latency number you are trying to measure.

On the index process, `POST /admin/faults` with body `{"delayMs":400}`.

- If env `FAULTS_ENABLED` is not `true`, return 404 `NOT_FOUND` and do not change state.
- Else set `AtomicLong delayMs`.
- `QueryEngine.search` calls `Thread.sleep(delayMs.get())` at the start when the value is positive. Catch `InterruptedException`, restore the interrupt flag, and throw `IndexNotReadyException`.
- `GET /admin/faults` returns the current delay so you can read it back.
- The coordinator exposes `POST /admin/faults/replica` with `{"replica":"index-a","delayMs":400}` and forwards it to that replica's admin URL, only when the coordinator's own `FAULTS_ENABLED=true`. Otherwise 404. This route is not hedged.

Call the engine with a delay of 50ms and see an elapsed time of at least 50ms. With `FAULTS_ENABLED=false`, `POST /admin/faults/replica` returns 404.

Commit:

```text
feat: inject delay on one replica

Why:
The SLO requires a slow copy, not a crashed one. The delay sits
in front of the scorer so hedging has a real tail to avoid.
The flag defaults off so a production-shaped start cannot be paused
by an anonymous POST.

Verify:
curl -s -X POST localhost fault route, then a search whose elapsed time includes the delay
```

## Commit 8 — `feat: emit Server-Timing on the coordinator`

**What.** Every search response carries `Server-Timing` for `index`, `authz`, `hedge`, `lag`, and `coord`, plus `X-Replica` and `X-Hedged`. Fault-injection sleep is reported as `fault`, separate from `index`.

**Why.** The 100ms line is server time. The header splits index, authorization, hedge, and coordinator so a client can quote the same numbers the server measured. Including the injected sleep inside `index` would make a drill look like a slow scorer.

**Significance.** The load generator and the phase-5 widget both read `coord`. A p99 computed from client wall time mixes laptop network into the SLO and makes a healthy index look late.

Every search response gets a `Server-Timing` header. Build it with `StringBuilder`, not string concatenation in a loop.

Metrics, each `dur` in milliseconds with one decimal:

- `index` — the winner replica's handler time. The replica adds its own `Server-Timing: index;dur=...` measuring snapshot search only. The coordinator forwards that value.
- `authz` — the replica measures `retainVisible` and adds `authz;dur=...`. The coordinator forwards it.
- `hedge` — milliseconds from coordinator start until the backup was issued, or `0` if no backup was issued.
- `lag` — `watermarkLagMs` from the winner's JSON body.
- `coord` — coordinator wall time from accept to just before writing the response.

Also set response header `X-Replica` to the winner id and `X-Hedged` to `true` or `false`.

With one replica delayed, the coordinator response contains `hedge;` in `Server-Timing` and `X-Hedged: true`.

On the replica, measure with `System.nanoTime()` around the engine call and around `retainVisible`. Do not include the fault-injection sleep in `index`. Include it in a separate `fault;dur=` metric so a demo can see the stall and the hedge separately. The load generator reads `coord`, not `fault`.

Commit:

```text
feat: emit Server-Timing on the coordinator

Why:
The 100ms line is server time. The header splits index, authz,
hedge, and coordinator time so a later UI and the load generator can
quote the same numbers a client measured.

Verify:
curl -sD - -o /dev/null -H 'X-Tenant-Id: acme' -H 'X-User-Id: ada' 'http://127.0.0.1:18483/v1/search?q=quarterly'
```

## Commit 9 — `perf: keep the scorer off the allocator`

**What.** A pass over `QueryEngine` and `Postings`: primitive arrays, a size-10 score buffer, a thread-local candidate buffer, no per-document objects. Ada's and Ivan's curls stay the same. A JMH run prints allocations, and you paste the profile into `docs/benchmarks/phase4-alloc.md`.

**Why.** Generational ZGC keeps pauses short. It does not make a per-document allocation free. Allocation rate still burns the cores the scorer needs. One visibility bitmap and the response objects are the budget; a `PriorityQueue` of boxed hits is not.

**Significance.** The p99 in the next commit is measured after this pass. A green latency number on an allocating scorer will move the moment the corpus or the concurrency grows, and the profile is how you know which one you shipped.

This commit is a pass over `QueryEngine` and `Postings`. Ada's and Ivan's curls stay the same.

Rules you apply, in this order, each as a small edit inside this one commit only if you can still describe it as one performance pass. If the diff gets large, split into `perf:` commits that each name the structure:

1. `Postings.contains` and `tf` use binary search on the primitive array. No `IntStream`, no boxing.
2. `TopK` stores `int[] ids` and `double[] scores` of length `capacity`. No `PriorityQueue<Scored>` where `Scored` is an object allocated per candidate. A simple insertion into a sorted array of 10 is enough and allocates nothing per offer. Replace `PriorityQueue` if you used it.
3. `visible(snapshot, user)` still allocates one `RoaringBitmap`. That allocation is allowed. Do not cache it on the snapshot (it depends on the user).
4. The candidate `int` buffer is taken from a `ThreadLocal<int[]>` of length 256, grown by doubling and stored back if a query needs more. Each query thread keeps its own buffer.
5. Do not build a `List<Integer>`.

`settings.gradle.kts` gains `include("loadgen")` in this commit. It is the first code that needs a benchmark harness. A single benchmark `QueryEngineBenchmark` builds the 20_000-doc sparse corpus from phase 2 in memory, with confirm disabled, and scores the sparse user. Run:

```bash
./gradlew :loadgen:jmh
```

You need the JMH plugin or a main that calls `org.openjdk.jmh.Main`. The Gradle plugin `me.champeau.jmh` version `0.7.2` is the straightforward choice. Apply it to `loadgen`.

The benchmark is not a CI gate on nanoseconds. It is a gate on allocations: JMH's `-prof gc` output, run once by you, gets pasted into `docs/benchmarks/phase4-alloc.md`. If `norm` allocations per query are dominated by the one visibility bitmap and the response objects, you are done. If you see per-document objects, go back to the list above.

Do not disable the Postgres confirm to win this number in the server. The benchmark disables it. The server does not.

Commit:

```text
perf: keep the scorer off the allocator

Why:
ZGC makes pauses short. It does not make a per-document allocation
free. The scorer's hot path is primitive arrays plus one bitmap
OR. The JMH gc profile is the evidence.

Verify:
Ada and Ivan curls unchanged
docs/benchmarks/phase4-alloc.md pasted from jmh -prof gc
```

## Commit 10 — `perf: record p99 under 100ms with one replica delayed`

**What.** `SloLoad` sends concurrent searches at the coordinator with `index-a` delayed 400ms. It exits 1 if any response is not 200, if the body contains the canary, or if p99 of `coord` is over 100ms. The printed line, the fault, and the machine go into `docs/benchmarks/phase4.md`.

**Why.** This is the SLO, measured on the path a caller uses, with the failure mode the spec names. The canary is checked in the same run so a fast path that leaks and a correct path that stalls both show up together. Warmup is discarded so the figure is not a cold JVM.

**Significance.** Tag `v0.4-slo` means this page exists and the number is at or under 100ms. Phase 5's widget draws the same line. Raising the threshold to make the run pass records a different product than the one the architecture describes.

`loadgen` main `io.aha.index.loadgen.SloLoad`:

1. Args or env: `COORDINATOR_URL` default `http://127.0.0.1:18483`, `CONCURRENCY` default `8`, `REQUESTS` default `400`, `PATH` the Ada quarterly search with headers.
2. A warmup of 50 requests, discarded.
3. `CONCURRENCY` virtual threads share an atomic counter up to `REQUESTS`.
4. Each request records coordinator-side elapsed time using `System.nanoTime` around `HttpClient.send`, and also parses `coord;dur=` from `Server-Timing` when present. The recorded time is the `coord` metric when the header is present, and the client elapsed time otherwise.
5. Fail the process with exit code 1 if any response is not 200, if the body contains `d-canary`, or if p99 of the `coord` samples is `> 100`. p99 index: sort, index `ceil(0.99 * n) - 1`.
6. Print p50, p99, max, hedge rate (fraction of responses with `X-Hedged: true`).

Run it with Compose up, both replicas ready, the coordinator up, and `FAULTS_ENABLED=true`. POST `delayMs=400` to `index-a` and `delayMs=0` to `index-b`, run the main, then set both delays back to 0.

The warmup is mandatory so the first samples are not a cold JVM. A few seconds is a normal run.

Write `docs/benchmarks/phase4.md` with the printed line, the fault configuration, and the machine. One sentence: "index-a slept 400ms per query; the coordinator's p99 stayed at or under 100ms; the canary was absent."

If p99 is over 100, leave the threshold at 100 and find the cause. Typical causes, in the order you should check:

1. Confirm SQL is opening connections outside `appDataSource`. Both pools already exist. Set the app pool's maximum size to 8 if the benchmark shows connection setup on the search path. One app pool per index process.
2. The hedge delay is larger than 20ms because p95 was poisoned by the slow replica before ejection. Ejection should move the primary to `index-b`. Print which replica won. When `index-a` is still primary, the ejection window is wrong.
3. JSON of the document body is in the response. Hits carry a snippet only.
4. A `POST` is being hedged and serializing on the oracle.

Commit:

```text
perf: record p99 under 100ms with one replica delayed

Why:
This is the SLO. One replica sleeps for 400ms. The coordinator
p99, the canary, and the status codes are checked together so a
fast path that leaks and a correct path that stalls both show up
in the same run.

Verify:
# delayMs=400 on index-a, then:
java -jar loadgen/build/libs/loadgen-0.1.0-SNAPSHOT.jar
```

## Merge

```bash
./gradlew assemble
git checkout main
git merge --ff-only feature/phase-4-hedged-slo
git tag -a v0.4-slo -m "Hedged coordinator, p99 under 100ms with one slow replica"
```

Exit bar:

- The load generator exited 0 once on your machine with the fault enabled, and `docs/benchmarks/phase4.md` has the numbers.
- Killing `index-b` (the fast one) makes searches return from `index-a` or return 503. They do not return `d-canary`. Try it with `docker compose stop index-b`, curl, then `docker compose start index-b`.
- `POST /v1/acl` through the coordinator still hits the oracle once. `curl` it and read a single `committedLsn`.
- `git tag --list 'v0.4-slo'` prints the tag.
- There is still no `web/` directory. Phase 5 is next, and only next.
