package com.polyzones.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Testcontainers
@SpringBootTest(classes = ZoneServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ZoneServiceApplicationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void contextStartsMigrationRunsAndHealthIsUp() throws Exception {
        Integer migrations = jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:8081/actuator/health")).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        HttpRequest prometheusRequest = HttpRequest.newBuilder(URI.create("http://localhost:8081/actuator/prometheus")).GET().build();
        HttpResponse<String> prometheusResponse = HttpClient.newHttpClient().send(prometheusRequest, HttpResponse.BodyHandlers.ofString());
        HttpRequest protectedManagement = HttpRequest.newBuilder(URI.create("http://localhost:8081/actuator/info")).GET().build();
        HttpResponse<String> protectedResponse = HttpClient.newHttpClient().send(protectedManagement, HttpResponse.BodyHandlers.ofString());
        assertThat(migrations).isEqualTo(6);
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("5s");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='zone_set_versions'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='trips'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='alerts'", Integer.class)).isEqualTo(1);
        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(prometheusResponse.statusCode()).isEqualTo(200);
        assertThat(prometheusResponse.body()).contains("jvm_memory_used_bytes");
        assertThat(protectedResponse.statusCode()).isEqualTo(401);
    }

    @Test
    void zoneToTripFlowPersistsTenantScopedVersionsEventsAndDurableAck() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        jdbc.update("insert into tenants(id,name) values (?,?)", tenant, "phase6-" + tenant);
        jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role) values (?,?,?,'unused','EMPLOYEE')",
                employee, tenant, "employee-" + employee);
        jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role) values (?,?,?,'unused','ADMIN')",
                admin, tenant, "admin-" + admin);
        jdbc.update("insert into app_users(id,tenant_id,username,password_hash,role) values (?,?,?,'unused','OPERATOR')",
                operator, tenant, "operator-" + operator);
        SecurityContextHolder.getContext().setAuthentication(authentication(admin, tenant, "ADMIN"));
        try {
            String zoneBody = """
                    {"zoneId":null,"type":"CITY","priority":10,"geometry":{"type":"Polygon","coordinates":[[[77.0,12.0],[77.01,12.0],[77.01,12.01],[77.0,12.01],[77.0,12.0]]]}}
                    """;
            var created = mvc.perform(post("/api/v1/zones").header("Idempotency-Key", "phase6-create-" + tenant)
                            .contentType(APPLICATION_JSON).content(zoneBody))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.setVersion").value(1)).andReturn();
            String zoneId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.getResponse().getContentAsString()).path("zoneId").asString();
            mvc.perform(post("/api/v1/zones/validate").contentType(APPLICATION_JSON).content(zoneBody))
                    .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
            mvc.perform(post("/api/v1/zones").header("Idempotency-Key", "phase6-create-" + tenant)
                            .contentType(APPLICATION_JSON).content(zoneBody))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.zoneId").value(zoneId)).andExpect(jsonPath("$.setVersion").value(1));
            mvc.perform(post("/api/v1/zones/query").contentType(APPLICATION_JSON).content("{\"lat\":12.005,\"lon\":77.005}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.setVersion").value(1))
                    .andExpect(jsonPath("$.zones[0].zoneId").value(zoneId));
            mvc.perform(post("/api/v1/zones/query/batch").contentType(APPLICATION_JSON)
                            .content("[{\"lat\":12.005,\"lon\":77.005},{\"lat\":12.006,\"lon\":77.006}]"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].setVersion").value(1)).andExpect(jsonPath("$[1].setVersion").value(1));
            mvc.perform(get("/api/v1/zones").param("setVersion", "1")).andExpect(status().isOk()).andExpect(jsonPath("$[0].zoneId").value(zoneId));
            mvc.perform(get("/api/v1/zones/" + zoneId).param("version", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
            mvc.perform(get("/api/v1/zones/" + zoneId + "/versions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(1));
            mvc.perform(get("/api/v1/zone-sets")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PUBLISHED"));
            Instant start = Instant.now();
            var started = mvc.perform(post("/api/v1/trips").contentType(APPLICATION_JSON)
                            .content("{\"employeeId\":\"" + employee + "\",\"startTime\":\"" + start + "\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String tripId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(started.getResponse().getContentAsString()).path("tripId").asString();
            Instant first = start.plusMillis(10);
            SecurityContextHolder.getContext().setAuthentication(authentication(operator, tenant, "OPERATOR"));
            mvc.perform(post("/api/v1/trips/" + tripId + "/pings").contentType(APPLICATION_JSON)
                            .content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + first + "\",\"lat\":12.005,\"lon\":77.002,\"accuracyMeters\":5},{\"seq\":1,\"eventTime\":\"" + first.plusSeconds(5) + "\",\"lat\":12.005,\"lon\":77.008,\"accuracyMeters\":5}]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
            mvc.perform(post("/api/v1/trips/" + tripId + "/pings").contentType(APPLICATION_JSON)
                            .content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + first + "\",\"lat\":12.005,\"lon\":77.002,\"accuracyMeters\":5},{\"seq\":1,\"eventTime\":\"" + first.plusSeconds(5) + "\",\"lat\":12.005,\"lon\":77.008,\"accuracyMeters\":5}]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("DUPLICATE"))
                    .andExpect(jsonPath("$.items[1].status").value("DUPLICATE"));
            mvc.perform(post("/api/v1/trips/" + tripId + "/complete")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.durableAckedSeq").value(1));
            mvc.perform(get("/api/v1/trips/" + tripId + "/attribution")).andExpect(status().isOk())
                    .andExpect(jsonPath("$['metersByZone']['" + zoneId + "']").isNumber()).andExpect(jsonPath("$.totalMeters").isNumber());
            mvc.perform(get("/api/v1/trips/" + tripId + "/events")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventId").exists()).andExpect(jsonPath("$[1].eventId").exists());
            mvc.perform(get("/api/v1/trips/" + tripId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
            mvc.perform(get("/api/v1/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
            UUID alertId = UUID.randomUUID();
            assertThat(jdbc.update("""
                    insert into alerts(alert_id,tenant_id,trip_id,zone_id,source_event_id,kind,severity,event_time,
                        zone_version,zone_version_set_version,evaluated_set_version)
                    select ?,tenant_id,trip_id,zone_id,event_id,'NO_GO_NIGHT_ENTRY','HIGH',event_time,
                        zone_version,zone_version_set_version,evaluated_set_version
                    from zone_events where tenant_id=? and trip_id=? order by transition_ordinal limit 1
                    """, alertId, tenant, UUID.fromString(tripId))).isEqualTo(1);
            mvc.perform(get("/api/v1/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].alertId").value(alertId.toString()));
            mvc.perform(post("/api/v1/alerts/" + alertId + "/ack")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.acknowledged").value(true));
            mvc.perform(post("/api/v1/alerts/" + alertId + "/ack")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.acknowledged").value(true));
            assertThat(jdbc.queryForObject("select row_version from alerts where tenant_id=? and alert_id=?", Long.class, tenant, alertId))
                    .isEqualTo(1L);

            var lateTripResponse = mvc.perform(post("/api/v1/trips").contentType(APPLICATION_JSON)
                            .content("{\"employeeId\":\"" + employee + "\",\"startTime\":\"" + Instant.now() + "\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String lateTripId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(lateTripResponse.getResponse().getContentAsString()).path("tripId").asString();
            Instant latePingTime = Instant.now();
            mvc.perform(post("/api/v1/trips/" + lateTripId + "/pings").contentType(APPLICATION_JSON)
                            .content("{\"pings\":[{\"seq\":1,\"eventTime\":\"" + Instant.now().minusSeconds(172800) + "\",\"lat\":12.005,\"lon\":77.005,\"accuracyMeters\":5},{\"seq\":1,\"eventTime\":\"" + latePingTime + "\",\"lat\":12.005,\"lon\":77.005,\"accuracyMeters\":5}]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                    .andExpect(jsonPath("$.items[0].reason").value("CLOCK_SKEW"))
                    .andExpect(jsonPath("$.items[1].status").value("ACCEPTED"));
            Thread.sleep(2500);
            mvc.perform(post("/api/v1/trips/" + lateTripId + "/pings").contentType(APPLICATION_JSON)
                            .content("{\"pings\":[{\"seq\":0,\"eventTime\":\"" + latePingTime + "\",\"lat\":12.005,\"lon\":77.005,\"accuracyMeters\":5}]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                    .andExpect(jsonPath("$.items[0].reason").value("LATE"));
            mvc.perform(post("/api/v1/trips/" + lateTripId + "/complete")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.durableAckedSeq").value(1));

            var queuedTripResponse = mvc.perform(post("/api/v1/trips").contentType(APPLICATION_JSON)
                            .content("{\"employeeId\":\"" + employee + "\",\"startTime\":\"" + Instant.now() + "\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String queuedTripId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(queuedTripResponse.getResponse().getContentAsString()).path("tripId").asString();
            Instant queuedEventTime = Instant.now();
            StringBuilder queuedBatch = new StringBuilder("{\"pings\":[");
            for (int sequence = 0; sequence < 32; sequence++) {
                if (sequence > 0) queuedBatch.append(',');
                queuedBatch.append("{\"seq\":").append(sequence).append(",\"eventTime\":\"").append(queuedEventTime)
                        .append("\",\"lat\":12.005,\"lon\":77.005,\"accuracyMeters\":5}");
            }
            queuedBatch.append("]}");
            mvc.perform(post("/api/v1/trips/" + queuedTripId + "/pings").contentType(APPLICATION_JSON).content(queuedBatch.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(32));
            mvc.perform(post("/api/v1/trips/" + queuedTripId + "/complete")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.durableAckedSeq").value(31));

            SecurityContextHolder.getContext().setAuthentication(authentication(admin, tenant, "ADMIN"));
            String updateBody = """
                    {"zoneId":"%s","type":"CITY","priority":10,"geometry":{"type":"Polygon","coordinates":[[[77.0,12.0],[77.01,12.0],[77.01,12.01],[77.0,12.01],[77.0,12.0]]]}}
                    """.formatted(zoneId);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/zones/" + zoneId)
                            .header("Idempotency-Key", "phase6-update-" + tenant).contentType(APPLICATION_JSON).content(updateBody))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.setVersion").value(2));
            mvc.perform(get("/api/v1/zones/" + zoneId + "/versions")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/zones/" + zoneId)
                            .header("Idempotency-Key", "phase6-delete-" + tenant))
                    .andExpect(status().isNoContent());

            UUID otherTenant = UUID.randomUUID();
            SecurityContextHolder.getContext().setAuthentication(authentication(UUID.randomUUID(), otherTenant, "ADMIN"));
            mvc.perform(get("/api/v1/trips/" + tripId)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        } finally { SecurityContextHolder.clearContext(); }
    }

    private static JwtAuthenticationToken authentication(UUID userId, UUID tenantId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject(userId.toString())
                .claim("tid", tenantId.toString()).claim("roles", List.of(role)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
