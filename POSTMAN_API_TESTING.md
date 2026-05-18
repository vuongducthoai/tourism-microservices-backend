# Postman API Testing Guide - Tourism Microservices

## API Base URL
```
http://localhost:8080
```

---

## 1. Authentication Endpoints

### 1.1 Register New User
**Endpoint:** `POST /api/auth/register`

**Request Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "fullName": "Test User",
    "email": "testuser@example.com",
    "password": "TestPass123",
    "confirmPassword": "TestPass123",
    "provinceCode": "01",
    "provinceName": "Ha Noi",
    "districtCode": "001",
    "districtName": "Ba Dinh"
}
```

**Expected Response:** `200 OK`
```json
{
    "message": "Đăng ký thành công! Vui lòng kiểm tra email để xác thực tài khoản."
}
```

**Notes:**
- Password must be at least 8 characters
- Password must contain uppercase, lowercase, and numbers
- Email must be unique
- All fields are required

---

### 1.2 Login User
**Endpoint:** `POST /api/auth/login`

**Request Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "email": "testuser@example.com",
    "password": "TestPass123"
}
```

**Expected Response:** `200 OK`
```json
{
    "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cC...",
    "refreshToken": "eyJhbGciOiJSUzI1NiIsInR5cC...",
    "tokenType": "Bearer",
    "expiredIn": 300,
    "user": {
        "userId": 1,
        "fullName": "Test User",
        "email": "testuser@example.com",
        "avatar": null,
        "role": "CUSTOMER",
        "provinceName": "Ha Noi",
        "districtName": "Ba Dinh",
        "coinBalance": 0
    }
}
```

**Notes:**
- Save the `accessToken` for authenticated requests
- Access token expires in 300 seconds (5 minutes)
- Use `refreshToken` to get a new access token

---

### 1.3 Refresh Token
**Endpoint:** `POST /api/auth/refresh-token`

**Request Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "refreshToken": "eyJhbGciOiJSUzI1NiIsInR5cC..."
}
```

**Expected Response:** `200 OK`
```json
{
    "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cC...",
    "refreshToken": "eyJhbGciOiJSUzI1NiIsInR5cC...",
    "tokenType": "Bearer",
    "expiredIn": 300
}
```

---

### 1.4 Logout
**Endpoint:** `POST /api/auth/logout`

**Request Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
    "refreshToken": "eyJhbGciOiJSUzI1NiIsInR5cC..."
}
```

**Expected Response:** `200 OK`
```json
{
    "message": "Đăng xuất thành công"
}
```

---

### 1.5 Verify Email
**Endpoint:** `GET /api/auth/verify-email?token=<verification-token>`

**Query Parameters:**
- `token` - Verification token sent to user's email

**Expected Response:** `200 OK`
```json
{
    "message": "Xác thực email thành công!"
}
```

---

### 1.6 Resend Verification Email
**Endpoint:** `POST /api/auth/resend-verification?email=<user-email>`

**Query Parameters:**
- `email` - User's email address

**Expected Response:** `200 OK`
```json
{
    "message": "Email xác thực đã được gửi lại."
}
```

---

## 2. Test Scenarios

### Scenario 1: Complete Registration & Login Flow
1. Call **Register** endpoint with new email
2. Check email for verification link (simulated in dev)
3. Call **Verify Email** with token
4. Call **Login** with email and password
5. Save `accessToken` for next requests

### Scenario 2: Token Refresh Flow
1. Login to get tokens
2. Wait for access token to expire (5 minutes) or test manually
3. Call **Refresh Token** with refresh token
4. Use new access token for protected endpoints

### Scenario 3: Logout Flow
1. Login to get tokens
2. Call **Logout** with refresh token
3. Try to use access token - should return 401 Unauthorized

---

## 3. Common Issues & Solutions

### Issue: 400 Bad Request on Register
**Cause:** Password doesn't meet requirements
**Solution:** Ensure password has:
- At least 8 characters
- At least one uppercase letter
- At least one lowercase letter  
- At least one digit

### Issue: 500 Internal Server Error on Login
**Cause:** Keycloak connection issue or user not found
**Solution:**
- Verify user was registered successfully
- Check if user exists in database
- Ensure Keycloak service is running

### Issue: 503 Service Unavailable from Gateway
**Cause:** Service not registered with Eureka yet
**Solution:**
- Wait for service to fully start (check Docker logs)
- Try direct service endpoint: `http://localhost:8081/api/auth/login`
- Verify Eureka shows service as UP

---

## 4. Direct Service Testing (Bypass Gateway)

If gateway is having issues, test services directly:

### IAM Service
```
http://localhost:8081/api/auth/login
```

### Tour Catalog Service
```
http://localhost:8082/api/tours
```

### Booking Service
```
http://localhost:8083/api/bookings
```

### Payment Service
```
http://localhost:8084/api/payment
```

### Forum Service
```
http://localhost:8085/api/posts
```

### Notification Service
```
http://localhost:8086/api/notifications
```

### Analytics Service
```
http://localhost:8087/api/dashboard
```

---

## 5. Postman Collection Import

To import this into Postman:

1. **Create new Collection:** "Tourism Microservices"
2. **Add Requests:**
   - POST Register
   - POST Login
   - POST Refresh Token
   - POST Logout
   - GET Verify Email
   - POST Resend Verification

3. **Set Environment Variable:**
   - Go to Environments
   - Create new: "Tourism Dev"
   - Variable: `baseUrl` = `http://localhost:8080`
   - Variable: `accessToken` = (populate after login)
   - Variable: `refreshToken` = (populate after login)

4. **Use Variables in Requests:**
   - URL: `{{baseUrl}}/api/auth/login`
   - Headers: `Authorization: Bearer {{accessToken}}`

---

## 6. Next Steps

1. Open Postman
2. Create requests following the endpoints above
3. Test Register → Login → Token Refresh flow
4. Save tokens in environment variables
5. Test with Frontend at `http://localhost:3000`

---

## Troubleshooting

**Check Service Health:**
```bash
# Check all containers running
docker ps

# Check service logs
docker logs tourism-iam-service
docker logs tourism-api-gateway

# Check Eureka
curl http://localhost:8761/eureka/apps
```
