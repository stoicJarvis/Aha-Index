# Aha-Index

Aha-Index is a multi-tenant document search service. For a tenant-scoped user and a keyword query it returns the 10 documents that user is allowed to read, ranked by BM25. A document with no allow-grant is invisible. A grant, revoke, or group-membership change committed in Postgres is reflected in search within 30 seconds. Under normal load, 99% of queries finish within 100ms of server time even when one index replica is slow.

## Read and build in this order

Do not open the frontend guide until `v0.4-slo` is tagged on `main`.

1. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the design you are implementing. Read it once before writing code, and again at the start of each phase.
2. [docs/implementation/00-before-you-write-code.md](docs/implementation/00-before-you-write-code.md) — tools, git rules, invariants, module layout.
3. [docs/implementation/01-phase-1-naive-oracle.md](docs/implementation/01-phase-1-naive-oracle.md)
4. [docs/implementation/02-phase-2-bitmap-prefilter.md](docs/implementation/02-phase-2-bitmap-prefilter.md)
5. [docs/implementation/03-phase-3-cdc-freshness.md](docs/implementation/03-phase-3-cdc-freshness.md)
6. [docs/implementation/04-phase-4-hedged-slo.md](docs/implementation/04-phase-4-hedged-slo.md)
7. Only after tag `v0.4-slo`: [docs/implementation/05-phase-5-frontend.md](docs/implementation/05-phase-5-frontend.md)

Tick items in [docs/CHECKLIST.md](docs/CHECKLIST.md) as you finish them.

## Rules you do not relax

- One commit does one thing. Tests land in the same commit as the behavior they lock.
- `main` is always demoable. Phase work happens on the feature branch named in that phase's guide. Merge with `git merge --ff-only`, then tag.
- The phase-1 visibility SQL stays in the repo forever. Later phases call it to confirm candidate hits and to generate the authorized set in tests.
- Frontend code, `package.json`, and Node tooling are created in phase 5. Earlier phases are Java, Postgres, Kafka, and Debezium only.
- Demo identity headers exist so tests and, later, the switcher can act as a user. The class that reads them is the only place those headers are trusted, and only when `AUTH_MODE=demo`.
