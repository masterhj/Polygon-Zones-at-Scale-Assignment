# 13 — Phase 10: One-Command Run, Docs, Design Note, Demo

**Goal:** everything a reviewer needs: `docker compose up --build`, README, design note, runbook, demo script, clean history.
**Requirements:** A3, A4, S5, S6, S7, P2, P5.

## 1. Docker / Compose (A4)

`Dockerfile` (repo root, multi-stage, reproducible):
```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build        # RESOLVE: use the current Temurin 25 tag; optionally pin a digest
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -pl zone-service -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 zones
WORKDIR /app
# ensure exactly one executable jar is copied (exclude any *-plain.jar)
COPY --from=build /src/zone-service/target/zone-service-*[!n].jar app.jar
USER 10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
Notes: the glob above is only a hint — make the jar name deterministic instead (set `<finalName>zone-service</finalName>` in the `spring-boot-maven-plugin`/build config and copy `zone-service.jar`). `curl` is installed so the compose healthcheck can probe the management port. Add a `.dockerignore` (`**/target`, `.git`, `.idea`, `docs/benchmarks/tmp`).

`compose.yaml` (no top-level `version:`):
```yaml
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: zones
      POSTGRES_USER: zones
      POSTGRES_PASSWORD: ${DB_PASSWORD:-zones_dev_only}
    volumes:
      - pgdata:/var/lib/postgresql        # Postgres 18 image layout
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U zones -d zones"]
      interval: 5s
      timeout: 3s
      retries: 20
  app:
    build: .
    # One active replica is required by D-20; do not scale this service horizontally.
    depends_on:
      db: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: demo
      DB_URL: jdbc:postgresql://db:5432/zones
      DB_USER: zones
      DB_PASSWORD: ${DB_PASSWORD:-zones_dev_only}
      JWT_SECRET: ${JWT_SECRET:-demo-only-change-me-demo-only-change-me-0123456789}
      DEMO_PASSWORD: ${DEMO_PASSWORD:-demo-pass-change-me}
    ports:
      - "8080:8080"
      - "127.0.0.1:8081:8081"           # management bound to localhost only
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8081/actuator/health/readiness || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 20s
volumes:
  pgdata:
```
- `docker compose up --build` must bring the whole system to **readiness** (snapshots loaded, demo data seeded) with no other command.
- `scripts/smoke-compose.sh`: `docker compose up -d --build --wait`, runs `scripts/demo-calls.sh` (login → create zones → query → trip → pings → attribution) asserting key outputs with `curl` + `jq`-free shell or a tiny Java `main` (do not require extra tools), then `docker compose down -v`. Run it in CI as a separate job.

## 2. README.md (root) — required sections, in this order
1. What this is (2 paragraphs) + badge for CI.
2. **Quick start** — `docker compose up --build`, demo credentials (demo-only!), URLs/ports.
3. **API tour** with real `curl` examples (login, validate polygon, create zones with a hole, point query, as-of query, start trip, post pings, complete, attribution, events, alerts, metrics).
4. **Design in one page**: architecture picture (link to design note), D-02 boundary rule, D-04 distance method, D-06 overlap priority, D-09 trip pinning rule — each in 2–4 sentences.
5. **Spatial index choice and complexity** (R2.3, P2) — why STR R-tree + edge bands, Big-O, memory formula, **where it stops scaling and why**.
6. **Distance method and measured error** (K1) — table from `docs/benchmarks/distance-error.csv`.
7. **Benchmarks** — environment table + results tables (throughput, p50/p99, build time, memory), the "during rebuild" comparison, HTTP-level numbers; link to raw files. **Only measured numbers.**
8. **Failure handling** summary + link to failure-mode table; list of failure-injection tests.
9. **Security & tenancy** summary; role matrix.
10. **Observability** summary + link to runbook; metrics list.
11. **Caching & staleness bounds** table.
12. **Assumptions** (all D-xx decisions, short) and **limitations**.
13. **Trade-offs & what was deliberately left out** (link `docs/tradeoffs.md`).
14. **Testing** — how to run each test group; link to `docs/test-matrix.md`.
15. **Project layout**, **how to run locally without Docker**, **contributing/commit style**.

## 3. Design note (`docs/design-note.md`, 1–2 pages — keep it tight)
Contents (A3, S2):
- Architecture diagram (Mermaid from file 03 §1; also export a PNG/SVG into `docs/img/` if a renderer is available — otherwise keep Mermaid, which GitHub renders).
- Data model summary (tables + the zone-set version idea).
- API summary (one table).
- **One sequence diagram — the hardest flow**: live zone edit while a trip is in progress (file 03 §6, updated to match the final code).
- **Failure-mode table** (file 03 §7 extended with everything actually implemented: *what fails / what the user sees / how the system recovers*).
- Decision list (D-xx) in one line each.
Verify the final length ≈ 2 pages when rendered; move anything extra to README/trade-offs.

## 4. Demo script (`docs/demo-script.md`, S7) — for the human to record
Target 6–8 minutes. Steps with exact commands and expected outputs (screenshots optional):
1. `docker compose up --build` → show readiness.
2. Login as admin of `acme`; show JWT claims (opaque ids only).
3. **Validation rejects**: open ring, bow-tie, hole outside — show specific error codes.
4. Create city + airport (with hole) + no-go zones; query points: inside, on edge, on hole edge, inside hole.
5. **Airport drop**: start trip, post pings in batches (use `scripts/gen-airport-trip.sh`/Java generator that outputs a ≈42 km route); show attribution **30.4 / 11.6 / 42.0**, events with dwell.
6. **Night no-go alert**: send a night-time trip entering the no-go zone; show the alert and the metric.
7. **Live edit**: run a background query loop (`scripts/query-loop.sh`), shrink the no-go zone, show `setVersion` changing with no errors; show old version still queryable; show trip pinned to the old version for billing.
8. **Failure scenario live**: (a) `docker compose pause db` during pings → pings may be `ACCEPTED` into bounded memory while `durableAckedSeq` stays fixed, queries remain available, readiness degrades; `docker compose unpause db` → checkpoint retries commit and durable progress resumes; **or** (b) stop the single app container mid-trip, restart it, resume from `durableAckedSeq + 1`, and verify final totals. Do not imply accepted pings are durable.
9. Duplicate + out-of-order pings → show counters in `/actuator/prometheus`.
10. Tenant isolation: tenant `globex` token cannot see `acme` resources (404).
11. Show `docs/benchmarks` results and the test suite run (`./mvnw verify` summary).
Provide `docs/api.http` / Postman collection used in the video.

## 5. Final polish checklist
- [ ] Repo hygiene: no secrets (only documented demo defaults), no `target/`, no IDE files, no large binaries.
- [ ] `LICENSE`, `README.md`, `docs/*` complete; links valid.
- [ ] Commit history: ≥ 25 meaningful commits following Conventional Commits, one or more per phase; no "wip" commits; no single giant commit. (If you worked in fewer commits, split by re-staging logically **before** final.)
- [ ] `CHANGELOG.md` (optional) and Git tag `v1.0.0`.
- [ ] `docs/FINAL-REPORT.md`: what was built, measured results, deviations (`docs/DECISIONS-DEVIATIONS.md`), known limitations, exact reproduction steps.
- [ ] One active application replica is configured; no multi-instance consistency is claimed (D-20).
- [ ] The human pushes to a **public** GitHub repo (`git remote add origin ...; git push -u origin main --tags`) — tell the human these exact commands in the final message; do not attempt to push without credentials.

## Suggested commits
- `build(docker): multi-stage image and compose stack with healthchecks`
- `feat(demo): demo profile seeder and scripts`
- `docs: README, design note, runbook, trade-offs, demo script`
- `chore(release): v1.0.0`
