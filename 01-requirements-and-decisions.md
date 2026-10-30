# 01 — Requirements and Binding Decisions

Source: *MoveInSync Backend Case Study 2026 — Case Study 7: Polygon Zones at Scale – Tariff Zones, No-Go Areas & Trip Attribution* (Advanced Track, IIIT & NIT).

## Part A — Requirements from the PDF (IDs are used in tests and the final checklist)

### I. Polygon model
| ID | Requirement |
|----|-------------|
| R1.1 | Reject polygons that are **not closed**. |
| R1.2 | Reject polygons that **self-intersect**. |
| R1.3 | Reject polygons whose **holes lie outside the outer ring**. |
| R1.4 | **Every edit creates a new version.** |
| R1.5 | **Old versions remain queryable.** |

### II. Point queries at speed
| ID | Requirement |
|----|-------------|
| R2.1 | Point-in-polygon is **correct for non-convex shapes and holes**. |
| R2.2 | A **defined rule** for points exactly on an edge or vertex. |
| R2.3 | **Spatial index** (R-tree, grid, or geohash cells) with **5,000 polygons and 50,000 pings/sec**; do not test every polygon per ping; **explain the choice**. |

### III. Trip attribution
| ID | Requirement |
|----|-------------|
| R3.1 | **Distance by zone:** for a trip path, compute km inside each tariff zone, **splitting segments exactly where they cross zone boundaries**. |
| R3.2 | Emit zone **entry and exit events with dwell time**. |
| R3.3 | Raise a **no-go alert** when a **night trip** enters a no-go zone. |

### IV. Live edits
| ID | Requirement |
|----|-------------|
| R4.1 | **No downtime:** rebuild the index in the background and **swap atomically**; queries never see a half-built index. |
| R4.2 | **Trip consistency:** define which polygon version applies to a trip in progress when a zone is edited, and apply it consistently. |

### Example scenarios that must be reproducible as automated tests
| ID | Scenario |
|----|----------|
| EX1 | An airport drop of **42 km** is billed as **30.4 km city + 11.6 km airport**, with the boundary crossing at the toll plaza. |
| EX2 | An admin **shrinks a no-go zone at 9:00 PM**. Pings after the swap use the new shape; a trip that **started at 8:50 PM** follows the rule defined in D-09; **query latency does not spike** during the rebuild. |

### Key points
| ID | Requirement |
|----|-------------|
| K1 | State the **projection / distance method** and the **error** it introduces (lat/lon is not a flat plane). |
| K2 | **Explicit floating-point tolerance** on boundaries. Tests for points **on edges, on vertices, inside holes**. |
| K3 | A **test that queries continuously during an edit** (index swap is a concurrency problem). |
| K4 | **Benchmark** point queries/sec against 5,000 polygons; report **index build time and memory**. |

### Plus points (Overall Evaluation Criteria)
| ID | Requirement |
|----|-------------|
| P1 | Authentication, role-based access (**employee / admin / operator**), **tenant isolation** (one client can never read another's data). |
| P2 | Time/space **complexity analysis** + small benchmark; **state where the system stops scaling and why**. |
| P3 | **Failure handling**: crashes, timeouts, duplicate and out-of-order messages, partial failures; at least one **failure-injection test**. |
| P4 | **OOP**: Java preferred; encapsulation, inheritance, polymorphism; clean controller/service/repository layering. |
| P5 | **Trade-offs** documented (consistency vs availability vs latency); say what was **deliberately left out**. |
| P6 | **Monitoring**: metrics + structured logging; counters for **lag, error rate, rejections, retries, queue depth**. |
| P7 | **Caching** with eviction policy; say exactly **what can go stale, for how long, how staleness is bounded**. |
| P8 | **Error handling**: meaningful, non-leaking messages; **no personal data** (phone, email, home location) in logs or error responses. |

### Advanced Track
| ID | Requirement |
|----|-------------|
| A1 | **Proven guarantees:** every hard rule has an automated test, including ≥1 concurrency test and ≥1 failure test. |
| A2 | **Measured performance:** load test/benchmark with throughput and **p50/p99** latency in the README **with the machine used**. |
| A3 | **Design note** (1–2 pages): architecture diagram, **one sequence diagram for the hardest flow**, **failure-mode table** (what fails / what the user sees / how the system recovers). |
| A4 | **One-command run** (e.g. `docker compose up`) including any DB/broker. |

### Submission instructions
| ID | Requirement |
|----|-------------|
| S1 | Java + Spring Boot (preferred). |
| S2 | Plan first (data model, components, APIs, failure modes) → put in the design note. |
| S3 | Core algorithm in plain classes testable without web server/DB. |
| S4 | Tests: normal + edge cases; unit + concurrency + failure + benchmark. |
| S5 | README: how to run, assumptions, benchmark results, trade-offs. |
| S6 | Public GitHub repo, meaningful commit history. |
| S7 | Demo video script: main flows, one failure scenario handled live, sample API calls (Postman/curl). |

---

## Part B — Binding design decisions (the PDF leaves these open)

Each decision has an ID that code comments, tests and docs must reference (e.g. `// D-02`).

### D-01 Stack
Java **25 LTS**, Spring Boot **4.1.x** (latest stable patch), Maven multi-module, PostgreSQL **18**, Flyway, Caffeine, Micrometer + Prometheus, Testcontainers 2.x, JMH. Details in file 02.

### D-02 Boundary rule (R2.2) — closed set
A polygon is a **closed region minus open holes**:
- A point **on the outer ring boundary** (edge or vertex) → **INSIDE**.
- A point **on a hole's boundary** (edge or vertex) → **INSIDE** (the hole is an open set, so its boundary belongs to the polygon).
- A point strictly inside a hole → **OUTSIDE**.
Implemented as a three-valued `Location {INSIDE, BOUNDARY, OUTSIDE}`; `contains()` returns `location != OUTSIDE`. Overlapping zones can therefore both claim a shared border point — resolved by D-06 priority. Document this in README.

### D-03 Tolerance (K2)
- Point-to-segment boundary test uses `BOUNDARY_EPSILON_DEG = 1e-9` degrees (≈ 0.11 mm at the equator), configurable via `zones.geometry.boundary-epsilon-deg`.
- Orientation/collinearity tests use the same epsilon scaled by the magnitude of the operands (`eps * max(1, |a|, |b|)`).
- Intersection-parameter dedupe in segment splitting uses `T_EPSILON = 1e-12` on the normalized parameter t ∈ [0,1].
- All tolerances live in one immutable `GeometryTolerance` record; no magic numbers elsewhere.

### D-04 Geometry space and distance (K1)
- **Containment, intersections, validation** are computed in **planar lon/lat degree space** (equirectangular, x = lon, y = lat). The intended domain is local/regional polygons under about 1,000 km that do not cross the antimeridian. Polygons whose lon-span exceeds 180° or that touch |lon| = 180 are rejected (`ANTIMERIDIAN_UNSUPPORTED`); poles are unsupported (|lat| > 89.9 rejected). The exact operational check for the approximate 1,000 km limit must be verified before implementation; do not infer a new geographic cutoff silently.
- **Distances** use **haversine** with mean Earth radius `R = 6371.0088 km`. Documented error versus the WGS-84 ellipsoid: up to ≈ 0.3–0.5 % over long north–south paths, far smaller over typical trip segments. **You must measure** the actual error on sample routes against a test-only Vincenty implementation and put the measured figures in README.
- **Segment splitting rule ("length-proportional split"):** for a GPS segment a→b with total haversine length L, crossing parameters t₀=0 < t₁ < … < tₙ=1 (computed in degree space; t is invariant under the affine lon/lat scaling), the sub-interval [tᵢ, tᵢ₊₁] gets length `L × (tᵢ₊₁ − tᵢ)`. This guarantees Σ(sub-lengths) == L exactly (to floating point), so zone distances always sum to total trip distance. State the second-order error of assuming straight-line interpolation in lon/lat between consecutive pings.
- Rounding for invoices: store metres as `double`; present kilometres to 3 decimals using **largest-remainder allocation** so the rounded parts sum to the rounded total (so EX1 prints 30.4 + 11.6 = 42.0).

### D-05 Polygon input contract
- GeoJSON-style `Polygon`: `coordinates: [ outerRing, hole1, hole2, … ]`, each ring `[[lon,lat], …]` (**lon first**).
- Rings must be **explicitly closed** (first == last, compared exactly). Not auto-closed → R1.1 rejects.
- Minimum 4 positions per ring (3 distinct + closing). No consecutive duplicate positions (zero-length edges rejected). Zero-area rings rejected.
- Max **50,000 vertices per polygon** and **256 holes** (configurable) to bound validation cost (DoS guard).
- Orientation is normalised after validation: outer ring CCW, holes CW (either input orientation accepted).
- Rings must be **simple**; rings may **not touch or cross each other or the outer ring** (holes strictly inside, no shared vertices, no nested holes). Touching at a single vertex is rejected (keeps the "closed minus open holes" semantics unambiguous).
- Coordinates must be finite; lon ∈ [-180,180], lat ∈ [-90,90] (stricter limits in D-04).
- Self-intersection detection must be sub-quadratic (Shamos–Hoey sweep or an STR/segment-grid broad phase). A naive O(n²) check may exist **only as a test oracle**.

### D-06 Zone types, overlap and billing priority
```
ZoneType: CITY, AIRPORT, TARIFF   -> billable ("tariff class"): have ratePerKm, optional surcharge
          NO_GO                   -> not billable; night-window safety rule
```
- Zones may overlap (the PDF asks "which **zones** is this point in?").
- **Billing winner** at a location = the billable zone with the **highest `priority`** (integer, default 0); ties broken by **lexicographically smallest zoneId** (deterministic).
- Distance outside every billable zone goes to the bucket key `UNZONED`.
- NO_GO zones never take part in billing; they only produce events/alerts.
- `POST /zones/query` returns **all** zones containing the point (every type), sorted by (priority desc, zoneId asc), with `billingZoneId` marked.

### D-07 "Night"
- Per-tenant `timezone` (IANA, default `Asia/Kolkata`) and night window `[night_start, night_end)` default **22:00–06:00**, wrapping midnight.
- A moment is "night" if its **event time converted to the tenant timezone** falls in the window. DST-safe via `ZonedDateTime`.
- The alert fires when an ENTER into a NO_GO zone happens at a night moment (see D-09 for which zone version is used). Trip-start-inside counts as an ENTER at start time.

### D-08 Version model
Two version counters:
1. **Zone version** — per zone, `1,2,3…`, immutable row per edit (create, update, soft-delete tombstone). Satisfies R1.4/R1.5.
2. **Zone-set version** — per tenant, monotonically increasing `bigint`; one value = one atomic publication of the entire zone collection (**the unit of indexing, swap, trip pinning, and historical queries**). A published zone-set version N is composed of, for each zone, the latest zone version with `set_version ≤ N` whose owning zone-set row is `PUBLISHED`, excluding tombstones. `BUILDING` and `FAILED` rows are never visible to historical reads. To build a candidate publication N, the publisher composes the latest `PUBLISHED` version before N plus the edits belonging to N; it must not use the public historical loader for an unpublished candidate.
Old versions are queryable by `?version=` (single zone) and `asOfSetVersion` (point queries, batch queries). `asOfSetVersion` must name a `PUBLISHED` set; a missing, `BUILDING`, or `FAILED` set returns `404 VERSION_NOT_FOUND`.

### D-09 Trip consistency rule (R4.2) — "billing pinned, safety live"
- At **trip start** the trip **pins** the tenant's current zone-set version `Vp`.
- **Billing-class zones (CITY/AIRPORT/TARIFF):** distance attribution **and ENTER/EXIT events** for the whole trip use the pinned snapshot `Vp` — a trip is billed under exactly one consistent set of rules, regardless of edits mid-trip.
- **NO_GO zones:** evaluated against the **latest published snapshot at the time each ping is processed** (a safety control must take effect immediately: a tightened no-go zone protects the very next ping; a shrunk one stops alerting). NO_GO events and alerts record `evaluatedSetVersion`.
- Pinned old snapshots must remain available while any trip pins them (reference-counted registry, D-13); after trip completion the pin is released.
- EX2 expected behaviour: trip started 8:50 PM pinned V; admin publishes V+1 at 9:00 PM; billing for that trip still uses V; no-go decisions after 9:00 PM use V+1.

### D-10 Ping contract, ordering, duplicates
```
Ping { tripId: UUID, seq: long (>=0, strictly increasing per trip, producer-assigned),
       eventTime: Instant (UTC), lon: double, lat: double, accuracyM?: double }
```
- Idempotency key = `(tenantId, tripId, seq)`.
- Per-trip processing is **single-threaded and in `seq` order** (sharded executors keyed by hash(tenantId, tripId)).
- **Duplicate:** `seq ≤ lastProcessedSeq` and already seen → counted `ping_duplicate_total`, acknowledged, ignored.
- **Out-of-order within window:** buffered in a per-trip reorder buffer (default `maxHold = 2 s`, `maxBuffered = 64`), released in seq order when the gap fills or the hold expires (then the gap is skipped and counted `ping_gap_skipped_total`).
- **Too late** (seq < next expected after the gap was skipped): rejected with reason `LATE`, counted `ping_rejected_total{reason="late"}`; **not** retroactively applied (documented trade-off).
- Pings older than `maxPingAge` (default 24 h) or with `eventTime` more than 5 min in the future → rejected (`CLOCK_SKEW`).
- Sequence state has separate meanings: `processedSeq` (`lastProcessedSeq`) is the highest sequence applied to the in-memory trip state; it may be ahead of storage. `durableAckedSeq` is the highest contiguous sequence durably resolved by a committed checkpoint. It advances only in the same database transaction that persists the matching trip state, distance totals, events, alerts, and skipped-gap metadata. A request item's `ACCEPTED` means admitted to bounded in-memory processing; it does not mean durable. The API returns `durableAckedSeq`, never a volatile sequence as an acknowledgement.
- A skipped gap is terminally resolved for the acknowledgement frontier, but a later arrival for a skipped sequence remains `LATE`, not `DUPLICATE`. Persist and recover skipped sequence ranges with the checkpoint. For `seq ≤ durableAckedSeq`, a sequence in a skipped range is `LATE`; any other resolved sequence is `DUPLICATE`. For uncheckpointed state, apply the same distinction from the in-memory state. Producers resume at `durableAckedSeq + 1`; already processed but uncheckpointed pings may be replayed and must be idempotent.
- Delivery semantics: **at-least-once from the producer + idempotent processing**. A failed checkpoint does not advance `durableAckedSeq`; accepted work remains in bounded memory and is retried. If the process crashes first, the producer replays from the last returned durable frontier. The `maxHold` gap-skipping rule remains in force.

### D-11 Spatial index choice (R2.3)
**Immutable STR-packed R-tree over polygon bounding boxes + per-polygon edge-band index**, built as one immutable `ZoneSnapshot`.
- Why not a grid/geohash: zone sizes vary by 4+ orders of magnitude (city vs. airport gate); a uniform grid either explodes in cells or leaves large candidate lists; geohash cells need polygon-to-cell covering and still need exact tests. STR bulk-loading gives a well-packed static tree suited to atomic swap. Point-stab work is expected to depend on tree traversal and candidate count `k`; the worst case remains O(n) when bounding boxes overlap broadly. Build duration and query rate are measured, never presumed to be milliseconds.
- Why not Tessera's copy-on-write quadtree path-copying: our updates are rare admin edits; rebuilding the whole tree is cheap and simpler to prove correct. (Idea borrowed: immutable snapshot + atomic publish.)
- Within a polygon, point-in-polygon would still be O(vertices) for 10k-vertex shapes, so each ring precomputes a **horizontal-band edge index** (edges bucketed by y-range into ~√n bands). Point queries inspect edges spanning the point's y-band (plus ε); segment splitting unions/deduplicates every band in its y-range. Long edges can be replicated into many buckets, so memory and candidate counts depend on edge-band incidences and have a linear-scan worst case; benchmark that workload.
- A `NaiveZoneIndex` (linear scan with the same bbox pre-check disabled) is kept as baseline for benchmarks and as a test oracle.

### D-12 Persistence
PostgreSQL 18 **without PostGIS** — the database is the durable source of truth (zones, versions, trips, checkpoints, events, alerts, users), **never queried per ping**. Geometry stored as GeoJSON **text** with bbox columns (avoids Hibernate JSON-type/Jackson-3 coupling).

### D-13 Edit flow and concurrency
Per tenant, a **single-writer publisher** (the initial deployment has exactly one active application instance; see D-20):
1. Validate edit before opening a transaction. In tx 1, lock the tenant row (`SELECT ... FOR UPDATE`), allocate a version greater than every existing set version (including `FAILED`), and write zone-version row(s) + zone-set version row with status `BUILDING` atomically. The per-tenant publisher lane spans allocation, build, publication/failure, and local swap; it is not released after tx 1.
2. Build the new immutable snapshot from DB state **off the request path** (builder thread).
3. Commit status `PUBLISHED` (tx 2) — the DB is now the truth.
4. `AtomicReference<ZoneSnapshot>.set/compareAndSet` — readers do `ref.get()` **once per request/batch** and use that reference throughout.
5. Invalidate caches, emit metrics.
Failure during build or before the tx 2 commit → status `FAILED`, old snapshot keeps serving, `index_swap_failures_total++`. A set committed as `PUBLISHED` is not later changed to `FAILED`; if the process stops after tx 2 and before the local swap, the process is unavailable and restart loads the latest `PUBLISHED` set. Rows left in `BUILDING` at startup are marked `FAILED`. Edits for one tenant are serialised through the whole lifecycle; different tenants run in parallel. The database row lock protects version allocation, while the one-instance deployment constraint supplies globally ordered in-memory publication.

### D-14 Tenancy and roles
- `tenantId` comes **only from the JWT claim `tid`**, never from request body/path/query.
- Roles: `EMPLOYEE`, `ADMIN`, `OPERATOR`. Matrix in file 10.
- Cross-tenant access returns **404 Not Found** (not 403) to avoid existence leaks.
- One in-memory snapshot registry per tenant; every repository method takes `tenantId`.

### D-15 Durability of trip state
Trip pipeline state is in memory; **checkpointed** to Postgres every `N=200` resolved pings or `T=2 s` (whichever first) and on trip completion/graceful shutdown. A checkpoint is one immutable shard-captured state at a known processing frontier and includes `processedSeq`, `durableAckedSeq`, skipped sequence ranges, per-zone metres, unzoned metres, open memberships, last point/time, and the next event transition ordinal. In one database transaction, persist that state and its exact cumulative distance totals, insert its events/alerts idempotently, and advance the durable acknowledgement. A checkpoint retry must write the same captured state; per-trip checkpoint writes are serialized and stale frontiers cannot overwrite newer ones. The API exposes only the committed `durableAckedSeq`. On restart, restore the complete checkpoint; producers replay from `durableAckedSeq + 1`; duplicates are ignored and skipped sequences remain `LATE`. Work admitted after that frontier but not committed is intentionally replayed. Final totals/events/alerts after crash+replay must equal an uninterrupted run (proved by test).

### D-16 Alerts delivery
Alerts are persisted (`alerts` table, deterministic id → idempotent), counted in metrics, logged (opaque ids), and exposed via `GET /alerts`. **No external broker** (Kafka/gRPC/WebSocket from reference repos are deliberately left out — listed under trade-offs). Webhook delivery is out of scope.

### D-17 Caching (P7)
See file 11. Immutable-version reads are never stale. In the initial one-instance design, the atomic latest pointer changes at local publication; any latest DTO cache must be keyed by the captured published set version (and evicted on publish) so a concurrent fill cannot make an old value appear current. Keep a 5 s TTL as a memory/recovery bound, not as a cross-instance consistency guarantee. **No point-query result cache** (index is already microseconds and rounding coordinates would violate D-02).

### D-18 Time source
All time via injected `Clock`. Tests use a controllable `MutableClock`.

### D-19 Domain identifier naming
The domain identifier type is `ZoneKey` (a UUID value object). `java.time.ZoneId` is reserved for the tenant's IANA time zone. API path/JSON names such as `zoneId` remain unchanged; the rename is only to remove the Java type-name collision. Any method receiving a time zone uses the fully qualified type in signatures/docs where ambiguity remains.

### D-20 Initial publication topology
The initial supported deployment is **one active `zone-service` instance per database**. Per-tenant publisher lanes and `AtomicReference` registries are process-local; no multi-instance watcher, lease/fencing protocol, or cross-instance invalidation is part of this design. Deployments must configure one replica. Horizontal/multi-instance operation is unsupported until a globally fenced publication protocol and coherent snapshot distribution are designed and tested. PostgreSQL remains the durable recovery source across a restart.

### D-21 Deterministic transition and alert identifiers
`TripState` maintains a monotonically increasing `nextTransitionOrdinal` for the whole trip. For each ping operation, all generated events are ordered deterministically by normalized segment parameter `t`, source (`BILLING_PINNED` before `NO_GO_LIVE` for ties), canonical lowercase zone UUID, then kind (`ENTER` before `EXIT` for otherwise equal keys); start, zone-change, and trip-end events use their defined operation parameter and the same tie-breaks. Assign one ordinal per emitted event. Event UUIDv5 uses a fixed documented namespace and an unambiguous binary/canonical name containing tenant UUID, trip UUID, and transition ordinal. The ordinal is part of the atomic checkpoint. Alert UUIDv5 uses a separate fixed namespace and the source event UUID plus alert kind, so one alert is idempotent per qualifying transition. Never derive identity from only `(trip, zone, seq, kind)`, because a segment can enter/exit the same zone more than once.

### D-22 Performance measurement semantics
Keep the case-study target of **50,000 point-containment queries per second against 5,000 polygons** as the in-process spatial-query target; report it as point queries/s (one query operation, not an HTTP ping). Separately measure (1) in-process point-query throughput/latency, (2) HTTP ping-ingestion admission throughput/latency (pings accepted into bounded processing), and (3) end-to-end ping processing throughput/latency (pings whose effects are included in a committed checkpoint, including attribution, events/alerts, and persistence). Report request p50/p99 for HTTP admission and enqueue-to-durable-checkpoint p50/p99 for end-to-end work. Never label admission rate as processing or durability throughput; a DB outage may raise admission temporarily while durable throughput is zero.

### D-23 Geometry edge-case verification
D-03 defines the boundary tolerance in degrees, so physical tolerance varies with latitude; report it as a coordinate-space tolerance and keep the equatorial distance only as an approximation. D-03's scaled orientation/collinearity epsilon does not give a complete dimensionally explicit formula. Before implementing that predicate, verify and document one formula against large-coordinate, near-collinear, endpoint-touch, and overlap cases. This verification must not change the closed-set boundary rule in D-02.
