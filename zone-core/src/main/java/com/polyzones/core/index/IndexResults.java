package com.polyzones.core.index;

import com.polyzones.core.geo.Location;
import com.polyzones.core.zone.Zone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class IndexResults {
    static final Comparator<ZoneHit> ORDER = Comparator.comparingInt(ZoneHit::priority).reversed().thenComparing(ZoneHit::id);
    private IndexResults() { }
    static ZoneHit hit(Zone zone, Location location) { return new ZoneHit(zone.id(), zone.version(), zone.type(), zone.priority(), location); }
    static List<ZoneHit> sorted(List<ZoneHit> hits) { hits.sort(ORDER); return List.copyOf(hits); }
    static List<ZoneHit> newHits() { return new ArrayList<>(); }
}
