# Polygon Zones at Scale

MoveInSync Backend Case Study 2026, Case Study 7. The canonical requirements and phased implementation roadmap are in the numbered Markdown files at the repository root.

## Development database

Start PostgreSQL 18 with `docker compose up -d postgres`. Local-only defaults are database `zones`, user `zones`, and password `zones_dev_only`; production credentials must be supplied with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

## Build

The project targets Java 25 and Maven Wrapper. Run `./mvnw -v`, then `./mvnw -q verify` with Docker available for Testcontainers integration tests. Phase 2 implements the plain-Java geometry core in `zone-core`.

### Distance accuracy (Phase 2)

`Haversine.distanceMeters` uses a mean Earth radius of 6,371,008.8 m. The test-only Vincenty analysis in [distance-error.csv](docs/benchmarks/distance-error.csv) ran 36 cases (1, 10, and 100 km; bearings 0°, 45°, and 90°; latitudes 0°, 12.97°, 28.6°, and 60°). The largest observed relative error was **0.56144%** (equatorial north/south sample); this is the measured maximum for this case grid, not a global bound. Degree-space linear interpolation's midpoint deviated from the geodesic midpoint by at most **383.985 m**, at 100 km and high latitude. This quantifies why the planar lon/lat model is intended for regional polygons and consecutive trip pings, not long geodesic routes.

The measurement ran on Apple M2 (8 CPU cores), 16 GB RAM, macOS 26.5.1, with Homebrew OpenJDK 25.0.2. Reproduce it with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl zone-core -Dtest=DistanceErrorAnalysisTest test`.

See [docs](docs/README.md) and [implementation log](docs/IMPLEMENTATION-LOG.md).

## Phase 3 — spatial index and measured point queries

`zone-core` now builds immutable STR-packed R-trees in private flat primitive arrays. `forEachContaining` reuses a per-thread traversal stack after initialization; the separate list-returning `query` allocates its results. A linear `NaiveZoneIndex` scans exact polygon geometry without the R-tree bbox shortcut and serves as an independent oracle. Tests compare complete sorted `ZoneHit` results over 50 fixed-seed scenarios × 100,000 points, plus node-capacity, boundary, hole, candidate-range, reentrancy and 16-reader cases.

Measured on Apple M2, 8 cores (4 performance + 4 efficiency), 16 GB RAM, macOS 26.5.1, OpenJDK 25.0.2:

| Point-query workload | Index | Zones × vertices | Threads | Throughput | Sample p50 / p99 | Build time | Retained heap |
|---|---|---:|---:|---:|---:|---:|---:|
| JMH complete point query | STR R-tree | 5,000 × 16 | 8 | 8,091,255 queries/s | 1.166 / 3.248 μs | — | — |
| JMH complete point query | STR R-tree | 5,000 × 500 | 8 | 2,923,697 queries/s | 2.000 / 12.576 μs | — | — |
| Open-loop, baseline | STR R-tree | 5,000 × 16 | 8 | 50,000 queries/s achieved | service 0.833 / 9.215 μs; deadline-to-completion 8.791 / 20.719 μs | — | — |
| Open-loop, rebuilding/swapping every 2 s | STR R-tree | 5,000 × 16 | 8 | 50,000 queries/s achieved | service 0.833 / 4.127 μs; deadline-to-completion 8.751 / 20.383 μs | — | — |

JMH allocation profiling measured `forEachContaining` at 0.00077 B/op (16 vertices) and 0.00216 B/op (500 vertices), effectively the profiler floor after warmup. The returned-list API measured about 98.6 B/op at 5,000 zones. The 5,000-zone build benchmark measured 2.679/2.759 ms AverageTime and 5.900/5.339 ms SingleShotTime for 16/500 vertices. Runtime heap-delta retained-size measurements were 7,279,272 B (5k × 16), 24,418,992 B (20k × 16), 122,581,096 B (100k × 16), and 120,986,544 B (5k × 500); this is a process-sensitive Runtime/GC measurement, not JOL.

The open-loop point-query harness completed 3,000,000 scheduled operations in each 60-second run with zero drops and zero worker failures. These figures measure one in-process containment query against an existing snapshot. **HTTP ingestion admission and end-to-end durable ping processing are not measured in Phase 3**, and point-query throughput must not be read as ping throughput. Reproduce with `BENCH_JDK25_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./scripts/bench.sh`; benchmark seeds, generator parameters, raw outputs, latency definitions and complexity/scaling limits are documented in [benchmark methodology](docs/benchmarks/methodology.md).
