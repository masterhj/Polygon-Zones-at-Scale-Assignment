# Polygon Zones at Scale

MoveInSync Backend Case Study 2026, Case Study 7. The canonical requirements and phased implementation roadmap are in the numbered Markdown files at the repository root.

## Development database

Start PostgreSQL 18 with `docker compose up -d postgres`. Local-only defaults are database `zones`, user `zones`, and password `zones_dev_only`; production credentials must be supplied with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

## Build

The project targets Java 25 and Maven Wrapper. Run `./mvnw -v`, then `./mvnw -q verify` with Docker available for Testcontainers integration tests. Phase 2 implements the plain-Java geometry core in `zone-core`.

### Distance accuracy (Phase 2)

`Haversine.distanceMeters` uses a mean Earth radius of 6,371,008.8 m. The test-only Vincenty analysis in [distance-error.csv](docs/benchmarks/distance-error.csv) ran 36 cases (1, 10, and 100 km; bearings 0°, 45°, and 90°; latitudes 0°, 12.97°, 28.6°, and 60°). The largest observed relative error was **0.56144%** (equatorial north/south sample); this is the measured maximum for this case grid, not a global bound. Degree-space linear interpolation's midpoint deviated from the geodesic midpoint by at most **383.985 m**, at 100 km and high latitude. This quantifies why the planar lon/lat model is intended for regional polygons and consecutive trip pings, not long geodesic routes.

The measurement ran on Apple M2 (8 CPU cores), 16 GB RAM, macOS 26.5.1, with Homebrew OpenJDK 25.0.2. Reproduce it with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./mvnw -q -pl zone-core -Dtest=DistanceErrorAnalysisTest test`.

See [docs](docs/README.md) and [implementation log](docs/IMPLEMENTATION-LOG.md).
