package com.polyzones.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.polyzones.service.ZoneServiceApplication;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(classes = ZoneServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIT {
    private static final String PASSWORD = "operator-password-123";
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired Clock clock;
    @Autowired RequestMappingHandlerMapping requestMappings;
    private Fixture fixture;

    @BeforeEach void createTenantUsers() {
        UUID tenantId = UUID.randomUUID();
        String tenantName = "security-" + tenantId;
        jdbc.update("insert into tenants(id,name) values (?,?)", tenantId, tenantName);
        fixture = new Fixture(tenantId, tenantName, createUser(tenantId, "admin", "ADMIN", true),
                createUser(tenantId, "operator", "OPERATOR", true), createUser(tenantId, "employee", "EMPLOYEE", true),
                createUser(tenantId, "other-employee", "EMPLOYEE", true), createUser(tenantId, "disabled", "ADMIN", false));
    }

    @Test void loginUsesJwtClaimsAndUnknownWrongPasswordAndDisabledUsersHaveOneFailureShape() throws Exception {
        String success = login(fixture.tenantName(), "admin", PASSWORD)
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900)).andReturn().getResponse().getContentAsString();
        var document = JsonMapper.builder().build().readTree(success);
        String token = document.path("accessToken").asString();
        var jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(fixture.admin().toString());
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(fixture.tenantId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getClaims().keySet()).doesNotContain("name", "email", "phone", "username");

        var wrongPassword = login(fixture.tenantName(), "admin", "not-the-password").andExpect(status().isUnauthorized()).andReturn();
        var unknownUser = login(fixture.tenantName(), "missing-user", PASSWORD).andExpect(status().isUnauthorized()).andReturn();
        var disabled = login(fixture.tenantName(), "disabled", PASSWORD).andExpect(status().isUnauthorized()).andReturn();
        for (var result : List.of(wrongPassword, unknownUser, disabled)) {
            assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).path("code").asString())
                    .isEqualTo("INVALID_CREDENTIALS");
        }
        var wrongBody = JsonMapper.builder().build().readTree(wrongPassword.getResponse().getContentAsString());
        var unknownBody = JsonMapper.builder().build().readTree(unknownUser.getResponse().getContentAsString());
        var disabledBody = JsonMapper.builder().build().readTree(disabled.getResponse().getContentAsString());
        assertThat(wrongBody.path("code").asString()).isEqualTo(unknownBody.path("code").asString()).isEqualTo(disabledBody.path("code").asString());
        assertThat(wrongBody.path("detail").asString()).isEqualTo(unknownBody.path("detail").asString()).isEqualTo(disabledBody.path("detail").asString());
    }

    @Test void bearerValidationRejectsExpiredTamperedWrongSignatureAndMissingTenantTokens() throws Exception {
        String good = token(fixture.operator(), fixture.tenantId(), List.of("OPERATOR"), Instant.now().minusSeconds(5), Instant.now().plusSeconds(120), true);
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + good)).andExpect(status().isOk());

        String expired = token(fixture.operator(), fixture.tenantId(), List.of("OPERATOR"), Instant.now().minusSeconds(600), Instant.now().minusSeconds(300), true);
        String missingTenant = token(fixture.operator(), fixture.tenantId(), List.of("OPERATOR"), Instant.now().minusSeconds(5), Instant.now().plusSeconds(120), false);
        String tampered = good.substring(0, good.length() - 1) + (good.endsWith("A") ? "B" : "A");
        byte[] otherKeyBytes = new byte[32]; java.util.Arrays.fill(otherKeyBytes, (byte) 0x31);
        var otherEncoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(new javax.crypto.spec.SecretKeySpec(otherKeyBytes, "HmacSHA256")));
        String wrongSignature = otherEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("moveinsync-polygon-zones").subject(fixture.operator().toString())
                        .claim("tid", fixture.tenantId().toString()).claim("roles", List.of("OPERATOR"))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).id(UUID.randomUUID().toString()).build())).getTokenValue();
        for (String invalid : List.of(expired, tampered, wrongSignature, missingTenant)) {
            mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        String malformedRoles = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("moveinsync-polygon-zones").subject(fixture.operator().toString())
                        .claim("tid", fixture.tenantId().toString()).claim("roles", "OPERATOR")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).id(UUID.randomUUID().toString()).build())).getTokenValue();
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + malformedRoles))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test void loginThrottleReturns429AfterFiveFailuresForTheTenantUserAndAddressKey() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            login(fixture.tenantName(), "admin", "wrong-password").andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"tenant\":\"" + fixture.tenantName() + "\",\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("300"));
    }

    @Test void foreignTenantCannotReadOrMutateResourcesAndCanReuseSameTripIdSafely() throws Exception {
        String adminA = accessToken("admin", PASSWORD);
        String operatorA = accessToken("operator", PASSWORD);
        String sharedKey = "tenant-scoped-idempotency-key";
        String polygon = "{\"zoneId\":null,\"type\":\"CITY\",\"priority\":1,\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[77,12],[77.01,12],[77.01,12.01],[77,12.01],[77,12]]]}}";
        var firstZone = mvc.perform(post("/api/v1/zones").header("Authorization", "Bearer " + adminA)
                        .header("Idempotency-Key", sharedKey).contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isCreated()).andReturn();
        UUID zoneA = UUID.fromString(JsonMapper.builder().build().readTree(firstZone.getResponse().getContentAsString()).path("zoneId").asString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneA)
                        .header("Authorization", "Bearer " + adminA).header("Idempotency-Key", "version-two")
                        .contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setVersion").value(2));

        UUID tripA = UUID.randomUUID();
        mvc.perform(post("/api/v1/trips").header("Authorization", "Bearer " + operatorA).contentType(APPLICATION_JSON)
                        .content("{\"tripId\":\"" + tripA + "\",\"employeeId\":\"" + fixture.employee()
                                + "\",\"startTime\":\"" + clock.instant() + "\"}"))
                .andExpect(status().isCreated());
        UUID eventId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        Instant eventTime = clock.instant();
        jdbc.update("insert into zone_events(event_id,tenant_id,trip_id,zone_id,zone_version,zone_version_set_version,evaluated_set_version,transition_ordinal,kind,event_time) values (?,?,?,?,2,2,2,1,'ENTER',?)",
                eventId, fixture.tenantId(), tripA, zoneA, java.sql.Timestamp.from(eventTime));
        jdbc.update("insert into alerts(alert_id,tenant_id,trip_id,zone_id,source_event_id,kind,severity,event_time,zone_version,zone_version_set_version,evaluated_set_version) values (?,?,?,?,?,'NO_GO_NIGHT_ENTRY','HIGH',?,2,2,2)",
                alertId, fixture.tenantId(), tripA, zoneA, eventId, java.sql.Timestamp.from(eventTime));

        UUID tenantB = UUID.randomUUID();
        String nameB = "foreign-" + tenantB;
        jdbc.update("insert into tenants(id,name) values (?,?)", tenantB, nameB);
        createUser(tenantB, "admin", "ADMIN", true);
        createUser(tenantB, "operator", "OPERATOR", true);
        UUID employeeBId = createUser(tenantB, "employee", "EMPLOYEE", true);
        String adminB = accessToken(nameB, "admin", PASSWORD);
        String operatorB = accessToken(nameB, "operator", PASSWORD);
        String employeeB = accessToken(nameB, "employee", PASSWORD);

        for (String token : List.of(adminB, operatorB, employeeB)) {
            mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound()); // no B set exists; A's zones are never returned.
            mvc.perform(get("/api/v1/zones/" + zoneA).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        for (String token : List.of(adminB, operatorB)) {
            mvc.perform(get("/api/v1/zones/" + zoneA + "/versions").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/zones?setVersion=2").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/zones/" + zoneA + "/versions").header("Authorization", "Bearer " + employeeB)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + operatorB).contentType(APPLICATION_JSON)
                        .content("{\"lat\":12.005,\"lon\":77.005,\"asOfSetVersion\":2}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + adminB).contentType(APPLICATION_JSON)
                        .content("{\"lat\":12.005,\"lon\":77.005,\"asOfSetVersion\":2}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + employeeB).contentType(APPLICATION_JSON)
                        .content("{\"lat\":12.005,\"lon\":77.005,\"asOfSetVersion\":2}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneA)
                        .header("Authorization", "Bearer " + adminB).contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneA)
                        .header("Authorization", "Bearer " + operatorB).contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneA)
                        .header("Authorization", "Bearer " + adminB)).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneA)
                        .header("Authorization", "Bearer " + employeeB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/" + tripA).header("Authorization", "Bearer " + operatorB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA).header("Authorization", "Bearer " + adminB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA).header("Authorization", "Bearer " + employeeB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA + "/attribution").header("Authorization", "Bearer " + operatorB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA + "/attribution").header("Authorization", "Bearer " + employeeB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA + "/events").header("Authorization", "Bearer " + operatorB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripA + "/events").header("Authorization", "Bearer " + employeeB)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/trips/" + tripA + "/pings").header("Authorization", "Bearer " + operatorB)
                        .contentType(APPLICATION_JSON).content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + clock.instant() + "\",\"lat\":12.005,\"lon\":77.005}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/alerts?tripId=" + tripA).header("Authorization", "Bearer " + operatorB))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/alerts?tripId=" + tripA).header("Authorization", "Bearer " + adminB))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/alerts?tripId=" + tripA).header("Authorization", "Bearer " + employeeB))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts/" + alertId + "/ack").header("Authorization", "Bearer " + operatorB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/alerts/" + alertId + "/ack").header("Authorization", "Bearer " + employeeB))
                .andExpect(status().isForbidden());

        var zoneB = mvc.perform(post("/api/v1/zones").header("Authorization", "Bearer " + adminB)
                        .header("Idempotency-Key", sharedKey).contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isCreated()).andReturn();
        UUID zoneBId = UUID.fromString(JsonMapper.builder().build().readTree(zoneB.getResponse().getContentAsString()).path("zoneId").asString());
        assertThat(zoneBId).isNotEqualTo(zoneA);
        for (String token : List.of(adminB, operatorB, employeeB)) {
            mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$[0].zoneId").value(zoneBId.toString()));
        }
        mvc.perform(post("/api/v1/zones").header("Authorization", "Bearer " + adminA)
                        .header("Idempotency-Key", sharedKey).contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.zoneId").value(zoneA.toString()))
                .andExpect(jsonPath("$.setVersion").value(1));

        mvc.perform(post("/api/v1/trips").header("Authorization", "Bearer " + operatorB).contentType(APPLICATION_JSON)
                        .content("{\"tripId\":\"" + tripA + "\",\"employeeId\":\"" + employeeBId
                                + "\",\"startTime\":\"" + clock.instant() + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/trips/" + tripA + "/pings").header("Authorization", "Bearer " + operatorB)
                        .contentType(APPLICATION_JSON).content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + clock.instant()
                                + "\",\"lat\":12.005,\"lon\":77.005}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
        assertThat(jdbc.queryForObject("select durable_acked_seq from trips where tenant_id=? and trip_id=?", Long.class,
                fixture.tenantId(), tripA)).isEqualTo(-1L);
        assertThat(jdbc.queryForObject("select acknowledged from alerts where tenant_id=? and alert_id=?", Boolean.class,
                fixture.tenantId(), alertId)).isFalse();
    }

    @Test void methodAndRequestMatchersEnforceRolesOwnershipAndTenantBoundaries() throws Exception {
        String adminToken = accessToken("admin", PASSWORD);
        String operatorToken = accessToken("operator", PASSWORD);
        String employeeToken = accessToken("employee", PASSWORD);
        String otherEmployeeToken = accessToken("other-employee", PASSWORD);

        mvc.perform(get("/api/v1/zones")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // The management endpoint is served on its dedicated port and is exercised by
        // ZoneServiceApplicationIT's real HTTP readiness check.
        mvc.perform(post("/api/v1/zones/validate").header("Authorization", "Bearer " + operatorToken)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/zones").header("Authorization", "Bearer " + operatorToken)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/zones/validate").header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content("{\"zoneId\":null,\"type\":\"CITY\",\"priority\":1,\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[77,12],[77.01,12],[77.01,12.01],[77,12.01],[77,12]]]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + employeeToken)
                        .contentType(APPLICATION_JSON).content("{\"lat\":12.9,\"lon\":77.6}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isNotFound()); // allowed latest read; this tenant has not published a snapshot yet.

        String polygon = "{\"zoneId\":null,\"type\":\"CITY\",\"priority\":1,\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[77,12],[77.01,12],[77.01,12.01],[77,12.01],[77,12]]]}}";
        var created = mvc.perform(post("/api/v1/zones").header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content(polygon))
                .andExpect(status().isCreated()).andReturn();
        String zoneId = JsonMapper.builder().build().readTree(created.getResponse().getContentAsString()).path("zoneId").asString();
        mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/zones/" + zoneId).header("Authorization", "Bearer " + employeeToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/zones/" + zoneId + "?version=1").header("Authorization", "Bearer " + employeeToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/zones/" + zoneId + "/versions").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/zones/" + zoneId + "/versions").header("Authorization", "Bearer " + employeeToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/zone-sets").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/zone-sets").header("Authorization", "Bearer " + employeeToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + operatorToken).contentType(APPLICATION_JSON)
                        .content("{\"lat\":12.005,\"lon\":77.005}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/zones/query").header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content("{\"lat\":12.005,\"lon\":77.005}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/zones/query/batch").header("Authorization", "Bearer " + operatorToken).contentType(APPLICATION_JSON)
                        .content("[{\"lat\":12.005,\"lon\":77.005}]"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/zones/query/batch").header("Authorization", "Bearer " + employeeToken).contentType(APPLICATION_JSON)
                        .content("[{\"lat\":12.005,\"lon\":77.005}]"))
                .andExpect(status().isForbidden());
        String editBody = "{\"zoneId\":\"" + zoneId + "\",\"type\":\"CITY\",\"priority\":1,\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[77,12],[77.01,12],[77.01,12.01],[77,12.01],[77,12]]]}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneId)
                        .header("Authorization", "Bearer " + operatorToken).contentType(APPLICATION_JSON).content(editBody))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneId)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON).content(editBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setVersion").value(2));
        mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].zoneId").value(zoneId));
        mvc.perform(get("/api/v1/zones?setVersion=1").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/trips").header("Authorization", "Bearer " + employeeToken).contentType(APPLICATION_JSON)
                        .content("{\"employeeId\":\"" + fixture.employee() + "\",\"startTime\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isForbidden());
        var started = mvc.perform(post("/api/v1/trips").header("Authorization", "Bearer " + operatorToken)
                        .contentType(APPLICATION_JSON).content("{\"employeeId\":\"" + fixture.employee() + "\",\"startTime\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String tripId = JsonMapper.builder().build().readTree(started.getResponse().getContentAsString()).path("tripId").asString();
        mvc.perform(get("/api/v1/trips/" + tripId).header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trips/" + tripId).header("Authorization", "Bearer " + employeeToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trips/" + tripId).header("Authorization", "Bearer " + otherEmployeeToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/" + tripId + "/pings").header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + Instant.now() + "\",\"lat\":12.005,\"lon\":77.005}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/" + tripId + "/pings").header("Authorization", "Bearer " + employeeToken)
                        .contentType(APPLICATION_JSON).content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + Instant.now() + "\",\"lat\":12.005,\"lon\":77.005}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/" + tripId + "/pings").header("Authorization", "Bearer " + operatorToken)
                        .contentType(APPLICATION_JSON).content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + Instant.now() + "\",\"lat\":12.005,\"lon\":77.005}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + operatorToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + employeeToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/" + tripId).header("Authorization", "Bearer " + accessTokenForTenant("operator", PASSWORD, UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/trips/" + tripId + "/complete").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());
        var adminTrip = mvc.perform(post("/api/v1/trips").header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content("{\"employeeId\":\"" + fixture.employee() + "\",\"startTime\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String adminTripId = JsonMapper.builder().build().readTree(adminTrip.getResponse().getContentAsString()).path("tripId").asString();
        mvc.perform(post("/api/v1/trips/" + adminTripId + "/complete").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneId)
                        .header("Authorization", "Bearer " + operatorToken)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneId)
                        .header("Authorization", "Bearer " + employeeToken)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneId)
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from trips where tenant_id=? and trip_id=?", String.class,
                fixture.tenantId(), UUID.fromString(tripId))).isEqualTo("COMPLETED");
    }

    @Test void everyMappedApiRouteRequiresAuthenticationExceptLoginAndHealth() throws Exception {
        int checkedRoutes = 0;
        for (var mapping : requestMappings.getHandlerMethods().keySet()) {
            var paths = mapping.getPathPatternsCondition();
            if (paths == null) continue;
            for (var pattern : paths.getPatterns()) {
                String route = pattern.getPatternString();
                if (!route.startsWith("/api/v1/") || "/api/v1/auth/login".equals(route)) continue;
                String path = route.replaceAll("\\{[^/]+}", UUID.randomUUID().toString());
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                    HttpMethod.valueOf(method.name()), path))
                            .andExpect(status().isUnauthorized());
                    checkedRoutes++;
                }
            }
        }
        assertThat(checkedRoutes).isGreaterThanOrEqualTo(18);
    }

    private UUID createUser(UUID tenantId, String username, String role, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role,enabled) values (?,?,?,?,?,?)",
                id, tenantId, username, passwords.encode(PASSWORD), role, enabled);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions login(String tenant, String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                .content("{\"tenant\":\"" + tenant + "\",\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private String accessToken(String username, String password) throws Exception {
        return accessToken(fixture.tenantName(), username, password);
    }

    private String accessToken(String tenantName, String username, String password) throws Exception {
        return JsonMapper.builder().build().readTree(login(tenantName, username, password)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asString();
    }

    private String accessTokenForTenant(String username, String password, UUID tenantId) throws Exception {
        String tenantName = "foreign-" + tenantId;
        jdbc.update("insert into tenants(id,name) values (?,?)", tenantId, tenantName);
        createUser(tenantId, username, "OPERATOR", true);
        return JsonMapper.builder().build().readTree(login(tenantName, username, password)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asString();
    }

    private String token(UUID user, UUID tenant, List<String> roles, Instant issued, Instant expiry, boolean includeTenant) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("moveinsync-polygon-zones").subject(user.toString())
                .claim("roles", roles).issuedAt(issued).expiresAt(expiry).id(UUID.randomUUID().toString());
        if (includeTenant) claims.claim("tid", tenant.toString());
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private record Fixture(UUID tenantId, String tenantName, UUID admin, UUID operator, UUID employee,
                           UUID otherEmployee, UUID disabled) { }
}
