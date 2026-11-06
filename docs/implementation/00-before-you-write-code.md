# 00 — Before you write code

This is the current step. You finish it before `settings.gradle.kts`, before `V001__init.sql`, and before any Java type.

Read [../ARCHITECTURE.md](../ARCHITECTURE.md) once, then work this file from top to bottom. Later phases assume the laws here. If a later phase disagrees with this file on schema, packages, or verification, this file wins and you fix the phase before writing that code.

There is no automated test suite in this project. Verification is `psql`, `curl`, `EXPLAIN`, and a benchmark `main` you run by hand. The sections below say what you do, why that action exists, and what is lost if you skip it.

## 0.1 Machine

**What.** Install and check three tools:

- Git
- Docker Engine with Compose v2 (`docker compose version` must work)
- JDK 21 (Temurin). `java -version` prints 21.

Gradle is fetched by the wrapper in phase 1. A JDK is enough to run that wrapper. Node is installed in phase 5, when the console is built.

```bash
java -version
docker compose version
git --version
```

`JAVA_HOME` points at the Temurin 21 home. On macOS with Temurin this is often `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. On Ubuntu it is often `/usr/lib/jvm/temurin-21-jdk-amd64`.

**Why.** Every later process is Java 21 bytecode talking to a Postgres 16 container. A different JDK changes virtual-thread and ZGC flags. A Compose v1 binary (`docker-compose` with a hyphen) will not run the files this repo adds in phase 1.

**Significance.** The latency and freshness claims are about this runtime. A setup that “almost” matches produces numbers you cannot compare with the phase write-ups.

## 0.2 Repository

**What.** Put the workbook in git as the first commit, if it is not already committed.

```bash
cd ~/Desktop/Projects/Aha-Index
git init -b main
git add README.md docs
git commit -m "$(cat <<'EOF'
docs: add the Aha-Index architecture and implementation workbook

Why:
The service is built from these guides. The phase-1 visibility
SQL remains the authority for who can read a document.

Verify:
git log --oneline -1
EOF
)"
```

If `git init` says the directory is already a repo, skip init. Leave `.gradle/` and `build/` untracked. Set `user.name` and `user.email` to your own identity when this clone does not have them.

**Why.** `main` has to stay a demoable history. The first commit is the design, so later commits can be read against it.

**Significance.** A phase branch cut from an empty tree has nothing to fast-forward onto. The tags in section 0.3 name that history.

## 0.3 Branching

**What.** `main` stays demoable. Each phase is one branch, cut from `main` after the previous tag.

| Phase | Branch | Tag when it lands on main |
| --- | --- | --- |
| 1 | `feature/phase-1-naive-oracle` | `v0.1-naive` |
| 2 | `feature/phase-2-bitmap-prefilter` | `v0.2-prefilter` |
| 3 | `feature/phase-3-cdc-freshness` | `v0.3-freshness` |
| 4 | `feature/phase-4-hedged-slo` | `v0.4-slo` |
| 5 | `feature/web-search-console` | `v0.5-console` |

Start phase 1, after this step’s exit checks:

```bash
git checkout main
git checkout -b feature/phase-1-naive-oracle
```

Finish a phase only after that phase’s exit checks pass:

```bash
git checkout main
git merge --ff-only feature/phase-1-naive-oracle
git tag -a v0.1-naive -m "Naive SQL oracle, default-deny search, baseline latency"
```

`--ff-only` refuses the merge when `main` has commits the branch lacks. Rebase the feature branch onto `main` and try again. The published history stays linear.

Commit subjects are imperative and lowercase after the prefix: `feat:`, `docs:`, `chore:`, `perf:`. The body has two parts, `Why:` and `Verify:`. The guides give the full message for every commit.

One commit is one behavior change. A mistake in a commit you have already built on is fixed in a new commit (`fix: ...`). Amending a commit you just made, have not pushed, and have not built on is fine.

**Why.** A reviewer can check out a tag and see one complete behavior: correct and slow, then indexed, then fresh, then within the latency bound, then visible in a browser.

**Significance.** Mixing a schema change and a ranking change in one commit makes a wrong hit list impossible to bisect. The prefixes are the map of that bisect.

## 0.4 One application, until a second process exists

**What.** Phase 1 is a single Spring Boot project. Source lives under `src/main/java` and `src/main/resources`. There is no `analysis/`, `api/`, or `oracle/` module. Packages inside that one tree (`io.aha.index.search`, later `io.aha.index.idx`) are how code is grouped.

```text
Aha-Index/
  settings.gradle.kts          # root name only, no include(...) until phase 4
  build.gradle.kts             # the Boot app; versions live here until a second module exists
  gradlew
  src/main/java/io/aha/index/
  src/main/resources/
  deploy/                      # created when that phase needs a container
  docs/
```

A library or a second Boot process is created in the phase that starts it:

| When | What gets added | Why it was not created earlier |
| --- | --- | --- |
| Phase 2 | RoaringBitmap and index classes in the same app | The brute-force API has to be callable first |
| Phase 3 | Kafka client, Debezium, `wal_level=logical` | Freshness is the step that reads the log |
| Phase 4 | Two more processes of the same jar, then a coordinator project, then a load-generator project | Hedging needs two copies. A second Gradle project appears when the program is different |
| Phase 5 | `web/` | The UI is a view of an API that already meets the SLO |

**Why.** An empty module is a second build file that does not answer a request. Two replicas run the same jar, so the tokenizer stays a class in that jar. The coordinator forwards HTTP and does not import it.

**Significance.** Phase 4 is allowed to split the tree. Phase 1 is not. A Gradle `include` added on the first day freezes a process boundary the brute-force search does not have.

## 0.5 Versions

**What.** Versions for this one application live in `build.gradle.kts`. A `gradle/libs.versions.toml` catalog is created in phase 4, when a second module has to share them. RoaringBitmap, the Kafka client, and JMH are added to the build file in the commit that first calls them. Caffeine is not on that list until a profile shows query tokenization on the hot path. If Maven Central has moved a patch and Gradle cannot resolve one of these, change only the patch number and record the substitution in the commit body.

```kotlin
id("org.springframework.boot") version "3.5.16"
id("io.spring.dependency-management") version "1.1.7"
id("com.diffplug.spotless") version "6.25.0"
```

Java toolchain 21. `google-java-format` 1.22.0. Jackson, Logback, SLF4J, HikariCP, and the Postgres JDBC driver come from the Spring Boot plugin. Phase 1 does not pin them and does not list libraries it does not import. Spotless is applied from the first Gradle commit so formatting is one command, `./gradlew spotlessApply`. `spring-boot-starter-jdbc` is added in the Postgres commit, not in the empty health commit.

Docker image tags, used when the phase tells you to:

- `postgres:16`
- `quay.io/debezium/kafka:3.7.0.Final`
- `quay.io/debezium/connect:3.7.0.Final`

**Why.** The plugin block is the list of versions this application uses. A second file that Gradle does not read can drift from the build. Formatting as a plugin keeps the Java style in the repo instead of in an editor setting.

**Significance.** The Spring Boot BOM is the pin for the HTTP stack. An extra Jackson version next to that BOM is a different product on the next clone. The SLO write-up has to name the same binaries.

## 0.6 Code standard

**What.** Production Java in this repo follows these rules from the first class.

- Language level 21. Compiler args `-parameters -Xlint:deprecation -Xlint:unchecked`. Source encoding UTF-8.
- Types that only carry data are `record`s. Utility classes are `final` with a private constructor. Fields that are not mutated are `final`.
- Input that crosses a process boundary is checked at that boundary: header shape, body size, token presence. Library methods throw a domain exception. The HTTP layer maps it to the status table in section 0.11.
- JDBC uses `PreparedStatement` and bind variables. SQL text is a constant. User input is never concatenated into SQL.
- One API write is one transaction: `setAutoCommit(false)`, work, `commit`, and `close` in `finally` (try-with-resources on the connection). A failure rolls back.
- Each request connection that reads or writes tenant data runs `SELECT set_config('app.tenant_id', ?, true)` before any other statement. `true` makes the setting transaction-local. Migrations, seeding, and the demo directory run as the table owner, which is the role that bypasses row-level security.
- HTTP is a `@RestController` in the Boot module for that process. `DemoPrincipalFilter` is the only type that reads `X-Tenant-Id` and `X-User-Id`. Errors are one `@RestControllerAdvice` that writes the status table in section 0.11. Request bodies larger than 1 MiB are rejected before the controller runs.
- Logging goes through SLF4J. One info line per request: method, path, status, millis, request id, tenant, user, query length. Document bodies and raw queries stay at debug or out of the log. Use Boot’s logging configuration in `application.yml`. A hand-written `logback.xml` is not added.
- Time is `timestamptz` in Postgres and `long` epoch millis in Java. The clock interface is `io.aha.index.time.SystemClock` with `long millis()`. Production binds `System::currentTimeMillis`. The freshness comparison reads that interface so a deployment can set `FRESHNESS_SLO_MS` without editing the comparison.
- Null is rejected at the boundary with `Objects.requireNonNull`. Public methods document the empty-input behavior in Javadoc when empty is meaningful (the tokenizer).
- Format with Spotless (`google-java-format`) before the commit.

**Why.** These are the checks a production review applies to a service that mixes SQL, JSON, and a security decision: parameterized SQL, explicit transactions, tenant context on the session, and logs that cannot leak the corpus.

**Significance.** The visibility bug this system is built to avoid is a missing `tenant_id` or a string pasted into SQL. The standard makes that bug a review comment on the first commit that would introduce it.

## 0.7 Schema standard

**What.** Phase 1 creates one migration, `oracle/src/main/resources/db/migration/V001__init.sql`. The filename is the Flyway convention: a version, two underscores, a description. The runner in phase 1 applies files in version order, stores a SHA-256 checksum, and refuses to continue when a recorded file’s bytes change. That is the same contract Flyway enforces, kept in a small runner because this repo has one migration and no migration framework.

The script below is the schema. Copy it. Table and column names are `snake_case`. Every timestamp is `timestamptz`. Every constraint has a name. Child rows name `ON DELETE`. Identifiers use the same pattern as the demo headers. The polymorphic ACL principal, which cannot be a single foreign key, is enforced by a trigger so a grant to a missing user fails in the database.

The application role `aha_app` is `NOSUPERUSER` and has no `BYPASSRLS`. Row-level security is enabled on every tenant-scoped table and is not forced, so the owning role `aha` (migrations, seed, `psql`, Debezium) still sees every row. Request connections use `aha_app` and set `app.tenant_id`. A connection that sets neither sees no tenant rows.

Logical decoding reads the heap and does not apply these policies. Debezium keeps working. `REPLICA IDENTITY` stays `DEFAULT`: a delete event needs the primary key, and `DEFAULT` records the primary key. `FULL` would copy title and body into the WAL on every delete.

```sql
CREATE SCHEMA IF NOT EXISTS search;

COMMENT ON SCHEMA search IS
    'System of record for tenants, documents, and allow-grants.';

CREATE TABLE search.schema_migrations (
    version            text PRIMARY KEY,
    description        text NOT NULL,
    checksum           text NOT NULL,
    execution_time_ms  integer NOT NULL,
    applied_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT schema_migrations_version_nonblank
        CHECK (length(btrim(version)) > 0),
    CONSTRAINT schema_migrations_checksum_sha256
        CHECK (checksum ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE search.schema_migrations IS
    'Applied migration versions and the SHA-256 of the script bytes.';

CREATE TABLE search.tenants (
    tenant_id   text PRIMARY KEY,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenants_tenant_id_format
        CHECK (tenant_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT tenants_name_nonblank
        CHECK (length(btrim(name)) > 0)
);

COMMENT ON TABLE search.tenants IS
    'A tenant is the isolation boundary. Every other business table carries tenant_id.';

CREATE TABLE search.users (
    tenant_id      text NOT NULL,
    user_id        text NOT NULL,
    display_name   text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_pkey PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT users_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES search.tenants (tenant_id) ON DELETE CASCADE,
    CONSTRAINT users_user_id_format
        CHECK (user_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT users_display_name_nonblank
        CHECK (length(btrim(display_name)) > 0)
);

COMMENT ON TABLE search.users IS
    'A user exists inside one tenant. The same user_id in another tenant is a different person.';

CREATE TABLE search.groups (
    tenant_id   text NOT NULL,
    group_id    text NOT NULL,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT groups_pkey PRIMARY KEY (tenant_id, group_id),
    CONSTRAINT groups_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES search.tenants (tenant_id) ON DELETE CASCADE,
    CONSTRAINT groups_group_id_format
        CHECK (group_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT groups_name_nonblank
        CHECK (length(btrim(name)) > 0)
);

COMMENT ON TABLE search.groups IS
    'A group is a principal that users inherit through group_memberships.';

CREATE TABLE search.group_memberships (
    tenant_id   text NOT NULL,
    group_id    text NOT NULL,
    user_id     text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT group_memberships_pkey PRIMARY KEY (tenant_id, group_id, user_id),
    CONSTRAINT group_memberships_group_fk
        FOREIGN KEY (tenant_id, group_id)
        REFERENCES search.groups (tenant_id, group_id) ON DELETE CASCADE,
    CONSTRAINT group_memberships_user_fk
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES search.users (tenant_id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE search.group_memberships IS
    'Membership is tenant-local. A row says this user currently inherits this group.';

CREATE TABLE search.documents (
    tenant_id   text NOT NULL,
    doc_id      text NOT NULL,
    title       text NOT NULL,
    body        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT documents_pkey PRIMARY KEY (tenant_id, doc_id),
    CONSTRAINT documents_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES search.tenants (tenant_id) ON DELETE CASCADE,
    CONSTRAINT documents_doc_id_format
        CHECK (doc_id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT documents_title_nonblank
        CHECK (length(btrim(title)) > 0),
    CONSTRAINT documents_body_nonblank
        CHECK (length(btrim(body)) > 0),
    CONSTRAINT documents_body_length
        CHECK (char_length(body) <= 100000)
);

COMMENT ON TABLE search.documents IS
    'A document is invisible until an acl_entries row grants it. There is no visibility flag on this table.';

CREATE TABLE search.acl_entries (
    tenant_id       text NOT NULL,
    doc_id          text NOT NULL,
    principal_type  text NOT NULL,
    principal_id    text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT acl_entries_pkey
        PRIMARY KEY (tenant_id, doc_id, principal_type, principal_id),
    CONSTRAINT acl_entries_document_fk
        FOREIGN KEY (tenant_id, doc_id)
        REFERENCES search.documents (tenant_id, doc_id) ON DELETE CASCADE,
    CONSTRAINT acl_entries_principal_type_chk
        CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT acl_entries_principal_id_format
        CHECK (principal_id ~ '^[A-Za-z0-9._-]{1,64}$')
);

COMMENT ON TABLE search.acl_entries IS
    'Allow-grants. Absence of a row is denial. principal_id points at users or groups according to principal_type; trg_acl_principal_exists enforces that.';

CREATE INDEX acl_entries_principal_idx
    ON search.acl_entries (tenant_id, principal_type, principal_id);

CREATE INDEX group_memberships_user_idx
    ON search.group_memberships (tenant_id, user_id);

CREATE INDEX documents_tenant_updated_idx
    ON search.documents (tenant_id, updated_at DESC);

CREATE FUNCTION search.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenants_touch_updated_at
    BEFORE UPDATE ON search.tenants
    FOR EACH ROW EXECUTE FUNCTION search.touch_updated_at();

CREATE TRIGGER users_touch_updated_at
    BEFORE UPDATE ON search.users
    FOR EACH ROW EXECUTE FUNCTION search.touch_updated_at();

CREATE TRIGGER groups_touch_updated_at
    BEFORE UPDATE ON search.groups
    FOR EACH ROW EXECUTE FUNCTION search.touch_updated_at();

CREATE TRIGGER documents_touch_updated_at
    BEFORE UPDATE ON search.documents
    FOR EACH ROW EXECUTE FUNCTION search.touch_updated_at();

CREATE FUNCTION search.enforce_acl_principal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.principal_type = 'USER' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM search.users u
            WHERE u.tenant_id = NEW.tenant_id
              AND u.user_id = NEW.principal_id
        ) THEN
            RAISE EXCEPTION 'unknown user principal % in tenant %',
                NEW.principal_id, NEW.tenant_id
                USING ERRCODE = '23503';
        END IF;
    ELSIF NEW.principal_type = 'GROUP' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM search.groups g
            WHERE g.tenant_id = NEW.tenant_id
              AND g.group_id = NEW.principal_id
        ) THEN
            RAISE EXCEPTION 'unknown group principal % in tenant %',
                NEW.principal_id, NEW.tenant_id
                USING ERRCODE = '23503';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER acl_entries_principal_exists
    BEFORE INSERT OR UPDATE ON search.acl_entries
    FOR EACH ROW EXECUTE FUNCTION search.enforce_acl_principal();

ALTER TABLE search.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE search.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE search.group_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE search.documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE search.acl_entries ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_tenant_isolation ON search.users
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY groups_tenant_isolation ON search.groups
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY group_memberships_tenant_isolation ON search.group_memberships
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY documents_tenant_isolation ON search.documents
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY acl_entries_tenant_isolation ON search.acl_entries
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

GRANT USAGE ON SCHEMA search TO aha_app;
GRANT SELECT ON search.tenants TO aha_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    search.users,
    search.groups,
    search.group_memberships,
    search.documents,
    search.acl_entries
TO aha_app;

ALTER ROLE aha SET search_path TO search, public;
ALTER ROLE aha_app SET search_path TO search, public;
```

`CREATE ROLE` cannot run inside the migration transaction. Compose creates the role on first boot from `deploy/postgres/init/01-role.sql`, mounted at `/docker-entrypoint-initdb.d/01-role.sql`:

```sql
CREATE ROLE aha_app LOGIN PASSWORD 'aha_app'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
```

Postgres runs that directory once, on an empty data directory, and each statement auto-commits. `CREATE ROLE` is legal there. It is not legal inside the migration's single transaction, which is why it stays out of `V001`.

The local passwords `aha` and `aha_app` are for the Compose database on your machine. A deployed environment replaces them and does not commit the replacement. A volume that already exists will not re-run `docker-entrypoint-initdb.d`; create the role by hand in that case, then migrate.

`principal_id` stays a single column. A second table per principal type would duplicate the grant key the index and the WAL both key on. The trigger is the integrity check a foreign key cannot express for that union.

**Why.** Multi-tenant Postgres practice is: tenant column on every business table, timestamps with a time zone, named constraints, an explicit `ON DELETE`, a non-owner role for the application, and row-level security as a second fence behind the `WHERE tenant_id = ?` the query already has. The checksum table is how a changed migration is caught before it is applied to a database that already ran the old bytes.

**Significance.** Search correctness is “this user, this tenant, these grants.” A schema that stores visibility as a boolean on `documents` can be flipped without a grant row. A schema without the tenant policy can return another tenant’s rows when one query forgets the predicate. Both failures are invisible in a single-tenant demo and fatal here. Locking the script in this step means phase 1 types against a reviewed model.

The role already sets `search_path` to `search, public`. Phase 1’s connection factory still sends `set_config('app.tenant_id', …)` on every request transaction, because row-level security reads that setting and a role default cannot know the caller.

## 0.8 Matching law

**What.** Phase 1 and phase 2 agree on what a match is. Write the class once, in `:analysis`, and call it from both.

`io.aha.index.analysis.Tokenizer`

- `List<String> tokens(String text)`
- `Map<String, Integer> termFreq(String text)` — counts of those tokens. Insertion order does not matter.

Algorithm for `tokens`:

1. Null or empty input returns an empty list.
2. `Normalizer.normalize(text, Normalizer.Form.NFKC)`.
3. `toLowerCase(Locale.ROOT)`.
4. Walk code points. A token is a maximal run where `Character.isLetterOrDigit(cp)` is true.
5. Keep every run, including single characters. Stemming and stop-word lists stay out of this version.

A document matches a query when every query token occurs at least once in `tokens(title + "\n" + body)`. Order does not matter. The match is a conjunction across tokens.

Phase-1 score, used by the oracle process: for each query token, `3 * count in title + 1 * count in body`, summed across tokens. Tie-break is `docId` ascending (lexicographic on the external id).

Phase-2 score is BM25, specified in the phase-2 guide. Tie-break is the dense internal id ascending, which follows external `docId` ascending because ids are assigned in that order.

**Why.** Two rankers that tokenize differently disagree on the hit list and the disagreement looks like a ranking bug. One class makes a ranking diff a ranking diff.

**Significance.** The oracle is the authority later phases check with `curl` and SQL. If the index tokenizes on its own, those checks stop meaning “same documents.”

## 0.9 Visibility law

**What.** A document `d` in tenant `T` is visible to user `U` when a row in `acl_entries` matches `(T, d)` and either:

- `principal_type = 'USER'` and `principal_id = U`, or
- `principal_type = 'GROUP'` and `(T, principal_id, U)` exists in `group_memberships`.

There is no public flag. A document with zero ACL rows is invisible to every user, including its author. Cross-tenant rows fail the predicate because `tenant_id` is part of every comparison, and they fail row-level security because the session’s `app.tenant_id` is `T`.

The SQL text of this predicate lives in one place: `io.aha.index.oracle.VisibilitySql.PREDICATE`. The full-scan search and the candidate-id confirmation both paste that constant into their statements.

Unknown user in a known tenant: the predicate matches nothing. Return 200 and zero hits. Unknown tenant: the same response, 200 and zero hits.

**Why.** Default deny is “no row,” which the database already understands. A missing user is an empty answer, and a 404 would tell the caller that the id was special.

**Significance.** Every later phase is an acceleration of this predicate. A second copy of the SQL will drift, and the drift is a document shown to the wrong person.

## 0.10 Identity law

**What.** `io.aha.index.oracle.PrincipalResolver` (phase 4 has its own copy in the coordinator, same rules):

- Read env `AUTH_MODE`. Absent means `demo`.
- `demo`: require headers `X-Tenant-Id` and `X-User-Id`, each matching `^[A-Za-z0-9._-]{1,64}$`. Otherwise 400 `MISSING_PRINCIPAL`.
- `token`: ignore those headers. Return 401 `UNAUTHENTICATED`. This build has no token parser. The branch exists so the demo headers have a single reader and a mode that refuses them.

Echo `X-Request-Id` when the caller sent one that matches `^[A-Za-z0-9._-]{1,64}$`. Otherwise generate a UUID and echo that. Put it on every log line for the request.

**Why.** Demo mode is a harness for the switcher. Production mode has to ignore client-supplied identity. One class is allowed to read the headers, and only in demo mode, so a later handler cannot copy them into the security context by accident.

**Significance.** An identity check scattered across handlers is how a new route ships without one. The 401 branch is the proof that the headers are not a credential.

## 0.11 HTTP law

**What.** JSON errors:

```json
{ "error": "human readable", "code": "MISSING_PRINCIPAL" }
```

Status codes:

| Situation | Status | `code` |
| --- | --- | --- |
| Missing or malformed principal in demo mode | 400 | `MISSING_PRINCIPAL` |
| Query missing, blank, or only punctuation | 400 | `NO_TOKENS` |
| Body larger than 1 MiB | 413 | `BODY_TOO_LARGE` |
| Unknown document on an ACL write | 404 | `NOT_FOUND` |
| Principal id is not a user or group in this tenant | 422 | `UNKNOWN_PRINCIPAL` |
| Token mode with no authenticator | 401 | `UNAUTHENTICATED` |
| Duplicate document id | 409 | `ALREADY_EXISTS` |
| Replica not caught up, snapshot replaying, or Postgres confirm failed | 503 | `INDEX_UNAVAILABLE` |
| Both replicas failed for a reason other than 503 | 502 | `UPSTREAM_FAILED` |

`GET /v1/documents/{docId}` returns the document only when it is visible. Missing and invisible are both 404 `NOT_FOUND`.

Writes (`POST /v1/documents`, `POST /v1/acl`, membership endpoints) are synchronous to Postgres. They are not hedged. Search is the only hedged route.

The response fields from phase 1 stay stable. Later phases add fields at the end of the JSON objects.

**Why.** Clients, the load generator, and the console share one error shape. Hedging a write would apply a grant twice and race the row that is supposed to be the single source of truth.

**Significance.** A status code invented in phase 4 breaks the console’s error states. The table is the contract.

## 0.12 Clock

**What.** `io.aha.index.time.SystemClock` is an interface with `long millis()`. Production uses `System.currentTimeMillis()`. The freshness threshold defaults to `30_000` milliseconds and is overridden with `FRESHNESS_SLO_MS` for a local check that should not wait half a minute.

**Why.** The fail-closed rule compares “now” with the commit time of the last applied log event. Reading that through one interface keeps the comparison the same when the threshold changes.

**Significance.** A hardcoded `System.currentTimeMillis()` inside the watermark forces every freshness check to wall-clock 30 seconds and makes the threshold a source edit.

## 0.13 What you refuse to cache

**What.**

- A search response key includes tenant and user.
- A search response cached past an ACL write includes `aclEpoch` in the key.
- A token cache, if a later profile adds one, stores `tokens(queryString)` and nothing else.

**Why.** A cache key without the principal serves one user’s hits to another. A token cache has no documents and no grants in it, so its lifetime is independent of the ACL log.

**Significance.** This is the client-side version of the leak the bitmap and the watermark exist to prevent.

## 0.14 How a step is verified

**What.** Each commit’s `Verify:` block is a command you run and read. Typical commands are `./gradlew assemble`, `psql`, `curl`, and a `java` main that prints a histogram. You write the observed output into the commit message or into `docs/benchmarks/` when the phase says to.

You do not add `src/test`, JUnit, jqwik, Testcontainers, AssertJ, Jest, Vitest, or Playwright. You do not add a `test` task configuration. JMH, when phase 4 adds it, is a benchmark main: it prints allocation numbers, and you paste them into the benchmark note.

**Why.** The behavior is demonstrated on the running Compose stack, which is the same path a caller uses. A second harness that boots its own database tends to become the thing that is maintained, while the compose path drifts.

**Significance.** The exit bar of every phase is a command with an observable result: Ada’s search lacks the canary, Ivan’s first hit is the canary, a paused consumer returns 503, a slow replica still yields a server p99 under 100ms.

## 0.15 Exit of this step

You are done with this step when all of the following are true:

- `java -version` reports 21, `docker compose version` runs, and `git --version` runs.
- The git history on `main` starts with the workbook commit (or already contains these docs).
- You can point at section 0.7 and say the phase-1 migration will be that script.
- The only code that may exist is the phase-1 health application: one Spring Boot project, no `web/` directory, no second Gradle module.

Next file: [01-phase-1-naive-oracle.md](01-phase-1-naive-oracle.md). Tick the setup lines in [../CHECKLIST.md](../CHECKLIST.md) as you finish them.
