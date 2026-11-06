# Checklist

Tick a line only after that commit is on the phase branch and the verify command passed. Do not tick phase 5 before `v0.4-slo` is on `main`.

## Setup

- [ ] JDK 21, Docker Compose, git
- [ ] First commit is the workbook

## Phase 1 — `feature/phase-1-naive-oracle`

- [ ] `chore: scaffold the Java 21 Gradle build`
- [ ] `chore: run Postgres 16 and prove JDBC connects`
- [ ] `feat: add the allow-grant schema and a migration runner`
- [ ] `feat: add the shared tokenizer`
- [ ] `feat: seed the canary corpus`
- [ ] `feat: list the documents a user can read`
- [ ] `feat: rank the visible set in memory and return ten hits`
- [ ] `feat: serve the oracle over HTTP`
- [ ] `test: lock the leak properties`
- [ ] `docs: record the phase-1 latency baseline`
- [ ] Fast-forward `main`, tag `v0.1-naive`
- [ ] Ada's curl has no canary. Ivan's first hit is the canary. No `web/` directory.

## Phase 2 — `feature/phase-2-bitmap-prefilter`

- [ ] `chore: add the index module`
- [ ] `feat: assign dense doc ids and store document records`
- [ ] `feat: build sorted postings lists`
- [ ] `feat: score with BM25 into a fixed top-10 heap`
- [ ] `feat: publish index snapshots through a volatile field`
- [ ] `feat: encode allow-grants as roaring bitmaps`
- [ ] `feat: intersect postings with the visibility bitmap before scoring`
- [ ] `feat: load a snapshot from Postgres and confirm candidates`
- [ ] `feat: serve the index on port 18481`
- [ ] `docs: record post-filter cost against pre-filter cost`
- [ ] Fast-forward `main`, tag `v0.2-prefilter`
- [ ] Revoke is visible without restart. New grant is not, and a test says so.

## Phase 3 — `feature/phase-3-cdc-freshness`

- [ ] `chore: run Kafka KRaft and Debezium Connect beside Postgres`
- [ ] `feat: register the Postgres connector with transaction metadata`
- [ ] `feat: parse Debezium events and buffer a transaction`
- [ ] `feat: apply a transaction into one published snapshot`
- [ ] `feat: rebuild from the log on start and fail closed when the log is stale`
- [ ] `test: bound grant and revoke propagation on a real log`
- [ ] Fast-forward `main`, tag `v0.3-freshness`
- [ ] Grant shows up within 30 seconds. Paused consumer returns 503. Restart replays from the beginning.

## Phase 4 — `feature/phase-4-hedged-slo`

- [ ] `feat: run a second index replica in its own consumer group`
- [ ] `chore: add the coordinator module`
- [ ] `feat: track replica latency with an EWMA and a p95 ring`
- [ ] `feat: hedge the backup replica at the primary p95`
- [ ] `feat: coalesce in-flight searches that share a replica and an epoch`
- [ ] `feat: eject a replica that is alive and slow`
- [ ] `feat: inject delay on one replica`
- [ ] `feat: emit Server-Timing on the coordinator`
- [ ] `perf: keep the scorer off the allocator`
- [ ] `test: hold p99 under 100ms with one replica delayed`
- [ ] Fast-forward `main`, tag `v0.4-slo`
- [ ] `docs/benchmarks/phase4.md` has a real p99 at or under 100ms with index-a delayed 400ms

## Phase 5 — `feature/web-search-console`

- [ ] `chore: scaffold the Next.js console`
- [ ] `feat: add a principal switcher that sends demo headers`
- [ ] `feat: debounce search input and abort the previous request`
- [ ] `feat: show server time against the 100ms line`
- [ ] `feat: grant, revoke, and measure propagation from the console`
- [ ] `feat: toggle a slow replica from the console`
- [ ] `docs: write the demo script`
- [ ] Fast-forward `main`, tag `v0.5-console`
- [ ] `docs/demo.md` has been run once, including a 375px-wide window
