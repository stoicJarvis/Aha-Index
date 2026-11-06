# Phase 1 — Naive oracle

Branch: `feature/phase-1-naive-oracle`

Tag when merged: `v0.1-naive`

You build a correct search path that will miss the 100ms SLO on purpose. It is the authority for "who can see this document" in every later phase.

This phase has no test sources. Each commit ends with `psql` or `curl`. The schema is the script in [00-before-you-write-code.md](00-before-you-write-code.md) section 0.7, copied into the migration file. Java follows section 0.6.

```bash
git checkout main
git checkout -b feature/phase-1-naive-oracle
```

There is no `web/` directory in this phase. There is no Kafka.

## Commit 1 — `chore: scaffold the Java 21 Gradle build`

Create the files below. Do not add search logic yet.

`.gitignore`:

```gitignore
.gradle/
build/
.idea/
*.iml
.classpath
.project
.settings/
out/
web/node_modules/
web/.next/
.DS_Store
*.log
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "aha-index"
include("analysis", "api", "oracle")
```

`build.gradle.kts`:

```kotlin
plugins {
    java
    id("com.diffplug.spotless") version "6.25.0" apply false
}

allprojects {
    group = "io.aha.index"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
        options.encoding = "UTF-8"
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/main/java/**/*.java")
            googleJavaFormat("1.22.0")
        }
    }
}
```

`gradle/libs.versions.toml`: copy the catalog from [00-before-you-write-code.md](00-before-you-write-code.md) section 0.5.

`analysis/build.gradle.kts`:

```kotlin
plugins { java }
```

`api/build.gradle.kts`:

```kotlin
plugins { java }

dependencies {
    implementation(libs.jackson.databind)
}
```

`oracle/build.gradle.kts`:

```kotlin
plugins { java }

dependencies {
    implementation(project(":analysis"))
    implementation(project(":api"))
    implementation(libs.postgres)
    implementation(libs.slf4j.api)
    implementation(libs.logback)
    implementation(libs.jackson.databind)
}
```

If `libs.jackson.databind` does not match the alias Gradle generated from the catalog, run `./gradlew :api:dependencies` and fix the alias. Gradle rewrites `jackson-databind` in the catalog to the accessor `jackson.databind`.

Generate the wrapper from any installed Gradle, or use the official wrapper jar. The required wrapper version is 8.11.1:

```bash
gradle wrapper --gradle-version 8.11.1
```

If you have no Gradle binary, download the 8.11.1 distribution zip from `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`, unpack it, and run its `bin/gradle wrapper --gradle-version 8.11.1` inside this repo. Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.

```bash
./gradlew assemble
java -version
```

`java -version` prints 21, and `assemble` exits 0. The tree has no `src/test` directory.

Commit message:

```text
chore: scaffold the Java 21 Gradle build

Why:
Later phases split the oracle, the index, and the coordinator.
The module boundaries exist before the search code so the oracle
cannot grow a dependency on the index.

Verify:
./gradlew assemble
java -version
```

## Commit 2 — `chore: run Postgres 16 and prove JDBC connects`

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
    command:
      - postgres
      - -c
      - wal_level=logical
      - -c
      - max_wal_senders=4
      - -c
      - max_replication_slots=4
    volumes:
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
```

`deploy/postgres/init/01-role.sql` is the role script from [00-before-you-write-code.md](00-before-you-write-code.md) section 0.7. `CREATE ROLE` has to happen here, outside the migration transaction. The mount path is relative to `deploy/`, which is the compose file's directory.

`wal_level=logical` is unused until phase 3. Setting it now means you will not have to destroy the volume later to change it. Named volume is optional. Add one if you want data to survive `docker compose down`:

```yaml
    volumes:
      - aha_pg:/var/lib/postgresql/data
volumes:
  aha_pg:
```

`io.aha.index.oracle.DbConfig` is a record: `String host, int port, String database, String user, String password`. `DbConfig.adminFromEnv()` reads `PGHOST` (default `127.0.0.1`), `PGPORT` (default `54329`), `PGDATABASE` (default `aha_index`), `PGUSER` (default `aha`), `PGPASSWORD` (default `aha`). `DbConfig.appFromEnv()` uses the same host, port, and database with `PGAPPUSER` (default `aha_app`) and `PGAPPPASSWORD` (default `aha_app`). Migrations and the seeder use the admin config. Request paths use the app config.

`io.aha.index.oracle.DataSourceFactory` returns a `java.sql.DriverManager` connection. A pool arrives in phase 4. One method: `Connection open(DbConfig config)`.

`io.aha.index.oracle.DbPing` has a `main` that opens the admin connection and prints the result of `SELECT 1`.

Apply the application plugin on the oracle module in this commit, with `mainClass` set to `io.aha.index.oracle.DbPing`. Commit 8 changes `mainClass` to `OracleMain`. `DbPing` stays in the tree.

```bash
docker compose -f deploy/docker-compose.yml up -d postgres
docker compose -f deploy/docker-compose.yml ps
./gradlew :oracle:run
```

Commit:

```text
chore: run Postgres 16 and prove JDBC connects

Why:
The oracle and the later candidate check both read Postgres.
Logical WAL is on from the first boot so phase 3 does not
recreate the data directory.

Verify:
docker compose -f deploy/docker-compose.yml ps
./gradlew :oracle:run
# prints 1
```

## Commit 3 — `feat: add the allow-grant schema and a migration runner`

`oracle/src/main/resources/db/migration/V001__init.sql` is the script in [00-before-you-write-code.md](00-before-you-write-code.md) section 0.7, byte for byte. That script creates schema `search`, the version table with a SHA-256 checksum, named constraints, `timestamptz` columns, the principal trigger, row-level security, and the `aha_app` role.

`REPLICA IDENTITY` stays `DEFAULT`. A delete event carries the primary key, which is what phase 3's applier needs.

`io.aha.index.oracle.Migrator`, using the admin connection:

1. Accept a `Connection` with `autoCommit` false.
2. Apply the explicit list `String[] VERSIONS = { "V001__init.sql" }`. Locate each file on the classpath under `db/migration/`.
3. `SELECT to_regclass('search.schema_migrations')`. When that is null, run `V001` with `Statement.execute` on the whole script (no `?` placeholders, so JDBC sends it as one simple query). Then insert the version row.
4. The version row stores `version`, a short `description`, the hex SHA-256 `checksum` of the script bytes, and `execution_time_ms`.
5. When the table exists and the version is present, compare the stored checksum to the file bytes. A mismatch throws. A match skips the file.
6. Commit. A second `migrate()` is a no-op.

Confirm with the admin role, which bypasses row-level security:

```bash
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "\dt search.*"
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "SELECT version, checksum FROM search.schema_migrations"
```

`search.documents` and `search.acl_entries` are present. Running migrate again leaves `count(*)` at 1. Inserting a document before its tenant fails the foreign key. Inserting an ACL row for a missing user fails the principal trigger.

Commit:

```text
feat: add the allow-grant schema and a migration runner

Why:
Visibility is the existence of an allow row. A missing row is a
denial, so the schema has no allow-flag to get wrong. The runner
records the script checksum so a later edit of V001 cannot pretend
it was the script this database already applied.

Verify:
psql "postgresql://aha:aha@127.0.0.1:54329/aha_index" -c "\dt search.*"
```

## Commit 4 — `feat: add the shared tokenizer`

Package `io.aha.index.analysis.Tokenizer`. Implement the algorithm in [00-before-you-write-code.md](00-before-you-write-code.md) section 0.8. The class is final. Methods are pure. No logging.

These inputs are the contract. Commit 7's hit list is wrong when any row here is wrong.

| Input | Tokens |
| --- | --- |
| `null` | empty |
| `""` | empty |
| `"Quarterly Revenue!"` | `quarterly`, `revenue` |
| `"ADA's Q4"` | `ada`, `s`, `q4` |
| `"café"` | `café` |
| `"  quarterly   revenue  "` | `quarterly`, `revenue` |

`termFreq("quarterly quarterly revenue")` returns quarterly=2, revenue=1. Calling `tokens` on the space-joined tokens of a letter-and-space string returns the same list.

Commit:

```text
feat: add the shared tokenizer

Why:
The oracle and the index have to agree on what a token is.
One class, no stemming, so a ranking diff is a ranking bug
rather than a tokenizer drift.

Verify:
./gradlew :analysis:compileJava spotlessCheck
```

## Commit 5 — `feat: seed the canary corpus`

`io.aha.index.oracle.CorpusSeeder.seed(Connection)` runs on the admin connection. It deletes, in foreign-key order, `acl_entries`, `group_memberships`, `documents`, `groups`, `users`, `tenants`, then inserts the rows below. It runs in one transaction. Calling it twice yields the same rows. Set `app.maintenance` is unnecessary on the admin connection because the owner bypasses row-level security.

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

Documents and grants. Body text is exact. The `curl` checks in later commits match these strings.

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

Seed a second time and repeat the counts. They stay the same.

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

## Commit 6 — `feat: list the documents a user can read`

`io.aha.index.oracle.VisibilitySql`:

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

`a.tenant_id = d.tenant_id` already scopes the ACL row, so the fragment binds three values: user id, membership tenant, membership user.

`DocumentStore.visibleDocuments(Connection, tenant, user)` runs:

```sql
SELECT d.doc_id, d.title, d.body
FROM documents d
WHERE d.tenant_id = ?
  AND <PREDICATE>
ORDER BY d.doc_id ASC
```

Bind: tenant, user, tenant, user. Four binds because the statement has the tenant `?` plus three in the predicate.

`DocumentStore.retainVisible(Connection, tenant, user, List<String> docIds)` runs the same predicate plus `AND d.doc_id = ANY (?)`. Return the subset, preserving the input order (filter the input list by the set the query returned). Empty input returns empty and does not hit the database.

`DocumentStore` write methods, each its own transaction:

- `insertDocument(tenant, docId, title, body)`. Tenant must exist. `docId` matches `^[a-z0-9][a-z0-9-]{0,63}$`, the same check as `documents_doc_id_format`. Title and body are non-blank after trim. Body length is at most 100_000 characters. A duplicate primary key throws the domain exception `AlreadyExists`. The connection is the app role with `app.tenant_id` set, so row-level security and the table checks both apply.
- `grant(tenant, docId, principalType, principalId)`. Document must exist. USER must exist in `users` for the tenant. GROUP must exist in `groups`. Insert. On unique violation, return normally (idempotent grant).
- `revoke(...)`. Delete the ACL row. Zero rows deleted is success.
- `addMembership` / `removeMembership`. Same idempotent pattern. Both user and group must exist in the tenant.

`insertDocument` does not write an ACL row. A new document is invisible until a grant.

Open an app-role session, `SELECT set_config('app.tenant_id', 'acme', true)`, and confirm:

| Call | Result |
| --- | --- |
| visible doc ids for acme/ada | `d-ada-1`, `d-eng-1`, `d-shared` only |
| visible for acme/ivan | contains `d-canary`, does not contain `d-eng-1` |
| visible for acme/cara | `d-legal-1`, `d-shared` |
| visible for acme/neil | the 40 fillers only |
| visible for globex/dana | `d-globex-1` |
| visible for acme/dana | empty |
| visible for globex/ada | empty |
| retainVisible(ada, [d-canary, d-eng-1, d-orphan]) | `[d-eng-1]` |
| insert `d-new` with no grant, visible for ada | does not contain `d-new` |
| grant USER ada on `d-new`, visible for ada | contains `d-new` |
| revoke that grant | does not contain `d-new` |
| remove ada from eng | ada loses `d-eng-1` and `d-shared`, keeps `d-ada-1` |

The membership case is the one people get wrong. `d-shared` is granted to eng and to legal. Ada is only in eng. Removing her from eng removes both eng documents.

Commit:

```text
feat: list the documents a user can read

Why:
The visibility predicate is the product invariant. Search and the
later candidate check both call this one SQL fragment.
A document insert does not grant the author, so a forgotten grant
fails closed.

Verify:
psql as aha_app with app.tenant_id=acme, Ada's visible ids are d-ada-1, d-eng-1, d-shared
```

## Commit 7 — `feat: rank the visible set in memory and return ten hits`

`io.aha.index.oracle.NaiveSearch`:

```java
public List<Hit> search(Connection conn, String tenant, String user, String query)
```

1. `List<String> queryTokens = tokenizer.tokens(query)`.
2. If `queryTokens` is empty, throw `NoTokensException`. The HTTP layer maps it to 400. The library does not know about HTTP.
3. `List<DocumentRow> rows = documentStore.visibleDocuments(...)`.
4. For each row, `titleFreq = tokenizer.termFreq(title)`, `bodyFreq = tokenizer.termFreq(body)`.
5. The row matches when every query token has `(titleFreq.getOrDefault(t,0) + bodyFreq.getOrDefault(t,0)) > 0`.
6. Score is `sum over query tokens of (3 * titleCount + 1 * bodyCount)`.
7. Sort by score descending, then `docId` ascending.
8. Return at most 10.
9. Snippet: concatenate `title + "\n" + body`, lowercase with the same NFKC + ROOT lowercase the tokenizer uses, find the first query token, take 60 characters before and 100 after, prefix `...` when the start was trimmed. Run this only for returned hits.

There is no `LIMIT` in the SQL of this class. The database returns the whole visible set. The slowness is the point.

Query `quarterly` unless noted. These are the results commit 8's `curl` must show.

Phase-1 scores for token `quarterly` (title count × 3 + body count × 1):

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

Ada, query `quarterly`, exact order: `d-ada-1`, `d-eng-1`, `d-shared`.

Tie on score 4 is broken by doc id ascending, and `d-ada-1` < `d-eng-1`.

Ivan's first hit is `d-canary`. Ada's hits do not contain `d-canary`, `d-orphan`, `d-legal-1`, `d-ben-1`, any `f-*`, or `d-globex-1`.

Cara, query `quarterly revenue` (both tokens required): `d-legal-1` then `d-shared`. `d-ben-1` contains `quarterly` and not `revenue`, so it is out even for Ben on this two-token query.

Neil gets 10 hits, all with ids starting `f-`, which are the lexicographically first 10 fillers because they tie on score. `f-01` through `f-10`.

Query `...` throws `NoTokensException`.

Query `quarterly` as user `nobody` in tenant `acme` returns an empty list, not an exception.

Commit:

```text
feat: rank the visible set in memory and return ten hits

Why:
The SQL path returns every document the user may read, and only
those. Ranking and LIMIT happen after that filter. Putting LIMIT
in an unfiltered keyword query hides Ada's documents behind the
contractor fillers.

Verify:
./gradlew :oracle:compileJava
```

## Commit 8 — `feat: serve the oracle over HTTP`

`io.aha.index.api` records, Jackson-friendly (public accessors, no logic):

- `SearchHit(String docId, String title, String snippet, double score)`
- `SearchResponse(List<SearchHit> hits, String mode, String replica, boolean hedged, String aclEpoch, long watermarkLagMs)`
- `ErrorBody(String error, String code)`
- `DocumentWrite(String docId, String title, String body)`
- `AclWrite(String docId, String principalType, String principalId, String op)` where `op` is `GRANT` or `REVOKE`
- `MembershipWrite(String groupId, String userId, String op)` where `op` is `ADD` or `REMOVE`
- `DirectoryUser(String userId, String displayName)`
- `DirectoryTenant(String tenantId, String name, List<DirectoryUser> users)`

`ObjectMapper` is one shared instance, `FAIL_ON_UNKNOWN_PROPERTIES` enabled.

`io.aha.index.oracle.OracleServer`:

- `HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)`.
- Port from `PORT`, default `18480`.
- Executor: `Executors.newVirtualThreadPerTaskExecutor()`.
- Each request opens an app-role JDBC connection, sets `app.tenant_id` to the resolved tenant, and closes the connection in `finally`. Phase 1 does not pool. Directory and migrate use the admin connection.
- `GET /v1/health` returns 200 `{"status":"ok","mode":"naive"}` with no principal required.
- `GET /v1/directory` lists tenants and users. Demo mode only. No principal required. This exists so phase 5 can fill the switcher. It is not a secret in demo mode. In `AUTH_MODE=token` return 401.
- `GET /v1/search?q=` principal required. `mode` is `naive`, `replica` is `oracle`, `hedged` false, `aclEpoch` `"0"`, `watermarkLagMs` 0. This process reads Postgres directly, so it is not behind a log.
- `GET /v1/documents/{docId}` principal required. 200 with title and body when visible, 404 otherwise.
- `POST /v1/documents` principal required. The principal's tenant is the document's tenant. Body is `DocumentWrite`. 201 on insert, 409 `ALREADY_EXISTS` on duplicate.
- `POST /v1/acl` body `AclWrite`. 204 on success. 404 unknown document. 422 unknown principal. Idempotent grant and revoke return 204.
- `POST /v1/memberships` body `MembershipWrite`. 204, 422 if the user or group is not in the tenant.
- Unknown path: 404 `NOT_FOUND`.
- Read the body with a cap of 1 MiB.

Log one line per request at info: method, path, status, millis, request id, tenant, user, and `qLength`. The query text is debug. Document bodies stay out of the log.

`oracle/src/main/resources/logback.xml`: console appender, pattern `%d{ISO8601} %-5level [%thread] %logger - %msg%n`, root level info.

`OracleMain.main` loads admin and app configs from the environment, migrates and optionally seeds on the admin connection when `SEED=true`, then starts the server and blocks. `AuthMode` is a constructor argument. `main` reads `AUTH_MODE`.

With the server up against Compose, these curls are the check:

- Ada search `quarterly` returns `d-ada-1`, `d-eng-1`, `d-shared`, in that order, and the body does not contain `d-canary`.
- Missing headers return 400 `MISSING_PRINCIPAL`.
- Restart with `AUTH_MODE=token`. Search with the demo headers returns 401.
- POST a document `d-temp`, search as ada and see it absent, POST grant USER ada, search again and see it present, POST revoke, and see it absent.
- GET that document as ben returns 404 while it is granted to ada. GET as ada returns 200 while granted.
- Query `%%%` returns 400 `NO_TOKENS`.
- Ivan's first hit for `quarterly` is `d-canary`.

Add a `Makefile` or `deploy/run-oracle.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
export PGHOST="${PGHOST:-127.0.0.1}"
export PGPORT="${PGPORT:-54329}"
export SEED="${SEED:-true}"
./gradlew :oracle:installDist
./oracle/build/install/oracle/bin/oracle
```

The application plugin is already applied. Point `mainClass` at `io.aha.index.oracle.OracleMain`.

Commit:

```text
feat: serve the oracle over HTTP

Why:
The HTTP shape (hits, mode, replica, hedged, aclEpoch, watermark)
is fixed here so later processes can replace the implementation
without breaking clients. Writes land in Postgres and become
visible on the next search because this process has no cache.

Verify:
SEED=true ./deploy/run-oracle.sh
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ada' \
  'http://127.0.0.1:18480/v1/search?q=quarterly'
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ivan' \
  'http://127.0.0.1:18480/v1/search?q=quarterly'
```

Ada's body contains `d-ada-1`, `d-eng-1`, `d-shared`, and does not contain `d-canary`. Ivan's first hit is `d-canary`.

The bug this phase refuses to ship is an unfiltered limit. This query hides Ada behind Neil's fillers:

```sql
SELECT doc_id FROM search.documents
WHERE tenant_id = 'acme' AND body ILIKE '%quarterly%'
ORDER BY doc_id ASC
LIMIT 10;
```

Run it as `aha` and compare the ids with Ada's curl. They differ. `NaiveSearch` keeps the filter in the SQL and limits after ranking.

## Commit 9 — `docs: record the phase-1 latency baseline`

`io.aha.index.oracle.NaiveBenchmark` is a `main` under `src/main`. It:

1. Expects compose Postgres up, migrates, seeds.
2. Inserts extra filler documents until the tenant `acme` has at least 5_000 documents containing the word `quarterly`, granted to GROUP contractors. Page the inserts in batches of 200 per transaction.
3. Warms 20 searches as ada for `quarterly`.
4. Times 200 searches. Record min, p50, p99, max in milliseconds. p99 index is `ceil(0.99 * 200) - 1` on the sorted samples (index 197 of 200).
5. Prints one line: `phase1 ada quarterly n=200 p50=.. p99=.. max=.. docs=5000+`.

Write the numbers into `docs/benchmarks/phase1.md` by hand from that output, with the date, the machine (cpu and RAM), and the sentence: "This p99 is above the 100ms SLO. Phase 2 is the response." Leave the SQL as it is. An index on `body` that makes this fast hides the lesson.

Also paste the result of:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT d.doc_id
FROM search.documents d
WHERE d.tenant_id = 'acme'
  AND EXISTS ( ... the visibility predicate for ada ...);
```

The plan in the markdown shows the sequential work.

Commit:

```text
docs: record the phase-1 latency baseline

Why:
The SLO is 100ms at p99. This commit writes down that the correct
oracle misses it, and by how much, before any index exists.

Verify:
java -cp ... io.aha.index.oracle.NaiveBenchmark
docs/benchmarks/phase1.md filled in
```

## Merge

```bash
./gradlew assemble spotlessCheck
git status
git checkout main
git merge --ff-only feature/phase-1-naive-oracle
git tag -a v0.1-naive -m "Naive SQL oracle, default-deny search, baseline latency"
```

Exit bar:

- `./gradlew assemble` exits 0 and the tree has no `src/test`.
- Curl as Ada does not return the canary. Curl as Ivan returns it first for `quarterly`.
- `docs/benchmarks/phase1.md` exists and states the SLO miss.
- `git tag --list 'v0.1-naive'` prints the tag.
- The tree has no `web/` directory and no `package.json`.
