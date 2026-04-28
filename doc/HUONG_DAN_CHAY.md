# Hướng dẫn chạy Tourism Microservices

---

## Yêu cầu trước khi chạy

- **Docker Desktop** đang chạy
- **Java 17 + Maven** đã cài (để build JAR)
- Mở terminal tại thư mục: `D:\HK8\tourism-microservices-backend`

---

## BƯỚC 1 — Build tất cả JAR (bắt buộc trước lần đầu hoặc khi sửa code)

```powershell
cd D:\HK8\tourism-microservices-backend
mvn clean package -DskipTests
```

> Kết quả mong đợi: `BUILD SUCCESS` ở cuối. Sẽ mất 2–5 phút.

---

## BƯỚC 2 — Chạy TOÀN BỘ (1 lệnh)

```powershell
cd D:\HK8\tourism-microservices-backend
docker compose up -d --build
```

> `--build` tự rebuild Docker image từ JAR mới nhất.  
> Lần đầu chạy mất ~5–10 phút vì phải build 10 image.  
> Lần sau nhanh hơn vì image đã cache.

### Kiểm tra tất cả đang chạy:

```powershell
docker compose ps
```

Kết quả mong đợi — tất cả `STATUS` là `Up ... (healthy)`:

| Container | Port |
|---|---|
| tourism-postgres | 5433 |
| tourism-redis | 6379 |
| tourism-rabbitmq | 5672, 15672 |
| tourism-service-discovery | 8761 |
| tourism-config-server | 8888 |
| tourism-api-gateway | 8080 |
| tourism-iam-service | 8081 |
| tourism-tour-catalog-service | 8082 |
| tourism-booking-service | 8083 |
| tourism-payment-service | 8084 |
| tourism-forum-service | 8085 |
| tourism-notification-service | 8086 |
| tourism-analytics-service | 8087 |

---

## BƯỚC 3 — Chạy TỪNG BƯỚC (nếu muốn kiểm soát thứ tự)

Các service phụ thuộc nhau theo thứ tự sau. Phải đợi mỗi bước healthy trước khi chạy bước tiếp.

### 3.1 — Hạ tầng (Infrastructure)

```powershell
docker compose up -d postgres redis rabbitmq
```

Đợi healthy (~30 giây):
```powershell
docker compose ps postgres redis rabbitmq
```

### 3.2 — Service Discovery (Eureka)

```powershell
docker compose up -d --build service-discovery
```

Đợi healthy (~40 giây):
```powershell
docker compose ps service-discovery
```

Kiểm tra Eureka Dashboard: http://localhost:8761

### 3.3 — Config Server

```powershell
docker compose up -d --build config-server
```

Đợi healthy (~40 giây):
```powershell
docker compose ps config-server
```

### 3.4 — API Gateway

```powershell
docker compose up -d --build api-gateway
```

Đợi healthy (~50 giây):
```powershell
docker compose ps api-gateway
```

### 3.5 — Các Business Services (có thể chạy cùng lúc)

```powershell
docker compose up -d --build iam-service tour-catalog-service booking-service payment-service forum-service notification-service analytics-service
```

Đợi healthy (~60–90 giây):
```powershell
docker compose ps
```

---

## Dừng tất cả

```powershell
docker compose down
```

> Volume data (PostgreSQL, Redis, RabbitMQ) **vẫn được giữ lại**.

Dừng và **xóa hết data**:
```powershell
docker compose down -v
```

---

## Xem log

```powershell
# Xem log một service
docker compose logs -f service-discovery

# Xem log nhiều service cùng lúc
docker compose logs -f service-discovery config-server api-gateway

# Xem 50 dòng cuối
docker compose logs --tail=50 iam-service
```

---

## Khởi động lại một service

```powershell
docker compose restart iam-service
```

Nếu đã sửa code và cần rebuild:
```powershell
# Build lại JAR trước
cd D:\HK8\tourism-microservices-backend
mvn clean package -DskipTests -pl iam-service

# Rebuild image và restart
docker compose up -d --build iam-service
```

---

## Xử lý lỗi thường gặp

### Container ở trạng thái `unhealthy`

```powershell
# Xem log tìm nguyên nhân
docker compose logs --tail=50 <tên-service>
```

### Port đã bị chiếm (port conflict)

```powershell
# Kiểm tra process đang dùng port (ví dụ 8080)
netstat -ano | findstr :8080
```

### PostgreSQL không tạo được database

```powershell
# Xóa volume cũ và chạy lại
docker compose down -v
docker compose up -d postgres
```

### Lỗi `lstat /target: no such file or directory`

Chưa build JAR. Chạy lại:
```powershell
mvn clean package -DskipTests
```

---

## Các URL quan trọng

| Service | URL |
|---|---|
| Eureka Dashboard | http://localhost:8761 |
| API Gateway | http://localhost:8080 |
| Config Server | http://localhost:8888 |
| RabbitMQ Management | http://localhost:15672 (user: `tourism` / pass: `tourism123`) |
| pgAdmin 4 (kết nối DB) | Host: `localhost`, Port: `5433`, User: `postgres`, Pass: `postgres` |

---

## Kết nối pgAdmin 4

1. Mở pgAdmin 4
2. Chuột phải **Servers** → **Register** → **Server...**
3. Tab **General**: Name = `Tourism Docker`
4. Tab **Connection**:
   - Host: `localhost`
   - Port: `5433`
   - Username: `postgres`
   - Password: `postgres`
5. Click **Save**

Các database có sẵn: `iam_db`, `tour_catalog_db`, `booking_db`, `payment_db`, `forum_db`, `notification_db`, `analytics_db`
