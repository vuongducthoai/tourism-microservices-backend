# Phase 2 — IAM Service với Keycloak: Hướng Dẫn Chi Tiết
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-11

---

## Tổng quan Phase 2

Phase 2 gồm 6 việc theo thứ tự:
1. Cập nhật `pom.xml` — thêm Keycloak Admin Client + OAuth2 Resource Server
2. Cập nhật `application.yml` — thêm Keycloak config, xóa JWT config
3. Cập nhật `User.java` — thêm field `keycloakId`, `migratedToKeycloak`
4. Xóa `RefreshToken.java` — Keycloak quản lý thay
5. Tạo các class mới: `KeycloakConfig`, `KeycloakAdminService`, `AuthService`, `AuthController`, DTOs
6. Cập nhật `SecurityConfig.java` — chuyển sang OAuth2 Resource Server

---

## Cấu trúc file sau Phase 2

```
iam-service/src/main/java/com/tourism/iam/
├── config/
│   ├── KeycloakConfig.java          ← TẠO MỚI
│   ├── SecurityConfig.java          ← SỬA LẠI
│   ├── ModelMapperConfig.java       ← GIỮ NGUYÊN
│   ├── CloudinaryConfig.java        ← GIỮ NGUYÊN
│   └── OpenApiConfig.java           ← GIỮ NGUYÊN
├── controller/
│   ├── AuthController.java          ← TẠO MỚI
│   └── UserController.java          ← GIỮ NGUYÊN
├── service/
│   ├── AuthService.java             ← TẠO MỚI (interface)
│   ├── KeycloakAdminService.java    ← TẠO MỚI
│   ├── impl/
│   │   ├── AuthServiceImpl.java     ← TẠO MỚI
│   │   └── UserServiceImpl.java     ← GIỮ NGUYÊN
│   └── UserService.java             ← GIỮ NGUYÊN
├── dto/
│   ├── request/
│   │   ├── LoginRequest.java        ← TẠO MỚI
│   │   ├── RegisterRequest.java     ← TẠO MỚI
│   │   └── RefreshTokenRequest.java ← TẠO MỚI
│   └── response/
│       ├── LoginResponse.java       ← TẠO MỚI
│       └── TokenResponse.java       ← TẠO MỚI
├── entity/
│   ├── User.java                    ← SỬA LẠI (thêm 2 field)
│   ├── RefreshToken.java            ← XÓA ĐI
│   ├── Role.java                    ← GIỮ NGUYÊN
│   └── BaseEntity.java              ← GIỮ NGUYÊN
└── repository/
    ├── UserRepository.java          ← GIỮ NGUYÊN
    └── RefreshTokenRepository.java  ← XÓA ĐI (nếu có)
```

---

## Bước 1 — Cập nhật pom.xml

**File:** `iam-service/pom.xml`

**Việc cần làm:** Thêm 2 dependency mới, xóa 3 dependency JWT cũ.

### Xóa các dependency JWT cũ (không cần nữa vì Keycloak xử lý):
```xml
<!-- XÓA 3 DÒNG NÀY -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
```

### Thêm 2 dependency mới vào sau `<!-- Security -->`:
```xml
<!-- Keycloak Admin Client — dùng để tạo/sửa user trong Keycloak qua REST API -->
<dependency>
    <groupId>org.keycloak</groupId>
    <artifactId>keycloak-admin-client</artifactId>
    <version>24.0.5</version>
</dependency>

<!-- OAuth2 Resource Server — dùng để validate Keycloak JWT token -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

> **Tại sao cần 2 thứ này?**
> - `keycloak-admin-client`: Để iam-service gọi Keycloak Admin API (tạo user khi register, sync user...)
> - `oauth2-resource-server`: Để Spring Security tự động validate JWT do Keycloak cấp (verify chữ ký, expiry...)

---

## Bước 2 — Cập nhật application.yml

**File:** `iam-service/src/main/resources/application.yml`

**Việc cần làm:** Xóa phần `jwt:`, thêm phần `keycloak:` và `spring.security.oauth2.resourceserver`.

### Xóa phần JWT cũ:
```yaml
# XÓA TOÀN BỘ PHẦN NÀY
jwt:
  secret: ${JWT_SECRET:59379ea8-777d-49e1-ac8e-03420874f6a3}
  access-token-expiration: 604800000
  refresh-token-expiration: 1209600000
```

### Thêm vào cuối phần `spring:` (sau phần rabbitmq):
```yaml
  # ─── OAuth2 Resource Server (validate Keycloak JWT) ───
  security:
    oauth2:
      resourceserver:
        jwt:
          # Keycloak tự publish public key tại endpoint này
          # Spring dùng nó để verify chữ ký JWT
          issuer-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/${KEYCLOAK_REALM:tourism}
          jwk-set-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/${KEYCLOAK_REALM:tourism}/protocol/openid-connect/certs
```

### Thêm section keycloak mới (sau phần cloudinary):
```yaml
# ─── Keycloak Config ───
keycloak:
  server-url: ${KEYCLOAK_SERVER_URL:http://localhost:8180}
  realm: ${KEYCLOAK_REALM:tourism}
  client-id: ${KEYCLOAK_CLIENT_ID:tourism-app}
  client-secret: ${KEYCLOAK_CLIENT_SECRET:tourism-app-secret}
  admin-username: ${KEYCLOAK_ADMIN_USERNAME:admin}
  admin-password: ${KEYCLOAK_ADMIN_PASSWORD:admin}
```

> **Lưu ý:** Khi chạy local (không qua Docker), Keycloak ở `localhost:8180`.
> Khi chạy trong Docker, biến môi trường `KEYCLOAK_SERVER_URL=http://keycloak:8080` sẽ override.

---

## Bước 3 — Sửa User.java

**File:** `iam-service/src/main/java/com/tourism/iam/entity/User.java`

**Việc cần làm:** Thêm 2 field mới để track việc migration sang Keycloak.

### Thêm 2 field vào cuối class (trước dấu `}`):
```java
// ID của user trong Keycloak (UUID dạng String)
// Dùng để map giữa iam_db user và Keycloak user
@Column(name = "keycloak_id")
private String keycloakId;

// Flag đánh dấu user đã được tạo trong Keycloak chưa
// false = user cũ (chỉ có trong iam_db, chưa sync Keycloak)
// true = đã có trong cả iam_db lẫn Keycloak
@Column(name = "migrated_to_keycloak")
private Boolean migratedToKeycloak = false;
```

> **Tại sao cần 2 field này?**
> - `keycloakId`: Để biết Keycloak UUID tương ứng với user trong iam_db
>   (Keycloak dùng UUID làm ID, iam_db dùng Integer — cần map 2 chiều)
> - `migratedToKeycloak`: Để biết user nào đã có trong Keycloak, user nào chưa
>   (Lazy migration — user cũ chỉ được tạo Keycloak khi login lần đầu)

---

## Bước 4 — Xóa RefreshToken.java

**File:** `iam-service/src/main/java/com/tourism/iam/entity/RefreshToken.java`

**Việc cần làm:** Xóa file này đi. Keycloak tự quản lý refresh token, không cần entity này nữa.

> Nếu có `RefreshTokenRepository.java` cũng xóa luôn.

---

## Bước 5A — Tạo KeycloakConfig.java

**File:** `iam-service/src/main/java/com/tourism/iam/config/KeycloakConfig.java`

**Mục đích:** Tạo bean `Keycloak` để các service khác inject vào dùng khi cần gọi Keycloak Admin API.

```java
package com.tourism.iam.config;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeycloakConfig {

    // Đọc các biến từ application.yml (hoặc env vars trong Docker)
    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.admin-username}")
    private String adminUsername;

    @Value("${keycloak.admin-password}")
    private String adminPassword;

    @Bean
    public Keycloak keycloakAdminClient() {
        // Tạo Keycloak admin client dùng tài khoản admin
        // Realm "master" vì đây là realm quản lý toàn bộ Keycloak
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")                    // Luôn dùng master để admin
                .clientId("admin-cli")              // Client mặc định của Keycloak admin
                .username(adminUsername)
                .password(adminPassword)
                .build();
    }
}
```

---

## Bước 5B — Tạo SecurityConfig.java (sửa lại)

**File:** `iam-service/src/main/java/com/tourism/iam/config/SecurityConfig.java`

**Mục đích:** Thay vì `anyRequest().permitAll()`, giờ dùng OAuth2 Resource Server để validate Keycloak JWT.

```java
package com.tourism.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // Cho phép dùng @PreAuthorize trên method
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Các endpoint auth không cần token
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/refresh-token",
                    "/api/auth/verify-email",
                    "/api/auth/resend-verification",
                    "/api/auth/google/login",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                // Tất cả endpoint còn lại cần JWT hợp lệ từ Keycloak
                .anyRequest().authenticated()
            )
            // Kích hoạt OAuth2 Resource Server — Spring tự validate JWT bằng JWKS từ Keycloak
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    // Converter để đọc roles từ JWT của Keycloak
    // Keycloak để roles trong claim "roles" (đã config trong realm-export.json)
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("roles");   // claim name trong JWT
        converter.setAuthorityPrefix("ROLE_");         // Spring Security cần prefix ROLE_

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
        return jwtConverter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

---

## Bước 5C — Tạo KeycloakAdminService.java

**File:** `iam-service/src/main/java/com/tourism/iam/service/KeycloakAdminService.java`

**Mục đích:** Wrapper cho Keycloak Admin API. Các method: tạo user, set password, assign role, enable/disable user.

```java
package com.tourism.iam.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakAdminService {

    private final Keycloak keycloak;

    @Value("${keycloak.realm}")
    private String realm;

    // Lấy realm resource để thao tác
    private RealmResource getRealmResource() {
        return keycloak.realm(realm);
    }

    private UsersResource getUsersResource() {
        return getRealmResource().users();
    }

    /**
     * Tạo user mới trong Keycloak
     * Trả về Keycloak UUID của user vừa tạo
     * enabled=false vì chưa verify email
     */
    public String createUser(String email, String password, String fullName,
                              String role, Integer userId) {
        // Tạo credential (password)
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setTemporary(false);
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);

        // Tạo user representation
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);         // Keycloak dùng email làm username
        user.setEmail(email);
        user.setFirstName(fullName);
        user.setEnabled(false);          // Chờ verify email mới enable
        user.setEmailVerified(false);
        user.setCredentials(List.of(credential));
        // Lưu userId (integer từ iam_db) vào attribute để dùng trong Protocol Mapper
        user.setAttributes(Map.of("userId", List.of(String.valueOf(userId))));

        // Gọi Keycloak Admin API tạo user
        Response response = getUsersResource().create(user);
        int status = response.getStatus();

        if (status != 201) {
            log.error("Failed to create Keycloak user: status={}", status);
            throw new RuntimeException("Cannot create user in Keycloak, status: " + status);
        }

        // Lấy Keycloak UUID từ Location header trả về
        String location = response.getLocation().toString();
        String keycloakId = location.substring(location.lastIndexOf("/") + 1);
        log.info("Created Keycloak user: email={}, keycloakId={}", email, keycloakId);

        // Gán role cho user
        assignRole(keycloakId, role);

        return keycloakId;
    }

    /**
     * Enable user sau khi verify email thành công
     */
    public void enableUser(String keycloakId) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmailVerified(true);
        getUsersResource().get(keycloakId).update(user);
        log.info("Enabled Keycloak user: keycloakId={}", keycloakId);
    }

    /**
     * Cập nhật password của user (dùng khi lazy migration)
     */
    public void updatePassword(String keycloakId, String newPassword) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setTemporary(false);
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(newPassword);
        getUsersResource().get(keycloakId).resetPassword(credential);
        log.info("Updated password for Keycloak user: keycloakId={}", keycloakId);
    }

    /**
     * Gán realm role cho user (CUSTOMER / ADMIN / TOUR_OWNER)
     */
    public void assignRole(String keycloakId, String roleName) {
        RoleRepresentation role = getRealmResource().roles().get(roleName).toRepresentation();
        getUsersResource().get(keycloakId).roles().realmLevel().add(List.of(role));
        log.info("Assigned role {} to Keycloak user: keycloakId={}", roleName, keycloakId);
    }

    /**
     * Tìm Keycloak user theo email
     * Trả về null nếu không tìm thấy
     */
    public String findUserIdByEmail(String email) {
        List<UserRepresentation> users = getUsersResource().searchByEmail(email, true);
        if (users.isEmpty()) return null;
        return users.get(0).getId();
    }

    /**
     * Disable user (khi admin block tài khoản)
     */
    public void disableUser(String keycloakId) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(false);
        getUsersResource().get(keycloakId).update(user);
    }
}
```

---

## Bước 5D — Tạo DTOs

### LoginRequest.java
**File:** `dto/request/LoginRequest.java`
```java
package com.tourism.iam.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank @Email
    private String email;

    @NotBlank
    private String password;
}
```

### RegisterRequest.java
**File:** `dto/request/RegisterRequest.java`
```java
package com.tourism.iam.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 100)
    private String fullName;

    @NotBlank @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "Mật khẩu phải có chữ hoa, chữ thường và số"
    )
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    private String provinceCode;
    private String provinceName;

    @NotBlank
    private String districtCode;
    private String districtName;
}
```

### RefreshTokenRequest.java
**File:** `dto/request/RefreshTokenRequest.java`
```java
package com.tourism.iam.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequest {
    @NotBlank
    private String refreshToken;
}
```

### LoginResponse.java
**File:** `dto/response/LoginResponse.java`
```java
package com.tourism.iam.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private Long expiresIn;     // seconds
    private UserInfo user;

    @Data
    @Builder
    public static class UserInfo {
        private Integer userId;
        private String fullName;
        private String email;
        private String avatar;
        private String role;
        private String provinceName;
        private String districtName;
        private BigDecimal coinBalance;
    }
}
```

### TokenResponse.java
**File:** `dto/response/TokenResponse.java`
```java
package com.tourism.iam.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private Long expiresIn;
}
```

---

## Bước 5E — Tạo AuthService interface

**File:** `iam-service/src/main/java/com/tourism/iam/service/AuthService.java`

```java
package com.tourism.iam.service;

import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
    void register(RegisterRequest request);
    void verifyEmail(String token);
    void resendVerification(String email);
    TokenResponse refreshToken(RefreshTokenRequest request);
    void logout(String refreshToken);
    void logoutAll(Integer userId);
}
```

---

## Bước 5F — Tạo AuthServiceImpl.java

**File:** `iam-service/src/main/java/com/tourism/iam/service/impl/AuthServiceImpl.java`

**Đây là class quan trọng nhất — chứa toàn bộ logic auth + lazy migration.**

```java
package com.tourism.iam.service.impl;

import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.UserRepository;
import com.tourism.iam.service.AuthService;
import com.tourism.iam.service.KeycloakAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate;

    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    // URL lấy token từ Keycloak
    private String getTokenUrl() {
        return keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    // URL logout từ Keycloak
    private String getLogoutUrl() {
        return keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 1. Tìm user trong iam_db
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Email hoặc mật khẩu không đúng"));

        // 2. Kiểm tra tài khoản có bị khóa không
        if (Boolean.FALSE.equals(user.getStatus())) {
            throw new RuntimeException("Tài khoản đã bị khóa");
        }

        // 3. Kiểm tra email đã verify chưa
        if (Boolean.FALSE.equals(user.getIsEmailVerified())) {
            throw new RuntimeException("Vui lòng xác thực email trước khi đăng nhập");
        }

        // 4. Lazy Migration — nếu user chưa có trong Keycloak thì tạo mới
        if (Boolean.FALSE.equals(user.getMigratedToKeycloak())) {
            migrateUserToKeycloak(user, request.getPassword());
        }

        // 5. Gọi Keycloak để lấy token (password grant)
        Map<String, Object> tokenData = getTokenFromKeycloak(request.getEmail(), request.getPassword());

        // 6. Cập nhật lastActiveAt
        user.setLastActiveAt(LocalDateTime.now());
        userRepository.save(user);

        // 7. Build response (giữ nguyên shape như monolithic)
        return LoginResponse.builder()
                .accessToken((String) tokenData.get("access_token"))
                .refreshToken((String) tokenData.get("refresh_token"))
                .tokenType("Bearer")
                .expiresIn(((Number) tokenData.get("expires_in")).longValue())
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

    /**
     * Lazy Migration: Lần đầu user cũ login → tạo account trong Keycloak
     * Dùng password text từ request vì BCrypt không thể decrypt
     */
    @Transactional
    private void migrateUserToKeycloak(User user, String plainPassword) {
        // Verify password với BCrypt hash trong iam_db trước
        if (!passwordEncoder.matches(plainPassword, user.getPassword())) {
            throw new RuntimeException("Email hoặc mật khẩu không đúng");
        }

        log.info("Migrating user to Keycloak: email={}", user.getEmail());

        // Tạo user trong Keycloak với password mới
        String keycloakId = keycloakAdminService.createUser(
                user.getEmail(),
                plainPassword,
                user.getFullName(),
                user.getRole().name(),
                user.getUserID()
        );

        // Enable ngay vì user đã verify email trong iam_db rồi
        keycloakAdminService.enableUser(keycloakId);

        // Lưu keycloakId và đánh dấu đã migrate
        user.setKeycloakId(keycloakId);
        user.setMigratedToKeycloak(true);
        userRepository.save(user);

        log.info("Migration complete: email={}, keycloakId={}", user.getEmail(), keycloakId);
    }

    /**
     * Gọi Keycloak Token Endpoint để lấy access_token + refresh_token
     * Dùng grant_type=password (Resource Owner Password Credentials)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getTokenFromKeycloak(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("username", email);
        body.add("password", password);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Keycloak login failed: {}", e.getMessage());
            throw new RuntimeException("Email hoặc mật khẩu không đúng");
        }
    }

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        // 1. Validate password match
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("Mật khẩu xác nhận không khớp");
        }

        // 2. Kiểm tra email đã tồn tại chưa
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email đã được sử dụng");
        }

        // 3. Tạo user trong iam_db
        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provinceCode(request.getProvinceCode())
                .provinceName(request.getProvinceName())
                .districtCode(request.getDistrictCode())
                .districtName(request.getDistrictName())
                .role(com.tourism.iam.entity.Role.CUSTOMER)
                .status(true)
                .isEmailVerified(false)
                .migratedToKeycloak(false)
                .verificationToken(UUID.randomUUID().toString())
                .verificationTokenExpiry(LocalDateTime.now().plusHours(24))
                .build();

        userRepository.save(user);

        // 4. Tạo user trong Keycloak (disabled — chờ verify email)
        try {
            String keycloakId = keycloakAdminService.createUser(
                    user.getEmail(),
                    request.getPassword(),
                    user.getFullName(),
                    "CUSTOMER",
                    user.getUserID()
            );
            user.setKeycloakId(keycloakId);
            user.setMigratedToKeycloak(true);
            userRepository.save(user);
        } catch (Exception e) {
            log.error("Failed to create Keycloak user during register: {}", e.getMessage());
            // Vẫn tiếp tục — user đã có trong iam_db, Keycloak sẽ được tạo khi login (lazy migration)
        }

        // 5. Gửi email verification (giữ nguyên logic cũ)
        // TODO: inject MailService và gọi sendVerificationEmail(user)
        log.info("Registered user: email={}", user.getEmail());
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new RuntimeException("Token không hợp lệ"));

        if (user.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token đã hết hạn");
        }

        // Enable trong iam_db
        user.setIsEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        userRepository.save(user);

        // Enable trong Keycloak
        if (user.getKeycloakId() != null) {
            keycloakAdminService.enableUser(user.getKeycloakId());
        }
    }

    @Override
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        // Gọi Keycloak để refresh token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
            Map<String, Object> data = response.getBody();

            return TokenResponse.builder()
                    .accessToken((String) data.get("access_token"))
                    .refreshToken((String) data.get("refresh_token"))
                    .tokenType("Bearer")
                    .expiresIn(((Number) data.get("expires_in")).longValue())
                    .build();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Refresh token không hợp lệ hoặc đã hết hạn");
        }
    }

    @Override
    public void logout(String refreshToken) {
        // Gọi Keycloak để revoke session
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(getLogoutUrl(), entity, Void.class);
    }

    @Override
    public void logoutAll(Integer userId) {
        // Tìm keycloakId từ iam_db rồi logout tất cả session
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        if (user.getKeycloakId() != null) {
            keycloakAdminService.getUsersResource()
                    .get(user.getKeycloakId())
                    .logout();
        }
    }

    @Override
    public void resendVerification(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại"));

        if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new RuntimeException("Email đã được xác thực");
        }

        user.setVerificationToken(UUID.randomUUID().toString());
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
        userRepository.save(user);

        // TODO: gửi lại email verification
    }
}
```

---

## Bước 5G — Tạo AuthController.java

**File:** `iam-service/src/main/java/com/tourism/iam/controller/AuthController.java`

```java
package com.tourism.iam.controller;

import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;
import com.tourism.iam.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // POST /api/auth/register
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.ok(Map.of("message",
            "Đăng ký thành công! Vui lòng kiểm tra email để xác thực tài khoản."));
    }

    // GET /api/auth/verify-email?token=xxx
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", "Xác thực email thành công!"));
    }

    // POST /api/auth/resend-verification?email=xxx
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(@RequestParam String email) {
        authService.resendVerification(email);
        return ResponseEntity.ok(Map.of("message", "Email xác thực đã được gửi lại."));
    }

    // POST /api/auth/refresh-token
    @PostMapping("/refresh-token")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    // POST /api/auth/logout
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody Map<String, String> body) {
        authService.logout(body.get("refreshToken"));
        return ResponseEntity.ok(Map.of("message", "Đăng xuất thành công"));
    }

    // POST /api/auth/logout-all
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, String>> logoutAll(@RequestBody Map<String, String> body) {
        authService.logoutAll(Integer.valueOf(body.get("userId")));
        return ResponseEntity.ok(Map.of("message", "Đã đăng xuất khỏi tất cả thiết bị"));
    }
}
```

---

## Bước 6 — Thêm RestTemplate Bean

**File:** `iam-service/src/main/java/com/tourism/iam/config/AppConfig.java` ← TẠO MỚI

`AuthServiceImpl` dùng `RestTemplate` để gọi Keycloak Token Endpoint. Cần khai báo bean này.

```java
package com.tourism.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

---

## Bước 7 — Thêm method cần thiết vào UserRepository

**File:** `iam-service/src/main/java/com/tourism/iam/repository/UserRepository.java`

Kiểm tra xem đã có các method này chưa, nếu chưa thêm vào:

```java
Optional<User> findByEmail(String email);
boolean existsByEmail(String email);
Optional<User> findByVerificationToken(String token);
```

---

## Tóm tắt thứ tự làm

| # | File | Việc làm |
|---|---|---|
| 1 | `pom.xml` | Xóa 3 jjwt, thêm keycloak-admin-client + oauth2-resource-server |
| 2 | `application.yml` | Xóa jwt section, thêm keycloak + resourceserver section |
| 3 | `entity/User.java` | Thêm `keycloakId`, `migratedToKeycloak` |
| 4 | `entity/RefreshToken.java` | Xóa file |
| 5 | `config/KeycloakConfig.java` | Tạo mới |
| 6 | `config/AppConfig.java` | Tạo mới (RestTemplate bean) |
| 7 | `config/SecurityConfig.java` | Sửa lại dùng oauth2ResourceServer |
| 8 | `service/KeycloakAdminService.java` | Tạo mới |
| 9 | `dto/request/*.java` | Tạo 3 file DTO |
| 10 | `dto/response/*.java` | Tạo 2 file DTO |
| 11 | `service/AuthService.java` | Tạo interface |
| 12 | `service/impl/AuthServiceImpl.java` | Tạo implementation |
| 13 | `controller/AuthController.java` | Tạo controller |
| 14 | `repository/UserRepository.java` | Thêm 3 method nếu thiếu |

---

## Lưu ý quan trọng

1. **`logoutAll` trong `AuthServiceImpl`** gọi `keycloakAdminService.getUsersResource()` — cần thêm method `getUsersResource()` public vào `KeycloakAdminService`.

2. **Mail service** — các chỗ có `// TODO: inject MailService` bạn tự inject `JavaMailSender` và gọi logic gửi mail từ monolithic sang.

3. **Sau khi code xong**, cần chạy migration SQL để thêm 2 cột mới vào bảng `users` trong `iam_db`:
```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS keycloak_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS migrated_to_keycloak BOOLEAN DEFAULT FALSE;
```
Hoặc để Hibernate tự tạo vì `ddl-auto: update`.
