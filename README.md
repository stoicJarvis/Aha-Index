# Aha-Index

Aha-Index is a multi-tenant document search service. For a tenant-scoped user and a keyword query it returns the 10 documents that user is allowed to read, ranked by BM25. A document with no allow-grant is invisible. A grant, revoke, or group-membership change committed in Postgres is reflected in search within 30 seconds. Under normal load, 99% of queries finish within 100ms of server time even when one index replica is slow.

## Read and build in this order

Do not open the frontend guide until `v0.4-slo` is tagged on `main`.

1. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the design you are implementing. Read it once before writing code, and again at the start of each phase.
2. [docs/implementation/00-before-you-write-code.md](docs/implementation/00-before-you-write-code.md) — tools, schema, and the rule that the build starts as one Spring Boot app.
3. [docs/implementation/01-phase-1-naive-oracle.md](docs/implementation/01-phase-1-naive-oracle.md) — the brute-force API. Search, upload, and grants work before any index exists.
4. [docs/implementation/02-phase-2-bitmap-prefilter.md](docs/implementation/02-phase-2-bitmap-prefilter.md)
5. [docs/implementation/03-phase-3-cdc-freshness.md](docs/implementation/03-phase-3-cdc-freshness.md)
6. [docs/implementation/04-phase-4-hedged-slo.md](docs/implementation/04-phase-4-hedged-slo.md)
7. Only after tag `v0.4-slo`: [docs/implementation/05-phase-5-frontend.md](docs/implementation/05-phase-5-frontend.md)

Tick items in [docs/CHECKLIST.md](docs/CHECKLIST.md) as you finish them.

## Rules you do not relax

- One commit does one thing. The `Verify:` block of that commit is a command you run against the process (`psql`, `curl`, a benchmark `main`). This repo has no test suite: no `src/test`, no JUnit, no jqwik, no Testcontainers, no Jest or Playwright.
- `main` is always demoable. Phase work happens on the feature branch named in that phase's guide. Merge with `git merge --ff-only`, then tag.
- The build starts as one Spring Boot application. A second process, a new library module, or a dependency is added in the commit that first needs it.
- The phase-1 visibility SQL stays in the repo forever. Later phases call it to confirm candidate hits before returning them.
- Schema, SQL, and Java follow [docs/implementation/00-before-you-write-code.md](docs/implementation/00-before-you-write-code.md): parameterized SQL, named constraints, `timestamptz`, a non-owner application role, and row-level security on tenant tables.
- Frontend code, `package.json`, and Node tooling are created in phase 5. Phase 1 is Java 21, Spring Boot, and Postgres. Kafka and Debezium arrive in phase 3. Spring Boot serves the HTTP API. Visibility SQL, index snapshots, hedging, and the Kafka offset commit stay in classes the controllers call.
- Demo identity headers exist so the switcher can act as a user. The class that reads them is the only place those headers are trusted, and only when `AUTH_MODE=demo`.
