# 02 — Tech Stack, Versions, Commands

Brief date: **2026-10-31**. Versions below were checked against public sources on that date. Where marked **RESOLVE**, look up the latest stable at build time (Maven Central / official site) — do not trust this file blindly, and **never pin a milestone/RC/SNAPSHOT**.

## 1. Platform choices

| Concern | Choice | Status / note |
|---|---|---|
| Language/JDK | **Java 25 (LTS)** | Verified: Java 25 is the current LTS (GA Sept 16, 2025); JDK 26/27 exist but are non-LTS. Use `eclipse-temurin:25` images. Set `maven.compiler.release=25`. |
| Framework | **Spring Boot 4.1.x** (latest 4.1 patch) on **Spring Framework 7.0.x**, Spring Security 7.x, Hibernate ORM 7.x | Verified: Boot 4.1.0 GA 2026-06-10 (4.0.7 also released). **RESOLVE** latest 4.1 patch. Boot 4 requires Jakarta EE 11 / Servlet 6.1. |
| Build | **Maven** multi-module + Maven Wrapper | **RESOLVE** latest stable 3.9.x for the wrapper (use Maven 4 only if GA and every plugin works). |
| Database | **PostgreSQL 18** (`postgres:18` image) | Verified: 18.x current major. Note: in the 18 Docker image mount the volume at `/var/lib/postgresql` (not `/var/lib/postgresql/data`). |
| Migrations | **Flyway** (via Boot's Flyway starter) + `flyway-database-postgresql` module | Version managed by Boot BOM. |
| JSON | **Jackson 3** (`tools.jackson.*` packages; annotations stay `com.fasterxml.jackson.annotation.*`) | Boot 4 default. Do **not** add `spring-boot-jackson2` / Jackson 2 compat. |
| Metrics | **Micrometer** + `micrometer-registry-prometheus`, Spring Boot Actuator | BOM-managed. |
| Logging | Spring Boot **built-in structured logging** (`logging.structured.format.console=ecs` or `logstash`) | No extra logback encoder dependency. |
| Cache | **Caffeine** via Spring Cache | BOM-managed. |
| Security | Spring Security 7 + **OAuth2 Resource Server (Nimbus JWT, HS256 or RS256)** | No JJWT dependency (reference repo hit JJWT version mismatches). |
| Resilience | **Spring Framework 7 core resilience** (`@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods`) | Verify annotation package in the Spring 7 reference; fall back to a tiny hand-written retry helper if unavailable. Do not add Resilience4j unless Boot-4-compatible release is confirmed. |
| Tests | JUnit Jupiter (BOM-managed), AssertJ, Awaitility, Mockito (BOM), **Testcontainers 2.x**, **ArchUnit**, optional **jqwik** | Verified: Testcontainers Java **2.0.5** current. 2.x artifact names changed (see §3). |
| Geometry oracle (tests only) | **JTS `jts-core`** | Last release I could verify: **1.20.0** (Sept 2024). **RESOLVE** newer. `test` scope only in `zone-core`; production geometry is our own code (S3). |
| Benchmarks | **JMH 1.37** (`jmh-core`, `jmh-generator-annprocess`), **HdrHistogram** (**RESOLVE** 2.2.x), optional **JOL** (**RESOLVE**) for memory | JMH 1.37 verified as the widely used latest. |
| Containers | Docker Compose v2 (`compose.yaml`) | Do not use the obsolete top-level `version:` key. |

## 2. Maven modules

```
polygon-zones-service/
├── pom.xml                 (parent, packaging=pom, imports Spring Boot BOM)
├── zone-core/              (plain Java 25, ZERO Spring/JPA/Jackson deps; JTS+JUnit+jqwik in test scope)
├── zone-service/           (Spring Boot app; depends on zone-core)
└── zone-bench/             (JMH + load-test harness; depends on zone-core, optionally zone-service)
```

Enforce "plain core" with `maven-enforcer-plugin` `bannedDependencies` (ban `org.springframework*`, `jakarta.persistence*`, `tools.jackson*`, `com.fasterxml.jackson*` in compile scope of `zone-core`) **and** an ArchUnit test.

## 3. Dependency list (zone-service) — verify exact starter names against the Boot 4.1 docs / start.spring.io

Spring Boot 4 split starters into smaller modules and added per-technology test starters. Expected names (**verify**; if a name does not resolve, generate the project from start.spring.io and copy what it produces):

Compile/runtime:
- `spring-boot-starter-webmvc`
- `spring-boot-starter-validation`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-security`
- `spring-boot-starter-security-oauth2-resource-server`
- `spring-boot-starter-actuator`
- `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine`
- `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` (runtime)
- `io.micrometer:micrometer-registry-prometheus` (runtime)

Test:
- `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-security-test` (and any other `*-test` companion of a used starter)
- `spring-boot-testcontainers`
- `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter` (2.x names; import `org.testcontainers:testcontainers-bom` 2.0.x or let the Boot BOM manage)
- `org.awaitility:awaitility`, `com.tngtech.archunit:archunit-junit5` (**RESOLVE**)
- JTS (`org.locationtech.jts:jts-core`) in `zone-core` test scope only

## 4. Generating the skeleton (needs network)

```bash
# Spring Initializr (omit bootVersion to get the latest stable; then inspect the generated pom)
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d groupId=com.polyzones -d artifactId=zone-service -d name=zone-service \
  -d packageName=com.polyzones.service \
  -d dependencies=web,validation,data-jpa,security,oauth2-resource-server,actuator,cache,flyway,postgresql,testcontainers \
  -o /tmp/zone-service.zip && unzip -q /tmp/zone-service.zip -d /tmp/zone-service-init
```
Copy only what you need (parent coordinates, plugin config) into the multi-module layout. If the Initializr dependency ids differ, read `https://start.spring.io/metadata/client` to find them. **Confirm the generated parent version is a final GA release (no `-M`, `-RC`, `-SNAPSHOT`).**

Wrapper:
```bash
mvn -N wrapper:wrapper -Dmaven=<latest-stable-3.9.x>
./mvnw -v        # must show Java 25
```

## 5. Everyday commands

```bash
./mvnw -q -pl zone-core test                         # core only, no Spring, no DB
./mvnw -q -pl zone-core,zone-service verify          # + integration tests (Testcontainers needs Docker)
./mvnw -q verify                                      # everything
./mvnw -q -pl zone-bench -am package -DskipTests      # build benchmarks
java -jar zone-bench/target/benchmarks.jar PointQuery -rf json -rff docs/benchmarks/jmh-pointquery.json
docker compose up --build                             # one-command run
docker compose down -v
```

Useful JVM flags for benchmark runs (record them in the README): `-Xms2g -Xmx2g -XX:+UseZGC` (compare with default G1 for p99 under the live-swap test and keep the better one).

## 6. BANNED / deprecated — do not use

| Don't | Use instead |
|---|---|
| `javax.*` imports | `jakarta.*` |
| `WebSecurityConfigurerAdapter`, `antMatchers/mvcMatchers`, `authorizeRequests()`, `@EnableGlobalMethodSecurity` | `SecurityFilterChain` bean, `authorizeHttpRequests(...)` with `requestMatchers`, `@EnableMethodSecurity` |
| `RestTemplate` for new code | `RestClient` (only if outbound calls are needed; none planned) |
| `com.fasterxml.jackson.databind.*` / `ObjectMapper` (Jackson 2) | `tools.jackson.databind.json.JsonMapper` (Jackson 3) |
| Spring Boot 3.x or 4.0 parent, any `-M*`/`-RC*`/`-SNAPSHOT` | latest stable 4.1.x |
| JJWT, Nimbus used manually without Spring support | Spring Security Resource Server JWT support (`NimbusJwtEncoder/Decoder`) |
| Undertow | Tomcat (default) |
| Spock | JUnit Jupiter |
| `spring.factories` auto-config registration | `META-INF/spring/*.imports` |
| `Thread.sleep` in tests | Awaitility / latches |
| `new Date()`, `System.currentTimeMillis()` in domain code | injected `Clock` |
| Hibernate `@Type` with legacy `hibernate-types` | none needed (geometry stored as text) |
| Compose file `version:` key | omit it |
| `md5` Postgres auth | SCRAM (default) |
| Committing `target/`, `node_modules/` | proper `.gitignore` |

## 7. Language features to use (Java 25)
Records for value objects/DTOs, sealed interfaces for events/results, pattern-matching `switch`, `var` sparingly, text blocks for SQL, `java.util.concurrent` (`AtomicReference`, `ConcurrentHashMap`, `StampedLock` only if justified), virtual threads **only** for blocking I/O at the HTTP edge (`spring.threads.virtual.enabled=true`); the ping pipeline uses a fixed set of **platform-thread shard executors** to guarantee per-trip ordering.
