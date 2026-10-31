package com.polyzones.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.service.ZoneServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = ZoneServiceApplication.class)
@ActiveProfiles({"test", "demo"})
class DemoDataSeederIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Autowired JdbcTemplate jdbc;

    @Test void demoProfileSeedsTwoIsolatedTenantsUsersAndSampleZoneSets() {
        assertThat(jdbc.queryForObject("select count(*) from tenants where name in ('acme','globex')", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from app_users u join tenants t on t.id=u.tenant_id where t.name in ('acme','globex')", Long.class)).isEqualTo(6L);
        assertThat(jdbc.queryForObject("select count(*) from zone_set_versions z join tenants t on t.id=z.tenant_id where t.name in ('acme','globex') and z.status='PUBLISHED'", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from zone_versions zv join tenants t on t.id=zv.tenant_id where t.name in ('acme','globex') and zv.deleted=false", Long.class)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("select count(*) from zone_versions zv join tenants t on t.id=zv.tenant_id where t.name='acme' and zv.zone_type='AIRPORT' and zv.geometry_geojson like '%77.55%'", Long.class)).isEqualTo(1L);
    }
}
