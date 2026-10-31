package com.polyzones.core.index;

import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Immutable zone-set publication unit; the backing ordinal array is never exposed. */
public final class ZoneSnapshot {
    private final UUID tenantId;
    private final long setVersion;
    private final Zone[] zones;
    private final ZoneIndex index;
    private final Map<ZoneKey,Integer> ordinalById;
    private final Instant builtAt;
    private final BuildStats buildStats;
    ZoneSnapshot(UUID tenantId,long setVersion,Zone[] zones,ZoneIndex index,Map<ZoneKey,Integer> ordinalById,Instant builtAt,BuildStats stats) {
        this.tenantId=tenantId;this.setVersion=setVersion;this.zones=zones.clone();this.index=index;this.ordinalById=Map.copyOf(ordinalById);this.builtAt=builtAt;this.buildStats=stats;
    }
    public UUID tenantId() { return tenantId; }
    public long setVersion() { return setVersion; }
    public Zone[] zones() { return zones.clone(); }
    public Zone zoneAt(int ordinal) { return zones[ordinal]; }
    public int zoneCount() { return zones.length; }
    public ZoneIndex index() { return index; }
    public Map<ZoneKey,Integer> ordinalById() { return ordinalById; }
    public Instant builtAt() { return builtAt; }
    public BuildStats buildStats() { return buildStats; }

    /** Computes added, changed and removed zone ids. Geometry is compared by normalized ring coordinates. */
    public ZoneSnapshotDiff diff(ZoneSnapshot previous) {
        if (previous == null) return new ZoneSnapshotDiff(ordinalById.keySet(), Set.of(), Set.of());
        Set<ZoneKey> added = new HashSet<>();
        Set<ZoneKey> changed = new HashSet<>();
        Set<ZoneKey> removed = new HashSet<>();
        for (Zone zone : zones) {
            Integer previousOrdinal = previous.ordinalById.get(zone.id());
            if (previousOrdinal == null) added.add(zone.id());
            else if (!sameZone(zone, previous.zones[previousOrdinal])) changed.add(zone.id());
        }
        for (Zone zone : previous.zones) if (!ordinalById.containsKey(zone.id())) removed.add(zone.id());
        return new ZoneSnapshotDiff(added, changed, removed);
    }

    private static boolean sameZone(Zone left, Zone right) {
        if (left.version() != right.version() || left.type() != right.type() || left.priority() != right.priority()) return false;
        return sameShape(left.shape(), right.shape());
    }

    private static boolean sameShape(com.polyzones.core.shape.PolygonShape left,
                                     com.polyzones.core.shape.PolygonShape right) {
        if (!sameRing(left.outer(), right.outer()) || left.holes().size() != right.holes().size()) return false;
        for (int i = 0; i < left.holes().size(); i++) if (!sameRing(left.holes().get(i), right.holes().get(i))) return false;
        return true;
    }

    private static boolean sameRing(com.polyzones.core.shape.Ring left, com.polyzones.core.shape.Ring right) {
        if (left.vertexCount() != right.vertexCount()) return false;
        for (int i = 0; i < left.vertexCount(); i++) {
            if (!left.vertexAt(i).equals(right.vertexAt(i))) return false;
        }
        return true;
    }
}
