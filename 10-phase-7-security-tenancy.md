# 10 — Phase 7: Authentication, Roles, Tenant Isolation

**Goal:** stateless JWT auth with three roles and hard tenant isolation.
**Requirements:** P1, P8. **Decisions:** D-14.

**Deployment constraint:** exactly one active `zone-service` instance per database in the initial design (D-20). Tenant filters and composite database keys remain mandatory; process-local registries are not a multi-instance consistency mechanism.

## 1. Authentication
- `POST /api/v1/auth/login` `{tenant, username, password}` → `{accessToken, expiresInSeconds, tokenType:"Bearer"}`.
  - Look up `(tenant name → tenant id, username)`; verify with `DelegatingPasswordEncoder` (default bcrypt; **do not** invent your own hashing). Constant-time response shape for unknown user vs wrong password (same message, same status `401`, similar latency — run a dummy hash check for unknown users).
  - Token: JWT signed with **HS256 using a secret from env `JWT_SECRET` (≥ 32 random bytes; fail fast at startup if missing/short outside the `demo`/`test` profile)** — or RS256 with a key pair from env/file if you prefer (document the choice). Use Spring Security's `NimbusJwtEncoder`/`NimbusJwtDecoder` (no JJWT).
  - Claims: `iss`, `sub` (user id, opaque UUID), `tid` (tenant id), `roles` (array, e.g. `["ADMIN"]`), `iat`, `exp` (default **15 min**), `jti`. **No** email/phone/name in the token.
  - Refresh tokens: **out of scope** (document under "left out"); clients re-login.
- Login throttling: per `(tenant, username, ip)` sliding window using a Caffeine cache (e.g. 5 failures / 5 min ⇒ `429`); log only the opaque tenant id and a hash of the username.
- Resource server config (`SecurityConfig`):
  - `SecurityFilterChain` with `authorizeHttpRequests`: `/api/v1/auth/login` and `/actuator/health/**` public; everything else authenticated; `anyRequest().denyAll()` as the final rule; stateless sessions; CSRF disabled **only** because there are no cookie sessions (comment why); CORS disabled by default (explicit allow-list property if needed).
  - `JwtAuthenticationConverter` maps `roles` → `ROLE_*` authorities.
  - Security headers defaults on; `HttpFirewall` strict (Spring default).
  - Management endpoints (`/actuator/prometheus`, etc.) on **port 8081**, not published by compose to the host except for demo, and protected by a separate role/basic-auth or network boundary — document the choice.

## 2. Authorisation matrix (enforce with `@EnableMethodSecurity` + `@PreAuthorize` on application services, and also at the filter-chain level for defence in depth)

| Capability | EMPLOYEE | OPERATOR | ADMIN |
|---|:-:|:-:|:-:|
| Create/update/delete/validate zones | ✗ | ✗ | ✓ |
| List/read zones & versions | ✓ (read, latest only) | ✓ | ✓ |
| Point queries (single/batch, as-of) | ✗ | ✓ | ✓ |
| Start trip / send pings / complete trip | ✗ | ✓ | ✓ (start/complete only) |
| Read a trip, attribution, events | **own trips only** (`trip.employeeId == jwt.sub`) | ✓ (tenant) | ✓ (tenant) |
| List/ack alerts | ✗ | ✓ | ✓ |
| Zone-set history / publish status | ✗ | ✓ | ✓ |
| Manage users | — (out of scope; seed only) | — | — |

Violations → `403` (same tenant) — but **foreign-tenant resources → `404`**.

## 3. Tenant isolation (D-14) — layered
1. **Source of truth:** `TenantContext.current()` is derived only from the validated JWT `tid`; there is **no** tenant id in request bodies/paths/query strings. A custom `OncePerRequestFilter` (after authentication) sets `TenantContext`/MDC (opaque tenant id) and clears it in `finally`.
2. **Repository layer:** every query includes `tenant_id`; ArchUnit rule from file 09; composite primary keys include `tenant_id`.
3. **Memory layer:** `SnapshotRegistry` keyed by tenant; `PinnedSnapshotRegistry` keys include tenant; ping pipeline state keyed by `(tenantId, tripId)` — a trip id from another tenant must be unreachable even if guessed. These registries are process-local and rely on the one-active-instance constraint; restart reloads from Postgres.
4. **Database layer (SHOULD):** Postgres Row-Level Security policies on tenant tables using `set_config('app.tenant_id', ?, true)` at transaction start (a `TransactionSynchronization` or a `DataSource` wrapper). If implemented, add an IT that disables the app-level `WHERE tenant_id` in a deliberately naive native query and proves RLS still blocks cross-tenant reads.
5. **Metrics/logs:** tenant label values are opaque ids; no tenant *names* in metrics; cardinality guard (if > 50 tenants, drop the tag).
6. **Caches:** all cache keys start with tenant id (`ZoneCacheKey(tenantId, zoneId, version)`); a unit test asserts two tenants with identical zone ids never share entries.

## 4. Seeding for the demo (profile `demo` only — `DemoDataSeeder`)
Two tenants `acme` and `globex`, each with `admin`, `operator`, `employee` users. Passwords from env vars (`DEMO_PASSWORD`, default documented as **demo-only** in the README); never enable the `demo` profile by default. Seed: a Bengaluru-style city zone, an airport zone with a hole (terminal apron) overlapping the city's edge, a tariff zone, and a night-sensitive no-go polygon — for the demo script.

## 5. Tests
- `AuthIT`: login success/failure shapes, expired token, tampered token, wrong signature, missing `tid`, token for disabled user.
- `RoleMatrixIT`: parameterised over (role × endpoint) from the matrix above, asserting 2xx/403 exactly.
- `TenantIsolationIT` (must be thorough): tenant A creates zones/trips/alerts; tenant B (every role) attempts: read by id (404), list (no A items), update/delete (404), query as-of A's set version (404), send pings to A's trip id (404), read A's attribution/events/alerts (404), guess A's idempotency key. Also: pings from tenant B for the *same* `tripId` UUID as A's trip create/affect nothing in A.
- `JwtClaimsTest`: no PII claims.
- `LoginThrottleTest`.
- `LogPrivacyTest` (shared with file 11): capture logs during all of the above and assert no email/phone-like patterns, no password strings, no JWT strings (`eyJ`), no raw lat/lon pairs.

## 6. Acceptance criteria
- [ ] Startup fails fast with an unmistakable message if `JWT_SECRET` is absent/weak (non-demo).
- [ ] Role matrix and tenant isolation tests pass with zero cross-tenant leakage.
- [ ] No endpoint is accidentally public (`SecurityRulesTest` enumerates `RequestMappingHandlerMapping` and asserts each non-allow-listed mapping returns 401 without a token).

## Suggested commits
- `feat(security): JWT resource server with roles and tenant claim`
- `feat(security): login with throttling and constant-shape errors`
- `feat(tenancy): tenant context filter and tenant-scoped caches`
- `test(security): role matrix and cross-tenant isolation suite`
