package com.polyzones.core.index;

import com.polyzones.core.geo.BoundingBox;
import java.util.List;
import java.util.function.IntConsumer;

public interface ZoneIndex {
    List<ZoneHit> query(double lon, double lat);
    void forEachContaining(double lon, double lat, IntConsumer zoneOrdinal);
    void forEachCandidate(BoundingBox box, IntConsumer zoneOrdinal);
    int size();
}
