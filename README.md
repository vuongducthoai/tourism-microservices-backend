# 🌍 Future Travel — Tourism Microservices (Backend)

Hệ thống backend cho nền tảng đặt tour du lịch **Future Travel**, xây dựng theo kiến trúc **microservices** với **Spring Boot 3.2**, **Spring Cloud**, **PostgreSQL**, **Redis**, **RabbitMQ**, **Keycloak** và **Docker**. Hệ thống còn tích hợp **trợ lý ảo AI** (Google Gemini + Pinecone vector DB) theo mô hình RAG.

---

## 📋 Mục lục

- [Công nghệ sử dụng](#-công-nghệ-sử-dụng)
- [Yêu cầu](#-yêu-cầu)
- [Kiến trúc hệ thống](#-kiến-trúc-hệ-thống)
- [Các service & chức năng](#-các-service--chức-năng)
- [Chạy toàn bộ hệ thống](#-chạy-toàn-bộ-hệ-thống)
- [⚠️ Build lại sau khi sửa code (rất quan trọng)](#️-build-lại-sau-khi-sửa-code-rất-quan-trọng)
- [Chạy từng service riêng lẻ](#-chạy-từng-service-riêng-lẻ)
- [Cổng dịch vụ](#-cổng-dịch-vụ)
- [Databases](#-databases)
- [Biến môi trường](#-biến-môi-trường)
- [Các lệnh hữu ích](#-các-lệnh-hữu-ích)

---

## 🧰 Công nghệ sử dụng

| Nhóm | Công nghệ |
|---|---|
| Ngôn ngữ / Framework | Java 17, Spring Boot 3.2, Spring Cloud |
| Giao tiếp service | Spring Cloud Gateway, Eureka (Service Discovery), OpenFeign |
| Dữ liệu | PostgreSQL, Redis (cache), Spring Data JPA / Hibernate |
| Message queue | RabbitMQ (event-driven, đồng bộ chatbot, thông báo) |
| Xác thực | Keycloak, JWT |
| AI / Chatbot | Google Gemini (LLM) + Pinecone (vector database) — RAG |
| Đóng gói | Docker, Docker Compose |
| Build | Maven (multi-module) |

---

## ✅ Yêu cầu

| Công cụ | Phiên bản tối thiểu |
|---|---|
| Docker Desktop | 24+ |
| Docker Compose | v2+ |
| Java (JDK) | 17+ |
| Maven | 3.8+ |

> **Lưu ý:** Dockerfile của mỗi service **chỉ copy file `.jar`** đã build sẵn (`COPY target/*.jar`), **không** tự biên dịch. Vì vậy **bắt buộc phải cài Java + Maven** để build `.jar` trước khi `docker compose build`. Xem mục [Build lại sau khi sửa code](#️-build-lại-sau-khi-sửa-code-rất-quan-trọng).

---

## 🏗️ Kiến trúc hệ thống

```
Client (Web / Mobile)
  └── API Gateway (:8080/api)
        ├── IAM Service (:8081)           ← Xác thực, phân quyền, hồ sơ người dùng, xu thưởng
        ├── Tour Catalog Service (:8082)  ← Tour, lịch khởi hành, giá, địa điểm, đánh giá, chính sách
        ├── Booking Service (:8083)       ← Đặt tour, coupon giảm giá, hoàn tiền, Green Fund
        ├── Payment Service (:8084)       ← Thanh toán
        ├── Forum Service (:8085)         ← Diễn đàn cộng đồng
        ├── Notification Service (:8086)  ← Thông báo (email, realtime)
        └── Analytics Service (:8087)     ← Thống kê doanh thu, phân tích AI, đồng bộ chatbot (RAG)

Infrastructure:
  ├── Service Discovery / Eureka (:8761)
  ├── Config Server (:8888)
  ├── PostgreSQL (:5433)
  ├── Redis (:6379)
  ├── RabbitMQ (:5672 | Management UI :15672)
  └── Keycloak (xác thực)
```

Các service giao tiếp đồng bộ qua **OpenFeign** (ví dụ Booking gọi Tour Catalog để lấy giá/lịch) và bất đồng bộ qua **RabbitMQ** (thông báo, đồng bộ dữ liệu chatbot lên Pinecone).

---

## 🧩 Các service & chức năng

| Service | Chức năng chính |
|---|---|
| **IAM** | Đăng ký/đăng nhập, JWT, phân quyền, hồ sơ người dùng, ví xu thưởng |
| **Tour Catalog** | Quản lý tour, **lịch khởi hành** (giá theo loại khách, vận chuyển, chính sách), địa điểm, đánh giá |
| **Booking** | Đặt tour, **hệ thống coupon** (theo lịch khởi hành & toàn hệ thống, nhiều-nhiều, tự chọn mã giảm nhiều nhất), hủy/hoàn tiền, Green Fund |
| **Payment** | Xử lý thanh toán đơn đặt tour |
| **Forum** | Bài viết, bình luận, tương tác cộng đồng |
| **Notification** | Gửi email & thông báo realtime (RabbitMQ + WebSocket) |
| **Analytics** | Dashboard doanh thu, **phân tích bằng AI (Gemini)**, đồng bộ dữ liệu tour/coupon lên **Pinecone** cho trợ lý ảo (RAG) |

---

## 🚀 Chạy toàn bộ hệ thống

```bash
# Bước 1: Clone project (nếu chưa có)
git clone <repo-url>
cd Tourism_Microservices

# Bước 2: Tạo file .env ở thư mục gốc (xem mục "Biến môi trường")

# Bước 3: Build .jar cho tất cả module rồi khởi động
mvn -DskipTests package
docker compose up -d --build

# Bước 4: Kiểm tra trạng thái
docker compose ps
```

**Dừng toàn bộ:** `docker compose down`
**Dừng và xóa cả dữ liệu (volumes):** `docker compose down -v`

> Muốn **giữ dữ liệu** thì tránh dùng cờ `-v` (nó xóa named volumes của PostgreSQL, Redis, RabbitMQ, Keycloak).

---

## ⚠️ Build lại sau khi sửa code (rất quan trọng)

Dockerfile chỉ `COPY target/*.jar` — **không biên dịch code Java**. Nếu chỉ chạy `docker compose build` sau khi sửa code, nó sẽ đóng gói lại **file `.jar` cũ** → code mới không có tác dụng.

Quy trình đúng gồm **3 bước**: Maven build → Docker build → khởi động lại.

```bash
# Ví dụ build lại 2 service booking + tour-catalog
cd /d D:\Tourism_Microservices

# 1) Biên dịch ra .jar mới (-am để build luôn shared-library phụ thuộc)
mvn -DskipTests -pl booking-service,tour-catalog-service -am package

# 2) Đóng gói Docker image từ .jar mới
docker compose build booking-service tour-catalog-service

# 3) Khởi động lại container (force-recreate để chắc chắn nạp image mới)
docker compose up -d --force-recreate --no-deps booking-service tour-catalog-service
```

> 💡 Có sẵn file `rebuild-services.bat` (Windows) làm tự động cả 3 bước cho các service thường sửa. Nếu máy chưa có Maven, có thể build `.jar` bằng IntelliJ (panel Maven → Lifecycle → `package`, nhớ skip tests) rồi chạy 2 lệnh `docker compose` phía trên.

---

## 🔧 Chạy từng service riêng lẻ

> Luôn khởi động **infrastructure** trước, rồi tới **Service Discovery**, cuối cùng mới tới các service nghiệp vụ.

```bash
# 1) Infrastructure
docker compose up -d postgres redis rabbitmq

# 2) Service Discovery (Eureka) — http://localhost:8761
docker compose up -d service-discovery

# 3) Service muốn chạy — chọn 1 trong 2 cách:

#   Cách A — Docker
docker compose up -d tour-catalog-service

#   Cách B — Maven (dev/debug, hot-reload)
mvn install -pl shared-library -am          # build shared-library 1 lần
mvn spring-boot:run -pl tour-catalog-service
```

### Bảng tên service & lệnh nhanh

| Service | Docker Compose name | Maven module | Cổng |
|---|---|---|---|
| Service Discovery | `service-discovery` | `service-discovery` | 8761 |
| Config Server | `config-server` | `config-server` | 8888 |
| API Gateway | `api-gateway` | `api-gateway` | 8080 |
| IAM | `iam-service` | `iam-service` | 8081 |
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

## 📦 Databases

Khi PostgreSQL khởi động lần đầu, script khởi tạo sẽ tự tạo các database:

| Database | Dùng cho |
|---|---|
| `iam_db` | IAM Service |
| `tour_catalog_db` | Tour Catalog Service |
| `booking_db` | Booking Service |
| `payment_db` | Payment Service |
| `forum_db` | Forum Service |
| `notification_db` | Notification Service |
| `analytics_db` | Analytics Service |

> Có sẵn các file `data_dump.sql` / `schema_dump.sql` để nạp dữ liệu mẫu khi cần.

---

## 🔐 Biến môi trường

Tạo file `.env` ở thư mục gốc (cùng cấp `docker-compose.yml`). Các biến quan trọng cho trợ lý ảo AI:

```env
# Google Gemini
GEMINI_API_KEY=<your-key>

# Pinecone (vector DB cho chatbot RAG)
PINECONE_API_KEY=<your-key>
PINECONE_ENV=<your-env>
PINECONE_HOST=<your-index-host>
```

> ⚠️ Với Spring, biến `${ENV:default}` sẽ dùng **giá trị rỗng** (không phải default) khi env var được set nhưng để trống. Vì vậy phải điền đầy đủ các biến Pinecone, nếu không analytics-service sẽ lỗi đồng bộ chatbot.

---

## 🛠️ Các lệnh hữu ích

```bash
# Xem log của một service
docker compose logs -f tour-catalog-service

# Xem 100 dòng log gần nhất (debug lỗi)
docker compose logs --tail=100 tour-catalog-service

# Restart / dừng một service
docker compose restart <tên-service>
docker compose stop <tên-service>

# Xem container đang chạy & tài nguyên
docker compose ps
docker stats
```

---

## 💡 Workflow phát triển đề xuất

```
1. Chạy infrastructure bằng Docker:
   docker compose up -d postgres redis rabbitmq service-discovery

2. Chạy service đang phát triển bằng Maven (để debug, hot-reload):
   mvn spring-boot:run -pl <tên-service>

3. Các service còn lại (nếu cần) chạy bằng Docker.
```
