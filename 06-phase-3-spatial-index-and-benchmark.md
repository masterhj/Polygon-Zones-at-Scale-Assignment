# 06 — Phase 3: Spatial Index + Benchmarks

**Goal:** an immutable STR-packed R-tree over polygon bounding boxes, plus a naive baseline, deterministic data generators, and real JMH/load measurements for **5,000 polygons**. Queries are selective in typical data, but an R-tree point stab has O(n) worst-case behavior; do not claim a guaranteed O(log n + k) bound.
**Requirements:** R2.3, K4, P2, A2. **Decisions:** D-06, D-11.

## 1. Interfaces and classes (`com.polyzones.core.index`)

```java
public interface ZoneIndex {
    /** All zones whose shape contains (lon,lat) per D-02, sorted by priority desc then zoneId asc. */
    List<ZoneHit> query(double lon, double lat);
    /** Zero-allocation variant for hot paths. Consumer receives zone index into the snapshot's zone array. */
    void forEachContaining(double lon, double lat, IntConsumer zoneOrdinal);
    /** Candidates whose bbox intersects the box (used by the segment splitter in phase 5). */
    void forEachCandidate(BoundingBox box, IntConsumer zoneOrdinal);
    int size();
}
```
- `record ZoneHit(ZoneKey id, int version, ZoneType type, int priority, Location location)`.
- `RTreeZoneIndex implements ZoneIndex` — **STR bulk-load**:
  1. Sort items by centre-x, slice into `S = ceil(sqrt(n / M))` vertical slabs (M = node capacity, default **16**; make it a constructor parameter), sort each slab by centre-y, pack runs of M into leaf nodes; repeat upward until one root.
  2. Store nodes in **flat primitive arrays** (`double[] minX,minY,maxX,maxY; int[] firstChild; int[] childCount`) for cache locality and low memory; leaf entries reference zone ordinals.
  3. Stab query: iterative traversal with an `int[]` stack (no recursion, no allocation on the hot path); bbox test uses `eps`-expanded boxes (D-03); at leaves call `PolygonShape.locate`.
  4. Fully immutable after construction ⇒ safe to share across threads without synchronisation.
- `NaiveZoneIndex implements ZoneIndex` — linear scan calling `locate` on every polygon (baseline + oracle for correctness tests).
- `ZoneSnapshot` (immutable): `tenantId`, `setVersion`, `Zone[] zones` (ordinal-indexed), `ZoneIndex index`, `Map<ZoneKey,Integer> ordinalById`, `Instant builtAt`, `BuildStats(buildNanos, polygonCount, vertexCount, approxBytes)`.
- `SnapshotBuilder.build(tenantId, setVersion, Collection<Zone>)` → `ZoneSnapshot`; parallelisable (building each polygon's edge bands with `parallelStream` is allowed; the result must be deterministic).

## 2. Complexity analysis to put in README / design note (P2)
Write down and justify (and where possible measure):
- R-tree build: STR sorting O(n log n) + packing O(n), excluding geometry decoding/validation. This is a work estimate, not an observed duration.
- R-tree point query: expected work is tree traversal plus `k` bbox candidates and their exact polygon tests. Worst case is O(n) candidates when boxes overlap broadly; `k` is data-dependent.
- Per-ring edge bands: point-band lookup is O(1) before candidate inspection, but candidate count is not guaranteed O(√v). Define `I` as total stored edge-to-band incidences; construction/storage are O(v + I), with worst-case I up to O(vB) for B bands. Point query is O(candidate edges in one band), worst-case O(v). Segment range queries visit all intersected bands, deduplicate edges, and may also degrade to O(v).
- Memory: report measured retained size from JOL/heap method, including actual node arrays, zone objects, vertices, band buckets, and edge-band incidences. Fixed bytes-per-entry/edge estimates are not a reliable bound because incidence varies by geometry.
- Where it stops scaling (state and demonstrate with a measured sweep, e.g. n ∈ {1k, 5k, 20k, 100k}; vertices/polygon ∈ {16, 500, 5k}): (1) heavy overlap ⇒ k grows; (2) polygons with enormous bboxes (country-sized) become candidates for most points; (3) very long thin polygons have large bboxes but small area (bbox false positives) → consider per-polygon coarse raster pre-test later (document as future work); (4) memory is O(V); (5) rebuild is O(total) so very frequent edits at 100k+ polygons would need incremental/partitioned rebuild; (6) single-node scope.

## 3. Deterministic data generator (`zone-core` test fixtures, reused by `zone-bench`)
`SyntheticZones.generate(long seed, int count, int verticesPerPolygon, double overlapFactor, double holeProbability, BoundingBox extent)`:
- Non-convex star-shaped polygons (random radii between 0.4R and R around a centre) so they are always simple; optional scaled-down inner star as a hole (guaranteed inside).
- Size distribution log-normal (many small tariff zones, few city-sized ones), placed over a metro extent (e.g. lon 77.4–77.8, lat 12.8–13.2) and a second "country" extent for the scaling sweep.
- `SyntheticPoints.generate(seed, n, extent, fractionNearBoundary)` — mix uniform points and points sampled very near polygon edges.
- All generated polygons must pass `PolygonValidator`.

## 4. Correctness tests for the index
- `RTreeZoneIndexTest`: for ≥50 random scenarios (seeds fixed in the test), `RTreeZoneIndex.query` equals `NaiveZoneIndex.query` for 100,000 random + boundary-biased points each.
- Overlapping zones: result order = priority desc, zoneId asc (D-06).
- Empty index; single polygon; identical bboxes; polygons touching at a border (a border point returns both).
- Node capacity variations (4, 16, 64) produce identical results.
- Thread-safety: 16 threads hammer one shared index with random points; results equal the single-thread oracle (no shared mutable state).

## 5. Benchmarks (zone-bench) — **measure, never invent**

### 5.1 JMH (`PointQueryBenchmark`)
This measures **in-process point-query throughput and latency** against an already-built snapshot. One operation is one point-containment query; it is not an HTTP request or complete trip ping.
- `@State(Scope.Benchmark)`; `@Param` index = {naive, rtree}; polygons = {100, 1000, 5000}; verticesPerPolygon = {16, 500}; `fractionNearBoundary` fixed 0.1.
- Modes: `Throughput` (point-query operations/s, 1 thread and `@Threads(Threads.MAX)`) and `SampleTime` (point-query latency percentiles). Warm-up ≥ 5×2 s, measurement ≥ 10×2 s, forks ≥ 2, `Blackhole` consumption. Pre-generate query points into a `double[]` (cycle through) to avoid RNG cost.
- `IndexBuildBenchmark`: build time for 5,000 polygons × {16, 500} vertices (`SingleShotTime` + `AverageTime`).
- `IndexMemoryReport` (plain `main`): retained size via JOL `GraphLayout.parseInstance(...).totalSize()` (or `Runtime` before/after full GC if JOL unavailable — say which). Output CSV.

### 5.2 Concurrent latency harness (`PointQueryLoadTest`, plain `main`)
This remains the open-loop **in-process point-query** workload, corresponding to the 50,000 queries/s target in D-22.
- Target rate **50,000 queries/s** (paced open-loop using a scheduler/ticker, not closed-loop) against 5,000 polygons for ≥60 s with N reader threads; record per-query latency with **HdrHistogram** (correct for coordinated omission via expected-interval recording); report achieved qps, p50, p90, p99, p99.9, max.
- Run once on the baseline snapshot and once **while a background thread rebuilds and swaps the snapshot every 2 s** (phase 4 reuses this for the "no latency spike" proof). Report the p99 difference.
- Save raw output under `docs/benchmarks/`:
  - `docs/benchmarks/environment.txt`: `uname -a`, CPU model & core count (`lscpu`), RAM, `java -version`, JVM flags, Docker presence, date.
  - `jmh-*.json`, `load-*.txt`, `memory.csv`, `distance-error.csv`.
- A script `scripts/bench.sh` runs everything and writes the files; README quotes the numbers verbatim with the environment next to them.

### 5.3 Service throughput reports (separate workloads)
- **Ping-ingestion admission:** HTTP harness reports pings/s whose request items were admitted to the bounded in-memory pipeline, request rate, and HTTP request p50/p99. `ACCEPTED` is not a durable acknowledgement.
- **End-to-end durable processing:** separately report pings/s whose effects are included in a committed checkpoint (attribution, totals, events/alerts and durable ack), plus enqueue-to-durable-checkpoint p50/p99. Include backlog/processing lag and measure long enough to reach steady state. During a DB outage, admission can continue temporarily while durable throughput is zero.
- Never label HTTP response rate, point-query ops/s, queue admission, or in-memory processing as end-to-end throughput. State the exact numerator and measurement window for every rate.

### 5.4 Report format (README tables)
| Workload | Index | Polygons | Verts/poly | Threads | Throughput (queries/s or pings/s) | p50 | p99 | Build time | Retained memory |
Fill separate point-query, admission, and durable-processing rows from real output only; if a run was not possible say "not measured". Keep request latency and enqueue-to-durable latency separate.

## 6. Acceptance criteria
- [x] R-tree results identical to naive baseline on all fixtures; complete sorted `ZoneHit` records are compared across 50 fixed-seed × 100,000-point scenarios and additional edge cases.
- [x] 5,000-polygon benchmark executed; raw JMH, build, candidate, load, environment and memory outputs are in `docs/benchmarks/`; README figures match those results.
- [x] Two 60-second open-loop 50k qps runs report achieved rate and latency percentiles with machine details; each delivered 50,000 queries/s with no drops or worker failures.
- [x] Complexity and measured scaling limits are documented in README and `docs/benchmarks/methodology.md`.
- [x] `forEachContaining` GC profile measured 0.00077 B/op (16 vertices) and 0.00216 B/op (500 vertices) at 5,000 zones, effectively profiler resolution after warmup; recorded in the benchmark report.

## Suggested commits
- `feat(core): immutable STR R-tree zone index with flat arrays`
- `feat(core): naive index baseline and zone snapshot`
- `test(core): index equivalence tests against naive oracle`
- `feat(bench): synthetic zone generator, JMH point-query and build benchmarks`
- `feat(bench): open-loop 50k qps load harness with HdrHistogram`
- `docs: first benchmark results with environment`
