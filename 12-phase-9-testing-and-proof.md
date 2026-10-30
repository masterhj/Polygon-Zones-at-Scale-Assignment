# 12 — Phase 9: Testing and Proof ("every hard rule has an automated test")

**Goal:** a traceable test suite: each requirement ID maps to at least one automated test; plus concurrency, failure and performance evidence.
**Requirements:** A1, A2, K2, K3, K4, S4.

## 1. Test pyramid and naming
- **Unit (fast, no Spring, no Docker):** `zone-core` — `*Test`.
- **Property-based:** `*PropertyTest` (jqwik or seeded random loops; log the seed on failure for reproducibility).
- **Integration:** `zone-service` — `*IT` run by Failsafe with Testcontainers PostgreSQL 18 (reuse one container per JVM via a shared `@ServiceConnection` bean or singleton container to keep the suite fast).
- **Concurrency:** `*ConcurrencyTest` (tag `concurrency`).
- **Failure injection:** `*FailureInjectionTest`/`*FailureTest` (tag `failure`).
- **Performance:** `@Tag("perf")` tests + JMH/harness (not part of the default unit run if they are slow; but `./mvnw verify` must still execute the **short** perf-sanity tests; the long benchmark runs through `scripts/bench.sh`).
- Mark each test method with `@DisplayName("R2.2 / D-02: point on hole edge is INSIDE")` style names including the requirement/decision ID so a script can build the traceability matrix.

## 2. Requirement → test matrix (create `docs/test-matrix.md`; generate part of it with a script scanning `@DisplayName`s)

| Req | Test class(es) (must exist) |
|---|---|
| R1.1 not closed | `PolygonValidatorTest.notClosed*`, `ZoneAdminIT.rejectsOpenRing` |
| R1.2 self-intersect | `PolygonValidatorTest.bowTie*`, `SelfIntersectionOracleTest` |
| R1.3 hole outside | `PolygonValidatorTest.holeOutside*`, `ZoneAdminIT.rejectsHoleOutside` |
| R1.4 new version per edit | `ZoneVersioningIT.everyEditCreatesVersion` |
| R1.5 old versions queryable | `ZoneVersioningIT.asOfQueries`, `LiveEditIT` |
| R2.1 non-convex & holes | `PolygonShapeContainsTest`, `GeometryPropertyTest` |
| R2.2 boundary rule | `PolygonShapeBoundaryRuleTest` (D-02) |
| R2.3 index | `RTreeZoneIndexTest`, `IndexEquivalencePropertyTest`, `PointQueryBenchmark` (evidence) |
| R3.1 distance split | `SegmentSplitterTest`, `AirportDropScenarioTest` (EX1), `TripFlowIT` |
| R3.2 events + dwell | `TripAttributorEventsTest`, `TripFlowIT` |
| R3.3 night no-go alert | `NoGoAlertTest`, `NightWindowTest` |
| R4.1 atomic swap | `SnapshotSwapConcurrencyTest`, `NoLatencySpikeDuringRebuildTest`, `LiveEditIT` |
| R4.2 trip consistency | `TripPinningRuleIT` (D-09), EX2 scenario in `LiveEditIT` |
| EX1 | `AirportDropScenarioTest` + `TripFlowIT.airportDrop` |
| EX2 | `LiveEditIT.shrinkNoGoAt9PM` + `NoLatencySpikeDuringRebuildTest` |
| K1 distance error | `HaversineErrorReportTest` (writes `docs/benchmarks/distance-error.csv`) |
| K2 tolerance | `PolygonShapeBoundaryRuleTest.epsilon*`, `SegmentIntersectorTest.epsilon*` |
| K3 queries during edit | `SnapshotSwapConcurrencyTest`, `LiveEditConcurrencyIT` (HTTP-level, below) |
| K4 benchmark | `PointQueryBenchmark`, `IndexBuildBenchmark`, `IndexMemoryReport`, `PointQueryLoadTest` |
| P1 auth/roles/tenancy | `AuthIT`, `RoleMatrixIT`, `TenantIsolationIT`, `SecurityRulesTest` |
| P2 complexity | README section + `ScalingSweepBenchmark` data |
| P3 failure handling | `PingPipelineTest`, `TripRecoveryFailureTest`, `CheckpointFailureTest`, `BuilderFailureInjectionTest`, `DbOutageIT`, `PoisonPingTest` |
| P4 OOP | `ArchitectureTest`, polymorphism tests (`ZoneHierarchyTest`, `IndexPolymorphismTest`) |
| P6 monitoring | `MetricsExposureIT`, `LogPrivacyTest` |
| P7 caching | `ZoneCacheTest`, `StalenessBoundTest` |
| P8 error handling | `ApiErrorShapeTest`, `LogPrivacyTest` |
| A4 one-command | `scripts/smoke-compose.sh` (CI job, see file 13) |

## 3. HTTP-level concurrency test (K3 at the API boundary) — `LiveEditConcurrencyIT`
- Boot the service with 5,000 synthetic zones loaded for one tenant (seed via repository/bulk loader for speed; ≥ 1,000 is acceptable in CI with an env override, but the official run uses 5,000).
- Start 8–16 client threads (virtual threads + `java.net.http.HttpClient`) hitting `POST /zones/query/batch` continuously for ≥ 15 s while an admin thread performs ≥ 20 edits (alternating grow/shrink of a "probe" zone that always contains point P_in or excludes P_out per version).
- Assertions: no non-2xx responses; each response's `setVersion` is non-decreasing per client thread; for each response, the probe membership matches the expected membership **for the `setVersion` reported in that very response** (proves no torn state); max observed latency and p99 reported (not asserted tightly in CI; asserted loosely `p99 < 250 ms` configurable).
- Write `target/live-edit-concurrency-report.txt`; copy to `docs/benchmarks/` for the official run.

## 4. Benchmarks & load test (A2) — how to report
- `scripts/bench.sh` runs separate workloads: distance-error test, JMH point query + build (+ `-prof gc`), memory report, open-loop 50k **in-process point queries/s** (idle and during rebuild), scaling sweep, HTTP batch-query load (`HttpLoadTest`: batch of 100–1,000 points per request, report points/s and HTTP request p50/p99), and ping load (`PingIngestLoadTest`: report **admitted pings/s** and HTTP request p50/p99, queue peak, processing lag). It must also report **end-to-end durable pings/s** and enqueue-to-checkpoint p50/p99, where a ping counts only after its effects and `durableAckedSeq` are committed. Use D-22 names and definitions; never call admission throughput end-to-end throughput.
- Capture environment: CPU model/cores, RAM, OS, Docker version, JDK build, JVM flags, git commit, date. Store at `docs/benchmarks/environment.txt` and quote next to each number in README.
- State clearly: the case-study 50k/s target is **in-process point-query throughput** (one query op against 5,000 zones on this one node); additionally report batched REST admission and end-to-end durable processing as separate metrics. Explain limits (CPU cores, JSON parse cost, DB checkpoint throughput, GC pauses). **Report honestly if a target is not met on the available hardware.**

## 5. Coverage and quality gates
- JaCoCo report; target ≥ 90% line / 85% branch for `zone-core` (report the achieved figure, do not fudge); mutation testing with PIT is optional (**RESOLVE** a Java-25-compatible release before using).
- `-Xlint:all -Werror` for `zone-core`.
- Flaky-test policy: no `Thread.sleep` for synchronisation; fixed seeds logged; concurrency tests run 3× in CI via `-Dsurefire.rerunFailingTestsCount=0` (do **not** mask flakiness with reruns).
- Optional: SpotBugs/Error Prone if compatible with Java 25 (**RESOLVE**).

## 6. Acceptance criteria
- [ ] `docs/test-matrix.md` lists every ID in file 01 with at least one passing test (script `scripts/check-matrix.sh` fails if an ID has none).
- [ ] `./mvnw -q verify` green on a clean machine with Docker.
- [ ] ≥ 1 concurrency test and ≥ 1 failure-injection test run in `verify`.
- [ ] Benchmarks executed; raw files committed in `docs/benchmarks/`.

## Suggested commits
- `test: add requirement-tagged display names and traceability script`
- `test(it): live edit concurrency at HTTP level`
- `feat(bench): http batch query and ping ingestion load tests`
- `docs: test matrix and benchmark environment capture`
