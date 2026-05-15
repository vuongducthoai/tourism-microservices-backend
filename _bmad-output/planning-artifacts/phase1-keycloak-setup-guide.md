# Phase 1 — Keycloak Setup: Hướng Dẫn Chi Tiết
**Author:** Winston (System Architect — BMAD)
**Date:** 2026-05-11

---

## Tổng quan Phase 1

Phase 1 gồm 3 việc:
1. Tạo database `keycloak_db` trong PostgreSQL container hiện có
2. Thêm Keycloak vào `docker-compose.yml`
3. Tạo file `realm-export.json` để Keycloak tự cấu hình khi khởi động

Sau Phase 1, bạn có thể:
- Truy cập Keycloak Admin Console tại http://localhost:8180
- Realm `tourism` đã được tạo sẵn với client, roles, protocol mapper
- Sẵn sàng để Phase 2 code iam-service kết nối vào

---

## Cấu trúc file cần tạo

```
Tourism_Microservices/
├── docker-compose.yml              ← CHỈNH SỬA (thêm keycloak + keycloak-db)
└── docker/
    ├── postgres/
    │   └── init-databases.sh       ← CHỈNH SỬA (thêm keycloak_db)
    └── keycloak/
        └── realm-export.json       ← TẠO MỚI (cấu hình realm tự động)
```

---

## Bước 1 — Thêm keycloak_db vào init-databases.sh

**File:** `docker/postgres/init-databases.sh`

**Mục đích:** Script này chạy 1 lần duy nhất khi PostgreSQL container khởi động lần đầu.
Nó tạo tất cả database cần thiết. Ta cần thêm `keycloak_db` vào đây.

> ⚠️ Lưu ý: Nếu container `tourism-postgres` đã chạy và đã có volume `postgres_data`,
> script này sẽ KHÔNG chạy lại. Bạn cần tạo DB thủ công bằng lệnh ở cuối bước này.

**Code:**
```bash
#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE iam_db;
    CREATE DATABASE tour_catalog_db;
    CREATE DATABASE booking_db;
    CREATE DATABASE payment_db;
    CREATE DATABASE forum_db;
    CREATE DATABASE notification_db;
    CREATE DATABASE analytics_db;
    CREATE DATABASE keycloak_db;           -- THÊM DÒNG NÀY
EOSQL

echo "✅ All 8 databases created successfully!"
```

**Nếu container đã chạy rồi, tạo DB thủ công:**
```bash
docker exec tourism-postgres psql -U postgres -c "CREATE DATABASE keycloak_db;"
```

---

## Bước 2 — Chỉnh sửa docker-compose.yml

**File:** `docker-compose.yml`

**Mục đích:** Thêm service `keycloak` vào compose. Keycloak sẽ dùng chung PostgreSQL 
container đã có (chỉ khác database `keycloak_db`), không cần tạo thêm postgres mới.

**Thêm 2 phần vào file:**

### Phần A — Thêm service `keycloak` vào section INFRASTRUCTURE (sau redis/rabbitmq):

```yaml
  # --- Keycloak ---
  keycloak:
    image: quay.io/keycloak/keycloak:24.0.5
    container_name: tourism-keycloak
    # "start-dev" = chế độ development (không cần SSL, log chi tiết)
    # "--import-realm" = tự động import realm-export.json khi khởi động
    command: start-dev --import-realm
    environment:
      # Kết nối đến PostgreSQL container đang có (dùng tên service "postgres" trong Docker network)
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak_db
      KC_DB_USERNAME: postgres
      KC_DB_PASSWORD: postgres
      # Tài khoản admin của Keycloak Admin Console
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      # Cho phép HTTP (không bắt HTTPS) - phù hợp môi trường dev
      KC_HOSTNAME_STRICT: "false"
      KC_HTTP_ENABLED: "true"
      # Tắt theme cache để reload nhanh khi dev
      KC_SPI_THEME_STATIC_MAX_AGE: "-1"
      KC_SPI_THEME_CACHE_THEMES: "false"
    ports:
      # 8180 trên máy host → 8080 trong container (Keycloak mặc định chạy port 8080)
      - "8180:8080"
    volumes:
      # Mount file realm-export.json vào đúng thư mục Keycloak đọc khi --import-realm
      - ./docker/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
    depends_on:
      postgres:
        condition: service_healthy   # Đợi postgres sẵn sàng mới khởi động
    healthcheck:
      test: [ "CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080 && echo -e 'GET /health/ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && cat <&3 | grep -q '200 OK'" ]
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 90s   # Keycloak khởi động chậm (~60s), cần start_period dài
    networks:
      - tourism-network
```

### Phần B — Thêm `keycloak_data` vào section volumes ở cuối file:

```yaml
volumes:
  postgres_data:
    driver: local
  redis_data:
    driver: local
  rabbitmq_data:
    driver: local
  keycloak_data:         # THÊM DÒNG NÀY (dù không dùng volume riêng, để rõ ràng)
    driver: local
```

### Phần C — Cập nhật `iam-service` để depends_on Keycloak:

Tìm phần `iam-service` trong docker-compose.yml và thêm `keycloak` vào `depends_on`:

```yaml
  iam-service:
    ...
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/iam_db
      - SPRING_DATASOURCE_USERNAME=postgres
      - SPRING_DATASOURCE_PASSWORD=postgres
      - EUREKA_HOST=service-discovery
      - REDIS_HOST=redis
      - RABBITMQ_HOST=rabbitmq
      # THÊM CÁC BIẾN MÔI TRƯỜNG KEYCLOAK:
      - KEYCLOAK_SERVER_URL=http://keycloak:8080
      - KEYCLOAK_REALM=tourism
      - KEYCLOAK_CLIENT_ID=tourism-app
      - KEYCLOAK_CLIENT_SECRET=tourism-app-secret
      - KEYCLOAK_ADMIN_USERNAME=admin
      - KEYCLOAK_ADMIN_PASSWORD=admin
    depends_on:
      service-discovery:
        condition: service_healthy
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      keycloak:                        # THÊM DÒNG NÀY
        condition: service_healthy     # THÊM DÒNG NÀY
```

---

## Bước 3 — Tạo realm-export.json

**File:** `docker/keycloak/realm-export.json`

**Mục đích:** File này cấu hình toàn bộ Realm `tourism` trong Keycloak:
- Tạo realm với tên `tourism`
- Tạo client `tourism-app` (ứng dụng của chúng ta)
- Tạo 3 roles: `CUSTOMER`, `ADMIN`, `TOUR_OWNER`
- Tạo Protocol Mapper để thêm `userId` vào JWT token
- Cấu hình token lifetime (access: 15 phút, refresh: 7 ngày)

> Keycloak đọc file này tự động nhờ flag `--import-realm` khi khởi động.
> Nếu realm đã tồn tại thì bỏ qua (không override).

**Xem code đầy đủ tại:** `docker/keycloak/realm-export.json` (file được tạo tự động bên dưới)

---

## Bước 4 — Kiểm tra sau khi chạy

```bash
# 1. Chạy Keycloak (chỉ chạy Keycloak + postgres):
docker-compose up -d postgres keycloak

# 2. Xem log Keycloak (đợi đến khi thấy "Keycloak 24.0.5 on JVM"):
docker-compose logs -f keycloak

# 3. Mở trình duyệt, vào Admin Console:
#    URL: http://localhost:8180
#    User: admin
#    Pass: admin

# 4. Kiểm tra Realm "tourism" đã được tạo:
#    Sidebar → Realms → chọn "tourism"
#    Clients → "tourism-app" phải có trong danh sách
#    Realm roles → CUSTOMER, ADMIN, TOUR_OWNER phải có

# 5. Test lấy token thủ công (dùng Postman hoặc curl):
curl -X POST http://localhost:8180/realms/tourism/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=tourism-app" \
  -d "client_secret=tourism-app-secret"

# Nếu trả về {"access_token": "..."} thì Phase 1 thành công!
```

---

## Giải thích các khái niệm Keycloak quan trọng

| Khái niệm | Giải thích | Tương đương trong code cũ |
|---|---|---|
| **Realm** | Không gian tách biệt, như 1 tenant. Ta dùng realm `tourism` | Không có tương đương |
| **Client** | Ứng dụng đăng ký với Keycloak. `tourism-app` là backend của ta | `JWT_SECRET` trong application.yml |
| **Client Secret** | Password của client, dùng để xác thực backend với Keycloak | `JWT_SECRET` |
| **Direct Access Grant** | Cho phép login bằng username/password qua API (không qua browser) | `AuthServiceImpl.login()` |
| **Access Token** | JWT Keycloak cấp, valid 15 phút | JWT tự tạo trong `JwtUtil` |
| **Refresh Token** | Token dài hạn để lấy access token mới | `RefreshToken` entity trong DB |
| **JWKS** | Public key endpoint, các service dùng để verify JWT | `JWT_SECRET` shared |
| **Protocol Mapper** | Thêm custom claims vào JWT (ta thêm `userId`) | `userId` claim trong `JwtUtil.generateAccessToken()` |
| **Realm Role** | Role cấp realm, ánh xạ với `Role` enum trong code | `Role.CUSTOMER`, `Role.ADMIN`, `Role.TOUR_OWNER` |

---

## Cấu trúc JWT Token Keycloak sẽ phát

Sau Phase 1+2, JWT access token có dạng:

```json
{
  "exp": 1234567890,
  "iat": 1234567000,
  "iss": "http://keycloak:8080/realms/tourism",
  "sub": "uuid-của-user-trong-keycloak",
  "email": "user@example.com",
  "preferred_username": "user@example.com",
  "userId": 42,
  "realm_access": {
    "roles": ["CUSTOMER"]
  }
}
```

Điểm khác với JWT cũ:
- `sub` là UUID (Keycloak ID) thay vì email
- Thêm `userId` (integer PK từ iam_db) qua Protocol Mapper → các service dùng cái này
- `role` nằm trong `realm_access.roles[]` thay vì field `role` trực tiếp
- Token ngắn hơn (15 phút thay vì 7 ngày)
