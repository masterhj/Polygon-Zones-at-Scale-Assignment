package com.polyzones.service.publish;

import com.polyzones.core.index.ZoneSnapshot;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-local atomic latest-snapshot registry. A request must capture latest exactly once. */
public final class SnapshotRegistry {
    private static final Logger log = LoggerFactory.getLogger(SnapshotRegistry.class);
    private final ConcurrentHashMap<UUID, AtomicReference<ZoneSnapshot>> latest = new ConcurrentHashMap<>();
    private final AtomicLong rejectedPublications = new AtomicLong();

    public ZoneSnapshot latest(UUID tenantId) {
        AtomicReference<ZoneSnapshot> ref = latest.get(requireTenant(tenantId));
        ZoneSnapshot snapshot = ref == null ? null : ref.get();
        if (snapshot == null) throw new IllegalStateException("No published snapshot for tenant");
        return snapshot;
    }

    /** @return true only when the higher-version snapshot became current. */
    public boolean publish(UUID tenantId, ZoneSnapshot next) {
        UUID tenant = requireTenant(tenantId);
        if (next == null || !tenant.equals(next.tenantId())) throw new IllegalArgumentException("Snapshot tenant mismatch");
        AtomicReference<ZoneSnapshot> reference = latest.computeIfAbsent(tenant, ignored -> new AtomicReference<>());
        while (true) {
            ZoneSnapshot current = reference.get();
            if (current != null && next.setVersion() <= current.setVersion()) {
                rejectedPublications.incrementAndGet();
                log.warn("Ignoring non-monotonic snapshot publication tenantId={} currentVersion={} requestedVersion={}",
                        tenant, current.setVersion(), next.setVersion());
                return false;
            }
            if (reference.compareAndSet(current, next)) return true;
        }
    }

    public long rejectedPublicationCount() { return rejectedPublications.get(); }

    public double versionOrZero(UUID tenantId) {
        AtomicReference<ZoneSnapshot> ref = latest.get(tenantId);
        ZoneSnapshot snapshot = ref == null ? null : ref.get();
        return snapshot == null ? 0 : snapshot.setVersion();
    }

    private static UUID requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        return tenantId;
    }
}
