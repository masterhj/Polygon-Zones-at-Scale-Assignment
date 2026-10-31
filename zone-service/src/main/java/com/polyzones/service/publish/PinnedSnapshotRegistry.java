package com.polyzones.service.publish;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.polyzones.core.index.ZoneSnapshot;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds in-use snapshots strongly and caches unpinned history by total vertex weight. */
public final class PinnedSnapshotRegistry {
    private final SnapshotRegistry latest;
    private final SnapshotLoader loader;
    private final Cache<Key, ZoneSnapshot> historical;
    private final ConcurrentHashMap<Key, Pinned> pinned = new ConcurrentHashMap<>();

    public PinnedSnapshotRegistry(SnapshotRegistry latest, SnapshotLoader loader, long maxVertices) {
        if (latest == null || loader == null || maxVertices < 1) throw new IllegalArgumentException("Invalid snapshot registry configuration");
        this.latest = latest;
        this.loader = loader;
        this.historical = Caffeine.newBuilder().maximumWeight(maxVertices)
                .weigher((Key key, ZoneSnapshot snapshot) -> (int) Math.min(Integer.MAX_VALUE,
                        snapshot.buildStats().vertexCount()))
                .build();
    }

    public ZoneSnapshot acquire(UUID tenantId, long setVersion) {
        if (tenantId == null || setVersion < 0) throw new IllegalArgumentException("Invalid snapshot key");
        Key key = new Key(tenantId, setVersion);
        Pinned entry = pinned.compute(key, (ignored, existing) -> {
            ZoneSnapshot snapshot = existing == null ? find(key) : existing.snapshot;
            return new Pinned(snapshot, existing == null ? 1 : Math.addExact(existing.references, 1));
        });
        return entry.snapshot;
    }

    public void release(UUID tenantId, long setVersion) {
        Key key = new Key(tenantId, setVersion);
        pinned.compute(key, (ignored, existing) -> {
            if (existing == null) throw new IllegalStateException("Snapshot is not pinned");
            if (existing.references > 1) return new Pinned(existing.snapshot, existing.references - 1);
            historical.put(key, existing.snapshot);
            return null;
        });
    }

    private ZoneSnapshot find(Key key) {
        ZoneSnapshot current;
        try { current = latest.latest(key.tenantId); }
        catch (IllegalStateException unavailable) { current = null; }
        if (current != null && current.setVersion() == key.setVersion) return current;
        ZoneSnapshot cached = historical.getIfPresent(key);
        if (cached != null) return cached;
        ZoneSnapshot loaded = loader.loadPublished(key.tenantId, key.setVersion);
        if (loaded == null || !loaded.tenantId().equals(key.tenantId) || loaded.setVersion() != key.setVersion)
            throw new VersionNotFoundException();
        return loaded;
    }

    public int pinnedSnapshotCount() { return pinned.size(); }

    private record Key(UUID tenantId, long setVersion) { }
    private record Pinned(ZoneSnapshot snapshot, int references) { }

    public static final class VersionNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public VersionNotFoundException() { super("Published snapshot version not found"); }
    }
}
