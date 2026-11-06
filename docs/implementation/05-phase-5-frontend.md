# Phase 5 — Frontend

Start this phase only after `git tag --list 'v0.4-slo'` prints `v0.4-slo`. The backend SLO is already proven without a browser. The UI is how a person sees that proof.

Every commit opens with what it changes, why that change stands alone, and the significance for the demo.

Branch: `feature/web-search-console`

Tag: `v0.5-console`

```bash
git checkout main
git checkout -b feature/web-search-console
```

## Commit 1 — `chore: scaffold the Next.js console`

**What.** A Next.js app in `web/`, TypeScript, Tailwind, and the shadcn primitives the later commits use. No search behavior yet. Node arrives in this phase.

**Why.** The console is a harness and a latency instrument. It waits until `v0.4-slo` so the UI is built against a backend that already meets the bound, and so earlier phases stay Java, Postgres, Kafka, and Debezium.

**Significance.** The pages that follow share one app router and one set of primitives. A second frontend, or a `src/` layout invented mid-phase, splits the switcher, the search page, and the telemetry card into components that do not share principal state.

Install Node 22 LTS. `node -v` prints v22.

From the repo root:

```bash
npx create-next-app@latest web \
  --typescript \
  --tailwind \
  --eslint \
  --app \
  --no-src-dir \
  --import-alias "@/*" \
  --use-npm \
  --turbopack
```

If the installed `create-next-app` rejects `--turbopack` or `--no-src-dir`, drop the rejected flag and rerun. Keep the app router at `web/app`. If the scaffolder offers a test runner, skip it, and delete any generated `*.test.*` or `__tests__` directory before the commit. This console has no test suite.

Then:

```bash
cd web
npx shadcn@latest init
npx shadcn@latest add button input card badge sheet select label
```

`init` will ask questions. Answers: TypeScript already set, style `new-york` if offered, base color `neutral`, CSS variables yes, no RSC-incompatible experiments. If a prompt has no bearing on this list, accept the default.

Add `"dev": "next dev --port 43123"` in `web/package.json` scripts if the scaffold does not already bind a port. The console always uses port **43123**.

`.gitignore` at the repo root already ignores `web/node_modules/` and `web/.next/`. Confirm `web/.gitignore` also ignores those. Do not commit `node_modules`.

`web/README.md` is five lines: what the console is, `npm install`, `npm run dev`, the URL `http://127.0.0.1:43123`, and the sentence "The coordinator must already be on port 18483."

Commit the scaffold only after `npm run build` succeeds once.

```text
chore: scaffold the Next.js console

Why:
The UI is a separate phase so the search SLO does not depend on
a browser. The port is 43123 to stay off the coordinator and the
replicas.

Verify:
cd web && npm run build
```

## Commit 2 — `feat: add a principal switcher that sends demo headers`

**What.** Two selects, tenant then user, filled from `GET /v1/directory`. The choice lives in `sessionStorage`. Changing it replaces the demo headers, clears hits, and aborts an in-flight search. The coordinator emits CORS only for the console's origin.

**Why.** Demo mode is a harness. The switcher is the only UI that sets `X-Tenant-Id` and `X-User-Id`. Users shown are the directory entries for the selected tenant. The server still checks tenant and membership on every request; the client filter is hygiene.

**Significance.** A slow response for Ada must not paint after you have switched to Ivan. Clearing the list in this commit, and aborting the request in the next, is how the console avoids showing one user's hits under another user's name.

The coordinator in demo mode trusts `X-Tenant-Id` and `X-User-Id`. The switcher is the only place the browser sets them.

`web/lib/principal.ts`:

- `Principal = { tenantId: string; userId: string }`
- `loadPrincipal()` reads `sessionStorage` key `aha.principal`. Returns null when absent or malformed.
- `savePrincipal(p)` writes it.
- `clearResultsCache()` is a callback the search module registers. The switcher calls it. Do not import the cache into this file and the switcher into the cache in a cycle. Put the cache in `web/lib/search-cache.ts` and call `searchCache.clear()` from the switcher.

`web/lib/api.ts`:

```ts
export const COORDINATOR = process.env.NEXT_PUBLIC_COORDINATOR_URL ?? "http://127.0.0.1:18483";

export function principalHeaders(p: Principal): Headers {
  const h = new Headers();
  h.set("X-Tenant-Id", p.tenantId);
  h.set("X-User-Id", p.userId);
  h.set("Accept", "application/json");
  return h;
}
```

`GET ${COORDINATOR}/v1/directory` fills the two shadcn `Select`s. Tenant first. When the tenant changes, the user select resets to the first user of that tenant, the cache clears, and any in-flight search is aborted (the abort registry comes in the next commit; call the function even if it is a no-op stub exported from `web/lib/inflight.ts` as `abortInflight()`).

Users shown are only the directory entries for the selected tenant. The client does not invent user ids.

On first load with an empty session, select `acme` / `ada` if the directory contains them, otherwise the first tenant and first user, and save.

The page header shows the display name and the tenant id in a `Badge`. Changing the user clears the hit list immediately, before the next search returns. A slow response for Ada must not paint after you have switched to Ivan. That is the next commit's job; in this commit, clear the list synchronously inside the select handler so a late response has nowhere correct to land once the abort exists.

CORS: the browser calls the coordinator directly. Add to the coordinator, in this same commit (this is a backend edit on the frontend branch, and that is allowed because the browser cannot function without it):

- On every response, if `Origin` is `http://127.0.0.1:43123` or `http://localhost:43123`, set `Access-Control-Allow-Origin` to that origin, `Access-Control-Allow-Headers` to `Content-Type, X-Tenant-Id, X-User-Id, X-Request-Id`, `Access-Control-Allow-Methods` to `GET, POST, OPTIONS`, `Access-Control-Expose-Headers` to `Server-Timing, X-Replica, X-Hedged`.
- `OPTIONS` returns 204.
- Any other origin gets no CORS headers.

`curl -D -` an allowed origin and a second origin. The allowed origin receives `Access-Control-Allow-Origin`. The other origin receives no allow-origin header.

Commit:

```text
feat: add a principal switcher that sends demo headers

Why:
Demo mode is a harness. The switcher is the only UI that sets the
two headers, the cache clears on change, and the coordinator only
emits CORS for the console's origin.

Verify:
curl -sD - -o /dev/null -H 'Origin: http://127.0.0.1:43123' -X OPTIONS http://127.0.0.1:18483/v1/search
curl -sD - -o /dev/null -H 'Origin: https://evil.example' -X OPTIONS http://127.0.0.1:18483/v1/search
cd web && npm run build
```

Manual check, written into the commit body under `Verify:` as well: load the page, pick Ivan, see the badge change. You will not have search results yet.

## Commit 3 — `feat: debounce search input and abort the previous request`

**What.** The input updates on every keystroke. A 120ms debounce feeds the query that is fetched. Each fetch gets an `AbortController`. A new query, a principal change, or an unmount aborts the previous one. Aborted responses are not written into state.

**Why.** The person sees the characters they typed. The request waits long enough to avoid a fetch per key, and short enough that a deliberate pause still searches. Aborting is what stops a slow query for Ada from landing after the switch to Ivan.

**Significance.** This is the client half of the isolation the server already enforces. A cache key without tenant and user, or a 503 replaced by a cached hit list, is the client-side version of serving a stale allow-set.

`web/app/page.tsx` is the console. One column on a narrow viewport. On `min-width: 768px`, the result list and the telemetry card (empty for now) sit in a two-column grid.

Search box: shadcn `Input`. The typed value updates on every keystroke. Do not debounce the controlled value. Debounce a copy:

```ts
const [raw, setRaw] = useState("");
const [debounced, setDebounced] = useState("");

useEffect(() => {
  const id = setTimeout(() => setDebounced(raw), 120);
  return () => clearTimeout(id);
}, [raw]);
```

Ignore updates that arrive during IME composition. `onCompositionStart` sets `composing` true, `onCompositionEnd` sets it false and only then allows the debounce effect to run. A simple way: the effect returns early while `composing` is true.

Fetch effect, depending on `[debounced, principal]`:

```ts
useEffect(() => {
  if (!principal) return;
  const tokens = debounced.trim();
  if (!tokens) {
    setHits(null);
    setError(null);
    return;
  }
  const controller = new AbortController();
  registerInflight(controller);
  let cancelled = false;
  (async () => {
    try {
      const res = await fetch(
        `${COORDINATOR}/v1/search?q=${encodeURIComponent(debounced)}`,
        { headers: principalHeaders(principal), signal: controller.signal },
      );
      const body = await res.json();
      if (cancelled) return;
      if (res.status === 503) {
        setHits(null);
        setError(body.error ?? "Index is behind the permission log");
        return;
      }
      if (!res.ok) {
        setHits(null);
        setError(body.error ?? "Search failed");
        return;
      }
      setError(null);
      setHits(body.hits);
    } catch (err) {
      if ((err as Error).name === "AbortError") return;
      if (!cancelled) setError("Network error");
    }
  })();
  return () => {
    cancelled = true;
    controller.abort();
  };
}, [debounced, principal]);
```

`registerInflight` keeps the latest controller in a module-level variable and aborts the previous one. `abortInflight` from the switcher calls it too.

Empty `debounced`: do not fetch. Show "Type a query. Results are limited to documents this user can read." Empty `hits` array with a 200: "No readable documents match." Error state: the message, and do not keep showing the previous user's hits. You already cleared them.

`web/lib/search-cache.ts`:

- `Map` keyed by `` `${tenant}\0${user}\0${normalized}`. ``
- `normalized` is `debounced.trim().toLowerCase().replace(/\s+/g, " ")`.
- Value: `{ hits, at }`.
- `get` returns the entry when `Date.now() - at < 5000`, else deletes it and returns null.
- Max 50 entries. On insert past 50, delete the oldest `at`.
- `clear()` drops the map. The switcher calls it.

The fetch effect checks the cache first. A hit paints immediately and still revalidates in the background when the entry is older than 5 seconds... the `get` above already expires at 5 seconds, so a cache hit is always fresh enough and you do not revalidate. Keep the implementation that simple. Do not serve a cache entry when the latest response was 503. `clear()` on 503 as well, so a fail-closed index cannot be masked by a 4-second-old allow list.

Commit:

```text
feat: debounce search input and abort the previous request

Why:
The input stays responsive while the request waits 120ms.
Aborting on a new query or a new principal is what stops Ada's
hits from painting after the switch to Ivan. A 503 clears the
cache so a stale allow-list cannot cover a fail-closed index.

Verify:
cd web && npm run build
```

Manual: type `quarterly` as Ada. You see `d-ada-1`, `d-eng-1`, `d-shared`, and you do not see `d-canary`. Switch to Ivan before a slow response returns (set `delayMs` to 800 on both replicas if you need the race). The list does not flash Ada's titles under Ivan's badge. Switch back. Ivan's search for `quarterly` shows the canary first.

## Commit 4 — `feat: show server time against the 100ms line`

**What.** A telemetry card reads `Server-Timing` and `performance.now()`. It shows server milliseconds, network milliseconds, replica, hedge, and lag, plus p50 and p99 of the last 50 server samples for this principal, with a mark at 100ms.

**Why.** Client RTT includes the laptop network. The SLO line is server time, which is the sum of the `Server-Timing` durations. Showing both stops a slow network from being told as an index miss. Samples reset when the principal changes so one user's latency is not reported as another's.

**Significance.** The number a demo quotes is the p99 on this card, from the same `coord` metric the load generator used. A chart of `performance.now()` alone measures a different thing than `docs/benchmarks/phase4.md`.

`web/lib/server-timing.ts`:

```ts
export function parseServerTiming(header: string | null): Record<string, number> {
  const out: Record<string, number> = {};
  if (!header) return out;
  for (const part of header.split(",")) {
    const bits = part.trim().split(";");
    const name = bits[0]?.trim();
    if (!name) continue;
    for (const param of bits.slice(1)) {
      const [k, v] = param.trim().split("=");
      if (k === "dur") out[name] = Number(v);
    }
  }
  return out;
}
```

The fetch path records `const t0 = performance.now()` before `fetch` and `const rtt = performance.now() - t0` after the body is parsed. `serverMs` is the `coord` duration from `Server-Timing`. `networkMs` is `Math.max(0, rtt - serverMs)` when `coord` is present, else `rtt`.

`web/components/telemetry-card.tsx` is a shadcn `Card`, pinned to the right column on desktop and stacked under the results below 768px. It shows, for the current principal only:

- Last query: server ms, network ms, replica (`X-Replica`), hedged (`X-Hedged`), lag (`lag` metric).
- A 100ms mark. The last server sample uses a green text class at or under 100 and an amber class above. Use Tailwind classes, not inline hex, so dark mode stays readable. Compare against `serverMs`, never against `rtt`.
- The last 50 server samples for this principal. p50 and p99 computed in the browser with the same index rule as the load generator: sort a copy, index `Math.ceil(0.99 * n) - 1`.
- Hedge count in those 50.

Reset the sample array inside the principal effect. Ada's samples are not Ivan's p99.

A 503 still records the server sample when `coord` is present, and the card shows the error. Do not drop the sample. A fail-closed response is part of the latency story.

Commit:

```text
feat: show server time against the 100ms line

Why:
The SLO is coordinator time, which Server-Timing already reports
as coord. The card quotes that number and keeps round-trip time
beside it so a slow laptop network is not mistaken for a slow index.

Verify:
cd web && npm run build
```

Manual: with `index-a` delayed 400ms and `index-b` healthy, search as Ada. The card shows a hedged request, the winner is `index-b`, and server ms is at or under 100 on a warm coordinator. If it is not, believe the card and go back to phase 4. Do not "fix" the card's threshold.

## Commit 5 — `feat: grant, revoke, and measure propagation from the console`

**What.** A permissions sheet grants or revokes the canary for the active user, then re-issues the search until the result flips or 30 seconds pass. It displays the elapsed time. A 503 clears the cached hits and shows the fail-closed message.

**Why.** Phase 3 proved propagation with a shell script. This commit makes the same bound visible: a grant or revoke committed through the coordinator, then a search that changes, with the wait on screen. Cached hits stay on screen through a 503 only if the console is willing to show a list the server just refused.

**Significance.** The freshness sentence of the spec is something a person can watch. The probe uses its own abort controller and stops if the principal changes mid-wait, so Ivan's revoke probe cannot update Ada's results.

A shadcn `Sheet` titled "Permissions", triggered by a `Button` "Edit access".

Fields:

- Document id, a `Select` of the ids you know from the seed: `d-eng-1`, `d-legal-1`, `d-ada-1`, `d-canary`, `d-shared`, `d-orphan`, `d-ben-1`, `d-globex-1`.
- Action: Grant or Revoke, for the **active user**, principal type USER.

Submit `POST ${COORDINATOR}/v1/acl` with the principal headers and JSON `{ docId, principalType: "USER", principalId: principal.userId, op }`.

Then run the probe:

1. Remember whether the doc id was in the current hit list.
2. Clear the search cache.
3. Reissue the current debounced query every 200ms with its own `AbortController`.
4. Stop when the presence of `docId` in `hits` flips to the expected state (present after grant, absent after revoke), or when 30 seconds have passed.
5. The sheet shows `Grant visible in 1.8s` or `Revoke visible in 0.4s` or `Exceeded 30s`. Use one decimal second.
6. Abort the probe if the principal changes or the sheet closes.

A revoke of a document the user could not see stays "absent" and would succeed instantly for the wrong reason. The sheet therefore requires the current query to be `quarterly`, and the copy under the button says so: "Run this with the query 'quarterly' so the document is in the match set."

If the query is not `quarterly`, the probe does not start and the sheet says why.

The canary check for a demo script you run by hand:

- As Ivan, query `quarterly`, revoke `d-canary`, probe reports the revoke, the hit disappears, the telemetry card still has no error.
- As Ada, grant `d-canary`, probe reports the grant, the hit appears. Revoke it before you commit, or revoke it and leave the seed repair in the commit message. Do not commit a dirty database. Re-run `CorpusSeeder` or `docker compose down -v` is too destructive if you care about the Kafka slot. Prefer a revoke that puts the row back, and assert the seed grant exists at the end of the manual script. The seeder is idempotent; `SEED=true` on a one-shot oracle start repairs rows. Run that.

Commit:

```text
feat: grant, revoke, and measure propagation from the console

Why:
The 30 second bound is visible here without reading logs. The probe
uses the same search route as a person, aborts on principal change,
and refuses to run when the query would not match the document.

Verify:
cd web && npm run build
manual grant and revoke of d-canary as Ivan, seed restored
```

## Commit 6 — `feat: toggle a slow replica from the console`

**What.** A switch posts `delayMs: 400` for `index-a`, and posts `0` when it is turned off. The telemetry card is where the effect shows up: the primary changes, hedges increase, and server p99 stays at or under 100ms.

**Why.** The fault route already exists for the load generator. The switch is how a person turns that fault on during a demo and reads the result on the same card as a normal search.

**Significance.** This is the "one replica is slow" sentence, visible. A toggle that only changes a label, without the fault call, demonstrates nothing about hedging.

A switch "Delay index-a by 400ms". It `POST`s `/admin/faults/replica` through the coordinator. Turning it off posts `delayMs: 0`.

If the coordinator returns 404, the card shows "Fault injection is off. Start the coordinator with FAULTS_ENABLED=true." The switch does not pretend it worked.

After a successful toggle, the next search's telemetry row is the evidence: hedged true, replica `index-b`, server time at or under 100ms.

Commit:

```text
feat: toggle a slow replica from the console

Why:
The SLO scenario has to be something you can turn on while looking
at the card. The toggle calls the same admin route the load generator
calls. A 404 stays visible so a coordinator that booted without
the flag does not look like a hedge failure.

Verify:
cd web && npm run build
toggle on, search, card shows index-b and server time at or under 100ms
```

## Commit 7 — `docs: write the demo script`

**What.** `docs/demo.md` is the ten-step script for a walkthrough: who Ada and Ivan are, the canary, a grant and a revoke with the measured delay, the slow-replica switch, and the 100ms line. It includes a pass at 375px wide.

**Why.** The console has a lot of state. The script is the order that shows isolation, freshness, and the SLO without skipping the step where those claims can fail.

**Significance.** Tag `v0.5-console` means this script has been run once, including the narrow window. A UI that looks right and has not been walked through in that order can still paint the wrong user's hits or hide a 503 behind a cache.

`docs/demo.md` is the script you follow in a portfolio walkthrough. Ten steps, each one sentence plus the URL or command:

1. Start compose, register the connector, start the coordinator with faults enabled, start `npm run dev` in `web`.
2. Open `http://127.0.0.1:43123`.
3. Ada, query `quarterly`. Name the three hits. Point at the canary's absence.
4. Switch to Ivan. The canary is first. Point at the sample reset on the card.
5. Switch tenant to Globex as Dana. Ada's documents are gone. `d-globex-1` matches.
6. Back to Ada. Open Permissions, grant `d-legal-1`, read the propagation line.
7. Revoke it. Read the propagation line.
8. Turn on the 400ms delay. Search again. Read replica and server time.
9. Stop `index-b`. Search. You either get a slow success or a 503. You do not get the canary.
10. Start `index-b`, turn the delay off.

Commit:

```text
docs: write the demo script

Why:
The walkthrough is the thing a reader of the repo can run without
reconstructing the SLO from the commit messages.

Verify:
read docs/demo.md against a live stack once
```

## Merge

```bash
git checkout main
git merge --ff-only feature/web-search-console
git tag -a v0.5-console -m "Search console: principal switcher, aborted queries, 100ms card"
```

Exit bar:

- `npm run build` succeeds.
- The manual script in `docs/demo.md` has been run once on your machine.
- Desktop and a 375px-wide window both usable: at 375px the card is under the results, not on top of them. Check with the browser's device mode.
- `v0.1-naive` through `v0.5-console` all exist on `main`.
