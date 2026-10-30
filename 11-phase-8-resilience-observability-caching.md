# 11 — Phase 8: Failure Handling, Monitoring, Caching

**Goal:** the three "operations" plus-points: fault tolerance (P3), monitoring (P6), caching (P7) — each with proof.
**Decisions:** D-10, D-15, D-17.

## 1. Fault tolerance (P3)

### 1.1 Mechanisms (implement all; each maps to a test in file 12)
| Failure | Mechanism |
|---|---|
| **Duplicate messages** | Idempotency key `(tenant, trip, seq)`; deterministic event/alert ids + `ON CONFLICT DO NOTHING`; admin edits via `Idempotency-Key`. |
| **Out-of-order messages** | Per-trip reorder buffer (bounded size + hold time), late rejection, counters. |
| **Crash** | Checkpointing (D-15), startup recovery, DB-first publishing (D-13), `BUILDING` rows reconciled on boot. |
| **Timeouts** | Hikari `connection-timeout`, JDBC connect/socket timeouts, Postgres `statement_timeout`; `spring.mvc.async.request-timeout`; publisher `timeout` parameter; bounded builder pool with `rejected ⇒ 503`. |
| **Partial failures** | Batch ping endpoint returns per-item results; checkpoint writes are one transaction (all-or-nothing) and idempotent; publish failure leaves old snapshot serving. |
| **Overload** | Bounded shard queues + `429 BACKPRESSURE` with `Retry-After`; admission control on batch size; `@ConcurrencyLimit` (Spring 7) on expensive admin validation/build calls to protect the query path. |
| **Dependency down (DB)** | Pings may be `ACCEPTED` into the single instance's bounded in-memory backlog; `durableAckedSeq` does not advance without checkpoint commit. Readiness reports DEGRADED/OUT_OF_SERVICE when backlog > limit; recovery retries the captured checkpoint and advances durable ack only after commit. Point queries never touch the DB. |
| **Poison data** | Per-ping exceptions are caught at the shard loop, counted (`ping_processing_errors_total`), the trip is not wedged, and the offending ping is dead-lettered to an in-memory ring + log line (opaque ids only). |
| **Shutdown** | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`, drain shards and checkpoint. |

Retries: use Spring Framework 7 `@Retryable` (verify package `org.springframework.resilience.annotation` and `@EnableResilientMethods`) with exponential backoff + jitter and a max attempts of 3 for **idempotent** DB writes only. Count attempts in `retry_total{operation=...}`. If the annotation API differs, write a small `RetryExecutor` class with the same semantics and unit tests (no new dependency).

### 1.2 Failure-injection tests (A1, P3) — at least these
- `BuilderFailureInjectionTest`: builder throws on the Nth publish ⇒ old snapshot serves, `FAILED` row, metric ↑, subsequent publish OK.
- `TripRecoveryFailureTest`: crash/replay equals uninterrupted run (file 08).
- `CheckpointFailureTest`: transient repository failure with retries (file 08).
- `DbOutageIT`: with Testcontainers, **pause/stop the Postgres container** during ping ingestion; assert (a) point queries keep working, (b) pings are accepted until the backlog cap then `429/503`, (c) after unpausing, backlog drains and final totals are correct, (d) readiness flips down and back up.
- `PoisonPingTest`: a ping that makes processing throw does not stop other trips on the same shard.

## 2. Monitoring (P6)

### 2.1 Metrics (Micrometer; Prometheus at `/actuator/prometheus` on port 8081) — exact names
| Metric | Type | Meaning / alert idea |
|---|---|---|
| `ping_ingest_total{result}` | counter | result ∈ accepted,duplicate,rejected — **error/reject rate** |
| `ping_rejected_total{reason}` | counter | reason ∈ invalid,late,clock_skew,backpressure,unknown_trip |
| `ping_duplicate_total`, `ping_out_of_order_total`, `ping_gap_skipped_total` | counters | data quality |
| `ping_queue_depth{shard}` | gauge | **queue depth**; alert if > 80% capacity |
| `ping_processing_lag_seconds` | timer/summary (p50/p95/p99) | processing time − enqueue time; not durability latency |
| `ping_enqueue_to_durable_seconds` | timer/histogram (p50/p95/p99) | enqueue time until the effects are included in a committed checkpoint; the end-to-end ping latency used with durable throughput |
| `ping_event_age_seconds` | summary | `now − eventTime` at processing (producer-side lag) |
| `ping_processing_errors_total` | counter | poison pings |
| `checkpoint_duration_seconds`, `checkpoint_failures_total`, `checkpoint_backlog_pings`, `durable_acked_seq` | timer/counter/gauges | persistence health and durable progress; never tag by trip id |
| `retry_total{operation,outcome}` | counter | **retries** |
| `point_query_seconds` | timer w/ percentile histogram | index latency (record with sampling, e.g. 1/100 on the hot path, to keep overhead negligible) |
| `index_build_seconds`, `index_swap_total`, `index_swap_failures_total`, `zone_set_version` | timer/counters/gauge | live-edit health |
| `snapshot_pinned_count`, `snapshot_historical_loads_total`, `snapshot_cache_*` | gauge/counter | memory/pins |
| `active_trips` | gauge | load |
| `no_go_alerts_total` | counter | business signal |
| `http_server_requests_*` | built-in | **error rate** by status/URI template |
| JVM/GC/threads/Hikari | built-in | standard |
Add a `MeterFilter` that caps tag cardinality; never use tripId/zoneId/employeeId/coordinates as tags.

### 2.2 Structured logging
- JSON logs via Boot structured logging (`logging.structured.format.console`), plus MDC keys: `correlationId`, `tenantId` (opaque UUID), `userId` (opaque UUID), `tripId` (opaque UUID), `shard`.
- Log events at the right level: publish start/success/failure (INFO/ERROR with set versions and durations), checkpoint failures (WARN), backpressure (WARN, rate-limited), startup load (INFO), security denials (WARN without credentials).
- **Never log:** passwords, JWTs, emails, phones, request bodies, coordinates (ping lat/lon), raw usernames. `LogSanitizer` helper for the few cases (hashes). `LogPrivacyTest` greps captured output for banned patterns.
- A request-scoped `MdcFilter` sets/clears MDC.

### 2.3 Runbook (`docs/runbook.md` — "the counters an on-call engineer needs at 3 AM")
A table: symptom → metric(s) → likely cause → action. Cover at least: ping lag rising; queue depth high; 429s; high rejection rate by reason; swap failures; checkpoint failures; readiness down after deploy; memory growth (pinned snapshots); login throttling spikes. Include example PromQL for each (rates over 5 m) and a suggested alert threshold. Provide an optional Grafana dashboard JSON **only if** you can generate valid JSON — otherwise skip.

## 3. Caching (P7)

| Cache | Key | Value | Eviction | What can be stale / bound |
|---|---|---|---|---|
| `zoneLatest` (Caffeine) | `(tenantId, zoneId, capturedSetVersion)` | latest zone DTO from that captured snapshot | `expireAfterWrite=5s`, `maximumSize=50_000`, **evict on publish** | A request captures one local snapshot and uses its version in the cache key; concurrent fills from an old request cannot be read as current after a swap. No cross-instance freshness claim applies (only one instance is supported). |
| `zoneVersion` (Caffeine) | `(tenantId, zoneId, version)` | immutable zone DTO | W-TinyLFU size-bounded | **Never stale** — versions are immutable. |
| `historicalSnapshots` | `(tenantId, setVersion)` | immutable `ZoneSnapshot` | weighted by vertex count (`maximumWeight`), W-TinyLFU; pinned ones held strongly in `PinnedSnapshotRegistry` | **Never stale** (immutable). Memory-bounded. |
| `loginThrottle` | `(tenant,user,ip)` | attempt counter | `expireAfterWrite=5m` | n/a |
| `Latest snapshot` | AtomicReference (not a cache) | snapshot | replaced on local publish | One instance: reads before the atomic swap see the old complete version; reads after see the new complete version. Build duration is measured; there is no other-instance polling bound. |

Explicitly **not cached:** point-query results (index is microseconds; rounding coordinates for cache keys would break D-02 and boundary correctness) — put this reasoning in the README.
Enable `recordStats()` and publish `cache_*` metrics (hit ratio dashboard). Tests: `ZoneCacheTest` (TTL via a `Ticker` fake, evict-on-publish, tenant key isolation), `LatestCacheVersionIsolationTest` (a request holding an old snapshot cannot repopulate a cache entry consumed as latest after publication). Do not simulate a second service instance as supported behavior.

## 4. Trade-offs document (P5) — create `docs/tradeoffs.md`
Must discuss, with the concrete choices made here:
- **Consistency vs availability vs latency:** during a build queries serve the last complete published snapshot (availability + latency win); after local atomic swap they use the new version. Billing consistency is pinned for the trip; no-go safety uses the latest snapshot captured for each ping. DB-first publish prioritizes durability over swap latency; at-least-once plus idempotency avoids exactly-once claims.
- Per-trip single-writer shards (ordering without locks) vs hot-shard risk.
- In-memory snapshots (speed) vs memory O(V) and restart warm-up.
- Late pings rejected rather than retroactively re-billed.
- Haversine + planar containment (speed/simplicity) vs ellipsoidal exactness (state measured error).
- Closed-set boundary rule (D-02) vs "ties are inside both zones" ⇒ priority resolution.
- **Deliberately left out:** Kafka/RabbitMQ ingestion (reference repo used Kafka; here REST batch + bounded queue), gRPC, WebSocket push, PostGIS, Redis, multi-region, multi-instance active service operation (initial deployment is one replica; see D-20), per-point result cache, refresh tokens, user management UI, antimeridian/polar polygons, 3D/elevation, map-matching/snap-to-road, GPS noise filtering/Kalman smoothing, retroactive re-billing, billing rates/currency calculation beyond distance split (rates stored but invoice maths out of scope), horizontal sharding of tenants across nodes.

## 5. Acceptance criteria
- [ ] Every failure row in §1.1 has a named test or an explicit "documented, not tested" note.
- [ ] Prometheus endpoint exposes every metric in §2.1 (an IT scrapes it and asserts presence of each name after exercising the flows).
- [ ] Runbook exists and references real metric names.
- [ ] Cache behaviour and staleness bounds are proven by tests and documented in README.
- [ ] `docs/tradeoffs.md` present.

## Suggested commits
- `feat(resilience): retries, timeouts, backpressure and poison-ping isolation`
- `feat(observability): micrometer metrics, structured logging and MDC`
- `feat(cache): caffeine caches with evict-on-publish and staleness tests`
- `test(failure): builder failure, db outage and poison ping injection`
- `docs: runbook and trade-offs`
