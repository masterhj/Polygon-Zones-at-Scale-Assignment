package com.polyzones.core.trip;

import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;

public record OpenMembership(ZoneKey zoneId, int zoneVersion, long zoneVersionSetVersion,
                             long evaluatedSetVersion, Instant enterTime, long enterSeq) { }
