# Phase 3 — API Gateway: Validate Keycloak JWT: Hướng Dẫn Chi Tiết
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-13

---

## Tổng quan Phase 3

Phase 3 gồm 4 việc theo thứ tự:
1. Cập nhật `pom.xml` — thay JJWT bằng OAuth2 Resource Server
2. Cập nhật `application.yml` — thêm Keycloak JWKS config, xóa `jwt.secret`
3. Tạo `SecurityConfig.java` — định nghĩa route public vs protected
4. Tạo `AuthHeaderFilter.java` — forward user info xuống downstream services

Sau Phase 3, bạn có thể:
- API Gateway tự động validate token do Keycloak cấp (verify chữ ký qua JWKS)
- Request không có token / token hết hạn bị chặn ngay tại Gateway → 401
- Các service downstream nhận `X-User-Id`, `X-User-Role`, `X-User-Email` qua header

---

## Cấu trúc file sau Phase 3

```
api-gateway/src/main/java/com/tourism/gateway/
├── ApiGatewayApplication.java       ← GIỮ NGUYÊN
├── config/
│   └── SecurityConfig.java          ← TẠO MỚI
└── filter/
    └── AuthHeaderFilter.java        ← TẠO MỚI

api-gateway/src/main/resources/
└── application.yml                  ← SỬA LẠI

api-gateway/pom.xml                  ← SỬA LẠI
```

---

## Bước 1 — Cập nhật pom.xml

**File:** `api-gateway/pom.xml`

**Việc cần làm:** Xóa 3 dependency JJWT cũ, thêm 2 dependency mới.

### Xóa các dependency JJWT cũ:
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

### Thêm 2 dependency mới:
```xml
<!-- Keycloak JWT Validation via OAuth2 Resource Server -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

> **Tại sao cần 2 thứ này?**
> - `oauth2-resource-server`: Tự động fetch JWKS từ Keycloak, verify chữ ký JWT mỗi request
> - `spring-boot-starter-security`: Cần thiết để cấu hình SecurityConfig trong Gateway

---

## Bước 2 — Cập nhật application.yml

**File:** `api-gateway/src/main/resources/application.yml`

**Việc cần làm:** Xóa phần `jwt:`, thêm phần `spring.security.oauth2.resourceserver`.

### Xóa phần JWT cũ:
```yaml
# XÓA TOÀN BỘ PHẦN NÀY
jwt:
  secret: ${JWT_SECRET:59379ea8-777d-49e1-ac8e-03420874f6a3}
```

### Thêm vào trong phần `spring:` (sau phần `data.redis`):
```yaml
  # ─── OAuth2 Resource Server (validate Keycloak JWT) ───
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/tourism
          jwk-set-uri: ${KEYCLOAK_SERVER_URL:http://localhost:8180}/realms/tourism/protocol/openid-connect/certs
```

> **Giải thích:**
> - `issuer-uri`: Gateway kiểm tra claim `iss` trong token phải khớp với giá trị này
> - `jwk-set-uri`: Endpoint để fetch public key của Keycloak (dùng verify chữ ký JWT). Gateway cache key này lại, không fetch mỗi request.

---

## Bước 3 — Tạo SecurityConfig.java

**File:** `api-gateway/src/main/java/com/tourism/gateway/config/SecurityConfig.java`

**Mục đích:** Định nghĩa route nào cần authenticate, route nào public (auth endpoints, swagger...).
Gateway dùng WebFlux (reactive) nên phải dùng `ServerHttpSecurity`, không phải `HttpSecurity`.

```java
package com.tourism.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // ─── Auth endpoints — public (không cần token) ───
                        .pathMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh-token",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification"
                        ).permitAll()

                        // ─── Swagger / OpenAPI — public ───
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**"
                        ).permitAll()

                        // ─── Actuator health check — public ───
                        .pathMatchers("/actuator/**").permitAll()

                        // ─── WebSocket — public (auth xử lý ở tầng STOMP) ───
                        .pathMatchers("/ws/**").permitAll()

                        // ─── Tất cả route còn lại — phải có token hợp lệ ───
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveKeycloakJwtConverter()))
                )
                .build();
    }
}
```

> **Lưu ý quan trọng:**
> - `@EnableWebFluxSecurity` thay vì `@EnableWebSecurity` — vì Gateway chạy trên Netty (reactive), không phải Tomcat (servlet)
> - `ServerHttpSecurity` thay vì `HttpSecurity` — cùng lý do trên
> - Các route trong `permitAll()` vẫn được Gateway forward bình thường mà không check token

---

## Bước 4 — Tạo ReactiveKeycloakJwtConverter.java

**File:** `api-gateway/src/main/java/com/tourism/gateway/config/ReactiveKeycloakJwtConverter.java`

**Mục đích:** Keycloak đặt roles trong claim `realm_access.roles` thay vì claim `roles` tiêu chuẩn.
Class này extract roles đúng chỗ để Spring Security hiểu được.

```java
package com.tourism.gateway.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ReactiveKeycloakJwtConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRoles(jwt);
        return Mono.just(new JwtAuthenticationToken(jwt, authorities));
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRoles(Jwt jwt) {
        // Keycloak đặt realm roles trong: { "realm_access": { "roles": ["CUSTOMER", "ADMIN"] } }
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !realmAccess.containsKey("roles")) {
            return List.of();
        }

        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
```

> **Tại sao cần class này?**
> Mặc định Spring Security đọc roles từ claim `scope` hoặc `authorities`.
> Keycloak lại đặt trong `realm_access.roles` — nếu không convert thì
> `@PreAuthorize("hasRole('ADMIN')")` sẽ không hoạt động.

---

## Bước 5 — Tạo AuthHeaderFilter.java

**File:** `api-gateway/src/main/java/com/tourism/gateway/filter/AuthHeaderFilter.java`

**Mục đích:** Sau khi Gateway validate token thành công, extract thông tin user từ JWT claims
rồi forward xuống downstream services qua HTTP headers. Các service downstream chỉ cần đọc header,
không cần parse JWT nữa.

```java
package com.tourism.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    Jwt jwt = jwtAuth.getToken();

                    // Extract claims từ Keycloak JWT
                    String userId = jwt.getClaimAsString("userId");   // custom claim từ Protocol Mapper
                    String email  = jwt.getClaimAsString("email");
                    String role   = extractRole(jwt);                  // từ realm_access.roles

                    // Forward xuống downstream service qua headers
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id",    userId != null ? userId : "")
                            .header("X-User-Email", email  != null ? email  : "")
                            .header("X-User-Role",  role   != null ? role   : "")
                            .build();

                    return exchange.mutate().request(mutatedRequest).build();
                })
                .defaultIfEmpty(exchange)   // request public (chưa auth) → giữ nguyên exchange
                .flatMap(chain::filter);
    }

    @SuppressWarnings("unchecked")
    private String extractRole(Jwt jwt) {
        // Lấy role đầu tiên trong realm_access.roles (CUSTOMER / ADMIN / TOUR_OWNER)
        var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return null;

        var roles = (java.util.List<String>) realmAccess.get("roles");
        if (roles == null || roles.isEmpty()) return null;

        // Bỏ qua các role mặc định của Keycloak
        return roles.stream()
                .filter(r -> r.equals("CUSTOMER") || r.equals("ADMIN") || r.equals("TOUR_OWNER"))
                .findFirst()
                .orElse(null);
    }

    @Override
    public int getOrder() {
        return -1;  // Chạy trước tất cả filter khác
    }
}
```

> **Luồng hoạt động:**
> ```
> Request → Gateway → [OAuth2 validate JWT] → [AuthHeaderFilter chạy]
>         → mutate request (thêm X-User-* headers) → forward đến service
> ```
>
> **Lưu ý `userId` claim:** Đây là custom claim được tạo ở Phase 1 (Protocol Mapper trong Keycloak).
> Claim này chứa integer ID từ `iam_db`, cho phép downstream service biết user là ai
> mà không cần query thêm vào iam-service.

---

## Kiểm tra sau khi hoàn thành

### 1. Build lại Gateway:
```bash
cd api-gateway
mvn clean compile
```

### 2. Khởi động và test với curl:

**Request không có token → phải nhận 401:**
```bash
curl -i http://localhost:8080/api/tours
# Expected: HTTP/1.1 401 Unauthorized
```

**Request có token hợp lệ → phải được forward:**
```bash
# Lấy token từ Keycloak trước
TOKEN=$(curl -s -X POST http://localhost:8180/realms/tourism/protocol/openid-connect/token \
  -d "grant_type=password&client_id=tourism-app&client_secret=SECRET&username=user@test.com&password=123456" \
  | jq -r '.access_token')

# Gọi API với token
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tours
# Expected: HTTP/1.1 200 OK
```

**Kiểm tra headers được forward:**
```bash
# Thêm endpoint debug tạm vào một service downstream để in headers
# Kiểm tra X-User-Id, X-User-Role, X-User-Email có đúng không
```

---

## Các lỗi thường gặp

| Lỗi | Nguyên nhân | Fix |
|---|---|---|
| `Unable to resolve the Configuration with the provided Issuer` | Gateway không kết nối được Keycloak | Kiểm tra Keycloak đang chạy ở port 8180 |
| `401` cho tất cả request kể cả public | `permitAll()` chưa đúng path | Kiểm tra path trong `SecurityConfig` |
| `X-User-Id` header rỗng | `userId` Protocol Mapper chưa được tạo trong Keycloak | Tạo Protocol Mapper theo Phase 1 mục 7.4 |
| `ClassCastException` trong `extractRole` | Token không có `realm_access` claim | Kiểm tra user đã được assign role trong Keycloak chưa |
| `NoSuchBeanDefinitionException: ReactiveKeycloakJwtConverter` | Class chưa được Spring scan | Đảm bảo class nằm đúng package hoặc thêm `@Component` |

---

*— Winston, System Architect*
*"Validate once at the gate. Trust inside the wall."*