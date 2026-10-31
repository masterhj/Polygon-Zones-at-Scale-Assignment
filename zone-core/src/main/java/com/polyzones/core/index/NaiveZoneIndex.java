package com.polyzones.core.index;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Location;
import com.polyzones.core.zone.Zone;
import java.util.List;
import java.util.function.IntConsumer;

/** Linear exact-geometry oracle. Point containment deliberately does not use polygon bboxes. */
public final class NaiveZoneIndex implements ZoneIndex {
    private final Zone[] zones;
    private final GeometryTolerance tolerance;
    public NaiveZoneIndex(Zone[] zones, GeometryTolerance tolerance) { this.zones = zones.clone(); this.tolerance = tolerance; }
    @Override public List<ZoneHit> query(double lon, double lat) {
        var hits = IndexResults.newHits();
        for (Zone zone : zones) {
            Location location = zone.shape().locate(lon, lat, tolerance);
            if (location != Location.OUTSIDE) hits.add(IndexResults.hit(zone, location));
        }
        return IndexResults.sorted(hits);
    }
    @Override public void forEachContaining(double lon, double lat, IntConsumer consumer) {
        for (int i=0;i<zones.length;i++) if (zones[i].shape().locate(lon,lat,tolerance) != Location.OUTSIDE) consumer.accept(i);
    }
    @Override public void forEachCandidate(BoundingBox box, IntConsumer consumer) {
        for (int i=0;i<zones.length;i++) if (zones[i].shape().bounds().expand(tolerance.boundaryEpsDeg()).intersects(box)) consumer.accept(i);
    }
    @Override public int size() { return zones.length; }
}
