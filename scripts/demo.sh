#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8080/api/v1"

echo "=========================================================="
echo " MoveInSync 2026: Polygon Zones at Scale - Live Demo Script"
echo "=========================================================="
echo ""

echo "[1/7] Logging in as ACME tenant ADMIN..."
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

echo "[2/7] Logging in as ACME tenant OPERATOR..."
OP_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenant":"acme","username":"operator","password":"DemoOnly-ChangeMe-2026"}')
OP_TOKEN=$(echo "$OP_LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
echo "✓ Operator token acquired successfully."
echo ""

echo "[3/7] Validating zone geometry (detecting non-closed ring)..."
INVALID_POLY='{"geometry":{"type":"Polygon","coordinates":[[[77.5,12.9],[77.6,12.9],[77.6,13.0],[77.5,13.0]]]}}'
VALIDATE_RESP=$(curl -s -X POST "$BASE_URL/zones/validate" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$INVALID_POLY")
echo "Validation Response (Issues expected): $VALIDATE_RESP"
echo ""

echo "[4/7] Querying seeded zones at Bengaluru coordinate (77.55, 12.93)..."
QUERY_RESP=$(curl -s -X POST "$BASE_URL/zones/query" \
  -H "Authorization: Bearer $OP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"lon":77.55,"lat":12.93}')
echo "Spatial Query Response: $QUERY_RESP"
echo ""

echo "[5/7] Starting a new trip for employee..."
EMPLOYEE_ID="656d706c-6f79-3365-b000-000000000000"
# Get employee user id
START_TRIP_RESP=$(curl -s -X POST "$BASE_URL/trips" \
  -H "Authorization: Bearer $OP_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"employeeId\":\"$EMPLOYEE_ID\",\"startTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")
echo "Start Trip Response: $START_TRIP_RESP"
TRIP_ID=$(echo "$START_TRIP_RESP" | grep -o '"tripId":"[^"]*' | cut -d'"' -f4 || echo "")
echo ""

if [ -n "$TRIP_ID" ]; then
  echo "[6/7] Streaming trajectory GPS pings for trip $TRIP_ID..."
  PING_RESP=$(curl -s -X POST "$BASE_URL/trips/$TRIP_ID/pings" \
    -H "Authorization: Bearer $OP_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"pings\":[
      {\"seq\":0,\"eventTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"lon\":77.42,\"lat\":12.82,\"accuracyMeters\":5.0},
      {\"seq\":1,\"eventTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"lon\":77.56,\"lat\":12.93,\"accuracyMeters\":5.0}
    ]}")
  echo "Ping Admission: $PING_RESP"
  echo ""

  echo "[7/7] Completing trip and retrieving tariff zone attribution..."
  COMPLETE_RESP=$(curl -s -X POST "$BASE_URL/trips/$TRIP_ID/complete" \
    -H "Authorization: Bearer $OP_TOKEN")
  echo "Complete Trip: $COMPLETE_RESP"
  echo ""

  ATTR_RESP=$(curl -s -X GET "$BASE_URL/trips/$TRIP_ID/attribution" \
    -H "Authorization: Bearer $OP_TOKEN")
  echo "Attribution Summary: $ATTR_RESP"
fi

echo ""
echo "=========================================================="
echo " Live demonstration flow finished successfully!"
echo "=========================================================="
