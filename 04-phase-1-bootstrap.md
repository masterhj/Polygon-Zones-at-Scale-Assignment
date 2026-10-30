# 04 — Phase 1: Repository Bootstrap

**Goal:** an empty-but-buildable multi-module project on Java 25 + Spring Boot 4.1.x, CI skeleton, Postgres via Compose, and the first commits.
**Requirements touched:** S1, S3, S6, A4. **Decisions:** D-01, D-12, D-18.

## Tasks

1. **Repo root** `polygon-zones-service/`:
   - `git init`, default branch `main`.
   - `.gitignore` (Java/Maven/IDE, `target/`, `.idea/`, `*.iml`, `.env`, `docs/benchmarks/tmp/`). Never commit build output.
   - `LICENSE` (MIT unless the human says otherwise).
   - Copy this brief's decisions into `docs/` later (phase 10); for now create `docs/DECISIONS-DEVIATIONS.md` with a header only.
2. **Maven wrapper + parent POM** (see file 02):
   - Parent `packaging=pom`, modules `zone-core`, `zone-service`, `zone-bench`.
   - Properties: `java.version=25`, `maven.compiler.release=25`, `project.build.sourceEncoding=UTF-8`.
   - Import the Spring Boot BOM (`spring-boot-dependencies`) in `dependencyManagement` (latest 4.1.x GA) so `zone-core` can stay Spring-free while sharing managed versions of JUnit/AssertJ.
   - Plugins: `maven-compiler-plugin` (with `-Xlint:all`, `-parameters`), `maven-surefire-plugin` (unit tests), `maven-failsafe-plugin` (integration tests `*IT`), `maven-enforcer-plugin` (Java ≥ 25, Maven ≥ 3.9, **banned dependencies in zone-core**, dependency convergence), `spring-boot-maven-plugin` only in `zone-service`, `jacoco-maven-plugin` (report only; **RESOLVE** latest).
3. **`zone-core`**: `pom.xml` with *no* compile dependencies. Test deps: JUnit Jupiter, AssertJ, `org.locationtech.jts:jts-core` (test scope, **RESOLVE** latest), optionally jqwik. Add `package-info.java` for each package describing intent. Add `module-info.java` only if every dependency is modular (skip otherwise — not required).
4. **`zone-service`**: `pom.xml` with the dependencies in file 02 §3. `ZoneServiceApplication` with `@SpringBootApplication`. `application.yaml` (profiles: default, `demo`, `test`) with at least:
   ```yaml
   spring:
     application.name: polygon-zones-service
     threads.virtual.enabled: true
     jackson: {}                # Jackson 3 defaults
     datasource:
       url: ${DB_URL:jdbc:postgresql://localhost:5432/zones}
       username: ${DB_USER:zones}
       password: ${DB_PASSWORD:zones_dev_only}
       hikari: { maximum-pool-size: 20, connection-timeout: 2000 }
     jpa: { open-in-view: false, hibernate.ddl-auto: validate }
     flyway.enabled: true
   server:
     shutdown: graceful
     port: 8080
   management:
     server.port: 8081
     endpoints.web.exposure.include: health,info,prometheus,metrics
     endpoint.health.probes.enabled: true
   logging.structured.format.console: ecs     # structured JSON logs (verify property name in Boot 4.1 docs)
   zones:
     geometry: { boundary-epsilon-deg: 1.0e-9, max-vertices: 50000, max-holes: 256 }
     pipeline: { shards: 0, queue-capacity: 10000, reorder-hold-ms: 2000, reorder-max-buffered: 64, checkpoint-every-pings: 200, checkpoint-every-ms: 2000 }
     cache: { zone-latest-ttl: 5s, historical-snapshots-max-vertices: 5000000 }
   ```
   (`shards: 0` ⇒ `availableProcessors()`.) Secrets only from env vars; the dev defaults above are for local/demo and must be called out in the README.
5. **`zone-bench`**: `pom.xml` with JMH 1.37 (`jmh-core`, annotation processor), HdrHistogram; `maven-shade-plugin` producing `target/benchmarks.jar` (**RESOLVE** plugin version). Empty `package-info` for now.
6. **Postgres for dev**: `compose.yaml` at root with only the `postgres:18` service for now (healthcheck `pg_isready`, named volume mounted at `/var/lib/postgresql`, env `POSTGRES_DB=zones POSTGRES_USER=zones POSTGRES_PASSWORD=zones_dev_only`, port 5432). The full app service is added in phase 10.
   The eventual Compose topology must contain one `zone-service` replica only (D-20); do not add replicas or describe the process-local publisher/registry as multi-instance safe.
7. **CI**: `.github/workflows/ci.yml` — on push/PR: checkout, `actions/setup-java` (Temurin 25, Maven cache), `./mvnw -B verify`. Docker is available on GitHub-hosted runners for Testcontainers. (**RESOLVE** current major versions of the actions; use the latest stable tags.)
8. **Smoke test**: `ZoneServiceApplicationIT` using Testcontainers PostgreSQL (`@ServiceConnection`) asserting the context starts, Flyway ran, and `/actuator/health` is UP. Initial Flyway `V1__tenants_users.sql` from file 03.
9. **`docs/` skeleton**: `docs/README.md` (index), `docs/benchmarks/.gitkeep`.

## Acceptance criteria

- [ ] `./mvnw -v` reports Java 25.
- [ ] `./mvnw -q verify` green from a clean clone (Docker running).
- [ ] `zone-core` has **no** non-test dependencies (`./mvnw -pl zone-core dependency:tree` proves it; enforcer rule active).
- [ ] No snapshot/milestone/RC versions anywhere (`grep -R "SNAPSHOT\|-M[0-9]\|RC[0-9]" --include=pom.xml .` is empty except the project's own version).
- [ ] CI workflow file present.

## Suggested commits
- `chore: initialise multi-module Maven project (Java 25, Spring Boot 4.1)`
- `chore(ci): add GitHub Actions build with Testcontainers`
- `feat(service): boot app with Flyway tenants/users schema and health smoke test`
- `chore(compose): add postgres 18 dev service`
