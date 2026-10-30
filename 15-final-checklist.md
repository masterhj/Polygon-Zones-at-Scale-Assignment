# 15 — Final Checklist (mapped to every line of the PDF)

Tick each box only when the evidence exists in the repo. For anything not ticked, write the honest reason next to it in `docs/FINAL-REPORT.md`.

## I. Polygon model
- [ ] R1.1 Not-closed polygons rejected (test: `PolygonValidatorTest`, `ZoneAdminIT`)
- [ ] R1.2 Self-intersecting polygons rejected, sub-quadratic detector, oracle-tested
- [ ] R1.3 Holes outside outer ring rejected (also touching/crossing/nested/overlapping holes)
- [ ] R1.4 Every edit creates a new zone version and a new zone-set version
- [ ] R1.5 Old versions queryable (`?version=`, `asOfSetVersion`)

## II. Point queries at speed
- [ ] R2.1 Correct for non-convex shapes and holes (table tests + property tests vs JTS oracle)
- [ ] R2.2 Boundary rule defined (D-02), documented in README, tested for edges/vertices/hole edges/hole vertices
- [ ] R2.3 STR R-tree + edge bands; choice and expected candidate-based work explained in README; worst-case O(n) overlap behavior stated
- [ ] 5,000 polygons × 50,000 point queries/s measured (in-process open-loop) and honestly reported
- [ ] Point-query, HTTP ping-admission, and end-to-end durable-processing rates/latencies reported separately (D-22)

## III. Trip attribution
- [ ] R3.1 Segments split exactly at boundary crossings; Σ zones == total; EX1 passes (30.4 / 11.6 / 42.0)
- [ ] R3.2 ENTER/EXIT events with interpolated times and dwell
- [ ] R3.3 Night no-go alert (tenant timezone window, wrap-around, DST-safe, idempotent ids)

## IV. Live edits
- [ ] R4.1 Background rebuild + atomic swap; torn-read concurrency test green; no-latency-spike evidence recorded
- [ ] R4.2 Trip consistency rule defined (D-09 "billing pinned, safety live") and tested (EX2)
- [ ] Initial deployment is explicitly one active service instance; no cross-instance publication guarantee is claimed (D-20)

## Key points
- [ ] K1 Distance method + measured error table in README
- [ ] K2 Explicit epsilon (`GeometryTolerance`), tests on edges, vertices, holes
- [ ] K3 Test that queries continuously during an edit (core-level and HTTP-level)
- [ ] K4 Benchmark: qps vs 5,000 polygons, index build time, memory; machine recorded

## Plus points
- [ ] P1 JWT auth, EMPLOYEE/ADMIN/OPERATOR matrix tested, tenant isolation suite (404 on foreign resources)
- [ ] P2 Complexity (time & space) analysis + benchmark + "where it stops scaling and why"
- [ ] P3 Failure handling: crash/replay, timeouts, duplicates, out-of-order, partial failures; ≥ 1 failure-injection test (we have several)
- [ ] `ACCEPTED` and `durableAckedSeq` have distinct semantics; crash replay, skipped ranges, events, and checkpointed totals are atomic (D-10/D-15)
- [ ] P4 OOP: Zone hierarchy, ZoneIndex polymorphism, controller/service/repository layering enforced by ArchUnit
- [ ] P5 `docs/tradeoffs.md` (consistency vs availability vs latency) + deliberate omissions
- [ ] P6 Metrics (lag, error rate, rejections, retries, queue depth) + structured logs + runbook
- [ ] P7 Caching with eviction policy; staleness table with bounds; tests
- [ ] P8 ProblemDetail errors without leakage; no PII in logs (`LogPrivacyTest`)

## Advanced Track
- [ ] A1 Every hard rule has an automated test (`docs/test-matrix.md` check script passes); concurrency + failure tests in `verify`
- [ ] A2 Load test/benchmark with throughput and p50/p99 in README **with machine**
- [ ] A3 Design note (1–2 pages) with architecture diagram, sequence diagram (live edit), failure-mode table
- [ ] A4 `docker compose up --build` runs everything (DB included) and reaches readiness

## Submission
- [ ] S1 Java + Spring Boot (Java 25, Boot 4.1.x, latest non-deprecated dependencies; `grep` for SNAPSHOT/RC/M returns nothing)
- [ ] S2 Plan documented first (design note)
- [ ] S3 Core algorithms in plain classes (`zone-core` has no framework deps; enforced)
- [ ] S4 Normal + edge-case + concurrency + failure + benchmark tests
- [ ] S5 README with run steps, assumptions, benchmark results, trade-offs
- [ ] S6 Meaningful commit history (≥ 25 commits, Conventional Commits), ready to push to a public GitHub repo; push commands listed for the human
- [ ] S7 `docs/demo-script.md` covering main flows, one failure handled live, sample API calls; `docs/api.http` + Postman collection

## Last-mile sanity (run these and paste results in FINAL-REPORT)
```bash
git status --short                      # clean
./mvnw -q verify                        # green
docker compose up --build --wait        # healthy
bash scripts/smoke-compose.sh           # passes
grep -RIn "SNAPSHOT\|-RC[0-9]\|-M[0-9]" --include=pom.xml . | grep -v "<version>.*-SNAPSHOT</version>" # no pre-release deps
grep -RIn "System.out\|printStackTrace\|Thread.sleep" zone-core zone-service/src/main || true
```
