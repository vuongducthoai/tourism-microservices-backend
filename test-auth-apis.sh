#!/bin/bash

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

API_GATEWAY="http://localhost:8080"
TIMESTAMP=$(date +%s%N | cut -b1-13)
TEST_EMAIL="testuser_${TIMESTAMP}@gmail.com"
TEST_PASSWORD="Test1234@"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║           TOURISM MICROSERVICES AUTH API TEST             ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Test 1: Register
echo -e "${YELLOW}[TEST 1] User Registration${NC}"
echo "Email: $TEST_EMAIL"
REGISTER_RESPONSE=$(curl -s -X POST "$API_GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"fullName\": \"Test User\",
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\",
    \"confirmPassword\": \"$TEST_PASSWORD\",
    \"provinceCode\": \"01\",
    \"provinceName\": \"Hà Nội\",
    \"districtCode\": \"001\",
    \"districtName\": \"Ba Đình\"
  }")

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"fullName\": \"Test User\",
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\",
    \"confirmPassword\": \"$TEST_PASSWORD\",
    \"provinceCode\": \"01\",
    \"provinceName\": \"Hà Nội\",
    \"districtCode\": \"001\",
    \"districtName\": \"Ba Đình\"
  }")

if [ "$HTTP_CODE" == "200" ]; then
  echo -e "${GREEN}✓ Status: $HTTP_CODE${NC}"
  echo "Response: $REGISTER_RESPONSE"
else
  echo -e "${RED}✗ Status: $HTTP_CODE${NC}"
  echo "Response: $REGISTER_RESPONSE"
fi
echo ""

# Test 2: Login before verification
echo -e "${YELLOW}[TEST 2] Login Before Email Verification${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\"
  }")

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\"
  }")

if [ "$HTTP_CODE" == "400" ] || [ "$HTTP_CODE" == "401" ]; then
  echo -e "${GREEN}✓ Status: $HTTP_CODE (Expected error before verification)${NC}"
  echo "Response: $LOGIN_RESPONSE"
else
  echo -e "${RED}✗ Status: $HTTP_CODE (Should be 400/401)${NC}"
  echo "Response: $LOGIN_RESPONSE"
fi
echo ""

# Test 3: Check email verification token in database
echo -e "${YELLOW}[TEST 3] Get Verification Token from Database${NC}"
VERIFICATION_TOKEN=$(psql -h localhost -U postgres -d iam_db -tc \
  "SELECT verification_token FROM users WHERE email = '$TEST_EMAIL' LIMIT 1;" 2>/dev/null || echo "")

if [ -z "$VERIFICATION_TOKEN" ]; then
  echo -e "${RED}✗ Could not retrieve verification token${NC}"
  echo "Running fallback: Check if user exists"
  psql -h localhost -U postgres -d iam_db -c \
    "SELECT user_id, email, status, is_email_verified FROM users WHERE email = '$TEST_EMAIL';" 2>/dev/null
else
  echo -e "${GREEN}✓ Token found: ${VERIFICATION_TOKEN:0:20}...${NC}"
fi
echo ""

# Test 4: Verify email
echo -e "${YELLOW}[TEST 4] Verify Email with Token${NC}"
if [ -n "$VERIFICATION_TOKEN" ]; then
  VERIFY_RESPONSE=$(curl -s -X GET "$API_GATEWAY/api/auth/verify-email?token=$VERIFICATION_TOKEN")

  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$API_GATEWAY/api/auth/verify-email?token=$VERIFICATION_TOKEN")

  if [ "$HTTP_CODE" == "200" ]; then
    echo -e "${GREEN}✓ Status: $HTTP_CODE${NC}"
    echo "Response: $VERIFY_RESPONSE"
  else
    echo -e "${RED}✗ Status: $HTTP_CODE${NC}"
    echo "Response: $VERIFY_RESPONSE"
  fi
else
  echo -e "${RED}✗ Cannot test verification without token${NC}"
fi
echo ""

# Test 5: Login after verification
echo -e "${YELLOW}[TEST 5] Login After Email Verification${NC}"
LOGIN_AFTER_RESPONSE=$(curl -s -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\"
  }")

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"$TEST_PASSWORD\"
  }")

if [ "$HTTP_CODE" == "200" ]; then
  echo -e "${GREEN}✓ Status: $HTTP_CODE${NC}"
  echo "Response: $LOGIN_AFTER_RESPONSE" | head -c 200
  echo ""
else
  echo -e "${RED}✗ Status: $HTTP_CODE${NC}"
  echo "Response: $LOGIN_AFTER_RESPONSE"
fi
echo ""

# Test 6: Test with duplicate email
echo -e "${YELLOW}[TEST 6] Register with Duplicate Email${NC}"
DUP_RESPONSE=$(curl -s -X POST "$API_GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"fullName\": \"Another User\",
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"Test1234@\",
    \"confirmPassword\": \"Test1234@\",
    \"provinceCode\": \"01\",
    \"provinceName\": \"Hà Nội\",
    \"districtCode\": \"001\",
    \"districtName\": \"Ba Đình\"
  }")

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"fullName\": \"Another User\",
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"Test1234@\",
    \"confirmPassword\": \"Test1234@\",
    \"provinceCode\": \"01\",
    \"provinceName\": \"Hà Nội\",
    \"districtCode\": \"001\",
    \"districtName\": \"Ba Đình\"
  }")

if [ "$HTTP_CODE" == "400" ]; then
  echo -e "${GREEN}✓ Status: $HTTP_CODE (Expected error)${NC}"
  echo "Response: $DUP_RESPONSE"
else
  echo -e "${RED}✗ Status: $HTTP_CODE (Should be 400)${NC}"
  echo "Response: $DUP_RESPONSE"
fi
echo ""

# Test 7: Wrong password
echo -e "${YELLOW}[TEST 7] Login with Wrong Password${NC}"
WRONG_PASS_RESPONSE=$(curl -s -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"WrongPassword123\"
  }")

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_GATEWAY/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$TEST_EMAIL\",
    \"password\": \"WrongPassword123\"
  }")

if [ "$HTTP_CODE" == "401" ] || [ "$HTTP_CODE" == "400" ]; then
  echo -e "${GREEN}✓ Status: $HTTP_CODE (Expected error)${NC}"
  echo "Response: $WRONG_PASS_RESPONSE"
else
  echo -e "${RED}✗ Status: $HTTP_CODE${NC}"
  echo "Response: $WRONG_PASS_RESPONSE"
fi
echo ""

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                    TEST COMPLETED                         ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
