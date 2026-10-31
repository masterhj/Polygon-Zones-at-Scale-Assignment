package com.polyzones.core.index;

import com.polyzones.core.zone.ZoneKey;
import java.util.Set;

/** Immutable summary of the zone identity changes between two published snapshots. */
public record ZoneSnapshotDiff(Set<ZoneKey> added, Set<ZoneKey> changed, Set<ZoneKey> removed) {
    public ZoneSnapshotDiff {
        added = Set.copyOf(added);
        changed = Set.copyOf(changed);
        removed = Set.copyOf(removed);
    }
}
