# IAM Service — Keycloak Architecture Design
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-11
**Status:** APPROVED FOR IMPLEMENTATION

---

## 1. Problem Statement

The current Tourism Microservices project has:
- Auth logic scattered in the **monolithic** Tourism_Backend (JWT self-signed, BCrypt passwords)
- `iam-service` only handles **user profile management**, not authentication
- No centralized identity provider — each service would need to replicate JWT validation logic
- 104 existing users in `iam_db` with BCrypt-hashed passwords

**Goal:** Replace self-signed JWT with **Keycloak** as the centralized Identity Provider, so:
- All microservices trust Keycloak-issued tokens (no shared secret needed)
- `iam-service` owns the full auth lifecycle
- Existing users are migrated to Keycloak seamlessly

---

## 2. Architecture Decision

### Decision: Keycloak as External Authorization Server (OAuth2/OIDC)

**Why Keycloak, not self-signed JWT:**
| Concern | Self-signed JWT | Keycloak |
|---|---|---|
| Token validation | Shared secret across all services | Public key (JWKS endpoint) — no secret sharing |
| User management UI | Custom build | Built-in admin console |
| Social login | Custom GoogleAuthService | Built-in Identity Providers |
| MFA | Custom build | Built-in |
| Token revocation | DB lookup every request | Introspection endpoint / short-lived tokens |
| Standards compliance | Proprietary | OAuth2 + OIDC compliant |

**Trade-off acknowledged:** Keycloak adds operational complexity (one more container, realm config). This is worth it given the microservices scale.

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLIENT (React Frontend)                       │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (:8080)                               │
│  - Route requests                                                    │
│  - Validate JWT via Keycloak JWKS (Resource Server)                 │
│  - Forward userId/role as headers to downstream services            │
└──────┬───────────────────┬───────────────────────────────────────────┘
       │                   │
       ▼                   ▼
┌─────────────┐   ┌─────────────────────────────────────────────────┐
│  iam-service│   │  Other Services (tour-catalog, booking, etc.)   │
│  (:8081)    │   │  - Validate JWT locally via Keycloak JWKS       │
│             │   │  - Extract userId/role from token claims        │
│  Auth APIs: │   └─────────────────────────────────────────────────┘
│  /register  │
│  /login     │◄──── Delegates to Keycloak Admin REST API
│  /refresh   │      for token issuance
│  /logout    │
│             │
│  User APIs: │◄──── Local iam_db (user profiles, coins, etc.)
│  /profile   │
│  /avatar    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    KEYCLOAK (:8180)                                  │
│  Realm: tourism                                                      │
│  Client: tourism-app (confidential)                                 │
│  - Issues Access Tokens (JWT, short-lived: 15 min)                 │
│  - Issues Refresh Tokens (long-lived: 7 days)                       │
│  - JWKS endpoint: /realms/tourism/protocol/openid-connect/certs     │
│  - User Federation: sync from iam_db                               │
└─────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────┐
│              PostgreSQL Docker (:5433)                               │
│  iam_db          → user profiles (source of truth for profile data) │
│  keycloak_db     → Keycloak internal data (new DB)                 │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Auth Flow Details

### 4.1 Login Flow
```
Client → POST /api/auth/login (email, password)
  → iam-service receives request
  → iam-service calls Keycloak Token Endpoint:
      POST /realms/tourism/protocol/openid-connect/token
      grant_type=password, username=email, password=password
  → Keycloak validates credentials, returns:
      { access_token, refresh_token, expires_in }
  → iam-service updates User.lastActiveAt in iam_db
  → iam-service returns LoginResponse to client
      (access_token, refresh_token, user profile from iam_db)
```

### 4.2 Register Flow
```
Client → POST /api/auth/register (fullName, email, password, ...)
  → iam-service validates input
  → iam-service creates user in iam_db (profile data)
  → iam-service creates user in Keycloak via Admin REST API:
      POST /admin/realms/tourism/users
      { username: email, email, credentials: [password], enabled: false }
  → iam-service sends verification email (existing logic)
  → On verify-email:
      iam-service enables user in Keycloak:
      PUT /admin/realms/tourism/users/{id} { enabled: true }
      + sets emailVerified: true in iam_db
```

### 4.3 Token Refresh Flow
```
Client → POST /api/auth/refresh-token (refreshToken)
  → iam-service calls Keycloak:
      POST /realms/tourism/protocol/openid-connect/token
      grant_type=refresh_token, refresh_token=...
  → Keycloak validates, returns new access_token + refresh_token
  → iam-service returns new tokens to client
```

### 4.4 Logout Flow
```
Client → POST /api/auth/logout (refreshToken)
  → iam-service calls Keycloak:
      POST /realms/tourism/protocol/openid-connect/logout
      refresh_token=...
  → Keycloak revokes session
  → iam-service returns success
```

### 4.5 Request Authentication (Other Services)
```
Client → GET /api/tours (Authorization: Bearer <access_token>)
  → API Gateway validates token:
      Fetches JWKS from Keycloak (cached)
      Verifies signature, expiry, issuer
  → Gateway forwards request + headers:
      X-User-Id: 123
      X-User-Role: CUSTOMER
      X-User-Email: user@example.com
  → tour-catalog-service trusts gateway headers (no re-validation needed)
    OR validates JWT independently (defense in depth)
```

---

## 5. Token Design

### Access Token Claims (Keycloak JWT)
```json
{
  "sub": "keycloak-user-uuid",
  "email": "user@example.com",
  "preferred_username": "user@example.com",
  "realm_access": {
    "roles": ["CUSTOMER"]
  },
  "userId": 123,
  "iss": "http://keycloak:8180/realms/tourism",
  "exp": 1234567890,
  "iat": 1234567000
}
```

**Note:** `userId` (integer PK from iam_db) is added as a custom claim via Keycloak Protocol Mapper. This allows downstream services to reference iam_db without extra lookups.

### Token Lifetimes
| Token | Lifetime | Rationale |
|---|---|---|
| Access Token | 15 minutes | Short-lived → reduces revocation window |
| Refresh Token | 7 days | Matches current monolithic behavior |
| Email verification | 24 hours | Existing behavior preserved |

---

## 6. User Migration Strategy

### 6.1 Problem
- 104 existing users in `iam_db` have BCrypt passwords
- Keycloak cannot import BCrypt hashes directly (different format)

### 6.2 Solution: Lazy Migration
```
On first login after migration:
1. User submits email + password
2. iam-service first tries Keycloak login (will fail — user not in Keycloak yet)
3. On failure: check iam_db for user + BCrypt.verify(password, hash)
4. If match: create user in Keycloak with new password
5. Mark user as migrated in iam_db (add migrated_to_keycloak: boolean)
6. Proceed with Keycloak login
7. On subsequent logins: Keycloak handles directly
```

**Why lazy migration:**
- Zero downtime — no big-bang migration script
- Users who never login don't need migration
- Passwords never stored in plaintext during migration

### 6.3 Admin/Bulk Migration Script
For admin users or forced migration — use Keycloak's password reset flow (sends email).

---

## 7. Keycloak Configuration

### 7.1 Realm: `tourism`
```
- Display Name: Tourism Platform
- Access Token Lifespan: 900s (15 min)
- Refresh Token Lifespan: 604800s (7 days)
- SSL Required: none (dev) / external (prod)
```

### 7.2 Client: `tourism-app`
```
- Client ID: tourism-app
- Client Secret: (generated, stored in env)
- Access Type: confidential
- Direct Access Grants: enabled (for password grant — login via API)
- Standard Flow: enabled (for future web SSO)
- Service Accounts: enabled (for iam-service admin operations)
```

### 7.3 Roles (Realm Roles)
```
- CUSTOMER (default for new registrations)
- TOUR_OWNER
- ADMIN
```

### 7.4 Protocol Mapper: userId
```
Name: userId
Mapper Type: User Attribute
User Attribute: userId
Token Claim Name: userId
Claim JSON Type: int
Add to access token: true
Add to ID token: true
```

---

## 8. Docker Compose Changes

### New Services to Add
```yaml
# Keycloak DB
keycloak-db:
  image: postgres:16-alpine
  container_name: tourism-keycloak-db
  environment:
    POSTGRES_DB: keycloak_db
    POSTGRES_USER: keycloak
    POSTGRES_PASSWORD: keycloak
  volumes:
    - keycloak_data:/var/lib/postgresql/data
  networks:
    - tourism-network

# Keycloak
keycloak:
  image: quay.io/keycloak/keycloak:24.0.5
  container_name: tourism-keycloak
  command: start-dev --import-realm
  environment:
    KC_DB: postgres
    KC_DB_URL: jdbc:postgresql://keycloak-db:5432/keycloak_db
    KC_DB_USERNAME: keycloak
    KC_DB_PASSWORD: keycloak
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: admin
    KC_HOSTNAME_STRICT: false
    KC_HTTP_ENABLED: true
  ports:
    - "8180:8080"
  volumes:
    - ./docker/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
  depends_on:
    keycloak-db:
      condition: service_started
  networks:
    - tourism-network
```

---

## 9. IAM Service Changes

### 9.1 New Dependencies (pom.xml)
```xml
<!-- Keycloak Admin Client -->
<dependency>
  <groupId>org.keycloak</groupId>
  <artifactId>keycloak-admin-client</artifactId>
  <version>24.0.5</version>
</dependency>

<!-- OAuth2 Resource Server (for validating Keycloak tokens) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

### 9.2 New Classes to Implement
```
iam-service/
├── controller/
│   └── AuthController.java          [NEW] - /login, /register, /refresh, /logout, /verify-email
├── service/
│   ├── AuthService.java             [NEW] - interface
│   ├── impl/
│   │   └── AuthServiceImpl.java     [NEW] - Keycloak integration
│   └── KeycloakAdminService.java    [NEW] - Keycloak Admin REST API wrapper
├── config/
│   ├── KeycloakConfig.java          [NEW] - Keycloak admin client bean
│   └── SecurityConfig.java         [MODIFY] - Add OAuth2 resource server
├── entity/
│   └── User.java                   [MODIFY] - Add keycloakId, migratedToKeycloak fields
├── dto/
│   ├── request/
│   │   ├── LoginRequest.java        [NEW]
│   │   ├── RegisterRequest.java     [NEW]
│   │   └── RefreshTokenRequest.java [NEW]
│   └── response/
│       ├── LoginResponse.java       [NEW]
│       └── TokenResponse.java       [NEW]
└── migration/
    └── UserMigrationService.java    [NEW] - Lazy migration logic
```

### 9.3 application.yml additions
```yaml
keycloak:
  server-url: ${KEYCLOAK_SERVER_URL:http://keycloak:8180}
  realm: tourism
  client-id: tourism-app
  client-secret: ${KEYCLOAK_CLIENT_SECRET:secret}
  admin-username: ${KEYCLOAK_ADMIN_USERNAME:admin}
  admin-password: ${KEYCLOAK_ADMIN_PASSWORD:admin}

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_SERVER_URL:http://keycloak:8180}/realms/tourism
          jwk-set-uri: ${KEYCLOAK_SERVER_URL:http://keycloak:8180}/realms/tourism/protocol/openid-connect/certs
```

---

## 10. Other Services Changes

### Minimal change required — each service needs:
```yaml
# application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8180/realms/tourism
          jwk-set-uri: http://keycloak:8180/realms/tourism/protocol/openid-connect/certs
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```java
// SecurityConfig.java
http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

---

## 11. Implementation Order

| Phase | Task | Priority |
|---|---|---|
| **Phase 1** | Add Keycloak + keycloak-db to docker-compose | MUST |
| **Phase 1** | Create realm-export.json (realm config) | MUST |
| **Phase 2** | Implement KeycloakAdminService in iam-service | MUST |
| **Phase 2** | Implement AuthController + AuthServiceImpl | MUST |
| **Phase 2** | Implement lazy user migration logic | MUST |
| **Phase 2** | Update SecurityConfig for Resource Server | MUST |
| **Phase 3** | Update API Gateway to validate Keycloak JWT | MUST |
| **Phase 4** | Update other services (Resource Server config) | SHOULD |
| **Phase 5** | Google OAuth2 via Keycloak Identity Provider | NICE |
| **Phase 5** | Keycloak realm auto-import on startup | NICE |

---

## 12. API Contract (Backward Compatible)

The following endpoints will be implemented in `iam-service` with **same request/response shape** as monolithic:

| Method | Path | Description |
|---|---|---|
| POST | /api/auth/register | Register new user |
| GET | /api/auth/verify-email | Verify email token |
| POST | /api/auth/resend-verification | Resend verification email |
| POST | /api/auth/login | Login → returns Keycloak tokens |
| POST | /api/auth/refresh-token | Refresh access token |
| POST | /api/auth/logout | Logout (revoke session) |
| POST | /api/auth/logout-all | Logout all devices |
| GET | /api/auth/profile | Get current user profile |
| POST | /api/auth/google/login | Google OAuth2 login |

**LoginResponse shape is preserved** — frontend does not need to change.

---

## 13. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Keycloak cold start slow in Docker | High | Medium | Add health check with longer start_period (60s) |
| Lazy migration breaks on concurrent login | Low | High | Use database lock on migration check |
| Keycloak token claim mismatch with existing code | Medium | High | Add Protocol Mapper for userId before testing |
| Keycloak Admin API rate limiting | Low | Low | Cache admin token, reuse until near expiry |
| Network latency: iam-service → Keycloak | Medium | Medium | Run both in same Docker network (sub-ms) |

---

*— Winston, System Architect*
*"Pick boring technology where you can. Keycloak is battle-tested. The complexity cost is a one-time setup, not an ongoing maintenance tax."*
