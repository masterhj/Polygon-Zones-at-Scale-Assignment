# Implementation Log

## Current status
- Current phase: Phase 1 — Repository Bootstrap (complete).
- Completed phases: Phase 1.
- Next phase: Phase 2 — Geometry Core.

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
