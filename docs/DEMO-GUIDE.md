# MoveInSync 2026: Polygon Zones at Scale — Live Demonstration Guide

This guide describes how to run and demonstrate the complete backend system live with a single command.

---

## 1. Quick Start (One Command)

Ensure Docker Desktop is running, then execute:

```bash
docker compose up --build
```

The stack initializes:
1. **PostgreSQL 18**: Applies Flyway migrations `V1` through `V6` with tenant schemas, composite primary keys, and spatial indexes.
2. **Zone Service Application (Java 25 / Spring Boot 4.1)**: Runs in `demo` mode, automatically creating:
   - Tenants: `acme` and `globex`
   - Users: `admin`, `operator`, `employee` with password `DemoOnly-ChangeMe-2026`
   - Pre-seeded Bengaluru zone configurations: City, Airport (with apron hole), Tariff zone, and Night No-Go zone.

The service is fully healthy when readiness returns `UP` on port `8081`.

---

## 2. Running the Automated Demonstration

In a separate terminal, execute:

```bash
./scripts/demo.sh
```

This runs an automated end-to-end walkthrough:
1. **Admin Authentication**: Obtains an HS256 stateless JWT token without personal identifiable information.
2. **Operator Authentication**: Generates an operator session with role-based permissions.
3. **Polygon Geometric Validation**: Submits an unclosed polygon ring and shows real-time validation error detection.
4. **Sub-microsecond Spatial Index Query**: Queries coordinates in Bengaluru against the STR R-tree spatial index.
5. **Trip Lifecycle**: Starts a tenant-isolated trip pinned to the latest zone set version.
6. **Ping Processing**: Submits GPS pings; the pipeline handles ordering, deduplication, and transition checks.
7. **Attribution & Fare Allocation**: Completes the trip and returns segment-split distance per tariff zone.

---

## 3. Manual Live Demonstration via cURL

### A. Login as Admin
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenant":"acme","username":"admin","password":"DemoOnly-ChangeMe-2026"}'
```

### B. Point Query (In-Memory STR R-Tree)
```bash
curl -X POST http://localhost:8080/api/v1/zones/query \
  -H "Authorization: Bearer <OPERATOR_OR_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"lon":77.55,"lat":12.93}'
```

### C. Live Zone Versioning & Non-Disruptive Swap
Create or update a zone as an Admin:
```bash
curl -X POST http://localhost:8080/api/v1/zones \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "TARIFF",
    "priority": 15,
    "geometry": {
      "type": "Polygon",
      "coordinates": [[[77.50,12.90],[77.58,12.90],[77.58,12.98],[77.50,12.98],[77.50,12.90]]]
    }
  }'
```
*Ongoing trips continue evaluating against their pinned version, while new queries immediately see the new atomically swapped snapshot.*

---

## 4. Teardown
```bash
docker compose down -v
```
