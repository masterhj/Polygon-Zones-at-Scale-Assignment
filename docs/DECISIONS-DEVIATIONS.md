# Decisions and Deviations

No architectural deviations from the canonical specifications have been made.

## Phase 2 specification clarifications

- **D-03 orientation tolerance:** the determinant has squared-degree units. For the orientation of `A→B` and `C`, classify as collinear when `abs(cross(B-A, C-A)) <= boundaryEpsDeg * hypot(B-A)`. This is the coordinate-space perpendicular-distance rule; it is translation invariant and preserves the configured degree tolerance for short as well as long edges. A zero-length baseline is handled with point distance. Proper-crossing parameters still use normalized segment parameters and `tEps`. This makes D-03/D-23 operational without changing D-02's closed-set boundary rule.
- **D-04 regional extent:** use the maximum haversine distance among the two diagonals of the polygon's longitude/latitude bounding box and reject values over 1,000,000 metres with `POLYGON_TOO_LARGE`. This is an explicit, conservative bound on geographic bbox diameter; the separate D-04 antimeridian and pole checks run first. This resolves the previously open operational check without changing the local lon/lat geometry model.
