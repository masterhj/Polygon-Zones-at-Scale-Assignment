package com.polyzones.service.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.polyzones.service.publish.SnapshotRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoneVersionGaugeRegistryTest {
    @Test
    @DisplayName("P6: snapshot version gauge tenant cardinality never exceeds configured cap")
    void capsOpaqueTenantGaugeTags() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ZoneVersionGaugeRegistry gauges = new ZoneVersionGaugeRegistry(meters, 2);
        SnapshotRegistry snapshots = new SnapshotRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 1; i <= 3; i++) {
                UUID tenant = new UUID(0, i);
                pool.submit(() -> gauges.register(tenant, snapshots));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(meters.getMeters().stream().filter(meter -> meter.getId().getName().equals("zone_set_version"))
                    .map(Meter::getId).count()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
}
