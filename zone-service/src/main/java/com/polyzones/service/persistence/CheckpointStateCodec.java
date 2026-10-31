package com.polyzones.service.persistence;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.trip.OpenMembership;
import com.polyzones.core.trip.SkippedRange;
import com.polyzones.core.trip.TripStateCheckpoint;
import com.polyzones.core.zone.ZoneKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Stable JSON boundary for the complete serialisable trip state; zone keys are stored as UUID values. */
public final class CheckpointStateCodec {
    private final JsonMapper mapper;
    public CheckpointStateCodec(JsonMapper mapper) { this.mapper = mapper; }

    public String encode(TripStateCheckpoint state) {
        return mapper.writeValueAsString(toDocument(state));
    }

    public TripStateCheckpoint decode(String json) {
        return fromDocument(mapper.readValue(json, Document.class));
    }

    private static Document toDocument(TripStateCheckpoint state) {
        return new Document(state.tenantId(), state.tripId(), state.pinnedSetVersion(),
                state.lastPoint() == null ? null : state.lastPoint().lon(),
                state.lastPoint() == null ? null : state.lastPoint().lat(),
                string(state.lastEventTime()), state.processedSeq(), state.durableAckedSeq(), state.nextTransitionOrdinal(),
                state.gapsSkipped(), state.latePings(), state.timeRegressions(), state.suspectJumps(), state.totalMeters(),
                state.lastSafetySetVersion(), state.skippedRanges(), new TreeMap<>(state.distanceMeters()), memberships(state.billingMemberships()),
                memberships(state.noGoMemberships()), state.completed());
    }

    private static TripStateCheckpoint fromDocument(Document document) {
        LonLat point = document.lastLon() == null ? null : new LonLat(document.lastLon(), document.lastLat());
        return new TripStateCheckpoint(document.tenantId(), document.tripId(), document.pinnedSetVersion(), point,
                instant(document.lastEventTime()), document.processedSeq(), document.durableAckedSeq(),
                document.nextTransitionOrdinal(), document.gapsSkipped(), document.latePings(), document.timeRegressions(),
                document.suspectJumps(), document.totalMeters(), document.lastSafetySetVersion(), document.skippedRanges(),
                document.distanceMeters(), memberships(document.billingMemberships()), memberships(document.noGoMemberships()),
                document.completed());
    }

    private static List<Membership> memberships(Map<ZoneKey, OpenMembership> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> entry.getValue())
                .map(value -> new Membership(value.zoneId().value(), value.zoneVersion(),
                value.zoneVersionSetVersion(), value.evaluatedSetVersion(), value.enterTime().toString(), value.enterSeq())).toList();
    }

    private static Map<ZoneKey, OpenMembership> memberships(List<Membership> values) {
        return values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                value -> new ZoneKey(value.zoneId()), value -> new OpenMembership(new ZoneKey(value.zoneId()),
                        value.zoneVersion(), value.zoneVersionSetVersion(), value.evaluatedSetVersion(),
                        Instant.parse(value.enterTime()), value.enterSeq())));
    }

    private static String string(Instant instant) { return instant == null ? null : instant.toString(); }
    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
    private record Membership(UUID zoneId, int zoneVersion, long zoneVersionSetVersion, long evaluatedSetVersion,
                              String enterTime, long enterSeq) { }
    private record Document(UUID tenantId, UUID tripId, long pinnedSetVersion, Double lastLon, Double lastLat,
                            String lastEventTime, long processedSeq, long durableAckedSeq, long nextTransitionOrdinal,
                            long gapsSkipped, long latePings, long timeRegressions, long suspectJumps, double totalMeters,
                            long lastSafetySetVersion, List<SkippedRange> skippedRanges,
                            Map<String, Double> distanceMeters, List<Membership> billingMemberships,
                            List<Membership> noGoMemberships, boolean completed) { }
}
