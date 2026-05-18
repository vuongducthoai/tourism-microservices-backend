# 🌍 Tourism Microservices

Dự án backend microservices cho hệ thống quản lý du lịch, xây dựng bằng **Spring Boot 3.2**, **Spring Cloud**, **PostgreSQL**, **Redis**, **RabbitMQ** và **Docker**.

---

## 📋 Mục lục

- [Yêu cầu](#-yêu-cầu)
- [Kiến trúc hệ thống](#-kiến-trúc-hệ-thống)
- [Chạy toàn bộ hệ thống](#-chạy-toàn-bộ-hệ-thống)
- [Chạy từng service riêng lẻ](#-chạy-từng-service-riêng-lẻ)
- [Cổng dịch vụ](#-cổng-dịch-vụ)
- [Các lệnh hữu ích](#-các-lệnh-hữu-ích)

---

## ✅ Yêu cầu

| Công cụ | Phiên bản tối thiểu |
|---|---|
| Docker Desktop | 24+ |
| Docker Compose | v2+ |
| Java (JDK) | 17+ |
| Maven | 3.8+ |

> **Lưu ý:** Nếu chỉ chạy bằng Docker thì **không cần** cài Java và Maven trên máy.

---

## 🏗️ Kiến trúc hệ thống

```
Client
  └── API Gateway (:8080)
        ├── IAM Service (:8081)           ← Xác thực & Phân quyền
        ├── Tour Catalog Service (:8082)  ← Quản lý tour du lịch
        ├── Booking Service (:8083)       ← Đặt tour
        ├── Payment Service (:8084)       ← Thanh toán
        ├── Forum Service (:8085)         ← Diễn đàn
        ├── Notification Service (:8086)  ← Thông báo
        └── Analytics Service (:8087)    ← Phân tích dữ liệu

Infrastructure:
  ├── Service Discovery / Eureka (:8761)
  ├── Config Server (:8888)
  ├── PostgreSQL (:5433)
  ├── Redis (:6379)
  └── RabbitMQ (:5672 | Management UI :15672)
```

---

## 🚀 Chạy toàn bộ hệ thống

> Cách này khởi động tất cả infrastructure + services cùng một lúc.

```bash
# Bước 1: Clone project (nếu chưa có)
git clone <repo-url>
cd Tourism_Microservices

# Bước 2: Build tất cả Docker images và chạy
docker-compose up -d --build

# Bước 3: Kiểm tra trạng thái
docker-compose ps
```

**Dừng toàn bộ:**
```bash
docker-compose down
```

**Dừng và xóa cả dữ liệu (volumes):**
```bash
docker-compose down -v
```

---

## 🔧 Chạy từng service riêng lẻ

> **Quan trọng:** Các services nghiệp vụ phụ thuộc vào infrastructure (PostgreSQL, Redis, RabbitMQ) và service-discovery. Hãy **luôn khởi động infrastructure trước**.

### Bước 1 — Khởi động Infrastructure (bắt buộc)

```bash
docker-compose up -d postgres redis rabbitmq
```

Chờ các container healthy (khoảng 15-30 giây), kiểm tra:
```bash
docker-compose ps
```

---

### Bước 2 — Khởi động Service Discovery (Eureka)

> Tất cả services phải đăng ký vào Eureka để giao tiếp với nhau.

```bash
docker-compose up -d service-discovery
```

Truy cập Eureka Dashboard: http://localhost:8761

---

### Bước 3 — Chạy service bạn muốn phát triển

Chọn **một trong hai cách** tùy nhu cầu:

#### 🐳 Cách A: Chạy bằng Docker (đơn giản)

```bash
# Chạy một service cụ thể
docker-compose up -d <tên-service>

# Ví dụ:
docker-compose up -d iam-service
docker-compose up -d tour-catalog-service
docker-compose up -d booking-service
```

#### ☕ Cách B: Chạy trực tiếp bằng Maven (cho phát triển / debug)

> Cách này giúp bạn hot-reload và debug dễ hơn, không cần rebuild Docker image mỗi lần sửa code.

```bash
# Build shared-library trước (chỉ cần làm 1 lần hoặc khi thay đổi shared-library)
mvn install -pl shared-library -am

# Chạy service cụ thể
mvn spring-boot:run -pl <tên-module>

# Ví dụ:
mvn spring-boot:run -pl iam-service
mvn spring-boot:run -pl tour-catalog-service
mvn spring-boot:run -pl booking-service
mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl forum-service
mvn spring-boot:run -pl notification-service
mvn spring-boot:run -pl analytics-service
```

> **Lưu ý khi dùng Cách B:** Service Discovery phải đang chạy (Docker). Các biến môi trường sẽ dùng giá trị mặc định trong `application.yml` — đảm bảo host/port của PostgreSQL, Redis, RabbitMQ khớp (thường là `localhost`).

---

### Bảng tên service & lệnh nhanh

| Service | Docker Compose name | Maven module | Cổng |
|---|---|---|---|
| Service Discovery | `service-discovery` | `service-discovery` | 8761 |
| Config Server | `config-server` | `config-server` | 8888 |
| API Gateway | `api-gateway` | `api-gateway` | 8080 |
| IAM Service | `iam-service` | `iam-service` | 8081 |
| Tour Catalog | `tour-catalog-service` | `tour-catalog-service` | 8082 |
| Booking | `booking-service` | `booking-service` | 8083 |
| Payment | `payment-service` | `payment-service` | 8084 |
| Forum | `forum-service` | `forum-service` | 8085 |
| Notification | `notification-service` | `notification-service` | 8086 |
| Analytics | `analytics-service` | `analytics-service` | 8087 |

---

## 🌐 Cổng dịch vụ

| Dịch vụ | URL |
|---|---|
| API Gateway | http://localhost:8080 |
| Eureka Dashboard | http://localhost:8761 |
| Config Server | http://localhost:8888 |
| RabbitMQ Management | http://localhost:15672 (user: `tourism` / pass: `tourism123`) |
| PostgreSQL | `localhost:5433` (user: `postgres` / pass: `postgres`) |
| Redis | `localhost:6379` |

---

## 🛠️ Các lệnh hữu ích

```bash
# Xem log của một service
docker-compose logs -f <tên-service>
# Ví dụ:
docker-compose logs -f iam-service

# Restart một service
docker-compose restart <tên-service>

# Dừng một service
docker-compose stop <tên-service>

# Build lại image của một service (sau khi sửa code)
docker-compose up -d --build <tên-service>

# Xem tất cả container đang chạy
docker-compose ps

# Xem tài nguyên CPU/RAM của containers
docker stats
```

---

## 📦 Databases

Khi PostgreSQL khởi động lần đầu, script `docker/postgres/init-databases.sh` sẽ tự động tạo các database sau:

| Database | Dùng cho |
|---|---|
| `iam_db` | IAM Service |
| `tour_catalog_db` | Tour Catalog Service |
| `booking_db` | Booking Service |
| `payment_db` | Payment Service |
| `forum_db` | Forum Service |
| `notification_db` | Notification Service |
| `analytics_db` | Analytics Service |

---

## 💡 Gợi ý workflow phát triển

```
1. Chạy infrastructure bằng Docker:
   docker-compose up -d postgres redis rabbitmq service-discovery

2. Chạy service đang phát triển bằng Maven (để debug):
   mvn spring-boot:run -pl <tên-service>

3. Các service còn lại (nếu cần) chạy bằng Docker:
   docker-compose up -d <service-khác>
```

> Cách này giúp bạn không phải rebuild Docker image mỗi khi sửa code, tăng tốc độ phát triển đáng kể.
