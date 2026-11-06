# Phase 1 — One Spring Boot app, brute-force search

Branch: `feature/phase-1-naive-oracle`

Tag when merged: `v0.1-naive`

This phase ends with one process on port 18480. You can upload a document, grant or revoke it, and search. Search loads every document the caller may read and ranks that set in memory. It is correct, and it is slow on purpose. There is no index, no Kafka, no second Gradle module, and no `web/` directory.

The schema is the script in [00-before-you-write-code.md](00-before-you-write-code.md) section 0.7. Java follows section 0.6. This phase has no test sources. Each commit ends with `psql` or `curl`.

Every commit states what it adds and why it waits until this commit.

```bash
git checkout main
git checkout -b feature/phase-1-naive-oracle
```

## Commit 1 — `chore: scaffold one Spring Boot application`

**What.** One Gradle project. One class, `io.aha.index.AhaIndexApplication`. `GET /v1/health` returns `{"status":"ok","mode":"naive"}`. Nothing else is on the classpath beyond the web starter, and `settings.gradle.kts` has no `include`.

**Why.** The first thing a caller can do is hit an endpoint and see the process is the search service. A tokenizer, a schema, and an index have no caller yet, so they are not created. Health is the check that Boot, the port, and the wrapper are the ones this repo will keep.

`settings.gradle.kts`:

```kotlin
rootProject.name = "aha-index"
```

`build.gradle.kts` applies Java 21, Spring Boot 3.5.16, the dependency-management plugin 1.1.7, and Spotless. The only dependency is `spring-boot-starter-web`, with no version of its own. The Boot plugin fills that version from its BOM. Jackson, Logback, and the embedded server come from that starter. There is no version catalog yet. Section 0.5 is the pin list.

`src/main/resources/application.yml`:

```yaml
server:
  address: 127.0.0.1
  port: ${PORT:18480}
spring:
  threads:
    virtual:
      enabled: true
```

`AhaIndexApplication` is `@SpringBootApplication`. `HealthController` sits in the same package, `io.aha.index`. One endpoint does not get its own directory. The name `web/` is reserved for the phase-5 console, which does not exist yet.

```bash
./gradlew bootJar
curl -s http://127.0.0.1:18480/v1/health
```

The second command needs `./gradlew bootRun` in another terminal. The body is `{"status":"ok","mode":"naive"}`.

Commit:

```text
chore: scaffold one Spring Boot application

Why:
The service has to answer on 18480 before it answers search.
Libraries and modules wait until a commit calls them.

Verify:
./gradlew bootJar
curl -s http://127.0.0.1:18480/v1/health
```

## Commit 2 — `chore: run Postgres 16 and prove JDBC connects`

**What.** Postgres 16 in Compose, the `aha_app` role, and two Hikari pools. A ping runner logs `SELECT 1` when `aha.ping=true`.

**Why.** Search in this phase is a SQL query. The database is the next thing the app needs, and it is still a normal Postgres. Logical WAL, Kafka, and Debezium are how phase 3 hears about commits. Turning them on now would configure a log nobody reads. Phase 3 recreates the local volume when it enables `wal_level=logical`.

`deploy/docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: aha
      POSTGRES_PASSWORD: aha
      POSTGRES_DB: aha_index
    ports:
      - "54329:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U aha -d aha_index"]
      interval: 2s
      timeout: 2s
      retries: 30
    volumes:
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
```

`deploy/postgres/init/01-role.sql` is the role script from section 0.7. `CREATE ROLE` cannot run inside the migration transaction, so it lives in the container's init directory. Init scripts run only on an empty data directory.

Add `spring-boot-starter-jdbc` and `runtimeOnly("org.postgresql:postgresql")`. Exclude Boot's automatic pool so it does not open a third connection as the wrong role:

```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

`io.aha.index.db.DataSources` is a `@Configuration` with two `HikariDataSource` beans, `adminDataSource` and `appDataSource`. Both read `PGHOST` (default `127.0.0.1`), `PGPORT` (default `54329`), and `PGDATABASE` (default `aha_index`). The admin bean uses `PGUSER` / `PGPASSWORD` (default `aha` / `aha`). The app bean uses `PGAPPUSER` / `PGAPPPASSWORD` (default `aha_app` / `aha_app`). Migrations and the seeder borrow the admin pool. Request paths borrow the app pool, set `app.tenant_id`, and commit or roll back that connection themselves.

`io.aha.index.db.DbPing` is an `ApplicationRunner` that borrows an admin connection, runs `SELECT 1`, and logs the value. It runs only when `aha.ping=true`.

```bash
docker compose -f deploy/docker-compose.yml up -d postgres
./gradlew bootRun --args='--aha.ping=true'
```

The log contains `1`. Stop the process with Ctrl-C.

Commit:

```text
chore: run Postgres 16 and prove JDBC connects

Why:
Brute-force search is a query against Postgres.
The log pipeline is phase 3's problem, so this database
is a normal Postgres 16.

Verify:
docker compose -f deploy/docker-compose.yml ps
./gradlew bootRun --args='--aha.ping=true'
```

## Commit 3 — `feat: add the allow-grant schema and a migration runner`

**What.** The section 0.7 script becomes `src/main/resources/db/V001__init.sql`. A runner applies it on the admin pool, stores the SHA-256 of the file in `schema_migrations`, and refuses to start when that checksum no longer matches a script that was already applied.

**Why.** The product rule is default-deny: no grant row means nobody can read the document, including the author. The tables are what the next commits insert into and what search will scan. Row-level security and the principal trigger are part of that rule, so they arrive with the tables rather than as a later hardening pass.

The runner is an `ApplicationRunner` on the admin pool, ordered before the web server accepts traffic. It creates schema `search` if needed, applies `V001` inside one transaction when the checksum is new, and no-ops when the checksum matches. A changed file with the same version name is a startup failure.

```bash
docker compose -f deploy/docker-compose.yml up -d postgres
./gradlew bootRun
```

In another terminal:

```bash
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "\dt search.*"
```

The tables from section 0.7 are listed. Start the app a second time and the log says the migration is already applied.

Commit:

```text
feat: add the allow-grant schema and a migration runner

Why:
Search and the grant API both need the same tables.
No grant row means the document is invisible, including to its author.

Verify:
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "\dt search.*"
```

## Commit 4 — `feat: seed the canary corpus`

**What.** Two tenants, overlapping groups, and a fixed set of documents. `d-canary` matches the same words as ordinary documents and is granted only to Ivan. `d-orphan` has no grant. Forty filler documents are granted to contractors. Seeding twice leaves the same rows.

**Why.** The next two commits need rows whose answers you already know. The canary is the leak detector: any missing tenant or ACL predicate returns it to Ada. The fillers are the limit detector: an unfiltered `LIMIT 10` hides Ada's documents behind Neil's corpus. A second tenant makes a missing `tenant_id` predicate fail in a way a single-tenant seed cannot.

`io.aha.index.search.CorpusSeeder.seed(Connection)` runs on the admin connection when `SEED=true`. It deletes, in foreign-key order, `acl_entries`, `group_memberships`, `documents`, `groups`, `users`, `tenants`, then inserts the rows below. It runs in one transaction. Calling it twice yields the same rows. The owner bypasses row-level security, so the seeder does not set `app.tenant_id`.

Tenants: `(acme, Acme)`, `(globex, Globex)`.

Users:

| tenant | user | display |
| --- | --- | --- |
| acme | ada | Ada |
| acme | ben | Ben |
| acme | cara | Cara |
| acme | neil | Neil |
| acme | ivan | Ivan |
| globex | dana | Dana |

Groups: `acme/eng` Engineering, `acme/legal` Legal, `acme/contractors` Contractors, `globex/staff` Staff.

Memberships: eng={ada, ben}, legal={cara}, contractors={neil}, staff={dana}. Ivan has no group.

Documents and grants. Body text is exact. Later curls match these strings.

| doc_id | tenant | title | body | grants |
| --- | --- | --- | --- | --- |
| d-eng-1 | acme | Quarterly revenue plan | The quarterly revenue plan covers the pipeline. | GROUP eng |
| d-legal-1 | acme | Quarterly revenue hold | Legal hold on quarterly revenue. | GROUP legal |
| d-ada-1 | acme | Ada quarterly notes | Ada private quarterly revenue notebook. | USER ada |
| d-canary | acme | Quarterly revenue canary | quarterly revenue quarterly revenue quarterly revenue canary secret | USER ivan |
| d-orphan | acme | Quarterly revenue orphan | quarterly revenue with no grant | none |
| d-ben-1 | acme | Ben oncall notes | The oncall rotation mentions quarterly once. | USER ben |
| d-shared | acme | Eng and legal bridge | quarterly revenue bridge document | GROUP eng, GROUP legal |
| d-globex-1 | globex | Quarterly revenue plan | Globex quarterly revenue plan | GROUP staff |

Then 40 fillers, `i` from 1 to 40:

- doc_id `f-01` … `f-40` (two digits)
- tenant acme
- title `Filler 01` … `Filler 40`
- body `quarterly filler document 01` matching the number
- grant GROUP contractors

After seeding, as `aha`:

```sql
SELECT count(*) FROM search.documents WHERE tenant_id = 'acme';          -- 47
SELECT count(*) FROM search.acl_entries WHERE doc_id = 'd-orphan';       -- 0
SELECT principal_type, principal_id FROM search.acl_entries WHERE doc_id = 'd-canary'; -- USER ivan
SELECT count(*) FROM search.documents WHERE tenant_id = 'globex';        -- 1
```

Seed a second time and the counts stay the same.

Commit:

```text
feat: seed the canary corpus

Why:
The canary matches the same words as ordinary documents and is
granted only to Ivan. Any missing tenant or ACL predicate returns
it to Ada. The filler documents exist so an unfiltered LIMIT 10
hides Ada's real hits behind Neil's corpus.

Verify:
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "SELECT count(*) FROM search.documents WHERE tenant_id = 'acme'"
```

## Commit 5 — `feat: upload a document and grant or revoke it`

**What.** Write routes on the same application: create a document, grant or revoke a principal, and add or remove a group membership. Creating a document does not grant the author. Reads of one document return 404 when the caller has no grant.

**Why.** The problem is not search alone. A caller has to change who can read a document and see that change on the next request. These routes are the write side. Search is the next commit, and it will call the same visibility predicate these writes fill in.

`io.aha.index.search.VisibilitySql` is the predicate every read pastes. Do not copy it into a second string.

```java
public final class VisibilitySql {
    private VisibilitySql() {}

    /**
     * Bind order after the caller has already bound tenant and any doc-id array:
     * 1 user id (USER principal)
     * 2 tenant id (membership lookup)
     * 3 user id again (membership lookup)
     */
    public static final String PREDICATE = """
        EXISTS (
            SELECT 1
            FROM acl_entries a
            WHERE a.tenant_id = d.tenant_id
              AND a.doc_id = d.doc_id
              AND (
                    (a.principal_type = 'USER' AND a.principal_id = ?)
                 OR (a.principal_type = 'GROUP' AND a.principal_id IN (
                        SELECT m.group_id
                        FROM group_memberships m
                        WHERE m.tenant_id = ?
                          AND m.user_id = ?
                    ))
              )
        )
        """;
}
```

`DocumentStore` methods, each its own transaction on the app pool with `set_config('app.tenant_id', ?, true)`:

- `insertDocument(tenant, docId, title, body)`. Tenant must exist. `docId` matches `^[a-z0-9][a-z0-9-]{0,63}$`. Title and body are non-blank after trim. Body length is at most 100_000 characters. A duplicate primary key throws `AlreadyExists`. No ACL row is written.
- `grant` / `revoke`. The document must exist. A USER must exist in `users` for the tenant. A GROUP must exist in `groups`. A unique violation on grant is success. Zero rows deleted on revoke is success.
- `addMembership` / `removeMembership`. Same idempotent pattern. Both user and group must exist in the tenant.
- `findVisible(tenant, user, docId)` returns the row only when the predicate matches. Otherwise the HTTP layer returns 404.

Request records live in `io.aha.index.http` next to the controllers. The package is `http`, so it is not the phase-5 `web/` directory. Phase 4's coordinator forwards the response body and does not need these classes extracted into a library.

- `DocumentWrite(String docId, String title, String body)`
- `AclWrite(String docId, String principalType, String principalId, String op)` where `op` is `GRANT` or `REVOKE`
- `MembershipWrite(String groupId, String userId, String op)` where `op` is `ADD` or `REMOVE`
- `ErrorBody(String error, String code)`

`DemoPrincipalFilter` is a `OncePerRequestFilter` and the only class that reads `X-Tenant-Id` and `X-User-Id`. `AUTH_MODE=token` ignores those headers and returns 401 for any route that needs a principal. `ApiExceptionHandler` writes the error JSON from section 0.11. A filter rejects a body larger than 1 MiB with 413 `BODY_TOO_LARGE`.

Routes, principal required:

- `POST /v1/documents` uses the principal's tenant. 201 on insert, 409 `ALREADY_EXISTS` on duplicate.
- `POST /v1/acl` returns 204. 404 unknown document. 422 unknown principal. Idempotent grant and revoke return 204.
- `POST /v1/memberships` returns 204, or 422 when the user or group is not in the tenant.
- `GET /v1/documents/{docId}` returns 200 with title and body when visible, 404 otherwise.

With `SEED=true` and the server up:

- POST `d-temp` as Ada. GET it as Ada returns 404. POST a USER grant for Ada. GET as Ada returns 200. GET as Ben returns 404. POST the revoke. GET as Ada returns 404 again.
- Remove Ada from `eng`. She can still GET `d-ada-1`. She can no longer GET `d-eng-1` or `d-shared`.

Commit:

```text
feat: upload a document and grant or revoke it

Why:
The caller has to change who can read a document.
A new document stays invisible until a grant, including to its author.

Verify:
SEED=true ./gradlew bootRun
# POST d-temp, GET 404, GRANT ada, GET 200, REVOKE, GET 404
```

## Commit 6 — `feat: search the visible set with a term count`

**What.** `GET /v1/search?q=` loads every document the caller may read, keeps those that contain every query token, scores them with `3 * title count + 1 * body count`, and returns at most 10. The SQL has no `LIMIT`.

**Why.** This is the brute-force answer to the problem: filter by grant first, then rank, then cut to 10. An unfiltered keyword query with `LIMIT 10` returns Neil's fillers and Ada's documents disappear. The tokenizer is a class in this commit because search is its first caller. Phase 2 will call the same class. A separate module would be a jar with one type and no second process.

`io.aha.index.search.Tokenizer` follows section 0.8. NFKC, root-locale lowercase, runs of letters and digits. No stemming, no stop-word list. The class is final. Methods are pure.

| Input | Tokens |
| --- | --- |
| `null` | empty |
| `""` | empty |
| `"Quarterly Revenue!"` | `quarterly`, `revenue` |
| `"ADA's Q4"` | `ada`, `s`, `q4` |
| `"café"` | `café` |
| `"  quarterly   revenue  "` | `quarterly`, `revenue` |

`termFreq("quarterly quarterly revenue")` returns quarterly=2, revenue=1.

`NaiveSearch.search(Connection, tenant, user, query)`:

1. Tokenize the query. An empty token list becomes 400 `NO_TOKENS`.
2. Load the visible rows with the predicate from commit 5. Bind tenant, user, tenant, user. Order by `doc_id`.
3. A row matches when every query token occurs in the title or the body.
4. Score is the sum over query tokens of `3 * title count + 1 * body count`.
5. Sort by score descending, then `docId` ascending. Return at most 10.
6. Snippet, only for returned hits: concatenate `title + "\n" + body`, lowercase the same way the tokenizer does, find the first query token, take 60 characters before and 100 after, and prefix `...` when the start was trimmed.

Response records, same package as the other JSON types:

- `SearchHit(String docId, String title, String snippet, double score)`
- `SearchResponse(List<SearchHit> hits, String mode, String replica, boolean hedged, String aclEpoch, long watermarkLagMs)`

`mode` is `naive`, `replica` is `oracle`, `hedged` is false, `aclEpoch` is `"0"`, `watermarkLagMs` is 0. Those fields stay, so later phases can change the engine without changing the client. `GET /v1/directory` lists tenants and users for the phase-5 switcher. It needs no principal in demo mode and returns 401 when `AUTH_MODE=token`.

Phase-1 scores for token `quarterly`:

| doc | title count | body count | score | visible to |
| --- | --- | --- | --- | --- |
| d-eng-1 | 1 | 1 | 4 | ada, ben |
| d-legal-1 | 1 | 1 | 4 | cara |
| d-ada-1 | 1 | 1 | 4 | ada |
| d-canary | 1 | 3 | 6 | ivan |
| d-orphan | 1 | 1 | 4 | nobody |
| d-ben-1 | 0 | 1 | 1 | ben |
| d-shared | 0 | 1 | 1 | ada, ben, cara |
| d-globex-1 | 1 | 1 | 4 | dana |
| each filler | 0 | 1 | 1 | neil |

Ada, query `quarterly`, exact order: `d-ada-1`, `d-eng-1`, `d-shared`. The tie on score 4 breaks by doc id, and `d-ada-1` < `d-eng-1`. Ivan's first hit is `d-canary`. Ada's hits do not contain `d-canary`, `d-orphan`, `d-legal-1`, `d-ben-1`, any `f-*`, or `d-globex-1`. Neil gets `f-01` through `f-10`.

`deploy/run-oracle.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
export PGHOST="${PGHOST:-127.0.0.1}"
export PGPORT="${PGPORT:-54329}"
export SEED="${SEED:-true}"
./gradlew bootRun
```

The bug this phase refuses to ship is an unfiltered limit. This query hides Ada behind Neil's fillers:

```sql
SELECT doc_id FROM search.documents
WHERE tenant_id = 'acme' AND body ILIKE '%quarterly%'
ORDER BY doc_id ASC
LIMIT 10;
```

Run it as `aha` and compare the ids with Ada's curl. They differ.

Commit:

```text
feat: search the visible set with a term count

Why:
Filter by grant, then rank, then return 10.
An unfiltered LIMIT 10 answers Neil's fillers and drops Ada.

Verify:
SEED=true ./deploy/run-oracle.sh
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ada' \
  'http://127.0.0.1:18480/v1/search?q=quarterly'
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ivan' \
  'http://127.0.0.1:18480/v1/search?q=quarterly'
```

Ada's body contains `d-ada-1`, `d-eng-1`, `d-shared`, and does not contain `d-canary`. Ivan's first hit is `d-canary`. Missing headers return 400 `MISSING_PRINCIPAL`. Query `%%%` returns 400 `NO_TOKENS`. `AUTH_MODE=token` returns 401 for the same curl.

## Commit 7 — `docs: record the phase-1 latency baseline`

**What.** A `main` grows the tenant to at least 5,000 documents and records min, p50, p99, and max for Ada's `quarterly` search, plus the `EXPLAIN` plan, in `docs/benchmarks/phase1.md`.

**Why.** The SLO is 100ms at p99. This commit writes down that the correct scan misses it, and by how much, before any index exists. An index on `body` that makes this fast would hide the reason phase 2 exists. JMH is not added. The main is one class, `io.aha.index.search.NaiveBenchmark`.

1. Expect Compose Postgres up, migrate, and seed.
2. Insert extra filler documents until tenant `acme` has at least 5,000 documents containing `quarterly`, granted to GROUP contractors. Batches of 200 per transaction.
3. Warm 20 searches as Ada for `quarterly`.
4. Time 200 searches. p99 index is `ceil(0.99 * 200) - 1` on the sorted samples (index 197 of 200).
5. Print `phase1 ada quarterly n=200 p50=.. p99=.. max=.. docs=5000+`.

Write the numbers into `docs/benchmarks/phase1.md` by hand, with the date, the machine, and the sentence: "This p99 is above the 100ms SLO. Phase 2 is the response." Paste the `EXPLAIN (ANALYZE, BUFFERS)` of Ada's visibility query. The plan shows the sequential work.

Commit:

```text
docs: record the phase-1 latency baseline

Why:
The SLO is 100ms at p99. This commit writes down that the correct
scan misses it, before any index exists.

Verify:
java -cp ... io.aha.index.search.NaiveBenchmark
docs/benchmarks/phase1.md filled in
```

## Merge

```bash
./gradlew assemble spotlessCheck
git status
git checkout main
git merge --ff-only feature/phase-1-naive-oracle
git tag -a v0.1-naive -m "Brute-force search in one Spring Boot app"
```

Exit bar:

- `./gradlew assemble` exits 0 and the tree has no `src/test`.
- `settings.gradle.kts` has no `include`.
- Curl as Ada does not return the canary. Curl as Ivan returns it first for `quarterly`.
- A document posted with no grant is invisible to its author until `POST /v1/acl`.
- `docs/benchmarks/phase1.md` exists and states the SLO miss.
- The tree has no `web/` directory and no `package.json`.

Phase 2 adds the index inside this same application. It does not add a process.
