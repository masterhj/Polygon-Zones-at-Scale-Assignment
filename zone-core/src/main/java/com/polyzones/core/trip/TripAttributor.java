package com.polyzones.core.trip;

import com.polyzones.core.geo.Haversine;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/** Deterministic, single-writer trip accumulator implementing pinned billing and live NO_GO evaluation (D-09). */
public final class TripAttributor {
    public static final String UNZONED = "UNZONED";
    public static final String NO_GO_NIGHT_ENTRY = "NO_GO_NIGHT_ENTRY";
    public static final double DEFAULT_MAX_SPEED_KMH = 250;
    private final TripState state;
    private final ZoneSnapshot pinned;

    public TripAttributor(UUID tenantId, UUID tripId, ZoneSnapshot pinnedSnapshot) {
        if (pinnedSnapshot == null || !pinnedSnapshot.tenantId().equals(tenantId)) throw new IllegalArgumentException("Pinned snapshot tenant mismatch");
        this.state = new TripState(tenantId, tripId, pinnedSnapshot.setVersion());
        this.pinned = pinnedSnapshot;
    }

    public TripAttributor(ZoneSnapshot pinnedSnapshot, TripState restoredState) {
        if (pinnedSnapshot == null || restoredState == null || !pinnedSnapshot.tenantId().equals(restoredState.tenantId())
                || pinnedSnapshot.setVersion() != restoredState.pinnedSetVersion())
            throw new IllegalArgumentException("Restored state does not match pinned snapshot");
        this.state = restoredState;
        this.pinned = pinnedSnapshot;
    }

    public TripState state() { return state; }

    public ProcessResult apply(Ping ping, ZoneSnapshot latestSnapshot, NightWindow tenantNight,
                               ZoneId tenantTimeZone) {
        return apply(ping, latestSnapshot, tenantNight, tenantTimeZone, DEFAULT_MAX_SPEED_KMH);
    }

    public ProcessResult apply(Ping ping, ZoneSnapshot latestSnapshot, NightWindow tenantNight,
                               ZoneId tenantTimeZone, double maxSpeedKmh) {
        if (ping == null || latestSnapshot == null || tenantNight == null || tenantTimeZone == null
                || !ping.tripId().equals(state.tripId()) || !latestSnapshot.tenantId().equals(state.tenantId())
                || !(maxSpeedKmh > 0) || !Double.isFinite(maxSpeedKmh)) throw new IllegalArgumentException("Invalid trip processing input");
        if (state.completed()) throw new IllegalStateException("Trip is already complete");
        if (ping.seq() <= state.processedSeq()) throw new IllegalArgumentException("Pings must be applied in increasing sequence order");

        boolean timeRegression = state.lastEventTime() != null && ping.eventTime().isBefore(state.lastEventTime());
        if (timeRegression) state.addTimeRegression();
        Instant eventTime = timeRegression ? state.lastEventTime() : ping.eventTime();
        List<Draft> drafts = new ArrayList<>();
        List<AlertCandidate> alerts = new ArrayList<>();
        boolean suspectJump = false;
        if (state.lastPoint() == null) {
            addPointMemberships(pinned, ping.location(), state.mutableBillingMemberships(), false, eventTime, ping.seq(), drafts);
            addPointMemberships(latestSnapshot, ping.location(), state.mutableNoGoMemberships(), true, eventTime, ping.seq(), drafts);
            state.lastSafetySetVersion(latestSnapshot.setVersion());
        } else {
            LonLat from = state.lastPoint();
            Instant fromTime = state.lastEventTime();
            double length = Haversine.distanceMeters(from, ping.location());
            double elapsedSeconds = secondsBetween(fromTime, eventTime);
            suspectJump = length > 0 && (elapsedSeconds <= 0 || (length / elapsedSeconds) * 3.6 > maxSpeedKmh);
            if (suspectJump) state.addSuspectJump();

            if (state.lastSafetySetVersion() != latestSnapshot.setVersion())
                reconcileNoGo(latestSnapshot, from, eventTime, ping.seq(), drafts);
            List<SubSegment> billingSegments = SegmentSplitter.splitBilling(pinned, from, ping.location(), com.polyzones.core.geo.GeometryTolerance.DEFAULT);
            accumulate(billingSegments);
            processTransitions(billingSegments,
                    pinned, state.mutableBillingMemberships(), false, fromTime, eventTime, ping.seq(), drafts);
            processTransitions(SegmentSplitter.splitNoGo(latestSnapshot, from, ping.location(), com.polyzones.core.geo.GeometryTolerance.DEFAULT),
                    latestSnapshot, state.mutableNoGoMemberships(), true, fromTime, eventTime, ping.seq(), drafts);
            state.lastSafetySetVersion(latestSnapshot.setVersion());
        }
        List<ZoneEvent> events = finalizeDrafts(drafts, alerts, tenantNight, tenantTimeZone);
        state.lastPoint(ping.location());
        state.lastEventTime(eventTime);
        state.processedSeq(ping.seq());
        return new ProcessResult(ping.seq(), events, alerts, suspectJump, timeRegression);
    }

    private void addPointMemberships(ZoneSnapshot snapshot, LonLat point, Map<ZoneKey, OpenMembership> memberships,
                                     boolean noGo, Instant eventTime, long seq, List<Draft> drafts) {
        TreeMap<ZoneKey, Zone> zones = containing(snapshot, point, zone -> noGo ? zone.type() == ZoneType.NO_GO : zone.type() != ZoneType.NO_GO);
        for (Zone zone : zones.values()) {
            memberships.put(zone.id(), new OpenMembership(zone.id(), zone.version(), snapshot.setVersion(),
                    snapshot.setVersion(), eventTime, seq));
            drafts.add(new Draft(0, noGo ? Source.NO_GO_LIVE : Source.BILLING_PINNED, zone.id(), zone.version(),
                    snapshot.setVersion(), snapshot.setVersion(), TransitionKind.ENTER, eventTime, null, EventReason.TRIP_START));
        }
    }

    private void reconcileNoGo(ZoneSnapshot next, LonLat at, Instant eventTime, long seq, List<Draft> drafts) {
        TreeMap<ZoneKey, Zone> now = containing(next, at, zone -> zone.type() == ZoneType.NO_GO);
        Map<ZoneKey, OpenMembership> open = state.mutableNoGoMemberships();
        for (var entry : new ArrayList<>(open.entrySet())) {
            ZoneKey id = entry.getKey();
            Zone current = now.get(id);
            OpenMembership previous = entry.getValue();
            if (current == null) {
                drafts.add(new Draft(1, Source.NO_GO_LIVE, id, previous.zoneVersion(), previous.zoneVersionSetVersion(),
                        next.setVersion(), TransitionKind.EXIT, eventTime, dwell(previous.enterTime(), eventTime), EventReason.ZONE_CHANGED));
                open.remove(id);
            } else {
                open.put(id, new OpenMembership(id, current.version(), next.setVersion(), next.setVersion(), previous.enterTime(), previous.enterSeq()));
            }
        }
        for (Zone zone : now.values()) if (!open.containsKey(zone.id())) {
            open.put(zone.id(), new OpenMembership(zone.id(), zone.version(), next.setVersion(), next.setVersion(), eventTime, seq));
            drafts.add(new Draft(1, Source.NO_GO_LIVE, zone.id(), zone.version(), next.setVersion(), next.setVersion(),
                    TransitionKind.ENTER, eventTime, null, EventReason.ZONE_CHANGED));
        }
    }

    private void accumulate(List<SubSegment> segments) {
        for (SubSegment segment : segments) {
            String key = segment.billingZoneOrdinal() < 0 ? UNZONED : pinned.zoneAt(segment.billingZoneOrdinal()).id().value().toString();
            state.addMeters(key, segment.meters());
            state.addTotalMeters(segment.meters());
        }
    }

    private void processTransitions(List<SubSegment> segments, ZoneSnapshot snapshot,
                                    Map<ZoneKey, OpenMembership> active, boolean noGo,
                                    Instant fromTime, Instant toTime, long seq, List<Draft> drafts) {
        Predicate<Zone> filter = zone -> noGo ? zone.type() == ZoneType.NO_GO : zone.type() != ZoneType.NO_GO;
        for (SubSegment segment : segments) {
            TreeMap<ZoneKey, Zone> desired = new TreeMap<>();
            for (int ordinal : segment.memberships()) {
                Zone zone = snapshot.zoneAt(ordinal);
                if (filter.test(zone)) desired.put(zone.id(), zone);
            }
            Instant transitionTime = interpolate(fromTime, toTime, segment.t0());
            for (var entry : new ArrayList<>(active.entrySet())) if (!desired.containsKey(entry.getKey())) {
                OpenMembership open = entry.getValue();
                drafts.add(new Draft(segment.t0(), noGo ? Source.NO_GO_LIVE : Source.BILLING_PINNED, entry.getKey(),
                        open.zoneVersion(), open.zoneVersionSetVersion(), snapshot.setVersion(), TransitionKind.EXIT,
                        transitionTime, dwell(open.enterTime(), transitionTime), EventReason.CROSSING));
                active.remove(entry.getKey());
            }
            for (Zone zone : desired.values()) if (!active.containsKey(zone.id())) {
                active.put(zone.id(), new OpenMembership(zone.id(), zone.version(), snapshot.setVersion(),
                        snapshot.setVersion(), transitionTime, seq));
                drafts.add(new Draft(segment.t0(), noGo ? Source.NO_GO_LIVE : Source.BILLING_PINNED, zone.id(),
                        zone.version(), snapshot.setVersion(), snapshot.setVersion(), TransitionKind.ENTER,
                        transitionTime, null, EventReason.CROSSING));
            }
        }
    }

    public TripCompletion complete(Instant endTime) {
        if (endTime == null || state.completed()) throw new IllegalStateException("Trip cannot be completed");
        Instant closeTime = state.lastEventTime() != null && endTime.isBefore(state.lastEventTime()) ? state.lastEventTime() : endTime;
        List<Draft> drafts = new ArrayList<>();
        addTripEndDrafts(state.mutableBillingMemberships(), Source.BILLING_PINNED, closeTime, drafts);
        addTripEndDrafts(state.mutableNoGoMemberships(), Source.NO_GO_LIVE, closeTime, drafts);
        state.mutableBillingMemberships().clear();
        state.mutableNoGoMemberships().clear();
        List<AlertCandidate> ignoredAlerts = new ArrayList<>();
        List<ZoneEvent> exits = finalizeDrafts(drafts, ignoredAlerts, null, null);
        double total = state.totalMeters();
        double parts = state.distanceMeters().values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(total - parts) > Math.max(1e-6, Math.abs(total) * 1e-6)) throw new IllegalStateException("Trip distance ledger does not balance");
        RoundingAllocator.Allocation rounded = RoundingAllocator.allocate(state.distanceMeters());
        TripSummary summary = new TripSummary(state.distanceMeters(), total, rounded.kilometersByKey(), rounded.roundedTotalKilometers());
        state.markCompleted();
        return new TripCompletion(summary, exits);
    }

    private void addTripEndDrafts(Map<ZoneKey, OpenMembership> active, Source source, Instant end, List<Draft> drafts) {
        for (var entry : active.entrySet()) {
            OpenMembership open = entry.getValue();
            drafts.add(new Draft(1, source, entry.getKey(), open.zoneVersion(), open.zoneVersionSetVersion(),
                    open.evaluatedSetVersion(), TransitionKind.EXIT, end, dwell(open.enterTime(), end), EventReason.TRIP_END));
        }
    }

    private List<ZoneEvent> finalizeDrafts(List<Draft> drafts, List<AlertCandidate> alerts,
                                           NightWindow night, ZoneId tenantTimeZone) {
        drafts.sort(Comparator.comparingDouble(Draft::parameter).thenComparing(Draft::source)
                .thenComparing(Draft::zoneId).thenComparingInt(draft -> draft.kind() == TransitionKind.ENTER ? 0 : 1));
        List<ZoneEvent> events = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            long ordinal = state.nextOrdinal();
            UUID eventId = DeterministicIds.eventId(state.tenantId(), state.tripId(), ordinal);
            ZoneEvent event = new ZoneEvent(eventId, state.tenantId(), state.tripId(), draft.zoneId(), draft.zoneVersion(),
                    draft.zoneVersionSetVersion(), draft.evaluatedSetVersion(), ordinal, draft.kind(), draft.eventTime(),
                    draft.dwellSeconds(), draft.reason());
            events.add(event);
            if (draft.source() == Source.NO_GO_LIVE && draft.kind() == TransitionKind.ENTER && night != null
                    && night.contains(draft.eventTime(), tenantTimeZone)) {
                alerts.add(new AlertCandidate(DeterministicIds.alertId(eventId, NO_GO_NIGHT_ENTRY), eventId,
                        state.tenantId(), state.tripId(), draft.zoneId(), draft.evaluatedSetVersion(),
                        NO_GO_NIGHT_ENTRY, draft.eventTime()));
            }
        }
        return List.copyOf(events);
    }

    private static TreeMap<ZoneKey, Zone> containing(ZoneSnapshot snapshot, LonLat point, Predicate<Zone> filter) {
        TreeMap<ZoneKey, Zone> result = new TreeMap<>();
        snapshot.index().forEachContaining(point.lon(), point.lat(), ordinal -> {
            Zone zone = snapshot.zoneAt(ordinal);
            if (filter.test(zone)) result.put(zone.id(), zone);
        });
        return result;
    }

    private static Instant interpolate(Instant start, Instant end, double t) {
        long nanos = Duration.between(start, end).toNanos();
        return start.plusNanos(Math.round(nanos * t));
    }
    private static Double dwell(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return Math.max(0, duration.getSeconds() + duration.getNano() / 1_000_000_000.0);
    }
    private static double secondsBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private enum Source { BILLING_PINNED, NO_GO_LIVE }
    private record Draft(double parameter, Source source, ZoneKey zoneId, int zoneVersion,
                         long zoneVersionSetVersion, long evaluatedSetVersion, TransitionKind kind,
                         Instant eventTime, Double dwellSeconds, EventReason reason) { }
}
