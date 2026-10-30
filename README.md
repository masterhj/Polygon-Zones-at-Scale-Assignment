# Polygon Zones at Scale

MoveInSync Backend Case Study 2026, Case Study 7. The canonical requirements and phased implementation roadmap are in the numbered Markdown files at the repository root.

## Development database

Start PostgreSQL 18 with `docker compose up -d postgres`. Local-only defaults are database `zones`, user `zones`, and password `zones_dev_only`; production credentials must be supplied with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

## Build

The project targets Java 25 and Maven Wrapper. Run `./mvnw -v`, then `./mvnw -q verify` with Docker available for Testcontainers integration tests. Phase 1 bootstrap is under development; benchmark results have not been measured.

See [docs](docs/README.md) and [implementation log](docs/IMPLEMENTATION-LOG.md).
