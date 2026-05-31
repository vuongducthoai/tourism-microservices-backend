#!/bin/bash
# chatbot-api-test.sh — Integration tests for chatbot APIs
# Usage: bash chatbot-api-test.sh
# Requires: analytics-service running on localhost:8085 (or via API gateway 8080)

BASE_URL="${CHATBOT_API_BASE:-http://localhost:8080}"
PASS=0
FAIL=0
SESSION_ID="test_session_$(date +%s)"

# ── Helpers ──────────────────────────────────────────────────────────────────
assert_field() {
  local desc="$1"
  local json="$2"
  local field="$3"
  local expected="$4"

  actual=$(echo "$json" | grep -o "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed 's/.*: *"//' | tr -d '"')
  if [ "$actual" = "$expected" ]; then
    echo "  ✅ PASS: $desc"
    PASS=$((PASS+1))
  else
    echo "  ❌ FAIL: $desc — expected '$expected', got '$actual'"
    FAIL=$((FAIL+1))
  fi
}

assert_contains() {
  local desc="$1"
  local json="$2"
  local needle="$3"

  if echo "$json" | grep -q "$needle"; then
    echo "  ✅ PASS: $desc"
    PASS=$((PASS+1))
  else
    echo "  ❌ FAIL: $desc — '$needle' not found"
    FAIL=$((FAIL+1))
  fi
}

chat() {
  local msg="$1"
  curl -s -X POST "$BASE_URL/api/chatbot/chat" \
    -H "Content-Type: application/json" \
    -d "{\"message\": \"$msg\", \"sessionId\": \"$SESSION_ID\", \"userId\": null}"
}

# ── Tests ────────────────────────────────────────────────────────────────────

echo ""
echo "=== Chatbot API Integration Tests ==="
echo "Base URL: $BASE_URL"
echo "Session:  $SESSION_ID"
echo ""

# Test 1: Basic health — should return a reply
echo "--- Test 1: Basic greeting ---"
RESP=$(chat "xin chào")
assert_contains "reply field present" "$RESP" '"reply"'
assert_contains "messageType field" "$RESP" '"messageType"'

# Test 2: Tour search intent
echo "--- Test 2: Tour search ---"
RESP=$(chat "tôi muốn tìm tour đi đà nẵng")
assert_contains "reply present" "$RESP" '"reply"'

# Test 3: Start booking flow
echo "--- Test 3: Booking flow start ---"
RESP=$(chat "tôi muốn đặt tour")
assert_contains "reply present" "$RESP" '"reply"'

# Test 4: BK lookup
echo "--- Test 4: BK lookup ---"
SESSION2="lookup_test_$(date +%s)"
RESP=$(curl -s -X POST "$BASE_URL/api/chatbot/chat" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"BK12345678\", \"sessionId\": \"$SESSION2\", \"userId\": null}")
assert_contains "reply present" "$RESP" '"reply"'

# Test 5: Payment help
echo "--- Test 5: Payment help ---"
RESP=$(chat "tôi cần giúp về thanh toán payos")
assert_contains "reply present" "$RESP" '"reply"'

# Summary
echo ""
echo "==========================="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "==========================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
