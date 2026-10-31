package com.polyzones.service.publish;

import java.util.List;
import java.util.UUID;
import com.polyzones.service.observability.ZoneVersionGaugeRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Repairs interrupted builds and reconstructs every tenant's latest durable snapshot before readiness. */
@Component
public final class StartupSnapshotLoader implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SnapshotLoader loader;
    private final SnapshotRegistry registry;
    private final ZoneVersionGaugeRegistry gauges;

    public StartupSnapshotLoader(JdbcTemplate jdbc, SnapshotLoader loader, SnapshotRegistry registry,
                                 ZoneVersionGaugeRegistry gauges) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.registry = registry;
        this.gauges = gauges;
    }

    @Override public void run(ApplicationArguments args) {
        jdbc.update("update zone_set_versions set status='FAILED' where status='BUILDING'");
        List<UUID> tenants = jdbc.query("select id from tenants order by id", (rs, row) -> rs.getObject(1, UUID.class));
        for (UUID tenant : tenants) {
            List<Long> versions = jdbc.query("select max(version) from zone_set_versions where tenant_id=? and status='PUBLISHED'",
                    (rs, row) -> { long value = rs.getLong(1); return rs.wasNull() ? null : value; }, tenant);
            if (versions.isEmpty() || versions.getFirst() == null) continue;
            long version = versions.getFirst();
            if (!registry.publish(tenant, loader.loadPublished(tenant, version)))
                throw new IllegalStateException("Startup snapshot version was not monotonic");
            gauges.register(tenant, registry);
        }
    }
}
