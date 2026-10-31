# 09 — Phase 6: Spring Boot Layers, REST API, Persistence, Error Model

**Goal:** a clean controller → service → repository application exposing the API in file 03, wiring the core and the publisher/pipeline from phases 3–5.
**Requirements:** P4, P8, S1, S3. **Decisions:** D-05, D-12, D-14.

## 1. Layering rules (enforced by ArchUnit — `ArchitectureTest`)
- `..web..` may depend on `..application..` and DTOs only. It must **not** import `..persistence..`, `javax/jakarta.persistence`, or `zone-core` shape internals (use mapper classes in `application`).
- `..application..` holds `@Transactional` boundaries and orchestration; no HTTP types (`HttpServletRequest`, `ResponseEntity`) inside.
- `..persistence..` owns entities/repositories/codecs; entities never leave the persistence/application boundary (map to records).
- `zone-core` imports **only** the JDK. Verify with ArchUnit across the classpath and the Maven enforcer rule.
- No field injection; constructor injection only; `final` fields; no `static` mutable state; no `@Autowired` on fields.
- No `System.out`, no `e.printStackTrace()`; use SLF4J with parameterised messages.
- Transaction boundaries are owned by application services (including narrow `ZonePublicationTxService` and `CheckpointPersistenceService` operations); read-only where possible. The publisher and trip checkpointer orchestrate these transactions but never hold one open during queue waits, geometry validation, snapshot building, or retries. A checkpoint's cumulative state, distance totals, events, alerts, and durable ack commit atomically.

## 2. DTOs and validation
- Java **records** for all request/response DTOs under `web.dto`, annotated with Jakarta Bean Validation (`@NotNull`, `@Size`, `@DecimalMin/@DecimalMax` for lat/lon, `@Valid` on nested).
- Geometry in DTOs: GeoJSON-like `{ "type":"Polygon", "coordinates":[[[lon,lat],…],…] }`. A `GeometryCodec` in `persistence` converts DTO ⇄ `PolygonShape` ⇄ stored GeoJSON text; the codec is the only place that touches the text format.
- Request size limits: `spring.servlet.multipart` unused; set `server.tomcat.max-swallow-size`, `server.max-http-request-header-size`, and a JSON body cap (e.g. 10 MB) through a filter/`spring.mvc` config — document the figure; reject larger with `413`.
- Pagination (`page`, `size ≤ 200`) on list endpoints.

## 3. Persistence
- Spring Data JPA (Hibernate 7) entities for `tenants`, `app_users`, `zones`, `zone_versions`, `zone_set_versions`, `trips`, `trip_zone_distance`, `zone_events`, and `alerts`. Set `open-in-view=false`, `ddl-auto=validate`, Flyway owns the schema. Every tenant-owned FK includes `tenant_id`; use composite references for user, trip, zone/version, and set-version relations. Keep DB checks for status/enum/positive version/range/totals invariants, and index FK columns. See the corrected schema sketch in file 03.
- **Every repository method takes `tenantId`** (e.g. `findByTenantIdAndTripId`). No `findById(UUID)`-style methods without tenant. ArchUnit test: repository interface methods declared in `persistence.repository` must have a `UUID tenantId` parameter (allow-list for `tenants`/`app_users` login lookup).
- Hot batch writes (exact cumulative `trip_zone_distance`/unzoned totals, `zone_events`/`alerts` `ON CONFLICT DO NOTHING`, trip checkpoint `jsonb`) via `JdbcClient` with batch updates; jsonb written with `?::jsonb`, serialised with the Jackson 3 `JsonMapper` bean. Serialize writes per trip and make stale checkpoint snapshots unable to overwrite a later durable frontier.
- Geometry stored as GeoJSON **text** + bbox/vertex_count/hash columns (D-12). `content_hash` = SHA-256 of canonical geometry (used for idempotency and to detect identical content). D-08/R1.4 still requires every accepted edit, including a no-op edit, to create a new zone version and zone-set version; identical geometry may reuse immutable geometry/index data, but it does not silently skip version creation.
- Optimistic concurrency where relevant (`@Version`) on mutable rows (`alerts.acknowledged`).
- Connection pool: HikariCP sized (`maximum-pool-size` 20 default; document reasoning), `connection-timeout=2000 ms`, `validation-timeout`, JDBC `socketTimeout`/`connectTimeout` and a **statement timeout** (`spring.datasource.hikari.data-source-properties.options=-c statement_timeout=5000` or equivalent; **verify** syntax).

## 4. Error handling (P8)
- One `@RestControllerAdvice` (`ApiExceptionHandler extends ResponseEntityExceptionHandler`) returning **RFC 9457 `ProblemDetail`** (`type`, `title`, `status`, `detail`, plus `code`, `correlationId`, and for validation `issues[]` with `code/field/message`).
- Mapping table: validation → 400/422 `VALIDATION_FAILED` or specific polygon codes; unknown/foreign-tenant resource → 404; role mismatch → 403 `FORBIDDEN`; unauthenticated → 401 `UNAUTHENTICATED` (with `WWW-Authenticate`); optimistic lock → 409; backpressure → 429 + `Retry-After`; downstream DB timeout → 503; anything else → 500 `INTERNAL_ERROR` with a generic message.
- **Never** include: stack traces, SQL, table/column names, class names, file paths, other tenants' ids, raw coordinates of employees, emails/phones, JWT contents. The `detail` is a curated sentence.
- `correlationId`: accept inbound `X-Request-Id` if it matches `^[A-Za-z0-9._-]{8,64}$`, else generate; put it in MDC and the response header.
- `server.error.include-*` set to `never`; custom `ErrorController`/`AuthenticationEntryPoint`/`AccessDeniedHandler` return ProblemDetail too (so security errors are shaped identically).
- Tests: `ApiErrorShapeTest` hits every error class and asserts the JSON schema, absence of banned substrings (`Exception`, `at com.`, `SQL`, `org.hibernate`, `postgres`, `jdbc`), and the correlation header.

## 5. Services (OOP; keep each cohesive)
- `ZoneAdminService` (validate/create/update/delete → `ZoneSetPublisher`), `ZoneQueryService` (latest/as-of/batch queries; captures one snapshot reference per call), `TripService` (start/complete/get/attribution/events), `AlertService`, `AuthService`.
- Strategy/polymorphism: `Zone` subclasses decide `billable()` and `onEntry(...)`; `ZoneIndex` injected via a `ZoneIndexFactory` (`rtree` default, `naive` selectable via property for benchmarks/tests only).
- Mappers (`ZoneMapper`, `TripMapper`) are plain classes with unit tests; avoid MapStruct unless a Boot-4/Java-25-compatible release is confirmed.

## 6. Controllers
- Thin: parse/validate, call one service method, map to DTO. Use `@PreAuthorize` (file 10) on methods. Return `201` + `Location` for creates. Batch endpoints return per-item results.
- `/api/v1` prefix via `spring.mvc.pathmatch`/`@RequestMapping`; consistent JSON field names (camelCase); ISO-8601 UTC timestamps.
- Add `docs/api.http` (JetBrains/VS Code REST client format) and `docs/postman_collection.json` generated by hand covering every endpoint; used by the demo.

## 7. Tests
- `@WebMvcTest` slices for controllers (validation, status codes, JSON shape) using the `*-test` starters.
- `ZoneAdminIT`, `ZoneQueryIT`, `TripFlowIT` with Testcontainers: full happy paths including EX1 through the HTTP API (create city+airport zones, start trip, post pings in several batches incl. a duplicate and an out-of-order batch, complete, fetch attribution).
- `ApiErrorShapeTest`, `ArchitectureTest`, `MapperTest`.

## 8. Acceptance criteria
- [ ] All endpoints in file 03 implemented with the documented roles/status codes. Zone/query/trip/alert routes are implemented; `/auth/login` and role enforcement are Phase 7 acceptance.
- [x] ArchUnit layering + repository-tenant-parameter rules pass.
- [x] Error bodies use a uniform ProblemDetail envelope and the tested categories do not leak internal details.
- [ ] `docs/api.http` runs end-to-end against `docker compose up`. This requires Phase 7 JWT and Phase 10 Compose service wiring.

## Suggested commits
- `feat(persistence): flyway schema, entities, tenant-scoped repositories, geometry codec`
- `feat(web): zone admin and query endpoints with validation`
- `feat(web): trip, pings, attribution, events and alerts endpoints`
- `feat(web): problem-detail error model without data leakage`
- `test: web slices, architecture rules, API integration tests`
