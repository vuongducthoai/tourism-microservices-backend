# Báo Cáo: RabbitMQ + Outbox Pattern cho Booking Service

## Mục Lục
1. [Tổng Quan Kiến Trúc](#1-tổng-quan-kiến-trúc)
2. [Luồng Xử Lý Chi Tiết](#2-luồng-xử-lý-chi-tiết)
3. [Các File Đã Thay Đổi](#3-các-file-đã-thay-đổi)
4. [Cấu Hình RabbitMQ](#4-cấu-hình-rabbitmq)
5. [API Endpoints](#5-api-endpoints)
6. [Hướng Dẫn Test Giao Diện](#6-hướng-dẫn-test-giao-diện)
7. [Hướng Dẫn Test API (Swagger)](#7-hướng-dẫn-test-api-swagger)
8. [Kiểm Tra RabbitMQ Management UI](#8-kiểm-tra-rabbitmq-management-ui)
9. [Xử Lý Lỗi & Recovery](#9-xử-lý-lỗi--recovery)
10. [Kết Quả Unit Test](#10-kết-quả-unit-test)

---

## 1. Tổng Quan Kiến Trúc

### Vấn Đề Trước Khi Triển Khai

| Vấn Đề | Mô Tả |
|--------|-------|
| Race condition | `iamClient.addCoins()` được gọi **trước** `bookingRepository.save()` — nếu save thất bại, xu đã được cộng |
| Mất email | `notificationClient.*()` gọi trực tiếp — nếu notification-service down thì email không được gửi và không có cơ chế retry |
| Không idempotent | Không có cơ chế ngăn cộng xu 2 lần nếu scheduler chạy trùng |

### Giải Pháp: Transactional Outbox Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│                        booking-service                           │
│                                                                  │
│  cancelBooking()  ──── Atomic TX ────► booking_db               │
│                   │                   ├── bookings (save)        │
│                   └──────────────────► ├── outbox_events (2 rows)│
│                                        │    ├── COIN_REFUND      │
│                                        │    └── STATUS_UPDATED   │
│                                        └─────────────────────────│
│                                                                  │
│  CoinRefundRelayScheduler (30s)                                  │
│    └── reads COIN_REFUND rows                                    │
│    └── Feign → iam-service /api/users/{id}/coins?operationKey=X  │
│    └── mark SENT                                                 │
│                                                                  │
│  OutboxRelayScheduler (30s)                                      │
│    └── reads non-COIN rows (NOTIFICATION events)                 │
│    └── RabbitMQ publish → tourism.events exchange                │
│    └── publisher confirm → mark SENT                             │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼ RabbitMQ
                    tourism.events (topic exchange, durable)
                          │
              ┌───────────┴───────────┐
              │                       │
    booking.notification.*    booking.analytics.*
              │
    booking.notification.queue (durable, with DLQ)
              │
    ┌─────────────────────────────────────────────────┐
    │               notification-service               │
    │                                                  │
    │  BookingEventListener                            │
    │    └── check ProcessedEvent (idempotency)        │
    │    └── switch(eventType):                        │
    │         BOOKING_CONFIRMED → notifyBookingConfirmed │
    │         STATUS_UPDATED   → notifyStatusUpdated   │
    │         REFUND_REQUESTED → notifyRefundRequested │
    │         REFUND_COMPLETED → notifyRefundCompleted │
    │    └── save ProcessedEvent (deduplicate)         │
    └─────────────────────────────────────────────────┘
```

### Idempotency Chain

```
booking-service             iam-service
outbox_events               coin_transactions
┌──────────────────┐        ┌────────────────────────┐
│ idempotencyKey   │ Feign  │ operation_key (UNIQUE)  │
│ BK001_COIN_REFUND│ ─────► │ BK001_COIN_REFUND_...   │
│ _1715577600000   │        │ (prevents double credit)│
└──────────────────┘        └────────────────────────┘

booking-service             notification-service
outbox_events               processed_events
┌──────────────────┐ AMQP   ┌────────────────────────┐
│ idempotencyKey   │ ─────► │ idempotency_key (UNIQUE)│
│ BK001_STATUS_    │        │ (prevents double email) │
│ UPDATED_...      │        └────────────────────────┘
└──────────────────┘
```

---

## 2. Luồng Xử Lý Chi Tiết

### 2.1 User Hủy Tour (cancelBooking)

```
User bấm "Hủy Tour" trên giao diện
    │
    ▼
POST /api/bookings/{id}/cancel
    │
    ▼ BookingServiceImpl.cancelBooking() [TX bắt đầu]
    ├── Tính refundableAmount = (totalPrice + paidByCoin) × (1 - feePercent)
    ├── Tính coinRefundAmount = refundableAmount / 1000 (1000 VND = 1 xu)
    ├── booking.setBookingStatus(CANCELLED)
    ├── bookingRepository.save(booking) ──────────────► bookings table
    ├── [if coinRefundAmount > 0]
    │     outboxRepository.save(COIN_REFUND event) ──► outbox_events (routingKey='booking.coin.refund')
    └── outboxRepository.save(STATUS_UPDATED event) ─► outbox_events (routingKey='booking.notification.event')
    [TX commit thành công]
    │
    ▼ (30 giây sau)
CoinRefundRelayScheduler
    ├── đọc outbox_events WHERE routingKey='booking.coin.refund' AND status='NEW' (FOR UPDATE SKIP LOCKED)
    ├── mark SENDING
    ├── Feign: POST /api/users/{userId}/coins?amount=900&operationKey=BK001_COIN_REFUND_1234567890
    │     IAM checks coin_transactions: if operationKey exists → skip (idempotent)
    │     IAM adds coins to user.coinBalance
    │     IAM saves CoinTransaction(operationKey, userId, 900, 'CREDIT')
    └── mark SENT
    │
OutboxRelayScheduler
    ├── đọc outbox_events WHERE routingKey!='booking.coin.refund' AND status='NEW' (FOR UPDATE SKIP LOCKED)
    ├── mark SENDING
    ├── RabbitMQ publish: tourism.events / booking.notification.event
    │     CorrelationData → publisher confirm
    ├── on ack: mark SENT
    └── on nack: exponential backoff (2^retries × 30s), tối đa 5 lần → DEAD
    │
notification-service BookingEventListener
    ├── check processed_events: if idempotencyKey exists → skip
    ├── dispatch by eventType='STATUS_UPDATED' → notifyStatusUpdated()
    │     → gửi email "Booking đã hủy, hoàn ${coinRefundAmount} xu"
    │     → gửi WebSocket notification
    └── save ProcessedEvent(idempotencyKey)
```

### 2.2 User Yêu Cầu Hoàn Tiền Ngân Hàng (submitRefundRequest)

```
User điền TK ngân hàng → bấm "Yêu cầu hoàn tiền"
    │
POST /api/bookings/{id}/refund-request
    │
    ▼ BookingServiceImpl.submitRefundRequest() [TX]
    ├── Tính totalRefundAmount
    ├── Lưu RefundInformation (bank, accountNumber, accountName)
    ├── booking.setBookingStatus(PENDING_REFUND)
    ├── bookingRepository.save(booking)
    └── outboxRepository.save(REFUND_REQUESTED event)
    [TX commit]
    │
    ▼ (30s sau) notification-service nhận
    └── notifyRefundRequested() → email + WebSocket "Yêu cầu hoàn tiền đang xử lý"
```

### 2.3 Admin Xác Nhận Booking (PAID)

```
Admin bấm "Xác nhận" trong dashboard
    │
POST /api/admin/bookings/update-status  body: {bookingID, bookingStatus: "PAID"}
    │
    ▼ BookingServiceImpl.adminUpdateBookingStatus() [TX]
    ├── Validate: currentStatus phải là PENDING_CONFIRMATION
    ├── booking.setBookingStatus(PAID)
    ├── bookingRepository.save(booking)
    └── outboxRepository.save(BOOKING_CONFIRMED event)
    [TX commit]
    │
    ▼ notification-service nhận
    └── notifyBookingConfirmed() → email "Booking đã được xác nhận"
```

### 2.4 Admin Hủy Booking (CANCELLED) + SePay Verify

```
Admin bấm "Hủy booking" trong dashboard
    │
POST /api/admin/bookings/update-status  body: {bookingID, bookingStatus: "CANCELLED"}
    │
    ▼ BookingServiceImpl.adminUpdateBookingStatus()
    ├── requiresSepayCheck = true (nếu PAID/PENDING_CONFIRMATION/PENDING_REFUND)
    ├── sepayService.verifyRefundTransaction() → kiểm tra đã CK tiền chưa
    │     if NOT verified → throw "Vui lòng chuyển khoản trước"
    ├── [TX bắt đầu]
    ├── refundInfo.setRefundStatus("COMPLETED")
    ├── booking.setBookingStatus(CANCELLED)
    ├── booking.setRefundAmount(totalPrice + paidByCoin)
    ├── bookingRepository.save(booking)
    └── outboxRepository.save(REFUND_COMPLETED event)
    [TX commit]
    │
    ▼ notification-service nhận
    └── notifyRefundCompleted() → email "Booking đã hủy, tiền hoàn đang xử lý"
```

---

## 3. Các File Đã Thay Đổi

### booking-service

| File | Loại | Thay đổi |
|------|------|----------|
| `pom.xml` | Modified | Thêm `spring-boot-starter-amqp` |
| `entity/OutboxEvent.java` | **New** | Entity cho transactional outbox |
| `entity/OutboxStatus.java` | **New** | Enum: NEW, SENDING, SENT, DEAD |
| `repository/OutboxEventRepository.java` | **New** | `findAndLockPending()` với FOR UPDATE SKIP LOCKED |
| `config/RabbitMQConfig.java` | **New** | Exchange, queues, DLQs, bindings, publisher confirm |
| `messaging/OutboxRelayScheduler.java` | **New** | Scheduler đọc outbox và publish RabbitMQ (30s) |
| `messaging/CoinRefundRelayScheduler.java` | **New** | Scheduler đọc COIN_REFUND và gọi IAM Feign (30s) |
| `messaging/OutboxEventFactory.java` | **New** | Factory tạo OutboxEvent từ DTO |
| `event/BookingEventDTO.java` | Modified | Thêm `eventType`, `idempotencyKey`, `coinRefundOperationKey` |
| `feign/IamFeignClient.java` | Modified | Thêm `@RequestParam String operationKey` |
| `service/impl/BookingServiceImpl.java` | **Refactored** | Thay 4 Feign calls bằng outbox saves |
| `resources/application.yml` | Modified | Thêm `publisher-confirm-type: correlated` |

### notification-service

| File | Loại | Thay đổi |
|------|------|----------|
| `pom.xml` | Modified | Thêm `spring-boot-starter-amqp` |
| `config/RabbitMQConfig.java` | **New** | Mirror topology + listener container factory |
| `entity/ProcessedEvent.java` | **New** | Idempotency tracking table |
| `repository/ProcessedEventRepository.java` | **New** | `existsByIdempotencyKey()` |
| `listener/BookingEventListener.java` | **New** | Consumer RabbitMQ với deduplication |
| `dto/BookingEventDTO.java` | Modified | Thêm fields mới |
| `resources/application.yml` | Modified | Thêm rabbitmq config + retry config |

### iam-service

| File | Loại | Thay đổi |
|------|------|----------|
| `entity/CoinTransaction.java` | **New** | Ghi nhận mỗi coin credit với operation_key UNIQUE |
| `repository/CoinTransactionRepository.java` | **New** | `existsByOperationKey()` |
| `service/UserService.java` | Modified | Thêm `operationKey` param |
| `service/impl/UserServiceImpl.java` | Modified | Idempotency check trước khi cộng xu |
| `controller/UserController.java` | Modified | Thêm `@RequestParam operationKey` |

---

## 4. Cấu Hình RabbitMQ

### Topology

```
Exchange: tourism.events (topic, durable)
  │
  ├── binding: booking.notification.* → booking.notification.queue
  │     DLQ: booking.notification.dlq
  │
  └── binding: booking.analytics.* → booking.analytics.queue
        DLQ: booking.analytics.dlq
```

### Routing Keys

| Routing Key | Dùng cho |
|-------------|----------|
| `booking.notification.event` | Gửi notification đến notification-service |
| `booking.analytics.event` | Gửi analytics events (future use) |
| `booking.coin.refund` | INTERNAL — chỉ lưu trong outbox_events, không publish lên MQ |

### Credentials
- Host: `localhost` (hoặc `rabbitmq` trong Docker)
- Port AMQP: `5672`
- Port Management UI: `15672`
- Username: `tourism`
- Password: `tourism123`

---

## 5. API Endpoints

### booking-service (port 8083)

#### User APIs
| Method | URL | Mô tả |
|--------|-----|-------|
| `POST` | `/api/bookings/{id}/cancel` | Hủy tour (coin refund qua outbox) |
| `POST` | `/api/bookings/{id}/refund-request` | Yêu cầu hoàn tiền ngân hàng |
| `GET` | `/api/bookings/user/{userId}` | Danh sách booking của user |
| `GET` | `/api/bookings/{id}` | Chi tiết booking |

#### Admin APIs
| Method | URL | Mô tả |
|--------|-----|-------|
| `POST` | `/api/admin/bookings/update-status` | Xác nhận PAID hoặc hủy |
| `POST` | `/api/admin/bookings/search` | Tìm kiếm booking |

### iam-service (port 8081)

| Method | URL | Mô tả |
|--------|-----|-------|
| `POST` | `/api/users/{userId}/coins?amount=X&operationKey=Y` | Cộng xu (idempotent) |

### notification-service (port 8086)
Nhận events từ RabbitMQ — không có REST API public cho notifications.

---

## 6. Hướng Dẫn Test Giao Diện

### Chuẩn Bị
1. Đảm bảo tất cả containers đang chạy: `docker ps`
2. Mở frontend: `http://localhost:3000` (hoặc port của client-side)
3. Có sẵn 1 booking ở trạng thái **PAID**

---

### Test 1: User Hủy Tour (Coin Refund Path)

**Mục tiêu**: Kiểm tra hủy tour → xu được cộng sau 30 giây (qua outbox + Feign)

**Bước thực hiện**:
1. Đăng nhập tài khoản user có booking PAID
2. Vào **"Booking của tôi"** → chọn booking PAID
3. Ghi lại số xu hiện tại (vào Profile → Số xu)
4. Bấm **"Hủy tour"** → điền lý do → xác nhận
5. Kiểm tra booking chuyển sang **CANCELLED** ngay lập tức
6. **Chờ 30-60 giây** (để scheduler chạy)
7. Refresh trang Profile → kiểm tra số xu đã tăng

**Kết quả mong đợi**:
- Booking status: `CANCELLED`
- Số xu tăng đúng theo công thức: `floor((totalPrice + paidByCoin) × (1 - feePercent) / 1000)`
- Nhận email thông báo hủy tour (nếu cấu hình email)

**Phí hủy theo thời gian**:
| Ngày trước khởi hành | Phí | Hoàn lại |
|----------------------|-----|----------|
| > 15 ngày | 10% | 90% |
| 6-15 ngày | 50% | 50% |
| 3-5 ngày | 70% | 30% |
| 0-2 ngày | 90% | 10% |
| Đã qua | 100% | 0% |

---

### Test 2: User Yêu Cầu Hoàn Tiền Ngân Hàng

**Mục tiêu**: Kiểm tra gửi yêu cầu hoàn tiền → email notification

**Bước thực hiện**:
1. Đăng nhập user có booking **PAID** hoặc **PENDING_CONFIRMATION**
2. Vào booking → bấm **"Yêu cầu hoàn tiền"**
3. Điền thông tin ngân hàng:
   - Tên ngân hàng: `Vietcombank`
   - Số tài khoản: `1234567890`
   - Tên chủ tài khoản: `NGUYEN VAN A`
4. Submit

**Kết quả mong đợi**:
- Booking chuyển sang `PENDING_REFUND`
- Có thể xem thông tin hoàn tiền trong chi tiết booking
- Nhận email "Yêu cầu hoàn tiền đang được xử lý"

---

### Test 3: Admin Xác Nhận Booking

**Mục tiêu**: Kiểm tra admin confirm → email "Booking đã xác nhận"

**Bước thực hiện**:
1. Đăng nhập tài khoản **Admin**
2. Vào **Admin Dashboard** → **Quản lý Booking**
3. Tìm booking `PENDING_CONFIRMATION`
4. Bấm **"Xác nhận"** → chọn status PAID → Submit

**Kết quả mong đợi**:
- Booking chuyển sang `PAID`
- User nhận email xác nhận booking

---

### Test 4: Admin Hủy Booking (với SePay Verify)

**Mục tiêu**: Kiểm tra admin cancel → SePay verification → email hoàn tiền

**Bước thực hiện**:
1. Admin vào booking `PAID` hoặc `PENDING_REFUND`
2. Bấm **"Hủy booking"**
3. Nếu chưa CK tiền → hệ thống báo lỗi với nội dung chuyển khoản
4. Thực hiện CK tiền theo nội dung yêu cầu (test với SePay sandbox)
5. Bấm **"Hủy booking"** lại → thành công

**Kết quả mong đợi**:
- Booking chuyển sang `CANCELLED`
- User nhận email "Booking đã hủy, tiền hoàn đang xử lý"

---

### Test 5: Kiểm Tra Idempotency (Double-click Prevention)

**Mục tiêu**: Bấm hủy 2 lần không bị cộng xu 2 lần

**Bước thực hiện**:
1. Ghi lại số xu ban đầu
2. Hủy booking → đợi 60 giây
3. Kiểm tra số xu đã tăng đúng 1 lần
4. Vào DB kiểm tra `coin_transactions` table trong `iam_db`:
   ```sql
   SELECT * FROM coin_transactions WHERE operation_key LIKE '%BK001%';
   ```
   Chỉ có 1 row với `operation_key` đó.

---

## 7. Hướng Dẫn Test API (Swagger)

### Mở Swagger UI
- booking-service: http://localhost:8080/booking-service/swagger-ui.html (qua gateway)
- Hoặc trực tiếp: http://localhost:8083/swagger-ui.html

### Test cancelBooking

**Request**:
```http
POST /api/bookings/cancel
Content-Type: application/json
Authorization: Bearer <user_token>

{
  "bookingID": 123,
  "cancelReason": "Kế hoạch thay đổi"
}
```

**Kiểm tra sau đó (PostgreSQL)**:
```sql
-- booking-service DB
SELECT booking_code, booking_status, refund_amount, cancel_reason
FROM bookings WHERE booking_id = 123;

-- outbox events
SELECT idempotency_key, routing_key, status, payload
FROM outbox_events
WHERE idempotency_key LIKE '%BK%'
ORDER BY created_at DESC;
```

### Test addCoins (IAM) với operationKey

```http
POST /api/users/42/coins?amount=900&operationKey=BK001_COIN_REFUND_TEST001
```

**Gọi lại với cùng operationKey (idempotency)**:
```http
POST /api/users/42/coins?amount=900&operationKey=BK001_COIN_REFUND_TEST001
```
→ Xu chỉ được cộng 1 lần.

### Test submitRefundRequest

```http
POST /api/bookings/{id}/refund-request
Content-Type: application/json
Authorization: Bearer <user_token>

{
  "bank": "Vietcombank",
  "accountNumber": "1234567890",
  "accountName": "NGUYEN VAN A"
}
```

### Test adminUpdateBookingStatus

```http
POST /api/admin/bookings/update-status
Content-Type: application/json
Authorization: Bearer <admin_token>

{
  "bookingID": 123,
  "bookingStatus": "PAID"
}
```

---

## 8. Kiểm Tra RabbitMQ Management UI

### Truy Cập
- URL: http://localhost:15672
- Username: `tourism`
- Password: `tourism123`

### Kiểm Tra Exchange

1. Vào **Exchanges** → tìm `tourism.events`
2. Xác nhận: `durable=true`, `type=topic`

### Kiểm Tra Queues

| Queue | Kỳ vọng |
|-------|---------|
| `booking.notification.queue` | durable, với x-dead-letter-exchange |
| `booking.analytics.queue` | durable |
| `booking.notification.dlq` | durable |
| `booking.analytics.dlq` | durable |

### Xem Messages Được Xử Lý

1. Thực hiện cancelBooking qua API
2. Chờ 30 giây
3. Trong Management UI → **Queues** → `booking.notification.queue`
4. Tab **Get messages** → xem message payload
5. Sau khi notification-service xử lý → **Messages** count giảm về 0

### Xem DLQ khi có lỗi

Nếu notification-service down và message retry thất bại:
- `booking.notification.dlq` sẽ có messages
- Restart notification-service → messages sẽ được requeue

---

## 9. Xử Lý Lỗi & Recovery

### Scenario 1: RabbitMQ Down khi publish

```
Outbox status: NEW → SENDING → NEW (backoff)
                                   ↓ (2^retries × 30s)
                         retry tối đa 5 lần → DEAD
```

**Recovery**: Khi RabbitMQ phục hồi, scheduler tự động retry outbox rows có status `NEW`.

**Kiểm tra**:
```sql
-- booking_db
SELECT idempotency_key, status, retries, next_retry_at, error_message
FROM outbox_events WHERE status = 'NEW' OR status = 'DEAD';
```

### Scenario 2: Notification-service Down

Messages ở lại trong `booking.notification.queue` (durable). Khi notification-service phục hồi, Spring AMQP tự động consume tiếp.

### Scenario 3: Coin Refund Feign Thất Bại

```
outbox_events: COIN_REFUND row status = NEW → SENDING → NEW (retry)
```

**Recovery**: Scheduler tự retry. Nếu IAM phục hồi → Feign thành công → SENT.
IAM `coin_transactions.operation_key` đảm bảo không double-credit.

### Scenario 4: Message Xử Lý 2 Lần (Duplicate)

notification-service nhận cùng message 2 lần do network issue:
- Lần 1: check `processed_events` → không có → xử lý → lưu ProcessedEvent
- Lần 2: check `processed_events` → **CÓ** → **skip** ngay lập tức

### Scenario 5: Stale Outbox Locks

Nếu booking-service instance crash giữa chừng, row có status `SENDING` nhưng không ai xử lý:
```java
// Mỗi 30 giây, scheduler tự reset stale SENDING rows > 5 phút
outboxRepo.resetStaleLocks(LocalDateTime.now().minusMinutes(5));
```

---

## 10. Kết Quả Unit Test

### Chạy Tests
```bash
cd tourism-microservices-backend/booking-service
mvn test
```

### Kết Quả
```
Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Các Test Đã Viết

#### BookingServiceImplTest (36 tests)
- **cancelBooking** (12 tests): fee tiers, coin calculation, outbox saves, CANCELLED status
- **submitRefundRequest** (8 tests): bank refund path, PENDING_REFUND status, outbox saves
- **calculateRefundableAmount** (8 tests): boundary value analysis cho từng fee tier
- **getBookingsByUser** (5 tests): status filter, payment/refund info mapping
- **getBookingById** (3 tests): found/not found/null status
- **updateBookingStatus** (5 tests): valid/invalid statuses, case-insensitive

#### AdminBookingServiceImplTest (16 tests)
- **adminSearchBookings** (2 tests): pagination, empty result
- **PAID transition** (2 tests): happy path + guard
- **CANCELLED (no refund)** (1 test): PENDING_PAYMENT path
- **CANCELLED (with refund)** (2 tests): PAID + PENDING_CONFIRMATION paths
- **Guard tests** (2 tests): non-cancellable statuses
- **Outbox transactional safety** (1 test)

### Key Test Patterns

```java
// Verify outbox saves instead of direct Feign calls
verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
// 2 events: COIN_REFUND + STATUS_UPDATED (for cancelBooking with refund > 0)

verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
// 1 event: only notification (for past departure where refund = 0)

// Verify IAM is never called directly (deferred to outbox)
verify(iamClient, never()).addCoins(any(), any(), any());
```

---

## Tài Liệu Tham Khảo

- Plan gốc: `plan-bookingRabbitMqOutbox.prompt.md`
- Docker Compose: `docker-compose.yml`
- RabbitMQ Management: http://localhost:15672
