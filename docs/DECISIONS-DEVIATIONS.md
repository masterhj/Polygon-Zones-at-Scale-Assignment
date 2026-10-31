# Decisions and Deviations

No architectural deviations from the canonical specifications have been made.

## Phase 2 specification clarifications

- **D-03 orientation tolerance:** the determinant has squared-degree units. For the orientation of `A→B` and `C`, classify as collinear when `abs(cross(B-A, C-A)) <= boundaryEpsDeg * hypot(B-A)`. This is the coordinate-space perpendicular-distance rule; it is translation invariant and preserves the configured degree tolerance for short as well as long edges. A zero-length baseline is handled with point distance. Proper-crossing parameters still use normalized segment parameters and `tEps`. This makes D-03/D-23 operational without changing D-02's closed-set boundary rule.
- **D-04 regional extent:** use the maximum haversine distance among the two diagonals of the polygon's longitude/latitude bounding box and reject values over 1,000,000 metres with `POLYGON_TOO_LARGE`. This is an explicit, conservative bound on geographic bbox diameter; the separate D-04 antimeridian and pole checks run first. This resolves the previously open operational check without changing the local lon/lat geometry model.

## Phase 5 sequence-gap retention

- **Skipped intervals:** retain every skipped sequence interval for the lifetime of the trip and coalesce only overlapping or adjacent intervals. This preserves D-10's exact `LATE` versus `DUPLICATE` classification after restart. No pruning, fixed cap, or lossy summary is safe under the current permanent-classification rule. Consequently, the reorder buffer and shard queue are bounded, but skipped-range metadata can grow without bound for trips with arbitrarily many disjoint gaps. This limitation is explicit; bounded long-lived trip memory is not claimed. Resolving it requires an explicit retention horizon or a persistence lookup contract that changes the current no-per-ping-DB-read design.
- **Alert severity storage:** the canonical alert schema requires a severity string but the case-study decisions define only the `NO_GO_NIGHT_ENTRY` kind and do not define a severity taxonomy. Persist this sole alert kind as `HIGH`; this is a storage label only and does not alter entry, timing, or delivery semantics.
