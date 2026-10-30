# Phase 2 Geometry Requirements-to-Tests Map

| Requirement / behavior | Automated test |
|---|---|
| Finite coordinate construction, longitude/latitude range validation | `GeometryValuesTest`, `PolygonValidatorTest` |
| Bounding-box validity, containment, expansion, intersection, union | `GeometryValuesTest` |
| D-02 closed-set boundary: outer and hole edges/vertices are included; strict hole interior excluded | `PolygonShapeBoundaryRuleTest` |
| Even-odd point location, half-open ray rule, concave shapes and holes | `PolygonShapeBoundaryRuleTest`, `GeometryPropertyTest` |
| Random star-shaped polygon containment matches independent JTS oracle on 100,000 non-boundary points | `GeometryPropertyTest` (100,000 generated query points; near-edge points excluded) |
| Closed rings, minimum size, duplicate edges, zero area, finite/range/antimeridian/extent/size/hole limits; aggregate issue results | `PolygonValidatorTest` |
| Self-intersection detector agrees with quadratic test oracle on 10,000 seeded polylines; adjacent shared endpoints accepted, overlaps/touches rejected | `PolygonValidatorTest`, `SelfIntersectionDetectorTest` |
| Outer-hole and hole-hole crossing, touching, outside, nesting and overlap validation | `PolygonValidatorTest` |
| Ring orientation normalization and bbox/index construction | `PolygonValidatorTest` |
| Edge-band point candidates equal full edge scan, including boundary tolerance and long edges | `EdgeBandIndexTest` |
| Edge-band segment-range candidates equal full scan and deduplicate across many bands | `EdgeBandIndexTest` |
| Segment none/cross/touch/collinear/parallel/degenerate cases and proper-crossing parameters | `SegmentIntersectorTest` |
| Stable haversine distance, interpolation domain and identity properties | `HaversineTest` |
| Haversine and degree-lerp midpoint errors against independent test-only Vincenty reference over the specified latitudes, distances and bearings | `DistanceErrorAnalysisTest`; emits `docs/benchmarks/distance-error.csv` |

The production core has no framework dependencies. JTS and the Vincenty oracle stay test-scoped. Tests use deterministic seeds and avoid treating near-boundary oracle disagreements as containment failures.
