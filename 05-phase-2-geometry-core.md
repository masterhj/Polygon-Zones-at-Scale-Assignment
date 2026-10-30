# 05 — Phase 2: Geometry Core (plain Java, `zone-core`)

**Goal:** immutable polygon model, strict validation, a correct point-in-polygon with the D-02 boundary rule and D-03 tolerance, and distance helpers. No Spring, no database.
**Requirements:** R1.1–R1.3, R2.1, R2.2, K1, K2, S3. **Decisions:** D-02, D-03, D-04, D-05.

## 1. Types to implement (package `com.polyzones.core.geo` and `...shape`)

- `record LonLat(double lon, double lat)` with a static factory that rejects NaN/∞.
- `record BoundingBox(double minLon, double minLat, double maxLon, double maxLat)` with `contains(lon,lat,eps)`, `intersects(BoundingBox)`, `expand(eps)`, `union`.
- `record GeometryTolerance(double boundaryEpsDeg, double tEps)` with `DEFAULT` = `1e-9`, `1e-12` (D-03).
- `enum Location { INSIDE, BOUNDARY, OUTSIDE }`.
- `final class Ring` — immutable closed ring stored as `double[] xs, ys` (length n+1, last == first), signed area, orientation normalised, bbox, and an **`EdgeBandIndex`**:
  - Split the ring's y-extent into `B ≈ ceil(sqrt(edgeCount))` equal bands; each band holds an `int[]` of edge indices whose [yMin,yMax] (expanded by eps) overlaps the band.
  - `Location locate(double x, double y, GeometryTolerance tol)` examines only the point's band (plus eps overlap handled at build time).
  - `forEachEdgeCandidate(double minY, double maxY, GeometryTolerance tol, IntConsumer edgeOrdinal)` examines and deduplicates the union of all bands touched by the range. The segment splitter must use this range query; looking at only one band can miss crossings in a tall segment.
- `final class PolygonShape` — outer `Ring` + `List<Ring>` holes (immutable), bbox, `vertexCount`, `Location locate(lon, lat, tol)` and `boolean contains(lon, lat, tol)`; static factory `PolygonShape.of(outer, holes, tol)` **only** returns a validated instance (private constructor ⇒ no invalid polygon can exist — encapsulation).

## 2. Point-in-polygon algorithm (R2.1, R2.2, K2)

Per ring, `locate(x, y)`:
1. If `y` outside `[minY - eps, maxY + eps]` or `x` outside `[minX - eps, maxX + eps]` → `OUTSIDE` quickly.
2. **Boundary test first:** for every candidate edge (from the point band), compute Euclidean distance from `(x,y)` to the segment in planar degree coordinates (clamped projection). If `dist ≤ eps` → `BOUNDARY`. (Vertices are covered because edges include endpoints.) This is the literal D-03 coordinate-space tolerance; its physical east-west distance varies with latitude, so do not describe `1e-9` degrees as a globally constant metre tolerance.
3. **Crossing-number (even–odd) test** with the **half-open rule** to avoid double-counting vertices: an edge `(x1,y1)-(x2,y2)` counts if `(y1 > y) != (y2 > y)` and the intersection abscissa `xi = x1 + (y - y1) * (x2 - x1) / (y2 - y1)` satisfies `x < xi`. Horizontal edges never count. Parity odd → `INSIDE`, else `OUTSIDE`.
4. Because step 2 ran first, near-boundary points never reach step 3 with ambiguous arithmetic.

`PolygonShape.locate`:
- outer `OUTSIDE` → `OUTSIDE`; outer `BOUNDARY` → `BOUNDARY` (INSIDE per D-02).
- else for each hole (bbox pre-check): hole `INSIDE` → `OUTSIDE` (inside the open hole); hole `BOUNDARY` → `BOUNDARY` (INSIDE per D-02); else continue.
- return `INSIDE`.
- `contains()` = `locate() != OUTSIDE`.

Document and unit-test the rule in `PolygonShapeBoundaryRuleTest` (name tests after D-02).

## 3. Validation (R1.1–R1.3) — `PolygonValidator`

Return **all** issues (not only the first) as `ValidationResult { List<ValidationIssue(code, ringIndex, detail) > }`; `PolygonShape.of` throws `InvalidPolygonException(ValidationResult)` if any. Codes (must match file 03 catalogue): `POLYGON_NOT_CLOSED`, `TOO_FEW_POINTS`, `DUPLICATE_CONSECUTIVE_POINTS`, `ZERO_AREA`, `INVALID_COORDINATE`, `ANTIMERIDIAN_UNSUPPORTED`, `POLYGON_SELF_INTERSECTS`, `RINGS_TOUCH_OR_CROSS`, `HOLE_OUTSIDE_OUTER`, `HOLES_OVERLAP`, `POLYGON_TOO_LARGE`.

Order of checks (cheap → expensive, short-circuit expensive ones if cheap ones failed):
1. Coordinate sanity & size limits.
2. Closed ring (first == last exactly) & min points.
3. Consecutive duplicates & zero-area (signed area ≈ 0 relative to bbox area).
4. **Self-intersection per ring** — sub-quadratic: implement **Shamos–Hoey** sweep (detect any intersection among non-adjacent segments; adjacent segments may share only their common endpoint; any other touching/collinear overlap = invalid) **or** a segment-STR/uniform-grid broad phase feeding the exact `SegmentIntersector`. Provide `NaiveSelfIntersectionDetector` O(n²) in **test sources** as an oracle.
5. **Ring-vs-ring** (outer vs each hole, hole vs hole): any segment intersection or touching → `RINGS_TOUCH_OR_CROSS`.
6. **Hole inside outer:** after step 5 passes, one vertex of each hole strictly inside outer (`OUTSIDE` ⇒ `HOLE_OUTSIDE_OUTER`).
7. **Holes not nested/overlapping:** after step 5, a hole vertex strictly inside another hole ⇒ `HOLES_OVERLAP`.
8. Normalise orientation (outer CCW, holes CW).

`SegmentIntersector` (exact-enough with eps): classify two segments as `NONE`, `PROPER_CROSS`, `TOUCH_ENDPOINT`, `COLLINEAR_OVERLAP`; also returns the parameters `(t, u)` for proper crossings — reused in phase 5. Use `cross(a,b,c)` with the scaled epsilon of D-03. The exact dimensionally consistent scaling formula for orientation/collinearity is not fully specified in D-03; mark it for verification before implementation, and test near-collinear and large-coordinate cases rather than inventing a formula silently.

## 4. Distance utilities (K1)

- `Haversine.distanceMeters(LonLat a, LonLat b)` with `R = 6_371_008.8 m`; use the numerically stable `atan2` form; clamp `a` into [0,1].
- `Haversine.lerp(a, b, t)` linear interpolation in degree space (documented as such).
- **Error analysis task (must be executed, not guessed):** in **test** sources, implement a Vincenty (or Karney) inverse for WGS-84 as the reference; compute the haversine relative error for: (a) 1 km, 10 km, 100 km segments at latitudes 0°, 12.97° (Bengaluru), 28.6° (Delhi), 60°; (b) bearings 0°, 45°, 90°. Write results to `docs/benchmarks/distance-error.csv` and a summary table in README. Also measure the **degree-space straight-line interpolation** error (midpoint of lerp vs geodesic midpoint) for the same cases.

## 5. Test plan (write tests first for each bullet)

`PolygonShapeContainsTest`, `PolygonShapeBoundaryRuleTest`, `PolygonValidatorTest`, `SegmentIntersectorTest`, `HaversineTest`, `GeometryPropertyTest`:

**Containment (R2.1, K2)** — table-driven with ASCII diagrams in comments:
- Convex square: inside, outside, each of the 4 edges (midpoint and near-end), each of the 4 vertices.
- **Concave "U"/"C" shape:** point in the notch (outside), point inside an arm, point exactly on the notch's re-entrant vertex.
- **Hole:** point in the ring body; strictly inside the hole (OUTSIDE); **on the hole edge, on the hole vertex (INSIDE per D-02)**; point between two holes.
- **Ray hits vertex:** point whose horizontal ray passes exactly through one vertex, through two vertices (a "V" tip), and along a horizontal edge (collinear with ray).
- Thin spike / sliver polygon; polygon with 10,000 vertices (star-shaped) — compare to oracle.
- Epsilon boundary: point at distance `0.5*eps` → BOUNDARY; at `2*eps` → strict INSIDE/OUTSIDE; diagonal edge midpoint computed in floating point.
- Extremes: bbox corner points, lat ±89, lon ±179.9; polygon far from origin (translation invariance: same shape at (0,0) and (77.59, 12.97) gives identical results after translating the query).
- **Property tests** (jqwik or seeded random with ≥100k cases): generate random simple polygons (star-shaped around a centre with random radii) with optional holes (smaller scaled star inside); for random points **not within 1e-7 of any edge**, `PolygonShape.contains` must equal JTS (`org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator` or `PointLocator`). Points within 1e-7 of an edge are asserted only to be `BOUNDARY` when they are within `eps` of an edge computed by the test.

**Validation (R1.x)** — each code gets ≥2 tests (positive reject + a near-miss accepted):
- Not closed (first ≠ last by 1 ulp) → reject.
- Bow-tie, figure-8 pinch at a vertex, collinear overlapping edges, long polygon whose last edge crosses the first → reject.
- Hole outside outer; hole crossing outer; hole touching outer at a vertex; hole touching hole; nested holes; identical holes → reject.
- Valid: concave polygon, polygon with 3 disjoint holes, polygon with 49,999 vertices → accept (and finishes < 2 s; assert via generous timeout, not as a benchmark).
- Orientation: CW input outer ring is normalised; result `locate` unchanged.
- Self-intersection detector agrees with the naive oracle on ≥10,000 random polylines (some intersecting, some not).

## 6. Acceptance criteria
- [ ] `./mvnw -q -pl zone-core test` green; no Spring on the classpath.
- [ ] 100% of D-02 boundary cases covered by named tests.
- [ ] Validator catches all of R1.1–R1.3 with specific codes and returns multi-issue results.
- [ ] `docs/benchmarks/distance-error.csv` generated from real runs; README stub lists the measured max error.
- [ ] Every magic number is in `GeometryTolerance`/constants with a comment referencing the decision ID.
- [ ] Point and segment edge-band queries are equivalent to scanning every edge, including a segment spanning many y-bands and long edges that overlap multiple bands.

## Suggested commits
- `feat(core): add LonLat, BoundingBox, tolerance and Location types`
- `feat(core): ring with edge-band index and boundary-aware point location`
- `feat(core): polygon validator with sweep-line self-intersection detection`
- `test(core): boundary rule, holes, concave and property tests against JTS oracle`
- `feat(core): haversine distance and measured error report`
