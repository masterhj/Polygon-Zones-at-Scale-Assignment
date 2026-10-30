# 14 — Reference Repositories: What to Borrow (ideas, not code)

Three public repositories were reviewed on 2026-10-31 **from their READMEs only** (source files were not read). They are inspiration for structure and ideas. **Do not copy code.** Licence status as visible on the repo pages:

| Repo | Visible licence | Rule |
|---|---|---|
| `meliharik/realtime_geo_fencing_service` | MIT | Ideas fine; if you ever reuse a snippet, keep the MIT notice. Prefer original code anyway. |
| `davidakpele/egcnetwork` | none shown | **No code reuse** — ideas only. |
| `shrishaanth/Tessera` | none shown ("student project") | **No code reuse** — ideas only. |

If the sandbox has network access you may open the source on GitHub to understand an idea better; still write your own implementation.

## 1. Summary of each repo

### A) `davidakpele/egcnetwork` — "Geofencing Service"
Spring Boot 3.2 / Java 21; PostgreSQL+PostGIS (`ST_Contains` per location update), Redis (latest location, per-user geofence state, dwell timers), Kafka, gRPC, WebSocket, JWT (USER/ADMIN/OPERATOR), Flyway, Docker Compose. Layout: `config/ controller/ grpc/ kafka/ websocket/ service/ repository/ model/{entity,dto,enums}/ security/ exception/ util/`. Detection pipeline: query containing geofences → compare with cached previous state → ENTER/EXIT/DWELL → persist event → create alert → publish.
**Weaknesses for our case:** DB spatial query on every ping (cannot hit 50k/s), no versioning, no tenant isolation, no atomic swap, PII fields in DTOs, heavyweight infrastructure we do not need.

### B) `meliharik/realtime_geo_fencing_service`
Spring Boot 3.2 / Java 17; JTS in-memory point-in-polygon, zones cached in Redis as WKT, PostGIS fallback, rate-limited duplicate alerts, Flyway, Docker Compose, CI workflow, Swagger, benchmark table. Primary/fallback path idea: cache → JTS check, DB on miss. Cache refresh every 30 min.
**Weaknesses:** staleness up to the refresh interval, no polygon validation/versioning/atomic swap, benchmark numbers are unverified claims, no multi-tenancy.

### C) `shrishaanth/Tessera`
Pure Java; Netty ingestion with sharding, **copy-on-write quadtree and immutable snapshots**, HAMT, ray-casting geofence, A* routing, JMH-style benchmark harness with `LatencyRecorder`, CSV output of results comparing Naive/Grid/Quadtree. **Weaknesses:** no auth/versioning/billing; repo commits `node_modules/` and `target/` (don't).

## 2. Borrow map by phase

| Phase | Idea | From | How we adapt it |
|---|---|---|---|
| 1 Bootstrap | Flyway migrations, Docker Compose with healthchecked Postgres, CI workflow | B, A | Same practice with Postgres 18, `compose.yaml` without `version:`, GitHub Actions on Java 25. |
| 1 | Clean `.gitignore`, no build output committed | (anti-pattern in C) | Enforce in `.gitignore` and review. |
| 2 Geometry | Ray-casting point-in-polygon | C (and textbook) | Our own implementation with boundary-first test, half-open rule, ε (D-02/D-03), edge-band index. JTS only as a **test oracle** (B uses JTS in production; we keep core dependency-free per S3). |
| 3 Index | Compare Naive vs Grid vs Quadtree with a harness and CSV output | C | JMH + HdrHistogram harness comparing Naive vs STR R-tree; CSV/JSON in `docs/benchmarks/`; real environment recorded. |
| 3 | Bounding-box prefilter before exact test (GiST idea) | B | Built in: STR R-tree over bboxes + eps-expanded boxes. |
| 4 Swap | **Immutable snapshot + atomic publication; readers never block** | C | `AtomicReference<ZoneSnapshot>` per tenant in a single active service instance; whole-tree rebuild instead of path-copying; DB-first publish. Multi-instance publication needs a separate globally fenced design and is out of scope (D-20). |
| 4 | Cache warm-up at startup | B | Startup snapshot load; readiness gate (instead of 30-minute refresh staleness). |
| 5 Attribution | ENTER/EXIT/DWELL state comparison with previous state; dwell timer; dedupe flag to avoid duplicate alerts | A, B | Deterministic event/alert ids (UUIDv5) + membership trackers; dwell on EXIT; idempotent replays; rate-limit not needed because ids dedupe. |
| 5 | Sharded ingestion by key for ordering | C (shards) | Sharded single-writer executors by `hash(tenant, trip)`. |
| 5 | Alert persistence + unread/ack endpoints | A | `alerts` table, `GET /alerts`, `POST /alerts/{id}/ack`. |
| 6 API | Response envelope + `GlobalExceptionHandler`, controller/service/repository/model/security/exception/util packages | A | Same layering; error model upgraded to RFC 9457 `ProblemDetail`; DTO records (B used Java records). |
| 6 | Swagger/OpenAPI docs | B | Optional: only if a Boot-4-compatible springdoc release is confirmed; otherwise `docs/api.http` + Postman collection (preferred, zero risk). |
| 7 Security | JWT access tokens, roles USER/ADMIN/OPERATOR, device/refresh handling | A | Roles renamed EMPLOYEE/ADMIN/OPERATOR; Spring Resource Server instead of JJWT (A documents JJWT version-mismatch errors); **add tenant isolation** (A lacks it); no refresh tokens (out of scope). |
| 8 Ops | Redis state cache for enter/exit | A, B | **Not** adopted: in-process state + checkpoints (no Redis dependency, lower latency, simpler one-command run); initial service is one active instance (D-20), documented in trade-offs. |
| 8 | Actuator health/metrics | A, B | Micrometer metrics + Prometheus + readiness gating on snapshot load. |
| 8 | Rate limiting duplicate alerts (B: 99.7 % fewer DB writes) | B | Replaced with idempotent ids + `ON CONFLICT DO NOTHING`. |
| 9 Testing | Test scenarios document with inside/outside/boundary/cache/rate-limit cases | B | `docs/test-matrix.md` + named tests with requirement IDs. |
| 9 | Benchmarks as a table in README | B, C | Same presentation, **measured** figures only, with machine details. |
| 10 Docs | Quick start, architecture ASCII diagram, API reference, "Common errors & fixes" table | A, B | README + design note (Mermaid) + `docs/troubleshooting.md` with the real issues you hit. |
| 10 | Docs site / live docs | B | Out of scope. |

## 3. Deliberately **not** borrowed
Kafka, gRPC, WebSocket/STOMP, PostGIS `ST_Contains` per ping, Redis geometry cache, A* routing/OSM graph, Swing/Leaflet demo UIs, custom binary wire protocol. Mention these in `docs/tradeoffs.md` as conscious omissions with one-line reasons (scope, one-command run, latency, operational cost).
