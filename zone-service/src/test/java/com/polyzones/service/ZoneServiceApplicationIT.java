package com.polyzones.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = ZoneServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ZoneServiceApplicationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired JdbcTemplate jdbc;

    @Test
    void contextStartsMigrationRunsAndHealthIsUp() throws Exception {
        Integer migrations = jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:8081/actuator/health")).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(migrations).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='zone_set_versions'", Integer.class)).isEqualTo(1);
        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
