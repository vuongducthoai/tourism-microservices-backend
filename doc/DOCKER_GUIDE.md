# Hướng Dẫn Chạy Docker & Kết Nối pgAdmin 4

> Thư mục gốc thực hiện tất cả lệnh: `D:\HK8\tourism-microservices-backend`

---

## Mục Lục
1. [Yêu cầu trước khi chạy](#1-yêu-cầu-trước-khi-chạy)
2. [Chạy từng service riêng lẻ](#2-chạy-từng-service-riêng-lẻ)
3. [Chạy toàn bộ hệ thống](#3-chạy-toàn-bộ-hệ-thống)
4. [Quản lý container](#4-quản-lý-container)
5. [Kết nối PostgreSQL Docker lên pgAdmin 4](#5-kết-nối-postgresql-docker-lên-pgadmin-4)
6. [Kiểm tra hệ thống hoạt động](#6-kiểm-tra-hệ-thống-hoạt-động)
7. [Xử lý lỗi thường gặp](#7-xử-lý-lỗi-thường-gặp)

---

## 1. Yêu Cầu Trước Khi Chạy

| Phần mềm | Phiên bản tối thiểu | Kiểm tra |
|---|---|---|
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |
| RAM trống | ≥ 8 GB | Task Manager |
| Disk trống | ≥ 5 GB | — |

> **Lưu ý Windows:** Đảm bảo Docker Desktop đang chạy (icon ở system tray) trước khi thực hiện bất kỳ lệnh nào.

---

## 2. Chạy Từng Service Riêng Lẻ

> Mở terminal, `cd` vào thư mục `D:\HK8\tourism-microservices-backend`

```powershell
cd D:\HK8\tourism-microservices-backend
```

### 2.1 Thứ tự khởi động đúng (quan trọng)

Các service có dependency, phải khởi động theo thứ tự:

```
1. postgres, redis, rabbitmq   ← Infrastructure
2. service-discovery           ← Eureka (phải healthy trước)
3. config-server               ← Sau service-discovery
4. api-gateway                 ← Sau service-discovery + redis
5. iam-service                 ← Sau postgres + redis + rabbitmq + service-discovery
6. tour-catalog-service        ← Sau step 5
7. booking-service             ← Sau step 5
8. payment-service             ← Sau step 5
9. forum-service               ← Sau step 5
10. notification-service       ← Sau step 5
11. analytics-service          ← Sau step 5
```

---

### 2.2 Bước 1 — Khởi động Infrastructure (bắt buộc đầu tiên)

```powershell
# Chạy PostgreSQL, Redis, RabbitMQ
docker compose up -d postgres redis rabbitmq
```

Chờ healthy (khoảng 15-30 giây), kiểm tra:
```powershell
docker compose ps postgres redis rabbitmq
```
Cột `STATUS` phải hiện `healthy`.

---

### 2.3 Bước 2 — Khởi động Service Discovery (Eureka)

```powershell
docker compose up -d service-discovery
```

Chờ ~30 giây, kiểm tra tại: http://localhost:8761

---

### 2.4 Bước 3 — Khởi động Config Server

```powershell
docker compose up -d config-server
```

Kiểm tra: http://localhost:8888/actuator/health

---

### 2.5 Bước 4 — Khởi động API Gateway

```powershell
docker compose up -d api-gateway
```

Kiểm tra: http://localhost:8080/actuator/health

---

### 2.6 Bước 5 — Khởi động từng Business Service

```powershell
# IAM Service (Auth, User)
docker compose up -d iam-service

# Tour Catalog Service
docker compose up -d tour-catalog-service

# Booking Service
docker compose up -d booking-service

# Payment Service
docker compose up -d payment-service

# Forum Service
docker compose up -d forum-service

# Notification Service
docker compose up -d notification-service

# Analytics Service
docker compose up -d analytics-service
```

---

### 2.7 Xem log từng service

```powershell
# Xem log realtime (Ctrl+C để thoát)
docker compose logs -f iam-service
docker compose logs -f tour-catalog-service
docker compose logs -f booking-service
docker compose logs -f payment-service
docker compose logs -f forum-service
docker compose logs -f notification-service
docker compose logs -f analytics-service
docker compose logs -f api-gateway

# Xem 100 dòng cuối
docker compose logs --tail=100 iam-service
```

---

## 3. Chạy Toàn Bộ Hệ Thống

### 3.1 Khởi động tất cả (một lệnh)

```powershell
cd D:\HK8\tourism-microservices-backend
docker compose up -d
```

> Docker Compose tự động xử lý thứ tự theo `depends_on`. Toàn bộ hệ thống sẽ sẵn sàng sau khoảng **3-5 phút** do các service phải chờ nhau healthy.

### 3.2 Theo dõi quá trình khởi động

```powershell
# Xem log tất cả service cùng lúc
docker compose logs -f

# Xem trạng thái tất cả container
docker compose ps
```

Output mẫu khi thành công:
```
NAME                              STATUS          PORTS
tourism-postgres                  healthy         0.0.0.0:5433->5432/tcp
tourism-redis                     healthy         0.0.0.0:6379->6379/tcp
tourism-rabbitmq                  healthy         0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
tourism-service-discovery         healthy         0.0.0.0:8761->8761/tcp
tourism-config-server             healthy         0.0.0.0:8888->8888/tcp
tourism-api-gateway               healthy         0.0.0.0:8080->8080/tcp
tourism-iam-service               healthy         0.0.0.0:8081->8081/tcp
tourism-tour-catalog-service      healthy         0.0.0.0:8082->8082/tcp
tourism-booking-service           healthy         0.0.0.0:8083->8083/tcp
tourism-payment-service           healthy         0.0.0.0:8084->8084/tcp
tourism-forum-service             healthy         0.0.0.0:8085->8085/tcp
tourism-notification-service      healthy         0.0.0.0:8086->8086/tcp
tourism-analytics-service         healthy         0.0.0.0:8087->8087/tcp
```

### 3.3 Build lại image trước khi chạy (khi có code mới)

```powershell
# Build lại tất cả và chạy
docker compose up -d --build

# Build lại 1 service cụ thể
docker compose up -d --build iam-service
docker compose up -d --build tour-catalog-service
```

---

## 4. Quản Lý Container

### 4.1 Dừng dịch vụ

```powershell
# Dừng tất cả (giữ lại data volume)
docker compose stop

# Dừng 1 service
docker compose stop iam-service

# Dừng và XÓA container (giữ lại volume data)
docker compose down

# Dừng, xóa container VÀ xóa toàn bộ data (⚠️ mất database)
docker compose down -v
```

### 4.2 Khởi động lại

```powershell
# Restart tất cả
docker compose restart

# Restart 1 service
docker compose restart iam-service
docker compose restart api-gateway
```

### 4.3 Xem thông tin container

```powershell
# Liệt kê tất cả container đang chạy
docker ps

# Liệt kê tất cả container (kể cả đã dừng)
docker ps -a

# Xem tài nguyên CPU/RAM từng container
docker stats

# Truy cập shell vào container
docker exec -it tourism-postgres bash
docker exec -it tourism-iam-service bash
```

### 4.4 Chạy chỉ Infrastructure (dev mode)

Khi develop local không cần chạy Spring Boot qua Docker, chỉ cần infra:

```powershell
# Chỉ chạy postgres + redis + rabbitmq + eureka
docker compose up -d postgres redis rabbitmq service-discovery
```

Sau đó chạy từng Spring Boot service trực tiếp từ IDE/Maven.

---

## 5. Kết Nối PostgreSQL Docker Lên pgAdmin 4

> PostgreSQL trong Docker expose ra host tại **cổng 5433** (không phải 5432 mặc định).

### 5.1 Đảm bảo PostgreSQL đang chạy

```powershell
docker compose up -d postgres
docker compose ps postgres
# STATUS phải là: healthy
```

### 5.2 Thêm Server trong pgAdmin 4

**Bước 1:** Mở pgAdmin 4 → Click chuột phải vào **Servers** → chọn **Register → Server...**

```
Object Explorer
└── Servers (1)
    └── [chuột phải] → Register → Server...
```

---

**Bước 2:** Tab **General** — đặt tên hiển thị

| Field | Giá trị |
|---|---|
| **Name** | `Tourism Microservices (Docker)` |

---

**Bước 3:** Tab **Connection** — điền thông tin kết nối

| Field | Giá trị |
|---|---|
| **Host name/address** | `localhost` |
| **Port** | `5433` |
| **Maintenance database** | `postgres` |
| **Username** | `postgres` |
| **Password** | `postgres` |
| **Save password?** | ✅ Bật |

> ⚠️ **Port phải là 5433** (không phải 5432) vì docker-compose map `"5433:5432"`.

---

**Bước 4:** Click **Save** → pgAdmin sẽ kết nối thành công.

---

### 5.3 Kết quả sau khi kết nối

Sau khi kết nối, bạn sẽ thấy cấu trúc như sau trong pgAdmin:

```
Object Explorer
└── Servers
    └── Tourism Microservices (Docker)
        └── Databases
            ├── postgres          ← DB mặc định
            ├── iam_db            ← User, Auth
            ├── tour_catalog_db   ← Tour, Location, Departure
            ├── booking_db        ← Booking, Coupon
            ├── payment_db        ← Payment
            ├── forum_db          ← Forum, Post
            ├── notification_db   ← Notification
            └── analytics_db      ← Dashboard Stats
```

> Các database `iam_db`, `tour_catalog_db`, v.v. được tạo tự động khi container PostgreSQL khởi động lần đầu qua script `docker/postgres/init-databases.sh`.

---

### 5.4 Kiểm tra database đã được tạo

Trong pgAdmin, mở **Query Tool** (chọn database `postgres` → Tools → Query Tool) và chạy:

```sql
SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname;
```

Kết quả mong đợi:
```
analytics_db
booking_db
forum_db
iam_db
notification_db
payment_db
postgres
tour_catalog_db
```

---

### 5.5 Xem bảng trong từng database

Sau khi các Spring Boot service chạy với `ddl-auto: update`, các bảng sẽ tự động được tạo. Ví dụ xem bảng trong `iam_db`:

```
iam_db → Schemas → public → Tables
├── users
└── refresh_tokens
```

---

## 6. Kiểm Tra Hệ Thống Hoạt Động

### 6.1 Bảng kiểm tra tất cả endpoint

| Service | URL kiểm tra | Kết quả mong đợi |
|---|---|---|
| API Gateway | http://localhost:8080/actuator/health | `{"status":"UP"}` |
| Eureka Dashboard | http://localhost:8761 | Trang web Eureka |
| Config Server | http://localhost:8888/actuator/health | `{"status":"UP"}` |
| IAM Service | http://localhost:8081/actuator/health | `{"status":"UP"}` |
| Tour Catalog | http://localhost:8082/actuator/health | `{"status":"UP"}` |
| Booking | http://localhost:8083/actuator/health | `{"status":"UP"}` |
| Payment | http://localhost:8084/actuator/health | `{"status":"UP"}` |
| Forum | http://localhost:8085/actuator/health | `{"status":"UP"}` |
| Notification | http://localhost:8086/actuator/health | `{"status":"UP"}` |
| Analytics | http://localhost:8087/actuator/health | `{"status":"UP"}` |
| RabbitMQ UI | http://localhost:15672 | User: `tourism` / Pass: `tourism123` |

### 6.2 Kiểm tra Eureka — tất cả service đã đăng ký

Mở http://localhost:8761 → phần **Instances currently registered with Eureka** phải hiển thị:
- `IAM-SERVICE`
- `TOUR-CATALOG-SERVICE`
- `API-GATEWAY`
- v.v.

---

## 7. Xử Lý Lỗi Thường Gặp

### ❌ Lỗi: `port is already allocated`

**Nguyên nhân:** Có process khác dùng cổng đó (PostgreSQL local đang chạy ở 5432, hoặc service khác).

```powershell
# Tìm process đang dùng cổng (ví dụ 5433)
netstat -ano | findstr :5433

# Kết thúc process theo PID
taskkill /PID <PID> /F
```

---

### ❌ Lỗi: Service `unhealthy` hoặc không lên

```powershell
# Xem log service đó
docker compose logs --tail=50 iam-service

# Restart service
docker compose restart iam-service
```

---

### ❌ Lỗi: `Cannot connect to the Docker daemon`

**Nguyên nhân:** Docker Desktop chưa khởi động.

→ Mở Docker Desktop, chờ icon system tray hiện `Docker Desktop is running`.

---

### ❌ Database không có trong pgAdmin

**Nguyên nhân:** Script `init-databases.sh` chỉ chạy khi volume chưa tồn tại (lần đầu tạo container).

```powershell
# Xóa volume và tạo lại (⚠️ mất toàn bộ data)
docker compose down -v
docker compose up -d postgres
docker compose logs -f postgres
# Chờ thấy: "✅ All 7 databases created successfully!"
```

---

### ❌ Lỗi kết nối pgAdmin: `could not connect to server`

Kiểm tra lại:
1. Container postgres đang chạy: `docker compose ps postgres`
2. Port đúng là **5433** (không phải 5432)
3. Host là **localhost** (không phải tên container)
4. Username/Password: `postgres` / `postgres`

---

### ❌ Service không tìm thấy nhau (Feign timeout)

**Nguyên nhân:** Service chưa đăng ký lên Eureka hoặc Eureka chưa sẵn sàng.

```powershell
# Kiểm tra eureka
curl http://localhost:8761/eureka/apps

# Restart service cần thiết
docker compose restart iam-service
```

---

## Tóm Tắt Nhanh (Quick Reference)

```powershell
# === Di chuyển vào thư mục ===
cd D:\HK8\tourism-microservices-backend

# === Chạy toàn bộ ===
docker compose up -d

# === Dừng toàn bộ ===
docker compose down

# === Xem trạng thái ===
docker compose ps

# === Xem log 1 service ===
docker compose logs -f <tên-service>

# === Build lại + chạy ===
docker compose up -d --build

# === Chỉ chạy infra (dev) ===
docker compose up -d postgres redis rabbitmq service-discovery
```

### Thông tin kết nối pgAdmin 4

```
Host:     localhost
Port:     5433
Database: postgres
Username: postgres
Password: postgres
```
