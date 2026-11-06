# Phase 3 — 30 second freshness

Branch: `feature/phase-3-cdc-freshness`

Tag: `v0.3-freshness`

```bash
git checkout main
git checkout -b feature/phase-3-cdc-freshness
```

You already have a correct index that is stale for grants until restart. This phase feeds it from Postgres logical replication. Revokes stay correct even during lag because `retainVisible` reads current rows. Grants become correct because the consumer adds them to the bitmap. If the consumer is too far behind, search returns 503 instead of a quietly incomplete index.

Still no frontend. The freshness probe is `deploy/freshness.sh`. This phase has no test sources.

## Commit 1 — `chore: run Kafka KRaft and Debezium Connect beside Postgres`

Extend `deploy/docker-compose.yml`. Keep the Postgres service. Add:

```yaml
  kafka:
    image: quay.io/debezium/kafka:3.7.0.Final
    ports:
      - "19092:9092"
    environment:
      CLUSTER_ID: "oh-sxaDRTcyAr6pFRbXyzA"
      NODE_ID: "1"
      NODE_ROLE: combined
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_LISTENERS: "PLAINTEXT://kafka:9092,CONTROLLER://kafka:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1"

  connect:
    image: quay.io/debezium/connect:3.7.0.Final
    ports:
      - "18083:8083"
    depends_on:
      - kafka
      - postgres
    environment:
      BOOTSTRAP_SERVERS: "kafka:9092"
      GROUP_ID: "aha-connect"
      CONFIG_STORAGE_TOPIC: "aha_connect_configs"
      OFFSET_STORAGE_TOPIC: "aha_connect_offsets"
      STATUS_STORAGE_TOPIC: "aha_connect_statuses"
      CONFIG_STORAGE_REPLICATION_FACTOR: "1"
      OFFSET_STORAGE_REPLICATION_FACTOR: "1"
      STATUS_STORAGE_REPLICATION_FACTOR: "1"
```

The advertised listener is the Docker DNS name `kafka`. The index process, when it runs on the host, cannot resolve `kafka` unless you add it to `/etc/hosts` or run the index in Compose too. Do both, later in this phase:

- Host development: add `127.0.0.1 kafka` is wrong because the advertised port inside the image is 9092, and the host mapped port is 19092. A client on the host using bootstrap `127.0.0.1:19092` receives metadata that says `kafka:9092` and then fails.
- Fix: set `KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://127.0.0.1:19092"` when the consumer runs on the host. The Connect container must then also reach the broker at that address, which it cannot.
- The clean fix for a one-broker demo: run the index replicas as compose services on the same network, bootstrap `kafka:9092`, and do not use the host port for the consumer. The host port 19092 is for `kcat` debugging. Set a second listener:

```yaml
KAFKA_LISTENERS: "INTERNAL://kafka:9092,EXTERNAL://0.0.0.0:19092,CONTROLLER://kafka:9093"
KAFKA_ADVERTISED_LISTENERS: "INTERNAL://kafka:9092,EXTERNAL://127.0.0.1:19092"
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT"
KAFKA_INTER_BROKER_LISTENER_NAME: "INTERNAL"
KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
```

Map `"19092:19092"`. Connect uses `INTERNAL://kafka:9092` via `BOOTSTRAP_SERVERS: kafka:9092`. A host-side test uses `127.0.0.1:19092`.

If the Debezium image's entrypoint overrides listener variables, read its `docker inspect` / docs for that tag and use the variable names it documents. The tutorial compose in the Debezium 3.7 examples is the fallback: one listener, and you run the consumer inside the compose network. Prefer running the replicas in compose (commit 6) over fighting advertised addresses.

Postgres needs a replication role. Add `deploy/postgres/init/01-debezium-role.sql` and mount it on `/docker-entrypoint-initdb.d/01-debezium-role.sql`. This script runs only on a fresh volume. If your volume already exists from phase 1, run the SQL by hand once.

```sql
CREATE ROLE debezium WITH REPLICATION LOGIN PASSWORD 'debezium';
GRANT CONNECT ON DATABASE aha_index TO debezium;
\c aha_index
GRANT USAGE ON SCHEMA public TO debezium;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO debezium;
```

`\c` does not run in every mount context. Split into two statements you execute with `psql -d aha_index` if the init script fails. The role must have `REPLICATION` and `SELECT` on the captured tables.

Bring the stack up:

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
curl -sf http://127.0.0.1:18083/connectors
```

The curl prints `[]` once Connect is up. It can take a minute. Retry rather than adding a sleep loop in Java.

Commit:

```text
chore: run Kafka KRaft and Debezium Connect beside Postgres

Why:
Phase 3's consumer needs a log and a connector process.
Replication factor 1 is the local stand-in. The listener split
lets Connect use the docker network and lets a host-side
consumer use 127.0.0.1:19092.

Verify:
curl -sf http://127.0.0.1:18083/connectors
```

## Commit 2 — `feat: register the Postgres connector with transaction metadata`

`deploy/connector.json`:

```json
{
  "name": "aha-postgres",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "debezium",
    "database.dbname": "aha_index",
    "topic.prefix": "aha",
    "table.include.list": "public.documents,public.acl_entries,public.group_memberships,public.users,public.groups,public.tenants",
    "publication.autocreate.mode": "filtered",
    "publication.name": "aha_publication",
    "slot.name": "aha_slot",
    "snapshot.mode": "initial",
    "provide.transaction.metadata": "true",
    "topic.transaction": "transaction",
    "decimal.handling.mode": "string",
    "time.precision.mode": "adaptive_time_microseconds",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "true",
    "value.converter.schemas.enable": "true",
    "tombstones.on.delete": "false",
    "heartbeat.interval.ms": "5000"
  }
}
```

`tombstones.on.delete=false` means a delete is a single event with `op=d`, not a second null-value tombstone. Your consumer then has one shape per delete.

`snapshot.mode=initial` means the first time this connector starts (no stored offset), Debezium writes a snapshot of every included table as `op=r` events, then streams the WAL. Those `op=r` events are how an empty index process builds state. You do not JDBC-bootstrap when `BOOTSTRAP=log`.

`deploy/register-connector.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
curl -sf -X PUT -H 'Content-Type: application/json' \
  --data @deploy/connector.json \
  http://127.0.0.1:18083/connectors/aha-postgres/config
echo
curl -sf http://127.0.0.1:18083/connectors/aha-postgres/status
echo
```

Use `PUT` on `/config` so re-running the script updates instead of failing with "connector already exists".

After registration, topics exist. Inspect them with the Kafka image's console consumer or `kcat` if you have it:

```bash
docker compose -f deploy/docker-compose.yml exec kafka bash -lc \
  'ls /kafka/bin | head'
```

Find the topics tool the image ships (`kafka-topics.sh` or similar) and list topics. You need to see, once the snapshot has run:

- `aha.public.documents`
- `aha.public.acl_entries`
- `aha.public.group_memberships`
- `aha.transaction`

Force one partition. After the topics exist:

```bash
# use the image's kafka-topics.sh path once you have listed it
kafka-topics.sh --bootstrap-server kafka:9092 \
  --alter --topic aha.public.documents --partitions 1
```

You cannot decrease partitions. Create them at 1 before the snapshot by setting the broker default `KAFKA_NUM_PARTITIONS: "1"` in the kafka service environment before the first boot. Add that variable in this commit if the first boot already happened with a higher default, delete the topics and the connector offsets and start again. One partition is a requirement, not a tuning knob. Write that sentence at the top of `connector.json` is illegal in JSON; write it in `deploy/README.md` instead... do not add a second README. Put the sentence as a comment in `register-connector.sh`.

Also set on the broker, before first topic creation:

```yaml
KAFKA_NUM_PARTITIONS: "1"
KAFKA_DEFAULT_REPLICATION_FACTOR: "1"
KAFKA_LOG_RETENTION_MS: "604800000"
```

Seven days of retention is what makes "seek to beginning on restart" possible. If the log expires, an index restart cannot rebuild, and the operator deletes the connector's offset so Debezium snapshots again. Say that in the commit body.

Commit:

```text
feat: register the Postgres connector with transaction metadata

Why:
The index rebuilds by replaying op=r snapshot events and then the
WAL. Transaction markers are what let a document insert and its
grant publish as one snapshot. A single partition is what gives
one consumer a deterministic replay.

Verify:
./deploy/register-connector.sh
topics aha.public.documents, aha.public.acl_entries, aha.transaction exist
```

## Commit 3 — `feat: parse Debezium events and buffer a transaction`

Add `kafka-clients` to the index module.

`io.aha.index.idx.cdc.ChangeEvent` is a sealed interface:

- `SnapshotRead(String topic, long offset, long lsn, long tsMs, String table, JsonNode after)`
- `TransactionBegin(String id, long tsMs)`
- `TransactionEnd(String id, long tsMs, int eventCount)`
- `RowChange(String topic, long offset, long lsn, long tsMs, String table, String op, String txId, long totalOrder, JsonNode before, JsonNode after)`

Parser `DebeziumParser.parse(String topic, long offset, String valueJson)`:

1. Parse JSON. The converter with schemas enabled wraps the record as `{"schema":..., "payload":...}`. Read `payload`. If `payload` is missing, the record itself is the payload (so a test can hand you a bare payload).
2. Transaction topic (`topic.endsWith(".transaction")`): read `payload.status`. `BEGIN` and `END` map to the transaction types. `id` is the string `txId:lsn`. `event_count` is present on `END`.
3. Data topic: `op` is `r`, `c`, `u`, or `d`. `source.lsn` is a long. `source.ts_ms` is a long. `source.table` is the table name. For `r`, there is no transaction object; emit `SnapshotRead`. For `c`/`u`/`d`, read `transaction.id` and `transaction.total_order`. `before` and `after` are objects or null.
4. Ignore `op=t` (truncate) by throwing `IllegalStateException`. You do not truncate these tables. A loud failure is the right behavior.

`TransactionBuffer`:

- `void onSnapshot(SnapshotRead e, Consumer<SnapshotRead> applyNow)` — calls `applyNow` immediately.
- `void onBegin(TransactionBegin e)` — record the id. A second begin for a new id while the previous id is still open means you did not see the previous end. Log an error and drop the incomplete buffer. With one partition and a correct connector this does not happen. The drop is so a bug does not wedge the process forever.
- `void onRow(RowChange e)` — append to the buffer for `e.txId`.
- `void onEnd(TransactionEnd e)` — if `buffer.size() == eventCount`, sort by `totalOrder` and hand the list to the applier, then clear. If the size is smaller, keep the END stored and wait. Rows can theoretically be observed after you have seen END if you ever split topics across threads. You have one thread, and Debezium writes the data topic and the transaction topic independently, so END can arrive before the data events are polled. Store a pending END. When a row arrives, if a pending END exists for that id and the sizes now match, flush. If `buffer.size() > eventCount`, throw. That is a bug in the parser (duplicate offsets). Deduplicate by `(topic, offset)` before buffering: a `Set` of seen offsets per topic, bounded by "we only go forward". If `offset <= lastOffset[topic]`, drop the event.

Keep one JSON fixture under `deploy/fixtures/`, copied in shape from the Debezium documentation's transaction and envelope examples. A `main` in the cdc package reads that fixture, feeds END before the two row events, and prints the rows the applier sees. They are both rows, in `total_order`, once each.

Commit:

```text
feat: parse Debezium events and buffer a transaction

Why:
Data topics and the transaction topic are not one ordered stream.
The buffer holds rows until END says how many belong together, then
releases them in total_order. Snapshot reads have no transaction
and apply immediately.

Verify:
./gradlew :index:compileJava
```

## Commit 4 — `feat: apply a transaction into one published snapshot`

`io.aha.index.idx.cdc.Applier` holds the per-tenant `IndexBuilder`s from phase 2. It is the only writer.

Snapshot read or a flushed transaction calls into the builder:

| table | op | action |
| --- | --- | --- |
| documents | r, c | `addDocument` |
| documents | u | remove old postings for that external id (the builder's `deleteDocument` content path, without dropping grants) then add the new tokens. Split `deleteDocument` so you have `reindexDocument` that only replaces postings and stored text. Grants stay. |
| documents | d | `deleteDocument` |
| acl_entries | r, c | grant user or group based on `principal_type` |
| acl_entries | d | revoke that principal on that doc. `before` holds the primary key. |
| acl_entries | u | treat as delete of `before` plus insert of `after`. You do not update ACL rows in the HTTP API. Handle it anyway. |
| group_memberships | r, c | `addMembership` |
| group_memberships | d | `removeMembership` |
| users, groups, tenants | r, c | ignore for search. They matter only so a snapshot that includes them does not crash the parser. Log at debug. |
| users, groups, tenants | u, d | log a warning and ignore. Deleting a user does not cascade ACL rows in your schema (no FK from `acl_entries.principal_id`). A deleted user with leftover grants still cannot call the API unless the resolver accepts them. Add a test that a search for a user absent from the `users` table returns empty if you want; the visibility SQL already returns empty when there are no grants. Do not block phase 3 on user-delete cascades. |

After a snapshot read, you may publish every N events or on a timer so a huge snapshot becomes queryable only at the end. Do **not** set `ready=true` during snapshot. Publish `ready=false` snapshots if you want metrics. Health reports `ready=false`.

After a flushed transaction, build and `publish` one `CorpusSnapshot` with `ready` unchanged (the consumer controls ready), `appliedLsn` = max LSN in the transaction, `appliedAtMillis` = the END event's `ts_ms`.

`addDocument` on an id that already exists must replace body and postings (replay of `op=r` after `op=c` in tests, and connector restarts). Make `addDocument` idempotent: if the external id exists, call the reindex path and keep the same dense id. Do not assign a second dense id. Test: apply the same snapshot read twice, `docCount()` is 1, search still returns it once.

Grant add is idempotent (`RoaringBitmap.add`). Revoke of a missing bit is idempotent.

Commit:

```text
feat: apply a transaction into one published snapshot

Why:
Readers take a volatile reference. Publishing once per database
transaction means they never see a document whose grant is in
the same commit but not in the snapshot. Re-applying the same
event keeps the same dense id, so replay-from-start does not
duplicate documents.

Verify:
./gradlew :index:compileJava
```

## Commit 5 — `feat: rebuild from the log on start and fail closed when the log is stale`

`io.aha.index.idx.cdc.LogConsumer`:

- Properties: `bootstrap.servers` from `KAFKA_BOOTSTRAP` default `127.0.0.1:19092`. `group.id` from `CONSUMER_GROUP` default `index-replica-a`. `enable.auto.commit=false`. `auto.offset.reset=earliest`. `key.deserializer` and `value.deserializer` are `StringDeserializer`. `isolation.level=read_committed`. `max.poll.interval.ms=300000`.
- Subscribe to the six data topics plus `aha.transaction`.
- `ConsumerRebalanceListener.onPartitionsAssigned`: `consumer.seekToBeginning(partitions)`. Set `ready=false`.
- Loop: `poll(Duration.ofMillis(200))`. Parse, buffer, apply. This loop is one platform thread. Do not hand records to an executor.
- When a poll returns an empty batch and you have been replaying, set `ready=true` and publish the current builders with `ready=true`. A later empty poll must not flip ready back to false.
- `lagMs` is `clock.millis() - lastEventTsMs` when you have seen an event, else `Long.MAX_VALUE` while not ready, else 0 when ready and no event has ever arrived (empty database). `FRESHNESS_SLO_MS` default `30000`. If `lagMs > slo`, `search` throws `IndexNotReadyException` even if `ready` is true. The confirm SQL does not waive this. A stuck consumer must become a 503 so grants cannot be silently missing past the SLO.
- Do not commit offsets. Replay-from-start is the restart story. A comment on the subscribe call states that a committed offset plus empty memory skips revokes.

`IndexMain` when `BOOTSTRAP=log` starts `LogConsumer` on a platform thread and starts HTTP. `BOOTSTRAP=jdbc` stays for tests that do not want Kafka.

`WatermarkTest` does not need Kafka. Drive `lagMs` with the fake clock and a published snapshot whose `appliedAtMillis` is 60 seconds before the clock. `QueryEngine.search` throws `IndexNotReadyException`. A second case: lag of 1 second does not throw.

Change `GrantIsInvisibleUntilReload` only in the next commit, when Kafka is in the test.

Health JSON adds `lagMs`, `ready`, `appliedLsn`, `consumerGroup`.

Commit:

```text
feat: rebuild from the log on start and fail closed when the log is stale

Why:
In-memory state does not survive a restart. Seeking to the
beginning replays snapshot and WAL, which is correct because apply
is idempotent. Serving a replica whose last event is older than
30 seconds would hide grants, so search returns 503.

Verify:
curl -s http://127.0.0.1:18481/v1/health
```

## Commit 6 — `feat: show grant and revoke propagation on the compose log`

`deploy/freshness.sh` runs against the Compose stack. Ports 18480 (oracle writes) and 18481 (index reads).

1. Compose is up, the connector is registered, and the index is `BOOTSTRAP=log` with `FRESHNESS_SLO_MS=30000`.
2. Wait until health `ready=true`, or stop at 60 seconds and treat that as a failed run.
3. The seed is already in Postgres before the connector snapshot, so Ada's search for `quarterly` already returns three hits.
4. `POST` a grant of `d-legal-1` to USER ada through the oracle. Leave the index process alone.
5. Poll the index search every 200ms. `d-legal-1` appears within 30 seconds. The script prints the elapsed milliseconds.
6. Revoke it through the oracle. Poll until `d-legal-1` is gone, within 30 seconds. The confirm path may drop it before the consumer applies the revoke. That still meets the bound.
7. Stop the consumer. Start the index again with `FRESHNESS_SLO_MS=1000` and do not poll Kafka. After 1500ms, search returns 503.

Paste the elapsed times into `docs/benchmarks/phase3.md`.

`--bootstrap=jdbc` still hides a grant until restart. `--bootstrap=log` shows it. The run script's comment says which flag you are using.

Commit:

```text
feat: show grant and revoke propagation on the compose log

Why:
The SLO is a wall-clock bound on the path from a Postgres commit
to a search hit. JDBC bootstrap still hides a new grant until
restart. The log path shows it, and a paused consumer with a
short SLO returns 503.

Verify:
docker compose -f deploy/docker-compose.yml up -d
./deploy/register-connector.sh
./deploy/freshness.sh
```

## Merge

```bash
./gradlew assemble
git checkout main
git merge --ff-only feature/phase-3-cdc-freshness
git tag -a v0.3-freshness -m "Debezium log, transactional apply, 30s fail-closed watermark"
```

Exit bar:

- A grant made through Postgres shows up in index search within 30 seconds without a restart.
- Stopping the consumer makes search return 503 once the SLO passes.
- Ada still never receives the canary.
- Restarting the index process (kill and start `BOOTSTRAP=log`) returns to `ready=true` and the same Ada hits, because it replayed from the beginning.
- No `web/` directory.
