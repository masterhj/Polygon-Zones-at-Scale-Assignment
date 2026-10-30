# 08 — Phase 5: Trip Attribution, Events, No-Go Alerts, Ping Pipeline

**Goal:** split each trip's path at exact zone-boundary crossings to compute km per zone; emit ENTER/EXIT with dwell time; raise night no-go alerts; ingest pings in order, deduplicated, with checkpointing.
**Requirements:** R3.1, R3.2, R3.3, EX1, K1, P3. **Decisions:** D-04, D-06, D-07, D-09, D-10, D-15.

## 1. Segment splitting (`zone-core/trip/SegmentSplitter`) — the heart of R3.1

Input: snapshot (for billing), segment `a→b` (two `LonLat` with times `ta`, `tb`).
Output: `List<SubSegment(t0, t1, billingZoneOrdinal|UNZONED, Set<ordinal> memberships)>`.

Algorithm:
1. `L = haversine(a,b)`. If `L == 0` or `a==b` → no sub-segments (but time still advances).
2. Candidate zones: `index.forEachCandidate(bbox(a,b) expanded by eps)`.
3. For each candidate polygon, query the union of edge-band buckets intersecting the segment's `[minY-eps, maxY+eps]` range; deduplicate edge ordinals, then exact-test those edges with `SegmentIntersector`. A point query uses one band, but a segment query must cover every intersected band. This is only a candidate filter: long edges and wide y-ranges can still yield many candidates. Collect crossing parameters `t ∈ (0,1)` (degree-space; invariant under independent affine lon/lat scaling — D-04). Handle:
   - proper crossing → one `t`;
   - touching an endpoint of the ring edge → `t` of the touch point (may or may not change membership; the midpoint test below resolves it);
   - collinear overlap with an edge → both overlap ends as `t` values.
4. Sort all `t` plus `{0,1}`; remove near-duplicates (`|Δt| ≤ tEps`).
5. For each consecutive pair `(ti, tj)`: take the **midpoint** `m = lerp(a,b,(ti+tj)/2)` and compute the membership set with `index.forEachContaining(m)`; the **billing zone** is the winner per D-06 among billable members (else `UNZONED`).
6. Sub-segment length = `L × (tj − ti)` (D-04 length-proportional split ⇒ Σ == L exactly).
7. Merge adjacent sub-segments with the same membership set (keeps the output small and the events clean).

Edge cases to cover in tests: segment lying entirely inside one zone; entirely outside; crossing a convex boundary twice; crossing a concave notch (in-out-in-out); passing exactly through a vertex; running **along** a boundary edge (collinear; with D-02 closed-set rule the boundary counts as inside); grazing a vertex tangentially (in and out at the same point → zero-length inside interval ⇒ dropped, no event); crossing a hole (zone → hole → zone); crossing overlapping zones with different priorities (billing winner changes at the inner boundary); zero-length segment; extremely short segments (< 1 mm); very long segment (e.g. 40 km across several zones); segment endpoints exactly on boundaries; antimeridian-adjacent coordinates rejected earlier.

## 2. Incremental attribution (`TripAttributor`) — pure, deterministic

State (`TripState`, serialisable via the service layer): `tripId`, `pinnedSetVersion`, `lastPoint`, `lastEventTime`, `processedSeq` (highest applied sequence), `durableAckedSeq` (committed contiguous resolved frontier), skipped sequence ranges, `nextTransitionOrdinal`, `Map<String zoneKey, double meters>`, `Map<ZoneKey, OpenMembership(enterTime, zoneVersion, zoneVersionSetVersion, enterSeq)> billingMemberships`, `Map<ZoneKey, OpenMembership> noGoMemberships`, counters (`gapsSkipped`, `latePings`).

`ProcessResult apply(Ping p, ZoneSnapshot pinned, ZoneSnapshot latest, NightWindow tenantNight, java.time.ZoneId tenantTz)`:
1. **First ping:** set `lastPoint`; compute membership at the point on both snapshots; emit ENTER (`reason=TRIP_START`) for every zone containing it (billing zones from `pinned`, NO_GO from `latest`). If the first ping is inside a NO_GO zone at night ⇒ alert (D-07).
2. **Subsequent ping:** `a=lastPoint`, `b=p`:
   - *Billing side (pinned):* `SegmentSplitter.split(pinned, a, b)` → add meters per billing zone key; for membership changes between consecutive sub-segments emit ENTER/EXIT with **interpolated event time** `ta + t·(tb − ta)`; EXIT carries `dwellSeconds = exit − enter`.
   - *Safety side (latest NO_GO only):* capture one latest snapshot reference at the start of processing this ping, then run the same splitter against that snapshot but only for `NO_GO` zones (a `ZoneTypeFilter`); emit NO_GO ENTER/EXIT and stamp its set version on every event. On each NO_GO ENTER at a night moment (tenant tz, D-07), create an `AlertCandidate` whose id is UUIDv5 derived from the deterministic source event id and `NO_GO_NIGHT_ENTRY` (D-21).
   - If `latest.setVersion` changes between pings so that a NO_GO zone now covers `a` (zone grew under the cab) → synthetic ENTER at the ping time with `reason=ZONE_CHANGED` (and alert if night). If a zone vanished or shrank away → EXIT `reason=ZONE_CHANGED`.
3. Sort all transition events for this operation by D-21's deterministic key, assign the next per-trip transition ordinals, then update `lastPoint`, `lastEventTime`, and `processedSeq`.
4. **Time monotonicity:** if `p.eventTime < lastEventTime` (while `seq` is in order) → clamp for dwell calculations and count `time_regression_total`; do not produce negative dwell.
5. **Implausible jumps (SHOULD):** if implied speed > `maxSpeedKmh` (default 250) between consecutive accepted pings → still attribute distance but flag `suspectJump` in the result/counter (do not silently drop billing data); document.

`complete(Instant endTime)`: close all open memberships with EXIT `reason=TRIP_END` (dwell to `endTime`), return `TripSummary` with:
- metres per zone key, total metres, `RoundingAllocator` output in km with 3 decimals (largest remainder);
- invariants asserted in code: `Σ zones + UNZONED == total` within `1e-6` m relative.

`NightWindow.contains(Instant, java.time.ZoneId tz)` per D-07 (wrap-around, DST-safe). `ZoneKey` is the domain UUID wrapper; use `java.time.ZoneId` only for time-zone rules.

## 3. Ping pipeline (`zone-service/pipeline`)

- `PingIngestService.ingest(tenantId, tripId, List<PingDto>)`:
  - Validate (finite coordinates, ranges, `seq ≥ 0`, `eventTime` window per D-10); invalid items ⇒ per-item `REJECTED(INVALID)`; request-level `400` only for malformed JSON/size > 500.
  - `BackpressureGuard`: bounded `ArrayBlockingQueue`/semaphore per shard (`queue-capacity` total); if full ⇒ whole request `429 BACKPRESSURE` with `Retry-After`, **nothing partially enqueued** (check capacity before enqueue or enqueue all-or-nothing).
  - Route to shard `Math.floorMod(hash(tenantId, tripId), shards)`; each shard is a single-thread executor (platform threads, named `trip-shard-*`) ⇒ per-trip single-writer ordering without locks.
  - Returns promptly with item statuses and `durableAckedSeq`. `ACCEPTED` means admitted to the bounded queue only; it does not imply processing or persistence. `durableAckedSeq` is the highest contiguous resolved sequence whose complete effects and checkpoint are committed.
- `TripProcessor` (runs on the shard thread): owns `TripState`, the `ReorderBuffer`, and dedupe/skipped-range classification for its trips; calls `TripAttributor.apply`, collects events/alerts/metre totals, and captures immutable persistence snapshots. Normal processing never queries Postgres per ping.
- `ReorderBuffer`: per-trip `TreeMap<Long, Ping>` bounded by `maxBuffered`; release in order from the next unresolved sequence; when the oldest buffered ping has waited `> maxHold` (injected `Clock` plus periodic shard tick), mark the missing interval skipped (`ping_gap_skipped_total++`) and continue. A later ping in a skipped interval is `REJECTED(LATE)`, including after restart. A resolved sequence not in a skipped interval is `DUPLICATE`. Persist and recover skipped ranges so the distinction remains exact. The memory/storage bound and pruning policy for long-lived trips with many disjoint gaps is a verification item in `CHANGELOG.md`.
- `TripCheckpointer`: every `checkpoint-every-pings` or `checkpoint-every-ms`, and on complete/shutdown, capture a shard-consistent state frontier. In one transaction persist `processed_seq`, `durable_acked_seq`, skipped-range/checkpoint JSON, exact cumulative `trip_zone_distance` totals and `trips.unzoned_meters`, and insert the snapshot's `zone_events`/`alerts` with conflict-safe IDs. Cumulative totals are assigned, never incremented on retry. Serialize writes per trip and reject stale-frontier overwrites. Advance in-memory `durableAckedSeq` only after commit. Retry transient failures (max 3 per attempt); if still failing, retain accepted state in bounded memory, increment `checkpoint_failures_total`, retry the same captured frontier later, and apply backpressure when the unpersisted backlog reaches its configured cap.
- **Recovery:** on startup (or lazily on first ping), load each `IN_PROGRESS` trip's last committed checkpoint, re-acquire its pinned snapshot, rebuild `TripState`, and resume producers from `durableAckedSeq + 1`. Processed but uncommitted state is absent and is replayed; committed state and events are restored atomically.
- **Graceful shutdown:** stop accepting, drain shards, final checkpoint (`SmartLifecycle` with phase ordering; `server.shutdown=graceful`).

## 4. Required tests

**Core (no Spring)**
- `SegmentSplitterTest` — every edge case in §1 with exact expected `t` values / metres (use simple geometry like axis-aligned squares and degree-space lines where expected intersections are computable by hand; assert to `1e-9`).
- `AirportDropScenarioTest` (**EX1**): build a route along a straight corridor totalling **≈ 42.000 km** (construct by solving for the point where cumulative haversine = 30.4 km and place the airport zone boundary there — the "toll plaza"); city zone covers the first part, airport zone the rest; pings every ~500 m with some jitter-free points; assert **30.400 ± 0.005 km city, 11.600 ± 0.005 km airport, sum 42.000 km**, and the rounded invoice shows 30.4 + 11.6 = 42.0. Also run with a **single** 42 km segment (two pings only) to prove the exact boundary split independent of ping spacing.
- `OverlapPriorityTest`: overlapping CITY (prio 0) and AIRPORT (prio 10): distance in the overlap billed to AIRPORT only; Σ preserved.
- `TripAttributorEventsTest`: ENTER/EXIT times interpolated correctly; dwell computed; trip start inside zone (`TRIP_START`); trip ends inside (`TRIP_END`); re-entering the same zone twice yields two dwell periods; tangent graze produces no events.
- `NoGoAlertTest` (**R3.3**): night (23:00 Asia/Kolkata) entry ⇒ exactly one alert; day (14:00) ⇒ none; entry at 21:59:59 vs 22:00:00 boundary; window wrap past midnight; DST zone (e.g. tenant in `America/New_York` on a DST change night) behaves correctly; start-inside-at-night ⇒ alert; two entries ⇒ two alerts with different ids; replay of the same pings ⇒ same ids (idempotent).
- `RoundingAllocatorTest`: parts always sum to rounded total; deterministic tie-break.
- Property test: for random zones and random polylines, `Σ zone metres + UNZONED == Σ haversine(segments)` within 1e-6 relative, and results are **invariant under ping batching** (same path as 1 vs many sub-pings along a straight line yields the same per-zone metres within a tolerance consistent with D-04).

**Service**
- `PingPipelineTest`: duplicates ⇒ metres unchanged; shuffled order within window ⇒ identical final result to the ordered run; gap beyond hold ⇒ skipped + counted; late ping ⇒ REJECTED(LATE); backpressure ⇒ 429 without partial enqueue.
- `TripRecoveryFailureTest` (**failure injection #1**): process N pings with checkpoint every 50; simulate a crash without final checkpoint; restart; replay from `durableAckedSeq+1`, including pings processed only in volatile state; final per-zone/unzoned metres, events, and alerts must equal an uninterrupted run exactly. Also replay a committed sequence and assert `DUPLICATE`, and replay a skipped sequence and assert `LATE`.
- `CheckpointFailureTest` (**failure injection #2**): make the repository throw for the first K attempts (test double) ⇒ pings may be `ACCEPTED`, `durableAckedSeq` does not advance, failure/retry metrics increment, and eventual persistence matches the same immutable checkpoint totals.

## 5. Acceptance criteria
- [ ] EX1 passes with 30.4 / 11.6 / 42.0.
- [ ] Σ invariants hold under property tests.
- [ ] Dedupe/reorder/late rules behave exactly as D-10 and are visible in metrics.
- [ ] Crash-and-replay yields identical results to an uninterrupted run.
- [ ] No unbounded memory: per-trip buffers, seen-sets and shard queues are bounded and tested.
- [ ] `durableAckedSeq` advances only after the matching atomic checkpoint commits; restart replays every accepted but uncommitted ping.
- [ ] Skipped-sequence metadata supports persistent `LATE` versus `DUPLICATE` classification. Its retention/size bound is resolved and tested before claiming long-lived-trip bounded memory (see `CHANGELOG.md`).

## Suggested commits
- `feat(core): segment splitter with exact boundary crossings`
- `feat(core): trip attributor with events, dwell and night no-go alerts`
- `feat(core): rounding allocator and night window`
- `test(core): airport drop scenario (EX1) and property invariants`
- `feat(pipeline): sharded ping ingestion with reorder buffer, dedupe, backpressure`
- `feat(pipeline): checkpointing, recovery and graceful shutdown`
- `test(pipeline): crash/replay and checkpoint failure injection`
