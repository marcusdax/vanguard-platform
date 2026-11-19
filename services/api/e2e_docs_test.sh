#!/usr/bin/env bash
set -euo pipefail

# Port is read from the PORT env var (defaults to 5000, matching server.ts).
# Start your server with `PORT=5001 ...` and run this script with `PORT=5001`
# so both the server and the test target the same port.
PORT="${PORT:-5000}"
API="http://localhost:${PORT}/api/v1"

echo "=== Target API: $API ==="

echo "=== 1. Register ==="
RESP=$(curl -s -X POST "$API/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"doc-e2e-$(date +%s)@roofpro.test\",\"password\":\"Password123!\",\"firstName\":\"Doc\",\"lastName\":\"Tester\",\"companyName\":\"E2E Docs Co\"}")
echo "$RESP" | python -m json.tool
TOKEN=$(echo "$RESP" | python -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
echo "Token OK: ${TOKEN:0:20}... (len ${#TOKEN})"

echo "=== 2. Upload (POST /documents/upload, field=files) ==="
UP=$(curl -s -X POST "$API/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "files=@backend/e2e-test-file.txt;filename=e2e-test.txt" \
  -F "fileType=OTHER" \
  -F "name=E2E Test Upload.json")
echo "$UP" | python -m json.tool
DOC_ID=$(echo "$UP" | python -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
echo "DOC_ID=$DOC_ID"

echo "=== 3. List (GET /documents?search=E2E) ==="
curl -s -X GET "$API/documents?search=E2E" \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool

echo "=== 4. Download (GET /documents/:id/download) ==="
curl -s -o /dev/null -w "HTTP %{http_code} | Content-Type: %{content_type} | Size: %{size_download} bytes\n" \
  -H "Authorization: Bearer $TOKEN" \
  "$API/documents/$DOC_ID/download"

echo "=== 5. Delete (DELETE /documents/:id) ==="
curl -s -X DELETE "$API/documents/$DOC_ID" \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool

echo "=== 6. Verify deletion (expect count 0) ==="
curl -s -X GET "$API/documents?search=E2E" \
  -H "Authorization: Bearer $TOKEN" | python -c "import sys,json; d=json.load(sys.stdin); print(f'count={d[\"count\"]} data_len={len(d[\"data\"])}')"

echo "=== ALL E2E STEPS PASSED ==="
