# Implementation Log

## Current status
- Current phase: Phase 2 — Geometry Core (complete).
- Completed phases: Phase 1, Phase 2.
- Next phase: Phase 3 — Spatial Index and Benchmark, only after Phase 2 acceptance.

## Phase 1 — Repository Bootstrap
- Requirements: S1, S3, S6, A4; decisions D-01, D-12, D-18.
- Status: complete. Established the root Maven reactor, wrapper, modules, Spring service baseline, Flyway tenant/user schema, Postgres Compose service, CI workflow, reusable engineering skill, and docs skeleton.
- Important decisions: project targets Java 25 per canonical specs; Maven Wrapper pins 3.9.16; Spring Boot 4.1.2; Maven distribution cache is project-local for this restricted environment. The runtime was explicitly selected from the installed Homebrew JDK 25.0.2 (default shell `java` remains Java 17).
- Files: root and module POMs; `.mvn/wrapper/*`, `mvnw`; `zone-core` package marker and bootstrap test; `zone-service` application/configuration/migration/Testcontainers smoke test; `zone-bench` POM/package marker; `compose.yaml`; `.github/workflows/ci.yml`; `.gitignore`, `LICENSE`, `README.md`; docs skeleton; `.agents/skills/moveinsync-engineering/SKILL.md`.
- Commands (with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`, `PATH=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin:$PATH`, `MAVEN_USER_HOME=$PWD/target/maven-user-home`): `./mvnw -Dmaven.repo.local=target/maven-repository -v` — exit 0, Maven 3.9.16, Java 25.0.2; `./mvnw -q -Dmaven.repo.local=target/maven-repository verify` — exit 0; `./mvnw -q -Dmaven.repo.local=/private/tmp/moveinsync-maven-repository clean verify` — exit 0 from a clean build; `./mvnw -Dmaven.repo.local=target/maven-repository -pl zone-core dependency:tree -Dscope=compile` — exit 0, no compile dependencies. Docker Desktop ran PostgreSQL 18 through Testcontainers.
- Tests: no unit tests are defined yet because Phase 1 has no core behavior; the integration smoke test passed (1 test, 0 failures/errors/skips). It verified Flyway applied one migration and `/actuator/health` returned HTTP 200 with `UP`.
- Review fixes: removed a placeholder package-name assertion; activated Failsafe and Enforcer (previously only plugin-managed), scoped framework dependency bans to `zone-core`, configured Failsafe to load compiled app classes after Boot repackage, corrected Testcontainers 2.x APIs, fixed datasource/health smoke-test wiring, and configured the shade plugin not to emit a generated dependency-reduced POM. Initial build failures were test-classpath/container API/Failsafe classpath/datasource-precedence issues and were corrected; final clean verification passed.
- Limitations: no geometry or business behavior is implemented in Phase 1. The default shell remains Java 17, so local commands should set `JAVA_HOME` to JDK 25 or use a CI environment that does. Compose starts only Postgres; adding the service container is Phase 10.
- Acceptance: all five Phase 1 criteria are checked in `04-phase-1-bootstrap.md`.

## Phase 2 — Geometry Core
- Requirements: R1.1–R1.3, R2.1–R2.2, K1–K2, S3; decisions D-02 through D-05.
- Status: complete; implementation, acceptance verification and independent diff review complete.
- Implementation: added immutable geo values; closed ring storage and y-band candidate index; even-odd containment with boundary-first D-02 semantics; polygon factory and aggregate validator; segment classifier; packed STR segment-envelope broad phase; Haversine distance and degree interpolation. Production dependencies remain zero.
- Tests: traceability in `docs/geometry-requirements-test-map.md`. Tests cover value behavior, D-02 outer/hole edges and vertices, tolerance, edge-band full-scan equivalence, validation structure/topology, large-ring acceptance, JTS containment samples, 10,000 detector-oracle cases, and Vincenty error analysis.
- Verification: `./mvnw -q -pl zone-core test` — exit 0, 19 tests, 0 failures/errors/skips; `./mvnw -q verify` — exit 0. The distance test generated 36 records in `docs/benchmarks/distance-error.csv`; maximum Haversine relative error 0.56144%, maximum degree-linear midpoint deviation 383.985244m. Machine: Apple M2, 8 cores, 16GB RAM, macOS 26.5.1, JDK 25.0.2. `git diff --check` passed; compile dependency inspection confirmed no Spring/JPA/JSON dependencies in `zone-core`.
- Clarifications: D-03 orientation determinant threshold is `coordinate epsilon × baseline length` (perpendicular-distance units); D-04 geographic extent uses maximum haversine bbox diagonal with a 1,000 km limit. Rationale and boundary behavior are recorded in `docs/DECISIONS-DEVIATIONS.md`.
