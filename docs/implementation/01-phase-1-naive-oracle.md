# Phase 1 — Naive oracle

Branch: `feature/phase-1-naive-oracle`

Tag when merged: `v0.1-naive`

You build a correct search path that will miss the 100ms SLO on purpose. It is the authority for "who can see this document" in every later phase.

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

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
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

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
}
```

If `libs.jackson.databind` does not match the alias Gradle generated from the catalog, run `./gradlew :api:dependencies` and fix the alias. Gradle rewrites `jackson-databind` in the catalog to the accessor `jackson.databind`.

Generate the wrapper from any installed Gradle, or use the official wrapper jar. The required wrapper version is 8.11.1:

```bash
gradle wrapper --gradle-version 8.11.1
```

If you have no Gradle binary, download the 8.11.1 distribution zip from `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`, unpack it, and run its `bin/gradle wrapper --gradle-version 8.11.1` inside this repo. Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.

Smoke test `analysis/src/test/java/io/aha/index/analysis/BuildSmokeTest.java`:

```java
package io.aha.index.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSmokeTest {
    @Test
    void jdkIs21() {
        assertEquals(21, Runtime.version().feature());
    }
}
```

`analysis/build.gradle.kts` needs the JUnit dependency for this test. Add `testImplementation(libs.junit.jupiter)` to the analysis module as well.

```bash
./gradlew test
```

Verify: the test prints `jdkIs21 PASSED` and the build exits 0.

Commit message:

```text
chore: scaffold the Java 21 Gradle build

Why:
Later phases split the oracle, the index, and the coordinator.
The module boundaries exist before the search code so the oracle
cannot grow a dependency on the index.

Verify:
./gradlew test
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
```

`wal_level=logical` is unused until phase 3. Setting it now means you will not have to destroy the volume later to change it. Named volume is optional. Add one if you want data to survive `docker compose down`:

```yaml
    volumes:
      - aha_pg:/var/lib/postgresql/data
volumes:
  aha_pg:
```

`io.aha.index.oracle.DbConfig` is a record: `String host, int port, String database, String user, String password`. `DbConfig.fromEnv()` reads `PGHOST` (default `127.0.0.1`), `PGPORT` (default `54329`), `PGDATABASE` (default `aha_index`), `PGUSER` (default `aha`), `PGPASSWORD` (default `aha`).

`io.aha.index.oracle.DataSourceFactory` returns a `java.sql.DriverManager` connection. Do not add a pool yet. One method: `Connection open(DbConfig config)`.

Test `PostgresConnectivityTest` uses Testcontainers `PostgreSQLContainer` with image `postgres:16`, starts it, opens a JDBC connection with the container's host, mapped port, username, password, and database, and runs `SELECT 1`. Assert the result is 1. Disable the test with an assumption if Docker is not available, but on your machine Docker is required, so a failure to start the container fails the test.

```bash
docker compose -f deploy/docker-compose.yml up -d postgres
docker compose -f deploy/docker-compose.yml ps
./gradlew :oracle:test --tests io.aha.index.oracle.PostgresConnectivityTest
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
./gradlew :oracle:test --tests io.aha.index.oracle.PostgresConnectivityTest
```

## Commit 3 — `feat: add the allow-grant schema and a migration runner`

`oracle/src/main/resources/db/V001__init.sql`:

```sql
CREATE TABLE schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenants (
    tenant_id TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE users (
    tenant_id TEXT NOT NULL REFERENCES tenants (tenant_id),
    user_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE groups (
    tenant_id TEXT NOT NULL REFERENCES tenants (tenant_id),
    group_id TEXT NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (tenant_id, group_id)
);

CREATE TABLE group_memberships (
    tenant_id TEXT NOT NULL,
    group_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    PRIMARY KEY (tenant_id, group_id, user_id),
    FOREIGN KEY (tenant_id, group_id) REFERENCES groups (tenant_id, group_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, user_id)
);

CREATE TABLE documents (
    tenant_id TEXT NOT NULL REFERENCES tenants (tenant_id),
    doc_id TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, doc_id)
);

CREATE TABLE acl_entries (
    tenant_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, doc_id, principal_type, principal_id),
    FOREIGN KEY (tenant_id, doc_id) REFERENCES documents (tenant_id, doc_id)
);

CREATE INDEX acl_entries_principal_idx
    ON acl_entries (tenant_id, principal_type, principal_id);

CREATE INDEX group_memberships_user_idx
    ON group_memberships (tenant_id, user_id);
```

`principal_id` cannot have a foreign key. It points at either `users` or `groups` depending on `principal_type`. The write path checks that. The database keeps the row shape honest.

`REPLICA IDENTITY` stays `DEFAULT`, which records the primary key on delete. Phase 3 needs those key columns in Debezium's `before` image. You do not need the previous body.

`io.aha.index.oracle.Migrator`:

1. Accept a `Connection`.
2. The caller has already set `autoCommit` to false.
3. Read classpath resources `db/V001__init.sql` (and later files in name order). Ship a single file in this commit. Discover files by keeping an explicit `String[] VERSIONS = { "V001__init.sql" }` rather than scanning the classpath, which is painful inside a jar.
4. Create `schema_migrations` only if you split it out. Because `V001` creates `schema_migrations` and the other tables in one script, the runner's first-run problem is: the migrations table does not exist yet. Handle it like this:
   - `SELECT to_regclass('public.schema_migrations')`.
   - If null, run the whole `V001` script, then the script itself has inserted nothing into `schema_migrations`. After the script succeeds, `INSERT INTO schema_migrations(version) VALUES ('V001__init.sql')`.
   - If the table exists, `SELECT 1 FROM schema_migrations WHERE version = ?`. Skip when present.
5. Commit the transaction.
6. A second `migrate()` on the same database is a no-op. Test that.

Split SQL on `;` only if you must. Prefer `Statement.execute(entireScript)`. PostgreSQL JDBC accepts multiple commands in one `execute` when the string has no prepared-statement placeholders. Keep the script free of `?`.

Test `MigratorTest` on Testcontainers:

- After migrate, `information_schema.tables` contains `documents` and `acl_entries`.
- Second migrate does not throw and `count(*)` of `schema_migrations` is 1.
- Insert a document only after inserting its tenant. The FK holds. Do that insert in the test, not in the migrator.

Commit:

```text
feat: add the allow-grant schema and a migration runner

Why:
Visibility is the existence of an allow row. A missing row is a
denial, so the schema has no allow-flag to get wrong. The runner
records the script version so later migrations cannot double-apply.

Verify:
./gradlew :oracle:test --tests io.aha.index.oracle.MigratorTest
```

## Commit 4 — `feat: add the shared tokenizer`

Package `io.aha.index.analysis.Tokenizer`. Implement the algorithm in [00-before-you-write-code.md](00-before-you-write-code.md) section 0.6. The class is final. Methods are pure. No logging.

`TokenizerTest` (JUnit, not jqwik yet):

| Input | Tokens |
| --- | --- |
| `null` | empty |
| `""` | empty |
| `"Quarterly Revenue!"` | `quarterly`, `revenue` |
| `"ADA's Q4"` | `ada`, `s`, `q4` |
| `"café"` | `café` |
| `"  quarterly   revenue  "` | `quarterly`, `revenue` |

`termFreq("quarterly quarterly revenue")` returns quarterly=2, revenue=1.

Add one jqwik property in `TokenizerProperties`: for any string of letters and spaces, `tokens` is idempotent on its own join. Generate with `@ForAll String` constrained to length 0..40 of `[a-zA-Z ]`. Assert `tokens(String.join(" ", tokens(s))).equals(tokens(s))` after you lowercase via the same normalizer. If this property is annoying because joining changes spacing only, assert `tokens(s).equals(tokens(String.join(" ", tokens(s))))`.

Commit:

```text
feat: add the shared tokenizer

Why:
The oracle and the index have to agree on what a token is.
One class, no stemming, so a ranking diff is a ranking bug
rather than a tokenizer drift.

Verify:
./gradlew :analysis:test
```

## Commit 5 — `feat: seed the canary corpus`

`io.aha.index.oracle.CorpusSeeder.seed(Connection)` deletes, in FK order, `acl_entries`, `group_memberships`, `documents`, `groups`, `users`, `tenants`, then inserts the rows below. It runs in one transaction. Calling it twice yields the same rows, not duplicates. Test that.

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

Documents and grants. Body text is exact. Tests match these strings.

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

`CorpusSeederTest` loads the schema, seeds, and asserts:

- `select count(*) from documents where tenant_id = 'acme'` is 47 (7 named + 40 fillers).
- `d-orphan` has zero ACL rows.
- `d-canary` has exactly one ACL row, USER ivan.
- globex has one document.
- seeding twice does not change the counts.

Commit:

```text
feat: seed the canary corpus

Why:
The canary matches the same words as ordinary documents and is
granted only to Ivan. Any missing tenant or ACL predicate returns
it to Ada. The filler documents exist so an unfiltered LIMIT 10
hides Ada's real hits behind Neil's corpus.

Verify:
./gradlew :oracle:test --tests io.aha.index.oracle.CorpusSeederTest
```

## Commit 6 — `feat: list the documents a user can read`

`io.aha.index.oracle.VisibilitySql`:

```java
public final class VisibilitySql {
    private VisibilitySql() {}

    /**
     * Bind order after the caller has already bound tenant and any doc-id array:
     * 1 tenant_id of the user
     * 2 user_id
     * 3 tenant_id of the user again
     * 4 user_id again
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

Wait: the comment and the SQL disagree if you also need tenant on the user-principal side. `a.tenant_id = d.tenant_id` already scopes the ACL row. The user id and membership tenant still need binds. Count the `?` in the fragment: three (`principal_id`, `m.tenant_id`, `m.user_id`). Fix the comment to say those three, in that order. Do not add a fourth.

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

- `insertDocument(tenant, docId, title, body)`. Tenant must exist. `docId` matches `^[a-z0-9-]{1,64}$`. Title and body are non-blank after trim. Body length ≤ 100_000. Duplicate primary key throws a domain exception `AlreadyExists`.
- `grant(tenant, docId, principalType, principalId)`. Document must exist. USER must exist in `users` for the tenant. GROUP must exist in `groups`. Insert. On unique violation, return normally (idempotent grant).
- `revoke(...)`. Delete the ACL row. Zero rows deleted is success.
- `addMembership` / `removeMembership`. Same idempotent pattern. Both user and group must exist in the tenant.

Do not grant inside `insertDocument`. A new document is invisible. That is a test.

`VisibilityStoreTest` on Testcontainers, after migrate + seed:

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
The visibility predicate is the product invariant. Search, the
later candidate check, and the tests all call this one SQL fragment.
A document insert does not grant the author, so a forgotten grant
fails closed.

Verify:
./gradlew :oracle:test --tests io.aha.index.oracle.VisibilityStoreTest
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

`NaiveSearchTest`, same container and seed. Query `quarterly` unless noted.

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
./gradlew :oracle:test --tests io.aha.index.oracle.NaiveSearchTest
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
- Each request opens a JDBC connection and closes it in `finally`. Phase 1 does not pool.
- `GET /v1/health` returns 200 `{"status":"ok","mode":"naive"}` with no principal required.
- `GET /v1/directory` lists tenants and users. Demo mode only. No principal required. This exists so phase 5 can fill the switcher. It is not a secret in demo mode. In `AUTH_MODE=token` return 401.
- `GET /v1/search?q=` principal required. `mode` is `naive`, `replica` is `oracle`, `hedged` false, `aclEpoch` `"0"`, `watermarkLagMs` 0. This process reads Postgres directly, so it is not behind a log.
- `GET /v1/documents/{docId}` principal required. 200 with title and body when visible, 404 otherwise.
- `POST /v1/documents` principal required. The principal's tenant is the document's tenant. Body is `DocumentWrite`. 201 on insert, 409 `ALREADY_EXISTS` on duplicate.
- `POST /v1/acl` body `AclWrite`. 204 on success. 404 unknown document. 422 unknown principal. Idempotent grant and revoke return 204.
- `POST /v1/memberships` body `MembershipWrite`. 204, 422 if the user or group is not in the tenant.
- Unknown path: 404 `NOT_FOUND`.
- Read the body with a cap of 1 MiB.

Log one line per request at info: method, path, status, millis, request id, tenant, user. Do not log `q` at info if you do not want queries in the log; log `qLength` instead. Log the query at debug.

`oracle/src/main/resources/logback.xml`: console appender, pattern `%d{ISO8601} %-5level [%thread] %logger - %msg%n`, root level info.

`OracleMain.main` loads `DbConfig.fromEnv()`, migrates, optionally seeds when `SEED=true`, then starts the server and blocks.

`OracleHttpTest` uses Testcontainers Postgres, starts `OracleServer` on port 0 (`server.bind` then read the actual port), seeds, and uses `java.net.http.HttpClient`.

Cases:

- Ada search `quarterly` returns the three ids from commit 7, in order, and the body does not contain `d-canary`.
- Missing headers return 400 `MISSING_PRINCIPAL`.
- `AUTH_MODE=token` on a server started with that config returns 401 for search even with the headers set. Build the server with an explicit `AuthMode` argument so the test does not mutate global env. `fromEnv()` is for `main` only.
- POST a document `d-temp`, search as ada, assert it is absent, POST grant USER ada, search again, assert it is present, POST revoke, assert it is absent.
- GET that document as ben returns 404 while it is granted to ada. GET as ada returns 200 while granted.
- Query `%%%` returns 400 `NO_TOKENS`.

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

For `installDist` to exist, apply the Application plugin to the oracle module:

```kotlin
plugins {
    java
    application
}
application {
    mainClass.set("io.aha.index.oracle.OracleMain")
}
```

Commit:

```text
feat: serve the oracle over HTTP

Why:
The HTTP shape (hits, mode, replica, hedged, aclEpoch, watermark)
is fixed here so later processes can replace the implementation
without breaking clients. Writes land in Postgres and become
visible on the next search because this process has no cache.

Verify:
./gradlew :oracle:test --tests io.aha.index.oracle.OracleHttpTest
SEED=true ./deploy/run-oracle.sh   # against compose Postgres
curl -s -H 'X-Tenant-Id: acme' -H 'X-User-Id: ada' \
  'http://127.0.0.1:18480/v1/search?q=quarterly'
```

The curl output contains `d-ada-1`, `d-eng-1`, `d-shared`, and does not contain `d-canary`.

## Commit 9 — `test: lock the leak properties`

`io.aha.index.oracle.VisibilityProperties` (jqwik), against Testcontainers. Start the container once (`@Testcontainers` + static container, or a JUnit extension). Migrate once. Do not depend on the fixed seed for the property; the property builds its own tiny world. Truncate tables at the start of each try, or use a unique tenant id per try (`"t" + System.nanoTime()` is not reproducible). Prefer jqwik's generated tenant id and delete that tenant's rows in a fixture. Simplest reproducible approach: one tenant `p`, wipe `p`'s rows at the start of the property.

Property 1, `@Property(tries = 100)`:

Generate:

- 1 to 8 users, ids `u0`…
- 1 to 4 groups, ids `g0`…
- each user a subset of groups
- 1 to 12 documents, ids `d0`…
- each document a set of grants, each grant either USER one of the users or GROUP one of the groups
- a query of 1 to 3 tokens drawn from the words that actually appear in the generated titles (also generate a title as 1 to 4 words from a small dictionary: `alpha beta gamma delta quarterly revenue`)

Insert that world. For every user, run `NaiveSearch`. For every hit, assert a grant path exists in the data you inserted (direct user grant, or a group grant plus membership). Assert no hit has a `docId` you did not insert. Assert `d-canary` is not involved; this property uses its own ids.

Property 2: take the fixed seed (a separate `@Test`, not a property) and for every seeded user, search `quarterly`. Assert the canary is in the hits if and only if the user is ivan. Assert no hit's tenant is wrong. You can pass tenant through the store; the HTTP response does not include tenant, so this test calls `NaiveSearch` directly and also checks `DocumentStore` for the hit's tenant.

Property 3: `@Test` named `unfilteredLimitWouldHideAdasDocs`. Insert is already seeded. Run a deliberately bad query in the test only:

```sql
SELECT doc_id FROM documents
WHERE tenant_id = 'acme' AND body ILIKE '%quarterly%'
ORDER BY doc_id ASC
LIMIT 10
```

Assert that this bad result is not equal to Ada's naive-search ids. This test documents the bug you refused to ship. If someone "simplifies" `NaiveSearch` into this SQL, the earlier exact-order test fails; this test explains why.

Commit:

```text
test: lock the leak properties

Why:
The seeded canary covers one scenario. The property covers random
grant graphs so a predicate that only works for the seed cannot
pass. The bad LIMIT query is kept in the test as a record of the
bug the oracle refuses to implement.

Verify:
./gradlew :oracle:test
```

## Commit 10 — `docs: record the phase-1 latency baseline`

`io.aha.index.oracle.NaiveBenchmark` is a `main` in `src/test` or in `src/main` under a `bench` package. It:

1. Expects compose Postgres up, migrates, seeds.
2. Inserts extra filler documents until the tenant `acme` has at least 5_000 documents containing the word `quarterly`, granted to GROUP contractors. Page the inserts in batches of 200 per transaction.
3. Warms 20 searches as ada for `quarterly`.
4. Times 200 searches. Record min, p50, p99, max in milliseconds. p99 index is `ceil(0.99 * 200) - 1` on the sorted samples (index 197 of 200).
5. Prints one line: `phase1 ada quarterly n=200 p50=.. p99=.. max=.. docs=5000+`.

Write the numbers into `docs/benchmarks/phase1.md` by hand from that output, with the date, the machine (cpu and RAM), and the sentence: "This p99 is above the 100ms SLO. Phase 2 is the response." Do not put a CI assertion on this number. Do not tune the SQL to chase it. An index on `body` that makes this fast hides the lesson; do not add one.

Also paste the result of:

```sql
EXPLAIN ANALYZE
SELECT d.doc_id
FROM documents d
WHERE d.tenant_id = 'acme'
  AND EXISTS ( ... the visibility predicate for ada ...);
```

You want the plan in the markdown so a reader can see the sequential work.

Commit:

```text
docs: record the phase-1 latency baseline

Why:
The SLO is 100ms at p99. This commit writes down that the correct
oracle misses it, and by how much, before any index exists.

Verify:
java run of NaiveBenchmark against compose, docs/benchmarks/phase1.md filled in
```

## Merge

```bash
./gradlew test
git status   # clean, benchmarks file committed
git checkout main
git merge --ff-only feature/phase-1-naive-oracle
git tag -a v0.1-naive -m "Naive SQL oracle, default-deny search, baseline latency"
```

Exit bar:

- `./gradlew test` is green.
- Curl as Ada does not return the canary. Curl as Ivan returns it first for `quarterly`.
- `docs/benchmarks/phase1.md` exists and states the SLO miss.
- `git tag --list 'v0.1-naive'` prints the tag.
- The tree has no `web/` directory and no `package.json`.
