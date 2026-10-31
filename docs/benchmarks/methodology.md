# Phase 3 benchmark methodology

All figures in this directory were measured on Apple M2 (8 CPU cores: 4 performance + 4 efficiency), 16 GB RAM, macOS 26.5.1 (Darwin 25.5.0), Homebrew OpenJDK 25.0.2. JMH used `-Xms512m -Xmx6g`; the load harness used the same heap settings. See [environment.txt](environment.txt) for raw host/JDK/Docker output. Runs are local in-process geometry/index workloads, not HTTP or durable trip processing.

## Deterministic datasets

- JMH point matrix: zone seed `0x6d697373` (1835627379), point seed `zoneSeed ^ 0x55aa` (1835607769), metro box `(77.4, 12.8)–(77.8, 13.2)`, overlap factor `0.12`, hole probability `0.10`, and 10% boundary-biased points. Zones are non-convex star rings with log-normal radius variation and optional scaled-down holes; all shapes pass the production validator. Parameters: 100/1,000/5,000 zones × 16/500 vertices; 65,536 pre-generated points cycled by each worker. The fixed seed determines exact hole/priority/coordinate draws.
- Candidate fanout scan: same seeds and generation parameters as the JMH matrix, 100,000 points per dataset. The R-tree reports bbox candidates/exact shape checks; the naive baseline reports all polygons as exact checks. Mean exact matches are computed from the R-tree's complete `ZoneHit` query results. Full counts and seeds are in [candidate-counts.csv](candidate-counts.csv).
- Open-loop workload: zone seed `0x6c6f6164` (1819238756), point seed `0x706f696e74` (482905517684), metro box as above, overlap `0.12`, hole probability `0.10`, 5,000 zones × 16 vertices, 3,000,000 deterministic points (10% boundary-biased), eight reader threads, 50,000 scheduled queries/s for 60 seconds. Rebuild mode constructs and publishes a new snapshot every two seconds. Each reader warms up with 10,000 queries before measurement.
- Build benchmark: seed `0x6275696c64`, 5,000 zones, 16 or 500 vertices, overlap `0.12`, hole probability `0.10`, same metro box. JMH uses two forks and its specified 5×2 s warmup and 10×2 s measurement for AverageTime and SingleShotTime.
- Memory sweep: seed `0x4d454d + count*31 + vertices`, overlap `0.12`, hole probability `0.10`, same metro box. Cases are 1k/5k/20k/100k × 16 vertices and 5k × 500 vertices.

## Measurements

### JMH point-query matrix

`jmh-point-query.json` is the raw full JMH result (`-prof gc`, two forks). One operation invokes a complete point-containment query on an already-built index. The selected 5,000-zone R-tree results:

| Vertices/zone | Threads | Throughput, complete `query()` | SampleTime p50 | SampleTime p99 | `forEachContaining` allocation |
|---:|---:|---:|---:|---:|---:|
| 16 | 1 | 1,879,162 queries/s | 0.542 μs | 1.458 μs | 0.00077 B/op |
| 16 | 8 | 8,091,255 queries/s | 1.166 μs | 3.248 μs | 0.00077 B/op |
| 500 | 1 | 707,934 queries/s | 1.124 μs | 5.496 μs | 0.00216 B/op |
| 500 | 8 | 2,923,697 queries/s | 2.000 μs | 12.576 μs | 0.00216 B/op |

The allocation values are JMH GC profiler readings at its practical resolution, effectively zero per operation after thread-local initialization. The returned-list `query()` allocates result objects; its throughput is not the zero-allocation API. The initial raw JSON's `candidateRetrieval` rows predate a leaf-level bbox filter correction and are superseded by [jmh-candidate-retrieval-corrected.json](jmh-candidate-retrieval-corrected.json); all point-query and allocation-probe rows are unaffected by that candidate-only fix.

Corrected candidate retrieval at 5,000 zones: 19,359,288 ops/s (16 vertices) and 16,778,052 ops/s (500 vertices), with `gc.alloc.rate.norm` at approximately `10^-3 B/op`. Full corrected results are in that JSON file. Build measurements are in [jmh-index-build-corrected.json](jmh-index-build-corrected.json): average 2.679 ms (16 vertices) / 2.759 ms (500), single-shot 5.900 ms / 5.339 ms. These are this machine's observations, not service startup guarantees.

### Open-loop target and snapshot rebuild

Both corrected 60-second runs scheduled and completed 3,000,000 queries (50,000 queries/s), with zero drops, zero worker failures, and queue high-water below 100. Raw outputs: [baseline](load-baseline.txt), [rebuild/swap](load-rebuild.txt).

| Mode | Achieved completed queries/s | Query service p50 / p99 (HdrHistogram expected-interval correction) | Scheduled-deadline-to-completion p50 / p99 | Drops | Snapshots built |
|---|---:|---:|---:|---:|---:|
| Baseline | 50,000 | 0.833 μs / 9.215 μs | 8.791 μs / 20.719 μs | 0 | 1 |
| Rebuild every 2 s | 50,000 | 0.833 μs / 4.127 μs | 8.751 μs / 20.383 μs | 0 | 30 |

The first latency pair measures query execution only and uses HdrHistogram expected-interval correction. The second measures scheduled deadline through queueing and completion; it is already open-loop and is reported without correction. This is in-process point-containment throughput, not HTTP request admission, full ping ingestion, trip attribution, durable processing, or a claim that those service workloads sustain 50,000 pings/s.

### Retained memory and limits

[memory.csv](memory.csv) uses `Runtime.totalMemory() - freeMemory()` before and after full GC around construction, retaining the snapshot graph. It is approximate and process-sensitive (not JOL); unlike the separate `BuildStats.approxBytes`, the heap delta includes zone/ring/vertex/edge-band structures reachable from the snapshot. Measured deltas: 7,279,272 B (5k × 16), 24,418,992 B (20k × 16), 122,581,096 B (100k × 16), and 120,986,544 B (5k × 500).

R-tree construction uses STR sorting, approximately O(n log n) work plus O(n) packing, excluding geometry creation/validation. Point queries usually visit tree nodes plus `k` bbox candidates and exact shapes; broad overlap remains O(n) worst case. Ring edge bands store `I` edge-to-band incidences: construction/storage are O(v + I), potentially O(vB) for B bands; point candidate checks can be O(v) worst case. Large overlapping/country-scale boxes inflate `k`; very long thin polygons cause bbox false positives; memory grows with polygon vertices and edge-band incidence; full rebuild work grows with the entire zone set. This is a single-node immutable-snapshot index. No service-level scale or durability claim is made in Phase 3.
