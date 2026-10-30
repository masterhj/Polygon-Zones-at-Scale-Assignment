# AGENTS.md — Polygon Zones at Scale (Master Brief)

> Read this file first, then follow the numbered files in order.
> Brief written: 2026-10-31. Target: MoveInSync Backend Case Study 2026, **Case Study 7 — "Polygon Zones at Scale: Tariff Zones, No-Go Areas & Trip Attribution"**, SDE Java Backend, **Advanced Track**.

## 1. Mission

Build a working, tested, benchmarked, documented Java backend service that:

1. Stores admin-drawn polygons (non-convex, with holes, thousands of vertices), validated and versioned.
2. Answers "which zones contain this point?" for 50,000 pings/sec against 5,000 polygons using a spatial index (never testing every polygon per ping).
3. Attributes every trip's distance to tariff zones (splitting segments exactly at boundary crossings), emits zone ENTER/EXIT events with dwell time, and raises no-go alerts for night trips.
4. Lets admins edit zones live: background index rebuild + atomic swap, with a defined rule for trips already in progress.
5. Ships the eight "plus points" (auth + tenant isolation, complexity analysis, failure handling, OOP layering, trade-offs, monitoring, caching, error handling) and every "Advanced Track" artefact (tests for every hard rule, measured benchmark, design note, one-command run).

The final repo will be pushed by the human to a **public GitHub repository**, so commit history must be meaningful.

## 2. Read order

| # | File | Purpose |
|---|------|---------|
| 01 | `01-requirements-and-decisions.md` | Every requirement from the PDF with an ID, plus the design decisions already made for the points the PDF leaves open. **These decisions are binding.** |
| 02 | `02-tech-stack-and-commands.md` | Verified versions, dependency list, banned/deprecated APIs, setup commands. |
| 03 | `03-architecture.md` | Modules, packages, class design, SQL schema, REST API, sequence diagram, failure-mode seed table. |
| 04 | `04-phase-1-bootstrap.md` | Repo, Maven multi-module skeleton, CI, Postgres, first commit. |
| 05 | `05-phase-2-geometry-core.md` | Polygon model, validation, point-in-polygon, boundary rule, distance. |
| 06 | `06-phase-3-spatial-index-and-benchmark.md` | STR R-tree, edge bands, naive baseline, JMH benchmarks. |
| 07 | `07-phase-4-versioning-and-live-swap.md` | Versioned zones, snapshot registry, atomic swap, pinned snapshots, concurrency test. |
| 08 | `08-phase-5-trip-attribution.md` | Segment splitting, distance by zone, events, dwell, no-go alert, ping pipeline. |
| 09 | `09-phase-6-api-persistence-oop.md` | Spring Boot layers, REST, JPA/JDBC, error model. |
| 10 | `10-phase-7-security-tenancy.md` | JWT, roles, tenant isolation. |
| 11 | `11-phase-8-resilience-observability-caching.md` | Failure handling, metrics, structured logs, caching. |
| 12 | `12-phase-9-testing-and-proof.md` | Test matrix (rule → test), concurrency/failure/benchmark/load tests. |
| 13 | `13-phase-10-docker-docs-demo.md` | docker compose, README, design note, demo script, final polish. |
| 14 | `14-reference-repos-borrow-map.md` | What to borrow (ideas only) from the three reference repos, per phase. |
| 15 | `15-final-checklist.md` | Tick-box checklist mapped to every line of the PDF. |

## 3. Non-negotiable ground rules

1. **Latest stable, non-deprecated stack.** Use the versions in file 02. Before adding any dependency, resolve its latest stable version from Maven Central / the Spring Boot BOM. Never use an API marked deprecated or removed in the pinned versions (see the banned list in file 02). Let the Spring Boot BOM manage versions of anything it manages; pin explicitly only what it does not.
2. **Core is plain Java.** All geometry, index, validation, splitting and trip-attribution algorithms live in module `zone-core` with **zero Spring/JPA/Jackson dependencies**, testable without a web server or database.
3. **Never fabricate numbers.** Benchmark results, latencies, memory figures, error bounds in README/design note must come from runs you actually executed in this environment. Record the machine (CPU model, cores, RAM, OS, JDK) next to every figure. If something could not be measured, write "not measured" and say why.
4. **Every hard rule has an automated test** (see file 12). The suite must include at least one concurrency test and one failure-injection test, and must pass with `./mvnw verify`.
5. **Inject time.** Never call `Instant.now()` / `LocalDateTime.now()` directly in domain or service code; inject `java.time.Clock`.
6. **No personal data in logs or error responses.** No phone, email, home location, or raw employee coordinates at INFO or above. Use opaque UUIDs. Error bodies never contain stack traces, SQL, class names, or other tenants' identifiers.
7. **Decisions in file 01 are final.** If you find a genuine contradiction or a decision that cannot work, stop that item, document the issue in `docs/DECISIONS-DEVIATIONS.md`, choose the smallest safe alternative, and continue. Do not silently deviate.
8. **Do not copy code from the reference repos.** Only two of them have a license that is visible (see file 14); use them as inspiration for structure and ideas, and write original code.
9. **Small, meaningful commits.** One commit (or several) per phase using Conventional Commits (`feat(core): ...`, `test(index): ...`, `docs: ...`). Never one giant commit. Suggested commit messages are listed at the end of each phase file.
10. **Keep going without asking** unless blocked by something only the human can provide (e.g. GitHub push credentials). Make a reasonable choice, write it in the PR/commit message and `docs/DECISIONS-DEVIATIONS.md`, and continue.

## 4. Definition of Done (whole project)

- `docker compose up --build` starts Postgres + the service with **one command**, and the service reports ready.
- `./mvnw verify` is green from a clean checkout (unit, property, integration with Testcontainers, concurrency, failure-injection tests).
- `docs/benchmarks/` contains raw outputs and a summary table (throughput, p50/p99, index build time, memory) with machine details; README quotes the same numbers.
- `docs/design-note.md` (1–2 pages) has: architecture diagram, one sequence diagram (live zone edit while a trip is in progress), failure-mode table.
- `README.md` has: how to run, API quick tour with curl, assumptions, decisions summary, trade-offs, what was deliberately left out, benchmark results, where the system stops scaling.
- `docs/demo-script.md` lets a human record the demo video (main flows, one failure handled live, sample API calls).
- File 15 checklist is fully ticked, or each unticked item has an honest explanation.

## 5. Working method

1. Create a task list from the phase files; work phase by phase in order (each phase compiles and passes its tests before the next starts).
2. After each phase: run `./mvnw -q verify`, commit, and tick the phase's acceptance criteria.
3. Prefer `Read/Write/Edit` file tools for files, shell for builds and runs.
4. If the sandbox has no network, write `pom.xml` by hand using the dependency list in file 02 instead of calling start.spring.io, and say so in the commit message.
5. At the end, produce `docs/FINAL-REPORT.md`: what was built, what was measured, what was left out, how to reproduce.
