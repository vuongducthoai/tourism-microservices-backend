# Phase 5 — Google OAuth2 via Keycloak: Hướng Dẫn Chi Tiết
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-13
**Priority:** NICE TO HAVE

---

## Tổng quan Phase 5

Phase 5 gồm 4 việc:
1. Tạo Google OAuth2 credentials (Google Cloud Console)
2. Cấu hình Google Identity Provider trong Keycloak realm
3. Cập nhật `realm-export.json` để auto-import cấu hình Google
4. Implement endpoint `POST /api/auth/google/login` trong iam-service

**Ý tưởng cốt lõi:** Keycloak đóng vai trò **trung gian** giữa app và Google.
Frontend không giao tiếp trực tiếp với Google — chỉ giao tiếp với Keycloak.
Keycloak xác thực với Google, rồi cấp token của Keycloak cho app.

```
Frontend → Keycloak → Google (xác thực) → Keycloak cấp JWT → Frontend
```

---

## Cấu trúc file sau Phase 5

```
iam-service/src/main/java/com/tourism/iam/
├── controller/
│   └── AuthController.java              ← SỬA LẠI (thêm endpoint google/login)
├── service/
│   ├── AuthService.java                 ← SỬA LẠI (thêm method googleLogin)
│   └── impl/
│       └── AuthServiceImpl.java         ← SỬA LẠI (implement googleLogin)
├── dto/
│   └── request/
│       └── GoogleLoginRequest.java      ← TẠO MỚI

docker/keycloak/
└── realm-export.json                    ← SỬA LẠI (thêm Google Identity Provider)
```

---

## Bước 1 — Tạo Google OAuth2 Credentials

**Làm trên Google Cloud Console:**

1. Truy cập https://console.cloud.google.com
2. Tạo project mới hoặc chọn project hiện có
3. Vào **APIs & Services → Credentials**
4. Click **Create Credentials → OAuth 2.0 Client ID**
5. Application type: **Web application**
6. Thêm Authorized redirect URIs:
```
http://localhost:8180/realms/tourism/broker/google/endpoint
```
> ⚠️ URI này là cố định của Keycloak — phải nhập chính xác.
> Khi deploy production thay `localhost:8180` bằng domain thật.

7. Lưu lại **Client ID** và **Client Secret**

---

## Bước 2 — Cấu hình Google Identity Provider trong Keycloak

**Cách thủ công qua Keycloak Admin Console:**

1. Truy cập http://localhost:8180 → đăng nhập admin/admin
2. Chọn realm `tourism`
3. Vào **Identity Providers → Add provider → Google**
4. Điền:
   - **Client ID**: (lấy từ Bước 1)
   - **Client Secret**: (lấy từ Bước 1)
   - **Default Scopes**: `openid email profile`
5. Bật **Trust Email** → `ON` (tránh bắt verify email với user Google)
6. **First Login Flow**: `first broker login` (Keycloak tự xử lý user mới)
7. Save

---

## Bước 3 — Cập nhật realm-export.json

**File:** `docker/keycloak/realm-export.json`

**Mục đích:** Khi Keycloak khởi động lại, tự động import cấu hình Google mà không cần cấu hình thủ công lại.

**Thêm section `identityProviders` vào file JSON (sau phần `clients`):**

```json
"identityProviders": [
  {
    "alias": "google",
    "displayName": "Google",
    "providerId": "google",
    "enabled": true,
    "trustEmail": true,
    "firstBrokerLoginFlowAlias": "first broker login",
    "config": {
      "clientId": "${GOOGLE_CLIENT_ID}",
      "clientSecret": "${GOOGLE_CLIENT_SECRET}",
      "defaultScope": "openid email profile",
      "syncMode": "IMPORT"
    }
  }
],

"identityProviderMappers": [
  {
    "name": "google-email-mapper",
    "identityProviderAlias": "google",
    "identityProviderMapper": "hardcoded-role-idp-mapper",
    "config": {
      "syncMode": "INHERIT",
      "role": "CUSTOMER"
    }
  }
]
```

> **Giải thích `identityProviderMappers`:**
> Khi user đăng nhập lần đầu bằng Google, Keycloak tự động gán role `CUSTOMER`.
> Không cần code xử lý gán role thủ công.

**Thêm biến môi trường vào `docker-compose.yml` cho service keycloak:**
```yaml
environment:
  GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID}
  GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}
```

---

## Bước 4 — Tạo GoogleLoginRequest.java

**File:** `iam-service/src/main/java/com/tourism/iam/dto/request/GoogleLoginRequest.java`

```java
package com.tourism.iam.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {
    @NotBlank
    private String code;           // Authorization code từ Google (frontend gửi lên)

    @NotBlank
    private String redirectUri;    // Phải khớp với URI đã đăng ký trong Google Console
}
```

---

## Bước 5 — Implement googleLogin() trong AuthServiceImpl

**Luồng hoạt động:**
```
1. Frontend redirect user đến Google login page (qua Keycloak URL)
2. User đăng nhập Google thành công
3. Google redirect về frontend kèm authorization code
4. Frontend gửi code lên iam-service: POST /api/auth/google/login
5. iam-service gửi code đến Keycloak để đổi lấy token
6. Keycloak trả về access_token + refresh_token
7. iam-service tạo/cập nhật user trong iam_db (nếu user mới)
8. Trả về LoginResponse cho frontend
```

**Thêm method vào `AuthService.java` interface:**
```java
LoginResponse googleLogin(GoogleLoginRequest request);
```

**Implement trong `AuthServiceImpl.java`:**
```java
@Override
@Transactional
public LoginResponse googleLogin(GoogleLoginRequest request) {
    // 1. Đổi authorization code lấy token từ Keycloak
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "authorization_code");
    body.add("client_id", clientId);
    body.add("client_secret", clientSecret);
    body.add("code", request.getCode());
    body.add("redirect_uri", request.getRedirectUri());

    HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

    Map<String, Object> tokenData;
    try {
        ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
        tokenData = (Map<String, Object>) response.getBody();
    } catch (HttpClientErrorException e) {
        throw new RuntimeException("Google login thất bại: " + e.getMessage());
    }

    // 2. Decode JWT để lấy thông tin user (email, name)
    String accessToken = (String) tokenData.get("access_token");
    Map<String, Object> claims = decodeJwtClaims(accessToken);
    String email = (String) claims.get("email");
    String fullName = (String) claims.get("name");

    // 3. Tìm hoặc tạo user trong iam_db
    User user = userRepository.findByEmail(email).orElseGet(() -> {
        User newUser = User.builder()
                .email(email)
                .fullName(fullName != null ? fullName : email)
                .role(Role.CUSTOMER)
                .password("")               // Google user không có password
                .status(true)
                .isEmailVerified(true)      // Google đã verify email
                .migratedToKeycloak(true)   // Đã có trong Keycloak
                .build();
        return userRepository.save(newUser);
    });

    // 4. Cập nhật keycloakId nếu chưa có
    if (user.getKeycloakId() == null) {
        String keycloakId = keycloakAdminService.findUserIdByEmail(email);
        user.setKeycloakId(keycloakId);
        user.setMigratedToKeycloak(true);
    }

    user.setLastActiveAt(LocalDateTime.now());
    userRepository.save(user);

    // 5. Trả về LoginResponse
    return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken((String) tokenData.get("refresh_token"))
            .tokenType("Bearer")
            .expiredIn(((Number) tokenData.get("expires_in")).longValue())
            .user(LoginResponse.UserInfo.builder()
                    .userId(user.getUserID())
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .avatar(user.getAvatar())
                    .role(user.getRole().name())
                    .provinceName(user.getProvinceName())
                    .districtName(user.getDistrictName())
                    .coinBalance(user.getCoinBalance())
                    .build())
            .build();
}

// Decode JWT payload mà không verify chữ ký (đã được Keycloak verify rồi)
private Map<String, Object> decodeJwtClaims(String token) {
    String[] parts = token.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
    try {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
    } catch (Exception e) {
        throw new RuntimeException("Không thể decode JWT claims");
    }
}
```

---

## Bước 6 — Thêm endpoint vào AuthController.java

```java
@PostMapping("/google/login")
public ResponseEntity<LoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
    return ResponseEntity.ok(authService.googleLogin(request));
}
```

---

## Luồng Frontend tích hợp

**Bước 1 — Frontend tạo URL redirect đến Keycloak Google login:**
```javascript
const KEYCLOAK_URL = "http://localhost:8180";
const REALM = "tourism";
const CLIENT_ID = "tourism-app";
const REDIRECT_URI = "http://localhost:3000/auth/google/callback";

const googleLoginUrl = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/auth`
  + `?client_id=${CLIENT_ID}`
  + `&redirect_uri=${encodeURIComponent(REDIRECT_URI)}`
  + `&response_type=code`
  + `&scope=openid email profile`
  + `&kc_idp_hint=google`;   // ← Bỏ qua màn hình chọn provider, thẳng vào Google
```

**Bước 2 — Callback page gửi code lên backend:**
```javascript
// Tại /auth/google/callback
const code = new URLSearchParams(window.location.search).get('code');

const response = await fetch('/api/auth/google/login', {
  method: 'POST',
  body: JSON.stringify({ code, redirectUri: REDIRECT_URI })
});
const data = await response.json();
// Lưu access_token, refresh_token vào localStorage/cookie
```

---

## Các lỗi thường gặp

| Lỗi | Nguyên nhân | Fix |
|---|---|---|
| `redirect_uri_mismatch` từ Google | URI trong Google Console không khớp | Kiểm tra URI phải là `http://localhost:8180/realms/tourism/broker/google/endpoint` |
| `invalid_grant` từ Keycloak | Code đã dùng hoặc hết hạn | Authorization code chỉ dùng 1 lần, redirect nhanh |
| User tạo 2 lần trong iam_db | Email Google trùng với email đã đăng ký | `findByEmail` trước khi `save` — đã handle trong code |
| `Trust Email` chưa bật | Keycloak bắt verify email Google | Bật `Trust Email` trong Identity Provider config |
| `kc_idp_hint=google` không hoạt động | Google IdP alias sai | Alias phải là `google` (chính xác) |

---

## Kiểm tra sau khi hoàn thành

```bash
# 1. Lấy Google authorization code (mở URL này trên browser)
http://localhost:8180/realms/tourism/protocol/openid-connect/auth?client_id=tourism-app&redirect_uri=http://localhost:3000/auth/google/callback&response_type=code&scope=openid+email+profile&kc_idp_hint=google

# 2. Sau khi Google redirect về, lấy code từ URL rồi test:
curl -X POST http://localhost:8080/api/auth/google/login \
  -H "Content-Type: application/json" \
  -d '{"code": "CODE_TU_URL", "redirectUri": "http://localhost:3000/auth/google/callback"}'

# Expected: LoginResponse với access_token, refresh_token, user info
```

---

*— Winston, System Architect*
*"Delegate identity to those who do it best. Google knows its users; Keycloak knows your app."*
