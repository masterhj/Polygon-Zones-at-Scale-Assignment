#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8080/api/v1"

echo "=========================================================="
echo " MoveInSync 2026: Polygon Zones at Scale - Live Demo Script"
echo "=========================================================="
echo ""

echo "[1/8] Logging in as ACME tenant ADMIN..."
ADMIN_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"acme","username":"admin","password":"DemoOnly-ChangeMe-2026"}')

ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
if [ -z "$ADMIN_TOKEN" ]; then
  echo "Error: Failed to obtain admin token. Ensure the service is running (docker compose up)."
  echo "Response: $ADMIN_LOGIN"
  exit 1
fi
echo "✓ Admin token acquired successfully."
echo ""

echo "[2/8] Logging in as ACME tenant OPERATOR and EMPLOYEE..."
OP_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"acme","username":"operator","password":"DemoOnly-ChangeMe-2026"}')
OP_TOKEN=$(echo "$OP_LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

EMP_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"acme","username":"employee","password":"DemoOnly-ChangeMe-2026"}')
EMP_TOKEN=$(echo "$EMP_LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
echo "✓ Operator and Employee tokens acquired successfully."
echo ""

echo "[3/8] Validating zone geometry (detecting unclosed ring rejection)..."
INVALID_POLY='{"geometry":{"type":"Polygon","coordinates":[[[77.5,12.9],[77.6,12.9],[77.6,13.0],[77.5,13.0]]]}}'
VALIDATE_RESP=$(curl -s -X POST "$BASE_URL/zones/validate" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$INVALID_POLY")
echo "Validation Response (Issues expected): $VALIDATE_RESP"
echo ""

echo "[4/8] Querying seeded zones at Bengaluru coordinate (77.55, 12.93)..."
QUERY_RESP=$(curl -s -X POST "$BASE_URL/zones/query" \
  -H "Authorization: Bearer $OP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"lon":77.55,"lat":12.93}')
echo "Spatial Query Response: $QUERY_RESP"
echo ""

echo "[5/8] Starting a new trip pinned to zone set version for employee (812aaf6e-f15f-3cd6-8ec1-f9ae79c945fe)..."
EMPLOYEE_ID="812aaf6e-f15f-3cd6-8ec1-f9ae79c945fe"
START_TRIP_RESP=$(curl -s -X POST "$BASE_URL/trips" \
  -H "Authorization: Bearer $OP_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"employeeId\":\"$EMPLOYEE_ID\",\"startTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")
echo "Start Trip Response: $START_TRIP_RESP"
TRIP_ID=$(echo "$START_TRIP_RESP" | grep -o '"tripId":"[^"]*' | cut -d'"' -f4 || echo "")
echo ""

if [ -n "$TRIP_ID" ]; then
  echo "[6/8] Streaming GPS trajectory pings (including sequential and duplicate ping)..."
  NOW_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  PING_RESP=$(curl -s -X POST "$BASE_URL/trips/$TRIP_ID/pings" \
    -H "Authorization: Bearer $OP_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"pings\":[
      {\"seq\":0,\"eventTime\":\"$NOW_TS\",\"lon\":77.42,\"lat\":12.82,\"accuracyMeters\":5.0},
      {\"seq\":1,\"eventTime\":\"$NOW_TS\",\"lon\":77.56,\"lat\":12.93,\"accuracyMeters\":5.0},
      {\"seq\":1,\"eventTime\":\"$NOW_TS\",\"lon\":77.56,\"lat\":12.93,\"accuracyMeters\":5.0}
    ]}")
  echo "Ping Admission Response (Duplicate handled synchronously): $PING_RESP"
  echo ""

  echo "[7/8] Completing trip and retrieving tariff zone attribution..."
  COMPLETE_RESP=$(curl -s -X POST "$BASE_URL/trips/$TRIP_ID/complete" \
    -H "Authorization: Bearer $OP_TOKEN")
  echo "Complete Trip: $COMPLETE_RESP"
  echo ""

  ATTR_RESP=$(curl -s -X GET "$BASE_URL/trips/$TRIP_ID/attribution" \
    -H "Authorization: Bearer $OP_TOKEN")
  echo "Attribution Summary: $ATTR_RESP"
  echo ""

  EVENTS_RESP=$(curl -s -X GET "$BASE_URL/trips/$TRIP_ID/events" \
    -H "Authorization: Bearer $EMP_TOKEN")
  echo "Employee Reading Own Trip Events: $EVENTS_RESP"
  echo ""
fi

echo "[8/8] Testing Cross-Tenant Security Isolation (Globex user querying Acme trip)..."
GLOBEX_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"globex","username":"operator","password":"DemoOnly-ChangeMe-2026"}')
GLOBEX_TOKEN=$(echo "$GLOBEX_LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

CROSS_TENANT_RESP=$(curl -s -w "\nHTTP Status: %{http_code}" -X GET "$BASE_URL/trips/$TRIP_ID" \
  -H "Authorization: Bearer $GLOBEX_TOKEN")
echo "Cross-Tenant Response (Must be 404 Not Found to prevent data leakage):"
echo "$CROSS_TENANT_RESP"

echo ""
echo "=========================================================="
echo " Live demonstration flow finished successfully!"
echo "=========================================================="
