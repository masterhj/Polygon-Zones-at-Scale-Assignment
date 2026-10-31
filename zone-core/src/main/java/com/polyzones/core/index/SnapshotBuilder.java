package com.polyzones.core.index;

import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SnapshotBuilder {
    private SnapshotBuilder() { }
    public static ZoneSnapshot build(UUID tenantId,long setVersion,Collection<Zone> source) {
        return build(tenantId,setVersion,source,GeometryTolerance.DEFAULT,Clock.systemUTC(),RTreeZoneIndex.DEFAULT_NODE_CAPACITY,
                (zones, tolerance, capacity) -> new RTreeZoneIndex(zones, tolerance, capacity));
    }
    public static ZoneSnapshot build(UUID tenantId,long setVersion,Collection<Zone> source,GeometryTolerance tolerance,Clock clock,int nodeCapacity) {
        return build(tenantId, setVersion, source, tolerance, clock, nodeCapacity,
                (zones, geometryTolerance, capacity) -> new RTreeZoneIndex(zones, geometryTolerance, capacity));
    }
    public static ZoneSnapshot build(UUID tenantId,long setVersion,Collection<Zone> source,GeometryTolerance tolerance,
                                     Clock clock,int nodeCapacity,ZoneIndexFactory indexFactory) {
        if(tenantId==null||setVersion<0||source==null||tolerance==null||clock==null||indexFactory==null) throw new IllegalArgumentException("Invalid snapshot metadata");
        long start=System.nanoTime();
        Zone[] zones=source.stream().sorted(Comparator.comparing(Zone::id)).toArray(Zone[]::new);
        Map<ZoneKey,Integer> ordinals=new LinkedHashMap<>(); long vertices=0;
        for(int i=0;i<zones.length;i++) {
            if(ordinals.put(zones[i].id(),i)!=null) throw new IllegalArgumentException("Duplicate zone id");
            vertices+=zones[i].shape().vertexCount();
        }
        ZoneIndex index=indexFactory.create(zones.clone(),tolerance,nodeCapacity);
        if(index==null||index.size()!=zones.length) throw new IllegalArgumentException("Zone index factory returned an incompatible index");
        long elapsed=System.nanoTime()-start;
        long indexBytes=index instanceof RTreeZoneIndex rtree ? rtree.primitiveArrayBytes() : (long)zones.length*32;
        long roughBytes=indexBytes+(long)zones.length*96+vertices*16;
        BuildStats stats=new BuildStats(elapsed,zones.length,vertices,roughBytes);
        return new ZoneSnapshot(tenantId,setVersion,zones,index,ordinals,clock.instant(),stats);
    }
}
