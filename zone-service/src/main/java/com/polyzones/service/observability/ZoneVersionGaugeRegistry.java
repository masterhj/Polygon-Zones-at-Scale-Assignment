package com.polyzones.service.observability;

import com.polyzones.service.publish.SnapshotRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registers one opaque-tenant version gauge per tenant, up to a configured cardinality limit. */
public final class ZoneVersionGaugeRegistry {
    private final MeterRegistry meters;
    private final int tenantCap;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    public ZoneVersionGaugeRegistry(MeterRegistry meters, int tenantCap) {
        if (meters == null || tenantCap < 0) throw new IllegalArgumentException("Invalid gauge registry configuration");
        this.meters = meters;
        this.tenantCap = tenantCap;
    }

    public synchronized void register(UUID tenantId, SnapshotRegistry snapshots) {
        if (tenantCap == 0 || registered.contains(tenantId) || registered.size() >= tenantCap) return;
        if (!registered.add(tenantId)) return;
        Gauge.builder("zone_set_version", snapshots, registry -> registry.versionOrZero(tenantId))
                .tag("tenant", tenantId.toString()).register(meters);
    }
}
