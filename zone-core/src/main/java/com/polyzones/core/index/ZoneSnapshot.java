package com.polyzones.core.index;

import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.Map;
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
}
