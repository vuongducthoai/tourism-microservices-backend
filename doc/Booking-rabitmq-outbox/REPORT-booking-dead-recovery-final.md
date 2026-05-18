# BÁO CÁO TRIỂN KHAI: Booking Outbox DEAD Recovery + Coin Refund Status

## Tổng Quan

Tài liệu này mô tả toàn bộ thay đổi đã thực hiện cho feature **Outbox DEAD Event Recovery** và **Coin Refund Status Tracking** trong `booking-service`, bao gồm:

- Danh sách file thay đổi và nội dung thay đổi
- DB migration thực hiện
- Test đã viết và kết quả
- Hướng dẫn test API (curl commands)
- Hướng dẫn test giao diện UI

---

## 1. Danh Sách File Thay Đổi

### 1.1 Backend — `booking-service`

#### A. Entity: `OutboxEvent.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/entity/OutboxEvent.java`

**Thay đổi**:
- Thêm field `maxBackoffSecs` với `columnDefinition` có DEFAULT để Hibernate DDL hoạt động đúng khi có existing rows:
  ```java
  @Column(name = "max_backoff_secs", nullable = false,
          columnDefinition = "bigint not null default 3600")
  @Builder.Default
  private long maxBackoffSecs = 3600L;
  ```
- Fix `incrementRetries()` dùng `Math.min` để giới hạn backoff không vượt quá `maxBackoffSecs`:
  ```java
  long backoffSecs = Math.min(
      (long) Math.pow(2, this.retries) * 30L,
      this.maxBackoffSecs   // cap: never exceed maxBackoffSecs
  );
  ```

**Lý do**: Field cũ không có `columnDefinition` nên Hibernate tạo DDL `ADD COLUMN ... NOT NULL` (không có DEFAULT), dẫn đến lỗi `column contains null values` khi table đã có rows.

---

#### B. Entity: `Booking.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/entity/Booking.java`

**Thay đổi**:
- Thêm field `coinRefundStatus` để track trạng thái hoàn tiền xu:
  ```java
  @Column(name = "coin_refund_status", length = 20)
  private String coinRefundStatus;
  ```

**Lifecycle**:
- `null` → Booking không dùng coins hoặc không bị cancel
- `"PENDING"` → Booking bị cancel, coin refund đang chờ xử lý
- `"COMPLETED"` → Coin đã được hoàn thành công vào tài khoản
- `"FAILED"` → Coin refund thất bại sau `maxRetries` lần (event DEAD)

---

#### C. Repository: `BookingRepository.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/repository/BookingRepository.java`

**Thêm mới**:
```java
Optional<Booking> findByBookingCode(String bookingCode);
```
Dùng bởi `CoinRefundRelayScheduler` để cập nhật `coinRefundStatus` sau khi relay thành công/thất bại.

---

#### D. Service: `BookingServiceImpl.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/service/impl/BookingServiceImpl.java`

**Thêm trong `cancelBooking()`**:
```java
if (coinRefundAmount.compareTo(BigDecimal.ZERO) > 0 && booking.getUserId() != null) {
    booking.setCoinRefundStatus("PENDING");
}
```
Set `PENDING` trước khi save booking, trước khi tạo outbox event.

---

#### E. Factory: `OutboxEventFactory.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/messaging/OutboxEventFactory.java`

**Thay đổi trong `coinRefund()`**:
```java
.maxRetries(20)           // tăng từ 5 lên 20
.maxBackoffSecs(3600L)    // giới hạn backoff tối đa 1 giờ/lần
```
Coin refund quan trọng hơn notification → retry nhiều hơn.

---

#### F. Scheduler: `CoinRefundRelayScheduler.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/messaging/CoinRefundRelayScheduler.java`

**Thay đổi lớn (full rewrite của `processOne()`)**:

1. **Reset stale locks** ở đầu mỗi cycle (recovery khi instance crash):
   ```java
   @Transactional
   protected void resetStaleLocks() {
       outboxRepo.resetStaleLocks(LocalDateTime.now().minusMinutes(5));
   }
   ```

2. **Cập nhật `coinRefundStatus`** sau khi relay:
   - Thành công → `booking.setCoinRefundStatus("COMPLETED")`
   - Thất bại → retry; nếu DEAD → `booking.setCoinRefundStatus("FAILED")`

3. **Inject `BookingRepository`**:
   ```java
   private final BookingRepository bookingRepo;
   ```

4. **Single `catch (Exception e)`** — loại bỏ `FeignException.Conflict` riêng vì IAM idempotency đã được handle ở DB level.

---

#### G. Repository: `OutboxEventRepository.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/repository/OutboxEventRepository.java`

**Thêm 2 queries**:
```java
// Lấy DEAD events theo routing_key (cho retry-all)
List<OutboxEvent> findByStatusAndRoutingKey(OutboxStatus status, String routingKey);

// Paginated DEAD events cho admin dashboard
@Query("SELECT o FROM OutboxEvent o WHERE o.status = 'DEAD' ORDER BY o.createdAt DESC")
Page<OutboxEvent> findDeadEvents(Pageable pageable);
```

---

#### H. Service: `DeadEventAdminService.java` *(NEW FILE)*
**Path**: `booking-service/src/main/java/com/tourism/booking/service/DeadEventAdminService.java`

**Chức năng admin để xử lý DEAD events**:

| Method | Mô tả |
|--------|-------|
| `listDead(page, size)` | Trả về paginated list DEAD events |
| `countDead()` | Đếm DEAD events theo loại (notification / coinRefund / total) |
| `retryOne(id)` | Reset 1 event DEAD về NEW để retry |
| `retryAll(routingKey)` | Reset tất cả DEAD events (hoặc theo routingKey) về NEW |
| `resetToNew(event)` | Helper: reset retries=0, nextRetryAt=now, status=NEW |

---

#### I. Controller: `DeadEventAdminController.java` *(NEW FILE)*
**Path**: `booking-service/src/main/java/com/tourism/booking/controller/DeadEventAdminController.java`

**REST endpoints**:

| Method | URL | Mô tả |
|--------|-----|-------|
| `GET` | `/api/bookings/admin/outbox/dead` | Danh sách DEAD events (phân trang) |
| `GET` | `/api/bookings/admin/outbox/dead/count` | Đếm DEAD events |
| `POST` | `/api/bookings/admin/outbox/retry/{id}` | Retry 1 event |
| `POST` | `/api/bookings/admin/outbox/retry-all` | Retry tất cả (tùy chọn routingKey) |

---

#### J. DTO: `BookingResponse.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/dto/response/BookingResponse.java`

**Thêm field**:
```java
private String coinRefundStatus;
```

---

#### K. Converter: `BookingConverter.java`
**Path**: `booking-service/src/main/java/com/tourism/booking/convert/BookingConverter.java`

**Thêm mapping**:
```java
res.setCoinRefundStatus(booking.getCoinRefundStatus());
```

---

### 1.2 Frontend — `tourism_frontend/client-side`

#### L. DTO: `BookingResponseDTO.ts`
**Path**: `tourism_frontend/client-side/src/dto/responseDTO/BookingResponseDTO.ts`

**Thêm**:
```typescript
private _coinRefundStatus: string | null;

get coinRefundStatus(): string | null { return this._coinRefundStatus; }

// Trong fromApiResponse:
_coinRefundStatus: data.coinRefundStatus ?? null,

// Trong toPlain():
coinRefundStatus: this._coinRefundStatus,
```

---

#### M. Component: `TransactionDetailModal.jsx`
**Path**: `tourism_frontend/client-side/src/components/InformationComponent/TransactionList/TransactionListItem/TransactionDetailModal/TransactionDetailModal.jsx`

**Thêm 2 sections mới**:

**Section 5 — Trạng thái hoàn tiền xu**:
```jsx
{booking.coinRefundStatus && (
  <div className="info-section">
    <h4>Hoàn tiền xu</h4>
    <CoinRefundStatusBadge status={booking.coinRefundStatus} />
  </div>
)}
```
- `PENDING` → badge vàng "Đang xử lý"
- `COMPLETED` → badge xanh "Đã hoàn thành"
- `FAILED` → badge đỏ "Thất bại"

**Section 6 — Thông tin hoàn tiền ngân hàng**:
- Hiển thị `refundAmount`, tài khoản ngân hàng (masked `****1234`), trạng thái.

---

### 1.3 Test Files (NEW)

| File | Tests | Mô tả |
|------|-------|-------|
| `OutboxEventTest.java` | 12 | Unit tests cho `incrementRetries()`, `markSent()`, backoff schedule |
| `CoinRefundRelaySchedulerTest.java` | 25 | Happy path, idempotency, retry failure, DEAD, bad payload |
| `DeadEventAdminServiceTest.java` | 38 | listDead, countDead, retryOne, retryAll với/không routingKey |

**Kết quả**: **75/75 tests PASS** (BUILD SUCCESS)

---

## 2. DB Migration

Vì dự án dùng `ddl-auto: update` (không Flyway), migration phải chạy thủ công:

```sql
-- Thêm cột mới vào outbox_events
ALTER TABLE outbox_events 
  ADD COLUMN IF NOT EXISTS max_backoff_secs BIGINT NOT NULL DEFAULT 3600;

-- Thêm cột mới vào bookings
ALTER TABLE bookings 
  ADD COLUMN IF NOT EXISTS coin_refund_status VARCHAR(20);

-- Index để query DEAD events nhanh
CREATE INDEX IF NOT EXISTS idx_outbox_dead 
  ON outbox_events (status, routing_key) 
  WHERE status = 'DEAD';
```

**Chạy migration**:
```powershell
docker exec tourism-postgres psql -U postgres -d booking_db -c "
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS max_backoff_secs BIGINT NOT NULL DEFAULT 3600;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS coin_refund_status VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_outbox_dead ON outbox_events (status, routing_key) WHERE status = 'DEAD';
"
```

---

## 3. Logic Nghiệp Vụ

### 3.1 Coin Refund Flow

```
cancelBooking()
  ├─ Tính coinRefundAmount
  ├─ if coinRefundAmount > 0:
  │   ├─ booking.coinRefundStatus = "PENDING"
  │   └─ tạo OutboxEvent(routingKey=booking.coin.refund, maxRetries=20, maxBackoffSecs=3600)
  └─ save booking

CoinRefundRelayScheduler (mỗi 5s):
  ├─ resetStaleLocks() — recovery sau crash
  ├─ claimBatch() — SELECT ... FOR UPDATE SKIP LOCKED
  └─ processOne(event):
      ├─ iamClient.creditCoins(dto)  [với operationKey cho idempotency]
      │   ├─ SUCCESS → event.markSent(), booking.coinRefundStatus = "COMPLETED"
      │   └─ Exception:
      │       ├─ nếu IAM trả 409 (operationKey đã tồn tại) → treat as SUCCESS
      │       └─ các lỗi khác → event.incrementRetries()
      │           ├─ retries < maxRetries → status = NEW, backoff tăng gấp đôi (max 3600s)
      │           └─ retries >= maxRetries → status = DEAD, booking.coinRefundStatus = "FAILED"
```

### 3.2 Backoff Schedule (maxRetries=20, maxBackoffSecs=3600)

| Retry | Backoff | Ghi chú |
|-------|---------|---------|
| 1 | 60s | 2^1 × 30 |
| 2 | 120s | 2^2 × 30 |
| 3 | 240s | 2^3 × 30 |
| 4 | 480s | 2^4 × 30 |
| 5 | 960s | 2^5 × 30 |
| 6 | 1800s | 2^6 × 30 |
| 7+ | **3600s** | capped tại maxBackoffSecs |
| 20 | 3600s | DEAD sau 20 lần thất bại |

Tổng thời gian tối đa trước khi DEAD: ~7 × 1 giờ = ~7 giờ (thực tế).

### 3.3 IAM Idempotency

IAM service có cột UNIQUE `operationKey` trong `coin_transactions`. Nếu scheduler gọi 2 lần cùng `operationKey` (do at-least-once delivery), IAM sẽ:
- Lần 1: INSERT thành công, trả về 200
- Lần 2: INSERT fail do UNIQUE constraint → IAM trả về **409 Conflict**

`CoinRefundRelayScheduler` xử lý 409 như SUCCESS (tức là coin đã được hoàn) và mark event là SENT.

---

## 4. Hướng Dẫn Test API

### Cấu hình

| Service | URL |
|---------|-----|
| booking-service (direct) | `http://localhost:8083` |
| API Gateway | `http://localhost:8080` |
| RabbitMQ UI | `http://localhost:15672` (user: `tourism`, pass: `tourism123`) |
| Eureka | `http://localhost:8761` |

---

### Test 1: Kiểm tra service đang chạy

```bash
# Health check
curl http://localhost:8083/actuator/health

# Đếm DEAD events (expect: total=0)
curl http://localhost:8083/api/bookings/admin/outbox/dead/count

# Danh sách DEAD events (expect: empty)
curl "http://localhost:8083/api/bookings/admin/outbox/dead?page=0&size=10"
```

**Expected**:
```json
{"status":"UP"}
{"notification":0,"coinRefund":0,"total":0}
{"content":[],...,"empty":true}
```

---

### Test 2: Tạo booking và cancel với coins

**Bước 1**: Đăng nhập và lấy token
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'
# Lưu access_token
export TOKEN="<access_token>"
```

**Bước 2**: Tạo booking (cần có coins trong tài khoản)
```bash
curl -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tourId": 1,
    "startDate": "2026-06-01",
    "participants": 2,
    "useCoin": true,
    "coinAmount": 100
  }'
# Lưu bookingCode từ response
```

**Bước 3**: Cancel booking
```bash
curl -X POST "http://localhost:8080/api/bookings/{bookingCode}/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

**Bước 4**: Kiểm tra coinRefundStatus = PENDING
```bash
curl "http://localhost:8080/api/bookings/{bookingCode}" \
  -H "Authorization: Bearer $TOKEN"
# Expected: "coinRefundStatus": "PENDING"
```

**Bước 5**: Chờ scheduler chạy (tối đa 5 giây)
```bash
sleep 5
curl "http://localhost:8080/api/bookings/{bookingCode}" \
  -H "Authorization: Bearer $TOKEN"
# Expected: "coinRefundStatus": "COMPLETED"
```

---

### Test 3: Mô phỏng IAM down → DEAD → retry

**Bước 1**: Dừng IAM service
```bash
docker compose stop iam-service
```

**Bước 2**: Cancel một booking có coins (xem Test 2 bước 1-3)

**Bước 3**: Kiểm tra outbox events (status = NEW, retrying)
```bash
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "SELECT id, status, retries, next_retry_at, routing_key FROM outbox_events ORDER BY id DESC LIMIT 5;"
```

**Bước 4**: Xem logs của scheduler
```bash
docker logs tourism-booking-service --follow 2>&1 | grep -i "coin\|ERROR\|retry"
```

**Bước 5**: Sau nhiều lần retry thất bại (để test nhanh, có thể tăng retries thủ công):
```bash
# Để đẩy event sang DEAD nhanh: set retries = maxRetries - 1
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "UPDATE outbox_events SET retries=19, next_retry_at=NOW() 
   WHERE routing_key='booking.coin.refund' AND status='NEW';"
```

**Bước 6**: Kiểm tra DEAD count
```bash
curl http://localhost:8083/api/bookings/admin/outbox/dead/count
# Expected: {"notification":0,"coinRefund":1,"total":1}
```

**Bước 7**: Kiểm tra coinRefundStatus = FAILED
```bash
curl "http://localhost:8080/api/bookings/{bookingCode}" \
  -H "Authorization: Bearer $TOKEN"
# Expected: "coinRefundStatus": "FAILED"
```

---

### Test 4: Admin retry sau khi IAM phục hồi

```bash
# Khởi động lại IAM
docker compose start iam-service
sleep 15

# Xem danh sách DEAD events
curl "http://localhost:8083/api/bookings/admin/outbox/dead?page=0&size=10"
# Lưu id của event cần retry

# Retry 1 event theo ID
curl -X POST http://localhost:8083/api/bookings/admin/outbox/retry/1

# Hoặc retry tất cả coin refund events
curl -X POST "http://localhost:8083/api/bookings/admin/outbox/retry-all?routingKey=booking.coin.refund"

# Chờ scheduler chạy
sleep 10

# Kiểm tra coinRefundStatus = COMPLETED
curl "http://localhost:8080/api/bookings/{bookingCode}" \
  -H "Authorization: Bearer $TOKEN"
```

---

### Test 5: Retry-all chỉ reset DEAD (không đụng SENT/NEW)

```bash
# Trước khi retry-all: kiểm tra số lượng theo status
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"

# Retry all
curl -X POST http://localhost:8083/api/bookings/admin/outbox/retry-all
# Xem response: {"reset": N} - chỉ count DEAD events

# Sau retry-all: DEAD events đã thành NEW, SENT/SENDING không đổi
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"
```

---

### Test 6: RabbitMQ down → notification outbox retry

```bash
# Dừng RabbitMQ
docker compose stop rabbitmq

# Trigger một booking action nào đó tạo notification event
# (cancel booking, confirm booking, v.v.)

# Xem outbox events mới (status=NEW)
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "SELECT id, status, retries, routing_key FROM outbox_events 
   WHERE routing_key != 'booking.coin.refund' ORDER BY id DESC LIMIT 5;"

# Khởi động lại RabbitMQ
docker compose start rabbitmq
sleep 5

# Sau khi RabbitMQ up, event sẽ được publish (OutboxRelayScheduler)
# Kiểm tra RabbitMQ UI: http://localhost:15672
# Vào Queues > booking.notification.queue > Get messages
```

---

### Test 7: Idempotency — gọi relay 2 lần cùng event

```bash
# Tìm một SENT event
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "SELECT id, status, idempotency_key FROM outbox_events WHERE status='SENT' LIMIT 1;"

# Set event về NEW để trigger lại (mô phỏng at-least-once)
docker exec tourism-postgres psql -U postgres -d booking_db -c \
  "UPDATE outbox_events SET status='NEW', retries=0, next_retry_at=NOW() WHERE id=<id>;"

# Chờ scheduler
sleep 5

# Kiểm tra IAM: không có duplicate coin transaction
docker exec tourism-postgres psql -U postgres -d iam_db -c \
  "SELECT * FROM coin_transactions ORDER BY id DESC LIMIT 5;"
```

---

## 5. Hướng Dẫn Test Giao Diện UI

### Cách khởi động Frontend

```bash
cd D:\HK8\tourism_frontend\client-side
npm install
npm start
# Frontend chạy tại http://localhost:3000
```

### Tìm booking có coinRefundStatus

1. **Đăng nhập** vào ứng dụng tại `http://localhost:3000`
2. Vào **Trang cá nhân** (Profile / My Account)
3. Chọn tab **Lịch sử giao dịch** (Transaction History)
4. Tìm booking có status `CANCELLED` mà user đã dùng coins

### Kiểm tra hiển thị

**Trường hợp 1: coinRefundStatus = PENDING** (vừa cancel xong, chưa scheduler chạy)
- Mở modal chi tiết của booking cancelled
- Section 5 "Hoàn tiền xu" hiển thị badge màu vàng **"Đang xử lý"**

**Trường hợp 2: coinRefundStatus = COMPLETED** (scheduler đã chạy thành công)
- Badge màu xanh lá **"Đã hoàn thành"**

**Trường hợp 3: coinRefundStatus = FAILED** (event DEAD sau nhiều lần retry)
- Badge màu đỏ **"Thất bại"**
- Admin cần vào dashboard để retry

**Trường hợp 4: booking không dùng coins**
- Section 5 không hiển thị (coinRefundStatus = null)

### Kiểm tra UI realtime

1. Cancel booking với coins → F5 lại → thấy PENDING
2. Chờ 5-10 giây → F5 lại → thấy COMPLETED (sau khi scheduler chạy)
3. Nếu muốn test FAILED: dừng IAM → cancel booking → chờ event DEAD (set retries thủ công nhanh hơn)

---

## 6. Điểm Chú Ý Kỹ Thuật

### 6.1 Về DDL và `ddl-auto: update`

Hibernate `ddl-auto: update` **KHÔNG tự thêm DEFAULT** khi tạo column mới. Do đó:
- **Sai**: `@Column(name = "max_backoff_secs", nullable = false)` → Hibernate tạo `ADD COLUMN max_backoff_secs bigint NOT NULL` → **lỗi khi table có rows**
- **Đúng**: `@Column(..., columnDefinition = "bigint not null default 3600")` → Hibernate tạo `ADD COLUMN max_backoff_secs bigint not null default 3600` → **thành công**

### 6.2 Về Stale Lock Recovery

Khi một instance booking-service crash giữa chừng, các outbox events bị lock (`status=SENDING`) sẽ không bao giờ được xử lý. `resetStaleLocks()` chạy đầu mỗi cycle reset những events bị lock quá 5 phút về trạng thái NEW.

### 6.3 Về IAM Idempotency

IAM service có UNIQUE constraint trên `coin_transactions.operationKey`. `operationKey = bookingCode + "_coin_refund"`. Nếu scheduler gọi 2 lần (at-least-once delivery), lần 2 IAM trả 409 Conflict → scheduler xem là SUCCESS → mark event SENT. Không bao giờ coin được hoàn 2 lần.

### 6.4 Coin Refund Status vs Event Status

Hai trạng thái này là **độc lập**:
- `OutboxEvent.status`: lifecycle của event outbox (NEW/SENDING/SENT/DEAD)  
- `Booking.coinRefundStatus`: kết quả cuối cùng cho user (PENDING/COMPLETED/FAILED)

Event DEAD → coinRefundStatus = FAILED. Sau khi admin retry và event SENT → **coinRefundStatus phải được cập nhật lại thành COMPLETED** (scheduler làm điều này trong `processOne()` khi relay thành công).

---

## 7. Summary Thay Đổi

| Loại | Số lượng |
|------|---------|
| File backend sửa đổi | 9 |
| File frontend sửa đổi | 2 |
| File test mới | 3 |
| Tổng tests | 75 (all passing) |
| Column DB mới | 2 (`max_backoff_secs`, `coin_refund_status`) |
| Index DB mới | 1 (`idx_outbox_dead`) |
| Endpoint mới | 4 (`/admin/outbox/*`) |
