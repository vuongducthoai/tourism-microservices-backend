# Phase 4 — Cập nhật các Service còn lại: Hướng Dẫn Chi Tiết
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-13

---

## Tổng quan Phase 4

Phase 4 cập nhật 5 service còn lại để nhận và tin tưởng Keycloak JWT:
- `tour-catalog-service`
- `booking-service`
- `payment-service`
- `forum-service`
- `notification-service`

Mỗi service chỉ cần làm **3 việc giống nhau**:
1. Thêm dependency `spring-boot-starter-oauth2-resource-server` vào `pom.xml`
2. Thêm Keycloak JWKS config vào `application.yml`
3. Tạo `SecurityConfig.java` — permit public endpoints, protect the rest

> **Lưu ý quan trọng:** Các service này KHÔNG cần tự extract token hay gọi Keycloak.
> API Gateway đã validate token và forward `X-User-Id`, `X-User-Role`, `X-User-Email` qua header.
> Service chỉ cần đọc header đó là đủ. SecurityConfig ở đây là tầng bảo vệ thứ 2 (defense in depth).

---

## Cấu trúc file cần thêm (áp dụng cho mỗi service)

```
{service}/src/main/java/com/tourism/{service}/
└── config/
    └── SecurityConfig.java    ← TẠO MỚI

{service}/src/main/resources/
└── application.yml            ← SỬA LẠI (thêm oauth2 config)

{service}/pom.xml              ← SỬA LẠI (thêm dependency)
```

---

## Bước 1 — Thêm dependency vào pom.xml (áp dụng cho TẤT CẢ service)

**Thêm 2 dependency sau vào mỗi file `pom.xml`:**

```xml
<!-- OAuth2 Resource Server — validate Keycloak JWT -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

> **Tại sao cần thêm dù Gateway đã validate?**
> Defense in depth — nếu ai đó bypass Gateway và gọi thẳng vào service port,
> service vẫn tự bảo vệ được. Đây là best practice trong microservices.

---

## Bước 2 — Thêm Keycloak config vào application.yml (áp dụng cho TẤT CẢ service)

**Thêm đoạn sau vào trong phần `spring:` của mỗi file `application.yml`:**

```yaml
  # ─── OAuth2 Resource Server (validate Keycloak JWT) ───
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/tourism
          jwk-set-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/tourism/protocol/openid-connect/certs
```

---

## Bước 3 — Tạo SecurityConfig.java cho từng service

**Template chung — copy và chỉnh `pathMatchers` cho từng service:**

```java
package com.tourism.{service}.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — xem chi tiết từng service bên dưới
                .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                // Tất cả endpoint còn lại cần token
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        // Keycloak đặt roles trong realm_access.roles, không phải claim "roles" tiêu chuẩn
        grantedAuthoritiesConverter.setAuthoritiesClaimName("realm_access.roles");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
```

---

## Chi tiết từng service

### 3.1 tour-catalog-service (port 8082)

**Public endpoints** (không cần đăng nhập để xem tour):
```java
.requestMatchers(
    "/api/tours",
    "/api/tours/{id}",
    "/api/locations/**",
    "/api/reviews/**",
    "/api/branch-contacts/**",
    "/actuator/health",
    "/v3/api-docs/**",
    "/swagger-ui/**"
).permitAll()
```

**Protected endpoints** (cần login):
- `POST /api/favorite-tours/**` — thêm tour yêu thích
- `POST /api/reviews/**` — viết đánh giá
- `POST /api/admin/tours/**` — ADMIN tạo/sửa tour

**Package:** `com.tourism.tourcatalog.config` hoặc `com.tourism.catalog.config` (kiểm tra package thực tế)

---

### 3.2 booking-service (port 8083)

**Public endpoints:**
```java
.requestMatchers(
    "/actuator/health",
    "/v3/api-docs/**",
    "/swagger-ui/**"
).permitAll()
```

**Tất cả `/api/bookings/**` và `/api/coupons/**` đều cần token** — không ai đặt tour khi chưa đăng nhập.

**Package:** `com.tourism.booking.config`

---

### 3.3 payment-service (port 8084)

**Public endpoints:**
```java
.requestMatchers(
    "/api/payment/webhook/**", 
    "/actuator/health",
    "/v3/api-docs/**",
    "/swagger-ui/**"
).permitAll()
```

> ⚠️ **Lưu ý webhook:** SePay gọi callback vào `/api/payment/webhook` từ server bên ngoài,
> không có Keycloak token. Phải `permitAll()` endpoint này, tự verify bằng secret key của SePay.

**Package:** `com.tourism.payment.config`

---

### 3.4 forum-service

**Public endpoints** (xem bài đăng không cần login):
```java
.requestMatchers(
    "/api/posts",
    "/api/posts/{id}",
    "/api/tags/**",
    "/api/categories/**",
    "/actuator/health",
    "/v3/api-docs/**",
    "/swagger-ui/**"
).permitAll()
```

**Protected endpoints** (cần login):
- `POST /api/posts/**` — tạo bài đăng
- `POST /api/bookmarks/**` — bookmark bài
- `POST /api/followers/**` — follow user

**Package:** `com.tourism.forum.config`

---

### 3.5 notification-service

**Public endpoints:**
```java
.requestMatchers(
    "/actuator/health",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/ws/**"           
).permitAll()
```

> ⚠️ **Lưu ý WebSocket:** `/ws/**` phải `permitAll()` vì Spring Security không thể validate
> JWT trong WebSocket handshake theo cách thông thường. Auth được xử lý sau khi kết nối,
> ở tầng STOMP message header.

**Package:** `com.tourism.notification.config`

---

## Cách đọc X-User-* headers trong service

Sau khi Gateway forward headers xuống, service đọc như sau:

```java
@GetMapping("/my-bookings")
public ResponseEntity<?> getMyBookings(
        @RequestHeader("X-User-Id") Integer userId,
        @RequestHeader("X-User-Role") String role) {

    return ResponseEntity.ok(bookingService.getByUserId(userId));
}
```

Hoặc tạo một utility method dùng chung:

```java
// Tạo file: config/UserContext.java
public class UserContext {

    public static Integer getUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        return userId != null ? Integer.parseInt(userId) : null;
    }

    public static String getUserRole(HttpServletRequest request) {
        return request.getHeader("X-User-Role");
    }

    public static String getUserEmail(HttpServletRequest request) {
        return request.getHeader("X-User-Email");
    }
}
```

---

## Thứ tự thực hiện

Làm từng service theo thứ tự ưu tiên:

| Thứ tự | Service | Lý do ưu tiên |
|---|---|---|
| 1 | `tour-catalog-service` | Service core, nhiều endpoint nhất |
| 2 | `booking-service` | Liên quan tiền — cần bảo mật cao |
| 3 | `payment-service` | Liên quan tiền — cần bảo mật cao |
| 4 | `forum-service` | Ít critical hơn |
| 5 | `notification-service` | Phức tạp nhất do WebSocket |

---

## Kiểm tra sau khi hoàn thành

**Test từng service — gọi thẳng vào port service (bypass Gateway):**

```bash
# Gọi không có token → phải 401
curl -i http://localhost:8082/api/bookings/1
# Expected: HTTP/1.1 401

# Gọi có token → phải 200
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/bookings/1
# Expected: HTTP/1.1 200
```

**Test qua Gateway — kiểm tra headers được forward:**
```bash
# Thêm log tạm vào controller để in headers
log.info("X-User-Id: {}", request.getHeader("X-User-Id"));
log.info("X-User-Role: {}", request.getHeader("X-User-Role"));
```

---

*— Winston, System Architect*
*"Each service is its own castle. The gateway is the city wall."*
