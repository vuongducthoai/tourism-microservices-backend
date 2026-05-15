# REPORT: Queue Health Monitor & Vietnam Timezone Fix

**Ngày hoàn thành:** 15/05/2026  
**Phạm vi:** booking-service · DeadEventsPage frontend · Unit tests · Timezone

---

## 1. Tổng quan

Session này bổ sung 3 nhóm thay đổi:

1. **Timezone** – Đồng bộ múi giờ `Asia/Ho_Chi_Minh` cho toàn bộ 10 Java services trong Docker Compose.
2. **Queue Health API** – Endpoint mới `GET /api/bookings/admin/outbox/rabbitmq-health` trả về tình trạng RabbitMQ thực tế theo business terms.
3. **Frontend improvements** – DeadEventsPage dùng ngôn ngữ nghiệp vụ, BookingItem hiển thị trạng thái hoàn xu, TransactionDetailModal thay emoji bằng icon chuẩn.

---

## 2. Timezone Fix

### Vấn đề
Timestamps trong log và database (PostgreSQL `TIMESTAMPTZ`) lưu theo UTC, nhưng ứng dụng Spring Boot trong container cũng chạy theo UTC → thời gian hiển thị trên UI sai 7 tiếng.

### Giải pháp
Thêm biến môi trường `TZ: Asia/Ho_Chi_Minh` vào tất cả 10 Java services trong `docker-compose.yml`:

```yaml
# api-gateway, booking-service, iam-service, tour-catalog-service,
# forum-service, notification-service, payment-service, analytics-service,
# config-server, service-discovery
environment:
  TZ: Asia/Ho_Chi_Minh
```

Sau khi áp dụng, tất cả timestamp trong log Spring Boot hiển thị đúng giờ Việt Nam (`+07:00`).

---

## 3. Queue Health Monitor API

### 3.1 Mục tiêu
Cho phép admin xem tình trạng thực tế của RabbitMQ message queue từ giao diện web, không cần truy cập RabbitMQ Management UI.

### 3.2 Các file mới/chỉnh sửa – Backend

| File | Trạng thái | Mô tả |
|------|-----------|-------|
| `booking-service/src/main/java/com/tourism/booking/dto/response/QueueHealthResponse.java` | **MỚI** | DTO `@Data @Builder` chứa: queue, ready, unacked, consumers, dlqReady, status, message, checkedAt |
| `booking-service/src/main/java/com/tourism/booking/service/QueueHealthService.java` | **MỚI** | Gọi RabbitMQ Management HTTP API, trả về trạng thái nghiệp vụ |
| `booking-service/src/main/resources/application.yml` | **CẬP NHẬT** | Thêm `rabbitmq.management.host/port/username/password` (đọc từ env vars) |
| `booking-service/src/main/java/com/tourism/booking/controller/DeadEventAdminController.java` | **CẬP NHẬT** | Thêm `GET /rabbitmq-health` endpoint |

### 3.3 Logic QueueHealthService

Gọi 2 URL RabbitMQ Management API:
- `http://{host}:{port}/api/queues/%2F/booking.notification.queue` — queue chính
- `http://{host}:{port}/api/queues/%2F/booking.notification.dlq` — dead letter queue

Kết hợp để trả về một trong 5 trạng thái:

| Status | Điều kiện | Ý nghĩa |
|--------|-----------|---------|
| `HEALTHY` | ready=0, consumers≥1, dlqReady=0 | Hệ thống hoạt động bình thường |
| `BACKLOG` | ready>0, consumers≥1 | Có tin chờ xử lý nhưng consumer đang chạy |
| `CONSUMER_DOWN` | consumers=0 (bất kể ready) | Notification-service đang tắt — kể cả khi queue tạm thời rỗng |
| `DLQ_ATTENTION` | dlqReady>0 | Có tin bị lỗi cần xem xét (ưu tiên cao nhất) |
| `BROKER_DOWN` | Kết nối thất bại (exception) | Không thể kết nối RabbitMQ Management API |

### 3.4 Bug Fix: RestTemplate double-encoding %2F

**Vấn đề:** Khi truyền URL dạng string vào `restTemplate.exchange(String, ...)`, Spring's `UriTemplate` sẽ encode lại `%2F` thành `%252F`, dẫn đến RabbitMQ API trả về `404 Not Found` cho cả queue chính lẫn DLQ. Kết quả là mọi request đều trả về `BROKER_DOWN`.

**Fix:** Dùng `URI.create()` để tạo URI đã được pre-encoded, bỏ qua bước encode của RestTemplate:

```java
// Trước (sai):
String url = String.format("http://%s:%d/api/queues/%%2F/%s", host, port, queue);
restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

// Sau (đúng):
URI uri = URI.create(String.format("http://%s:%d/api/queues/%%2F/%s", host, port, queue));
restTemplate.exchange(uri, HttpMethod.GET, entity, Map.class);
```

### 3.5 application.yml

```yaml
rabbitmq:
  management:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_MANAGEMENT_PORT:15672}
    username: ${RABBITMQ_USERNAME:tourism}
    password: ${RABBITMQ_PASSWORD:tourism123}
```

### 3.6 API Response mẫu

```json
{
  "queue": "booking.notification.queue",
  "ready": 0,
  "unacked": 0,
  "consumers": 1,
  "dlqReady": 0,
  "status": "HEALTHY",
  "message": "Hàng đợi thông báo hoạt động bình thường",
  "checkedAt": "2026-05-15T17:43:00"
}
```

---

## 4. Frontend Changes

### 4.1 DeadEventsPage

- **Ngôn ngữ nghiệp vụ:** Thay routing key kỹ thuật (`booking.payment.refund.coins`) bằng nhãn người dùng (`Hoàn xu khi hủy tour`)
- **Queue Health section:** Hiển thị card tình trạng queue với badge màu theo status
- **Hook `useQueueHealth`:** Tự động poll API `/rabbitmq-health` mỗi 30 giây
- **AdminSidebar:** Đổi tên menu từ "DEAD Events" → "Sự cố xử lý nền"

### 4.2 BookingItem – coinRefundStatus badge

Thêm mini badge bên dưới status chính hiển thị trạng thái hoàn xu:

| Trạng thái DB | Badge hiển thị | Màu |
|--------------|----------------|-----|
| `PENDING` | `⬡ Đang hoàn xu` | Amber |
| `COMPLETED` | `✓ Đã hoàn xu` | Xanh lá |
| `FAILED` | `⚠ Hoàn xu lỗi` | Đỏ |

### 4.4 Frontend build

- Fixed duplicate `export default DeadEventsPage` trong `DeadEventsPage.jsx` (xóa block cuối thừa)
- Fixed duplicate `const DeadEventItem` trong `DeadEventItem.jsx` (xóa component cũ dùng raw routing key)
- Fixed import path trong `useQueueHealth.ts`: thêm `.ts` extension cho nhất quán với các hook khác
- **Frontend build thành công**: `npm run build` → `603.81 kB build/static/js/main.js`

### 4.5 TransactionDetailModal – Coin format + Email text + Coin format

**Icons:** Thay thế emoji bằng lucide-react icons chuẩn:

| Emoji cũ | Icon mới |
|---------|---------|
| 🪙 | `<Coins>` |
| 💰 | `<DollarSign>` |
| ⏳ | `<Clock3>` |
| ✅ | `<CheckCircle2>` |
| ⚠️ | `<AlertTriangle>` |

Thêm notification hint (`<Bell>` icon + blue info box) cho booking có trạng thái `CANCELLED`.

---

## 5. Unit Tests

### Tổng kết test coverage

| Test class | Số tests | Kết quả |
|-----------|---------|---------|
| `ApplicationTests` | 1 | ✅ Pass |
| `OutboxEventRepositoryTest` | 14 | ✅ Pass |
| `BookingServiceTest` | 28 | ✅ Pass |
| `OutboxProcessorTest` | 10 | ✅ Pass |
| `BookingStatusServiceTest` | 9 | ✅ Pass |
| `BookingNotificationPublisherTest` | 14 | ✅ Pass |
| `QueueHealthServiceTest` | 9 | ✅ Pass |
| `DeadEventAdminControllerTest` | 7 | ✅ Pass |
| **Tổng** | **92** | ✅ **BUILD SUCCESS** |

### QueueHealthServiceTest (9 tests)

Dùng `ReflectionTestUtils.setField()` inject mock `RestTemplate`. Stubs dùng `argThat((URI uri) -> uri.toString().contains(...))` vì service dùng `URI.create()`:

- `HEALTHY`: queue rỗng, có consumer, dlq rỗng
- `BACKLOG`: có message, có consumer
- `CONSUMER_DOWN` (với message): `ready=3, consumers=0`
- `CONSUMER_DOWN` (không message): `ready=0, consumers=0` — **hành vi mới**
- `DLQ_ATTENTION`: dlq có message (ưu tiên hơn CONSUMER_DOWN)
- `DLQ_ATTENTION` wins over CONSUMER_DOWN
- `BROKER_DOWN`: network exception
- Queue name chính xác
- Unacked value được populate

### DeadEventAdminControllerTest (7 tests)

Dùng `@ExtendWith(MockitoExtension.class)` + `MockMvcBuilders.standaloneSetup()` (không load Spring context). Fix key issues:
- `ObjectMapper` cần `findAndRegisterModules()` + `JavaTimeModule` + `MappingJackson2HttpMessageConverter` để serialize `Page<T>`
- `PageImpl` cần `PageRequest.of(0, 20)` (không thể dùng `PageImpl(List.of())` không có pageable)

---

## 6. Deployment

```bash
# Build JAR
cd booking-service
mvn clean package -DskipTests

# Rebuild Docker image
cd ..
docker compose build booking-service

# Restart container (không ảnh hưởng services khác)
docker compose up -d --no-deps booking-service
```

Kiểm tra sau deploy:
```powershell
Invoke-RestMethod "http://localhost:8080/api/bookings/admin/outbox/rabbitmq-health"
# Expected: {"status":"HEALTHY",...}
```
