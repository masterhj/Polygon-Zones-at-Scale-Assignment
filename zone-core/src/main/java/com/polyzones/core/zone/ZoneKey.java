package com.polyzones.core.zone;

import java.util.UUID;

/** Opaque zone identifier. Unsigned comparison matches canonical UUID text ordering. */
public record ZoneKey(UUID value) implements Comparable<ZoneKey> {
    public ZoneKey { if (value == null) throw new IllegalArgumentException("value is required"); }
    @Override public int compareTo(ZoneKey other) {
        int high = Long.compareUnsigned(value.getMostSignificantBits(), other.value.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }
}
