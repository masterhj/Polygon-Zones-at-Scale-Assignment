# 07 — Phase 4: Versioning, Atomic Index Swap, Pinned Snapshots

**Goal:** every zone edit creates a new immutable version; the tenant's index is rebuilt in the background and swapped atomically; old versions stay queryable; trips pin a version per D-09.
**Requirements:** R1.4, R1.5, R4.1, R4.2, K3, EX2. **Decisions:** D-08, D-09, D-13, D-17.

## 1. Core pieces (`zone-core` + `zone-service/publish`)

### In `zone-core`
- `ZoneSnapshot` already immutable (phase 3). Add `ZoneSnapshot.diff(prev)` (added/changed/removed ids) for logging/metrics.
- `SnapshotBuilder` must be a pure function: `(tenantId, setVersion, zones) -> snapshot` (no I/O).

### In `zone-service`
- `SnapshotRegistry`:
  - `ConcurrentHashMap<UUID tenantId, AtomicReference<ZoneSnapshot>> latest`.
  - `ZoneSnapshot latest(UUID tenantId)` → **single `get()`**; callers must capture the reference once per request/batch and never re-read mid-operation.
  - `void publish(UUID tenantId, ZoneSnapshot next)` uses `updateAndGet`/`compareAndSet` with a monotonicity guard (`next.setVersion > current.setVersion` else ignore + log + metric).
- `SnapshotLoader.loadPublished(tenant, N)`: rebuilds only a `PUBLISHED` snapshot from zone versions whose owning set is `PUBLISHED`, at or before N; choose the latest per zone and then exclude tombstones. The requested N itself must be `PUBLISHED`.
- `SnapshotLoader.loadCandidate(tenant, N)`: publisher-only path; compose earlier `PUBLISHED` sets plus edits in exactly N while N is `BUILDING`. It must exclude every other `BUILDING` or `FAILED` set. This prevents failed/unfinished edits from leaking into historical versions.
- `PinnedSnapshotRegistry`:
  - `ZoneSnapshot acquire(tenantId, setVersion)` / `void release(tenantId, setVersion)` with reference counts; a snapshot with count > 0 is held strongly; count == 0 moves to a **Caffeine** cache bounded by total vertex weight (`zones.cache.historical-snapshots-max-vertices`, W-TinyLFU eviction); a miss reloads via `SnapshotLoader`.
  - Latest snapshot is also reachable here (no duplicate memory: same object reference).
- `ZoneSetPublisher`:
  - One **single-writer lane per tenant** (`ConcurrentHashMap<UUID, ReentrantLock>` or a per-tenant single-thread executor; choose one and justify). Hold the lane from allocation through build, `PUBLISHED` commit, and local swap. Different tenants publish in parallel. This is process-local and relies on D-20's one-active-instance deployment constraint.
  - `PublishResult publish(tenantId, EditCommand cmd, Duration timeout)` following D-13 steps 1–5 exactly:
    1. Validate (`PolygonValidator` ⇒ 422 with all issues) before a DB transaction.
    2. tx1: lock the tenant row (`SELECT ... FOR UPDATE`), allocate `nextSetVersion` greater than every existing status (including failed versions), and insert `zones`, `zone_versions`, and `zone_set_versions(status=BUILDING)` atomically.
    3. Build the candidate snapshot **outside every transaction** on a bounded builder executor using `loadCandidate`, recording `index_build_seconds`.
    4. tx2: set `PUBLISHED` and `published_at`; commit is the durable publication point.
    5. Atomically advance the local registry to the higher version, invalidate/version-scope caches, record `index_swap_total`, and update `zone_set_version`.
  - On build or pre-commit publication failure: tx3 marks `FAILED`; `index_swap_failures_total++`; throw `PublishFailedException` (no pointer change). Once tx2 commits, do not mark the set failed; if the process stops before local swap, it is unavailable and startup loads the latest published set.
  - Edit **idempotency**: the admin API accepts an optional `Idempotency-Key` header; a repeated key with identical body returns the original result, with different body ⇒ 409 (store in `zone_set_versions` or a small table).
  - **Coalescing (SHOULD):** if several edits queue while one is building, the next build may include all queued edits as separate zone versions but one set version per edit is still recorded (keep it simple: no coalescing is acceptable; if you add it, document and test).
- `StartupSnapshotLoader` (`ApplicationRunner`/`SmartLifecycle`): for each tenant, mark leftover `BUILDING` sets `FAILED`, load the latest `PUBLISHED` set and register it. Readiness (`HealthIndicator`/`AvailabilityState`) turns UP only after all tenants loaded; liveness independent.
- **Multi-instance watcher is out of scope.** The initial deployment runs exactly one active service instance (D-20). Do not claim cross-instance staleness bounds or run a polling watcher as a substitute for globally ordered publication. A future multi-instance design needs a lease/fencing token or equivalent global publication order plus durable notification/reconciliation and tests.

## 2. Trip pinning (D-09)
- `TripService.start`: reads `registry.latest(tenant)` **once**, stores `pinned_set_version` in `trips`, calls `PinnedSnapshotRegistry.acquire`.
- `TripService.complete/abort` and the pipeline on shutdown: `release`.
- After restart, the trip processor re-acquires the pinned snapshot through the loader.
- Expose `pinnedSetVersion` and `evaluatedSetVersion` on trip/alert/event responses.

## 3. Historical queries (R1.5)
- `GET /zones/{id}?version=n`, `GET /zones/{id}/versions`, `POST /zones/query {asOfSetVersion}` — the latter acquires a PUBLISHED historical snapshot from `PinnedSnapshotRegistry`/`loadPublished`; missing, BUILDING, FAILED, or foreign-tenant set ⇒ `404 VERSION_NOT_FOUND`.
- Tests: create zone (v1), edit twice, then query a point that is inside v1 but outside v3 at each `asOfSetVersion`, asserting the correct membership; tombstone behaviour (deleted in v4 ⇒ absent in set 4, present in set 3).

## 4. Required tests (K3, R4.1, R4.2, EX2)

### 4.1 `SnapshotSwapConcurrencyTest` (pure core + registry; no Spring needed — K3)
Design so a **torn read is detectable**:
- Create zone sets where **version k** consists of a known set `Z_k` of N zones all containing a fixed probe point `P`, and each zone carries `setVersion=k` in its metadata and zone ids `zone-k-0..N-1`.
- 16 reader threads loop: `s = registry.latest(); hits = s.index.query(P)`; assert **all hits belong to `s.setVersion`** and `hits.size() == N`. Also assert per-thread observed versions are **non-decreasing**.
- 1 writer thread publishes versions 2..200 as fast as it can (each a full `SnapshotBuilder.build`).
- Run ≥ 10 s (or until 200 publishes); use `CountDownLatch`/`Phaser`, not sleeps. Fail on any mixed-version observation, exception, or version regression. Include `-ea`.
- Variant: a **slow build** (inject a delay) proves readers continue to be served from the old snapshot during the build and the swap itself is O(1).

### 4.2 `NoLatencySpikeDuringRebuildTest` (EX2 "query latency does not spike")
- Using the load harness from phase 3 at a modest rate (e.g. 20k qps for 10 s) on a 5,000-polygon snapshot, rebuild+swap every 500 ms in the background.
- Record p50/p99 in the baseline window vs the rebuild window. Assert `p99_rebuild <= max(3 × p99_baseline, p99_baseline + 2 ms)` (thresholds configurable; mark the test `@Tag("perf")` and run in `verify` but allow `-DskipPerf`). Always write the actual numbers into `target/perf-report.txt` and copy them to `docs/benchmarks/` when you run the official benchmark.

### 4.3 `LiveEditIT` (Spring + Testcontainers)
- Start service, create tenant/admin, create zones, query point (inside), PUT shrink, query again → outside; check `setVersion` incremented and the old version still returns the old answer via `asOfSetVersion`.
- **EX2 scenario** with a controllable `Clock`:
  1. Set clock 20:50 (tenant tz); start trip T (pins V).
  2. Send pings inside the no-go zone's *old* area but outside the *new* area; assert **no alert** if the alert condition uses the latest set after the edit, and assert the billing distances for T still use V.
  3. Advance clock to 21:00, admin shrinks no-go zone ⇒ V+1 published.
  4. Send more pings: those inside only the old shape produce **no new no-go ENTER** (evaluated on V+1); trip T's `pinnedSetVersion` is still V and attribution of billing zones unchanged.
  5. Start trip T2 after the swap ⇒ pins V+1.
- Failure: inject an exception in the builder for one edit ⇒ response `500 PUBLISH_FAILED`, `GET /zone-sets` shows `FAILED`, latest unchanged, queries still served, metric incremented; next edit receives a version greater than every prior row (gaps are allowed; versions must be strictly increasing).
- Restart test: stop the app context after publish, start a new context on the same DB, assert latest snapshot version equals last PUBLISHED and the in-progress trip's pinned snapshot is reloadable.

### 4.4 Publisher unit tests
- Two admins edit the same tenant concurrently (2 threads × 20 edits): set versions are unique, strictly increasing, final snapshot contains all successful edits; no deadlock. Verify that the lane remains held across the build and publication boundary.
- Two tenants editing concurrently never block each other (assert interleaving via timestamps/latches).

## 5. Acceptance criteria
- [ ] Concurrency swap test passes repeatedly (run it 20× in CI loop locally: `for i in $(seq 20); do ./mvnw -q -pl zone-core -Dtest=SnapshotSwapConcurrencyTest test || break; done`).
- [ ] DB is the source of truth: kill/restart reproduces state.
- [ ] Old versions queryable for single zones and point queries.
- [ ] D-09 behaviour demonstrated by a named, readable test (`TripPinningRuleIT`).
- [ ] Metrics exist: `index_build_seconds` (timer), `index_swap_total`, `index_swap_failures_total`, `zone_set_version` gauge (opaque tenant tag only under the cardinality cap); all results refer to this one active instance.
- [ ] A failed or BUILDING version is never composed into an as-of query; candidate build is the only path allowed to include its own BUILDING set.

## Suggested commits
- `feat(publish): snapshot registry with atomic latest reference`
- `feat(publish): zone set publisher with single-writer lane and DB-first commit`
- `feat(publish): pinned and historical snapshot registry with weighted eviction`
- `test(publish): torn-read concurrency test and no-latency-spike test`
- `test(it): live edit and trip pinning integration tests incl. build failure`
