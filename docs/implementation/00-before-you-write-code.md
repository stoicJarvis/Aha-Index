# 00 — Before you write code

Read [../ARCHITECTURE.md](../ARCHITECTURE.md) first. This file is the setup and the laws every later phase assumes.

## 0.1 Machine

You need:

- Git
- Docker Engine with Compose v2 (`docker compose version` must work)
- JDK 21 (Temurin). `java -version` prints 21.
- Gradle is fetched by the wrapper in phase 1. You need a JDK to run the wrapper, not a system-wide Gradle install.

You do not install Node until phase 5.

Check:

```bash
java -version
docker compose version
git --version
```

`JAVA_HOME` points at the Temurin 21 home. On macOS with Temurin this is often `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. On Ubuntu it is often `/usr/lib/jvm/temurin-21-jdk-amd64`.

## 0.2 Repository

```bash
cd ~/Desktop/Projects/Aha-Index
git init -b main
git add README.md docs
git commit -m "$(cat <<'EOF'
docs: add the Aha-Index architecture and implementation workbook

The service is built by hand from these guides. The phase-1 oracle
remains the visibility authority for every later phase.
EOF
)"
```

If `git init` says the directory is already a repo, skip init. Do not commit `.gradle/` or `build/`.

Set `user.name` and `user.email` to your own identity if this clone does not have them. The history is part of the portfolio.

## 0.3 Branching

`main` is always demoable. Each phase is one branch, cut from `main` after the previous tag.

| Phase | Branch | Tag when it lands on main |
| --- | --- | --- |
| 1 | `feature/phase-1-naive-oracle` | `v0.1-naive` |
| 2 | `feature/phase-2-bitmap-prefilter` | `v0.2-prefilter` |
| 3 | `feature/phase-3-cdc-freshness` | `v0.3-freshness` |
| 4 | `feature/phase-4-hedged-slo` | `v0.4-slo` |
| 5 | `feature/web-search-console` | `v0.5-console` |

Start a phase:

```bash
git checkout main
git checkout -b feature/phase-1-naive-oracle
```

Finish a phase, only after that phase's exit checks pass:

```bash
git checkout main
git merge --ff-only feature/phase-1-naive-oracle
git tag -a v0.1-naive -m "Naive SQL oracle, default-deny search, baseline latency"
```

`--ff-only` refuses the merge if `main` has commits the branch lacks. That is what you want. Rebase the feature branch onto `main` and try again. Do not create a merge commit.

Commit subjects are imperative and lowercase after the prefix: `feat:`, `test:`, `docs:`, `chore:`, `perf:`. The body has two parts, `Why:` and `Verify:`. The guides give the full message for every commit. Use them.

One commit is one behavior change. If a test fails, fix it in a new commit (`fix: ...`) rather than rewriting the previous commit after you have moved on. Before the commit is pushed anywhere, amending a commit you just made and have not built on is fine.

## 0.4 Module layout you will grow into

```text
Aha-Index/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  gradlew
  deploy/docker-compose.yml          # Postgres in phase 1; Kafka and Debezium in phase 3
  deploy/connector.json              # phase 3
  deploy/register-connector.sh       # phase 3
  deploy/run-index.sh                # phase 2
  analysis/                          # Tokenizer. Created in phase 1.
  api/                               # JSON records. Created in phase 1.
  oracle/                            # JDBC visibility, naive search, HTTP on 18480.
  index/                             # phase 2. Replicas on 18481 and 18482.
  coordinator/                       # phase 4. Public port 18483.
  loadgen/                           # phase 4.
  web/                               # phase 5. Do not create this directory earlier.
  docs/
```

Java package root: `io.aha.index`.

Dependencies between modules, and the direction they are allowed to point:

- `:analysis` depends on nothing of yours.
- `:api` depends on Jackson only.
- `:oracle` depends on `:analysis`, `:api`, JDBC.
- `:index` depends on `:analysis`, `:api`, `:oracle` (for `VisibilityStore` only), RoaringBitmap, Kafka client.
- `:coordinator` depends on `:api`. It talks to replicas over HTTP. It does not depend on `:index`.
- `:loadgen` depends on `:api`.

If `:index` starts importing the HTTP server of `:oracle`, stop and move the shared type down into `:api` or `:oracle`'s non-server package.

## 0.5 Versions

Put these in `gradle/libs.versions.toml` in the first commit. If Maven Central has moved a patch and Gradle cannot resolve one of them, change only the patch number and record the substitution in the commit body.

```toml
[versions]
java = "21"
jackson = "2.21.2"
junit = "5.14.4"
jqwik = "1.10.2"
assertj = "3.27.7"
postgres = "42.7.4"
testcontainers = "1.20.4"
slf4j = "2.0.16"
logback = "1.5.12"
roaring = "1.6.29"
kafka = "4.3.1"
caffeine = "3.2.0"
jmh = "1.37"

[libraries]
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
jqwik = { module = "net.jqwik:jqwik", version.ref = "jqwik" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
postgres = { module = "org.postgresql:postgresql", version.ref = "postgres" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgres = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-kafka = { module = "org.testcontainers:kafka", version.ref = "testcontainers" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
logback = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
roaring = { module = "org.roaringbitmap:RoaringBitmap", version.ref = "roaring" }
kafka-clients = { module = "org.apache.kafka:kafka-clients", version.ref = "kafka" }
caffeine = { module = "com.github.ben-manes.caffeine:caffeine", version.ref = "caffeine" }
jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
jmh-generator = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }
```

Docker image tags, used when the phase tells you to:

- `postgres:16`
- `quay.io/debezium/kafka:3.7.0.Final`
- `quay.io/debezium/connect:3.7.0.Final`

## 0.6 Matching law

Phase 1 and phase 2 must agree on what a match is. Write this class once, in `:analysis`, and call it from both.

`io.aha.index.analysis.Tokenizer`

- `List<String> tokens(String text)`
- `Map<String, Integer> termFreq(String text)` — counts of those tokens, insertion order does not matter.

Algorithm for `tokens`:

1. Null or empty input returns an empty list.
2. `Normalizer.normalize(text, Normalizer.Form.NFKC)`.
3. `toLowerCase(Locale.ROOT)`.
4. Walk code points. A token is a maximal run where `Character.isLetterOrDigit(cp)` is true.
5. Keep every run, including single characters. Do not stem. Do not drop stop-words.

A document matches a query when every query token occurs at least once in `tokens(title + "\n" + body)`. Order does not matter. This is a conjunction across tokens, a disjunction across title and body.

Phase-1 score, used only by the oracle process: for each query token, `3 * count in title + 1 * count in body`, summed across tokens. Tie-break is `docId` ascending (lexicographic on the external id).

Phase-2 score is BM25, specified in the phase-2 guide. Tie-break is the dense internal id ascending, which follows external `docId` ascending because ids are assigned in that order.

## 0.7 Visibility law

A document `d` in tenant `T` is visible to user `U` when a row in `acl_entries` matches `(T, d)` and either:

- `principal_type = 'USER'` and `principal_id = U`, or
- `principal_type = 'GROUP'` and `(T, principal_id, U)` exists in `group_memberships`.

There is no public flag. A document with zero ACL rows is invisible to every user, including its author. Cross-tenant rows never match because `tenant_id` is part of every predicate.

The SQL text of this predicate lives in one place: `io.aha.index.oracle.VisibilitySql.PREDICATE`. The full-scan search and the candidate-id confirmation both paste that constant into their statements. A second copy of the predicate is a bug even if the tests pass today.

Unknown user in a known tenant: the predicate matches nothing. Return 200 and zero hits. Do not 404. A 404 would tell the caller that the user id is special.

Unknown tenant: same response, 200 and zero hits.

## 0.8 Identity law

`io.aha.index.oracle.PrincipalResolver` (phase 4 has its own copy in the coordinator, same rules):

- Read env `AUTH_MODE`. Absent means `demo`.
- `demo`: require headers `X-Tenant-Id` and `X-User-Id`, each matching `^[A-Za-z0-9._-]{1,64}$`. Otherwise 400 `MISSING_PRINCIPAL`.
- `token`: ignore those headers. Return 401 `UNAUTHENTICATED`. The test `TokenModeIgnoresHeadersTest` locks this. You do not build a real token parser.

Echo `X-Request-Id` if the caller sent one that matches `^[A-Za-z0-9._-]{1,64}$`. Otherwise generate a UUID and echo that. Put it on every log line for the request. Do not log document bodies.

## 0.9 HTTP law

JSON errors:

```json
{ "error": "human readable", "code": "MISSING_PRINCIPAL" }
```

Status codes you will use:

| Situation | Status | `code` |
| --- | --- | --- |
| Missing or malformed principal in demo mode | 400 | `MISSING_PRINCIPAL` |
| Query missing, blank, or only punctuation | 400 | `NO_TOKENS` |
| Body larger than 1 MiB | 413 | `BODY_TOO_LARGE` |
| Unknown document on an ACL write | 404 | `NOT_FOUND` |
| Principal id is not a user or group in this tenant | 422 | `UNKNOWN_PRINCIPAL` |
| Token mode with no real authenticator | 401 | `UNAUTHENTICATED` |
| Replica not caught up, snapshot replaying, or Postgres confirm failed | 503 | `INDEX_UNAVAILABLE` |
| Both replicas failed for a reason other than 503 | 502 | `UPSTREAM_FAILED` |

`GET /v1/documents/{docId}` returns the document only when it is visible. Missing and invisible are both 404 `NOT_FOUND`.

Writes (`POST /v1/documents`, `POST /v1/acl`, membership endpoints) are synchronous to Postgres. They are not hedged. Search is the only hedged route.

## 0.10 Clock

`io.aha.index.time.SystemClock` is an interface with `long millis()`. Production uses `System.currentTimeMillis()`. Tests pass a fake. The 30 second comparison uses this clock, so the fail-closed test does not sleep for 30 seconds. The default threshold constant is `30_000` and a unit test asserts that constant. The integration test that pauses the consumer sets `FRESHNESS_SLO_MS=1000` for that process only.

## 0.11 What you refuse to cache

- Do not cache a search response under a key that omits tenant and user.
- Do not cache a search response past an ACL write in a process that has no `aclEpoch` in the key.
- Caffeine, when you add it in phase 4, stores `tokens(queryString)` and nothing else.

## 0.12 Exit of this step

You have a git repo whose first commit is the workbook, JDK 21 and Docker work, and you have not created a Gradle file yet. That happens in phase 1, commit 1.
