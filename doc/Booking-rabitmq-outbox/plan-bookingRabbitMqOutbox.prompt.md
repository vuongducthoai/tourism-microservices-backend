# Plan: Tối ưu Booking Admin/User bằng RabbitMQ + Outbox Pattern

---

## 1. Tại sao cần làm — vấn đề hiện tại

### 1.1 Vấn đề với Feign fire-and-forget (hiện tại)

```
[HTTP Request]
     │
     ▼
BookingServiceImpl.cancelBooking()
     │
     ├── iamClient.addCoins(userId, 900)       ← Feign sync HTTP call #1
     │       └── nếu IAM down → throw → booking KHÔNG lưu
     │           (nhưng nếu crash giữa chừng → coins ĐÃ cộng, booking chưa lưu = DOUBLE REFUND)
     │
     ├── bookingRepository.save(CANCELLED)      ← DB commit
     │
     └── notificationClient.notifyStatusUpdated()  ← Feign sync HTTP call #2
             └── nếu notification-service down → email BỊ MẤT VĨNH VIỄN
                 (catch Exception chỉ log, không retry)
```

**2 vấn đề nghiêm trọng:**
1. **Email mất vĩnh viễn**: notification-service down → `catch (Exception e) { log.error(...) }` → email không bao giờ gửi lại
2. **Double refund risk**: `addCoins` thành công + booking save fail → user nhận coins nhưng booking vẫn PAID → user cancel lại → coins 2 lần

### 1.2 Tại sao RabbitMQ là giải pháp đúng

| Vấn đề | Feign (hiện tại) | RabbitMQ + Outbox |
|---|---|---|
| notification-service down | Email mất vĩnh viễn | Message nằm queue, khi service up lại → tự gửi |
| RabbitMQ down | N/A | Outbox trong DB → scheduler relay khi MQ up |
| Double refund coins | Race condition | Outbox idempotency key → đúng 1 lần |
| Audit trail | Không có | Mọi event lưu trong outbox_events table |

---

## 2. Kiến trúc tổng thể

```
┌─────────────────────────────────────────────────────────────────────────┐
│  booking-service (port 8083)                                            │
│                                                                         │
│  ┌─────────────────┐    @Transactional     ┌──────────────────────┐    │
│  │ BookingService  │ ─────────────────────▶│  booking_db          │    │
│  │                 │    1. UPDATE bookings  │  ┌──────────────┐   │    │
│  │ cancelBooking() │    2. INSERT outbox    │  │   bookings   │   │    │
│  │ adminUpdate()   │                        │  ├──────────────┤   │    │
│  │ requestRefund() │                        │  │outbox_events │◀──┤    │
│  └────────┬────────┘                        │  │ (guaranteed) │   │    │
│           │                                 │  └──────┬───────┘   │    │
│  ┌────────▼────────┐                        └─────────┼───────────┘    │
│  │OutboxRelayJob   │ ◀──── @Scheduled/30s             │                │
│  │(reads pending   │       reads pending              │                │
│  │ outbox rows)    │       outbox rows                │                │
│  └────────┬────────┘                                  │                │
│           │ RabbitTemplate.send()                     │                │
└───────────┼───────────────────────────────────────────┼────────────────┘
            │                                           │
            ▼                                    INSERT outbox_events
    ┌───────────────────────────────────────┐    (atomic with booking save)
    │  RabbitMQ  (port 5672)                │
    │                                       │
    │  Exchange: tourism.events (topic)     │
    │                                       │
    │  Routing keys:                        │
    │   booking.notification.*              │──▶ booking.notification.queue
    │   booking.analytics.*                 │──▶ booking.analytics.queue
    │                                       │
    │  DLQ after 3 retries:                 │
    │   booking.notification.dlq            │
    │   booking.analytics.dlq               │
    └──────────────────┬────────────────────┘
                       │
            ┌──────────┼──────────────┐
            ▼                         ▼
  ┌──────────────────┐      ┌──────────────────────┐
  │ notification-svc │      │  analytics-service   │
  │ (port 8086)      │      │  (port 8087)         │
  │                  │      │                      │
  │ @RabbitListener  │      │ @RabbitListener      │
  │ ── handleEvent() │      │ ── invalidateCache() │
  │   ├── sendEmail  │      │ ── updateRedis()     │
  │   ├── saveNotif  │      └──────────────────────┘
  │   └── pushWS     │
  └──────────────────┘
```

---

## 3. Outbox Pattern — Đảm bảo "at-least-once delivery"

### 3.1 Tại sao cần Outbox?

Vấn đề: Sau khi booking DB commit, nếu app crash trước khi gửi message RabbitMQ → message mất.

```
@Transactional
cancelBooking() {
    bookingRepository.save(CANCELLED);  // ← DB commit thành công
    // ← app CRASH ở đây
    rabbitTemplate.send(...);            // ← KHÔNG BAO GIỜ CHẠY
}
```

**Outbox Pattern giải quyết:**
```
@Transactional
cancelBooking() {
    bookingRepository.save(CANCELLED);      // Step 1: cùng transaction
    outboxRepository.save(new OutboxEvent); // Step 2: cùng transaction
    // Nếu crash ở đây → cả 2 đều rollback → consistent
    // Nếu commit thành công → outbox_events có row → scheduler sẽ relay
}

// Scheduler chạy mỗi 30s, tách transaction riêng:
@Scheduled(fixedDelay = 30_000)
@Transactional
processOutbox() {
    String instanceId = InetAddress.getLocalHost().getHostName();

    // FOR UPDATE SKIP LOCKED — an toàn khi deploy nhiều instance:
    // Instance A lock row #1 → Instance B bỏ qua row #1, lấy row #2
    List<OutboxEvent> batch = outboxRepo
        .findAndLockPending(LocalDateTime.now(), BATCH_SIZE);
    // Native SQL: SELECT * FROM outbox_events
    //             WHERE status = 'NEW' AND next_retry_at <= NOW()
    //             LIMIT 100 FOR UPDATE SKIP LOCKED

    // Đánh dấu SENDING trước (trong cùng transaction) → commit ngay
    batch.forEach(e -> {
        e.setStatus(SENDING);
        e.setLockedBy(instanceId);
        e.setLockedAt(LocalDateTime.now());
    });
    outboxRepo.saveAll(batch); // flush

    // Gửi sau khi đã mark SENDING (ngoài transaction trên)
    for (OutboxEvent event : batch) {
        try {
            // Publisher Confirm: chỉ mark SENT khi broker ack
            // rabbitTemplate cần spring.rabbitmq.publisher-confirm-type=correlated
            CorrelationData cd = new CorrelationData(event.getIdempotencyKey());
            rabbitTemplate.convertAndSend(
                event.getExchange(), event.getRoutingKey(), event.getPayload(), cd);
            // Callback ack=true  → setStatus(SENT), sentAt=NOW()
            // Callback ack=false → scheduleRetry(event, nackCause)
        } catch (Exception e) {
            scheduleRetry(event, e.getMessage());
            // next_retry_at = NOW() + 2^retries * 30s (exponential backoff)
            // nếu retries >= max_retries → status = DEAD → cần alert
        }
    }
}

// scheduleRetry():
void scheduleRetry(OutboxEvent event, String error) {
    event.incrementRetries();
    if (event.getRetries() >= event.getMaxRetries()) {
        event.setStatus(DEAD); // → alert, cần manual re-trigger
    } else {
        long backoffSecs = (long) Math.pow(2, event.getRetries()) * 30;
        event.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSecs));
        event.setStatus(NEW);  // unlock để lần sau retry
    }
    event.setErrorMessage(error);
    outboxRepo.save(event);
}
```

### 3.2 Schema bảng `outbox_events`

```sql
CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,  -- bookingCode_eventType_epochMs
    exchange        VARCHAR(100) NOT NULL,
    routing_key     VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,          -- JSON của BookingEventDTO
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    -- State machine: NEW → SENDING → SENT  (thành công)
    --                NEW/SENDING → NEW     (retry sau backoff)
    --                NEW → DEAD            (sau max_retries lần thất bại)
    retries         INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 5,
    locked_by       VARCHAR(100),                   -- hostname:pid instance đang giữ lock
    locked_at       TIMESTAMP,                      -- thời điểm lấy lock
    next_retry_at   TIMESTAMP    NOT NULL DEFAULT NOW(), -- exponential backoff
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP,
    error_message   TEXT,
    version         INT          NOT NULL DEFAULT 0  -- optimistic locking nếu cần
);

-- Index để scheduler query nhanh
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_retry_at)
    WHERE status = 'NEW';
```

**Idempotency key**: `BK12345678_CANCELLED_1715577600000` → nếu scheduler relay 2 lần cùng key, notification-service nhận 2 lần nhưng check `idempotency_key` và bỏ qua lần 2.

> ⚠️ **Multi-instance safety**: Nếu deploy 2+ booking-service instances, scheduler dùng `FOR UPDATE SKIP LOCKED` để tránh 2 instance cùng đọc row PENDING. Xem chi tiết ở mục 3.3.

---

### 3.3 Multi-instance safety — `FOR UPDATE SKIP LOCKED`

Nếu deploy 2+ booking-service instances, cả 2 scheduler có thể đọc cùng row `NEW` và publish trùng:

```
Instance A: SELECT * FROM outbox_events WHERE status='NEW' LIMIT 100;  → lấy row #1
Instance B: SELECT * FROM outbox_events WHERE status='NEW' LIMIT 100;  → lấy row #1 (trùng!)
Instance A: rabbitTemplate.send(row#1)  → message #1 vào queue
Instance B: rabbitTemplate.send(row#1)  → message #1 vào queue LẦN 2 → notification trùng!
```

Giải pháp — `FOR UPDATE SKIP LOCKED` trong PostgreSQL:

```sql
-- OutboxEventRepository.java (native query):
@Query(value = """
    SELECT * FROM outbox_events
    WHERE status = 'NEW' AND next_retry_at <= NOW()
    ORDER BY created_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<OutboxEvent> findAndLockPending(@Param("batchSize") int batchSize);
```

```
Instance A: FOR UPDATE SKIP LOCKED → lock row #1, row #2
Instance B: FOR UPDATE SKIP LOCKED → bỏ qua row #1 và #2 (đã lock), lấy row #3, #4
→ Không bao giờ publish trùng
```

Lưu ý: method này phải được gọi **trong transaction** (`@Transactional` trên service method) để lock giữ trong suốt thời gian xử lý batch.

---

## 4. Vấn đề Coin Refund — Cần xử lý đặc biệt

### 4.1 Vấn đề hiện tại (race condition nguy hiểm)

```java
// BookingServiceImpl.cancelBooking() — HIỆN TẠI ❌
@Transactional
public BookingResponse cancelBooking(...) {
    // Step 1: Tính coins
    BigDecimal coins = refundable.divide(COIN_RATE);

    // Step 2: Gọi IAM (NGOÀI transaction này!)
    iamClient.addCoins(userId, coins);   // ← IAM DB commit riêng

    // Step 3: Save booking
    bookingRepository.save(CANCELLED);  // ← nếu FAIL → rollback booking
                                         //    nhưng coins đã cộng rồi!
    // → USER NHẬN COINS + BOOKING VẪN CÒN PAID
}
```

Scenario nguy hiểm:
1. `addCoins` → IAM DB: `coinBalance += 900` ✅
2. `bookingRepository.save()` → **DB lỗi** → Exception
3. Transaction rollback → booking vẫn `PAID`
4. User thấy booking vẫn PAID → cancel lại → nhận thêm 900 coins nữa = **900 coins bị duplicate**

### 4.2 Giải pháp với Outbox + Coin Queue

Coins cũng phải đi qua Outbox:

```
@Transactional
cancelBooking() {
    // 1. Tính toán (không gọi external service)
    BigDecimal coins = calculateCoins(booking);

    // 2. Lưu booking (trong transaction)
    booking.setBookingStatus(CANCELLED);
    booking.setRefundAmount(refundable);
    booking.setCoinRefundPending(coins);   // NEW field: đánh dấu chờ cộng coin
    bookingRepository.save(booking);

    // 3. Lưu 2 outbox events CÙNG TRANSACTION
    outboxRepository.save(OutboxEvent.coinRefund(bookingCode, userId, coins));
    outboxRepository.save(OutboxEvent.notification(bookingCode, "CANCELLED", ...));
    // Transaction commit → cả 3 bảng đồng thời
}

// Coin Relay (separate scheduler):
// Đọc outbox events type=COIN_REFUND → gọi iamClient.addCoins() → mark SENT
// IAM service: idempotency key trong coin request → bỏ qua nếu đã xử lý
```

**Tại sao không dùng RabbitMQ cho coins mà vẫn cần Feign IAM?**
Lý do: `addCoins()` là **financial operation** cần confirm từ IAM, không phải fire-and-forget. Với Outbox relay qua Feign:
- Booking CANCELLED trong DB trước
- Coin relay chạy sau (tối đa 30s delay — chấp nhận được)
- IAM dùng bảng `coin_transactions` (không phải field đơn trong User) để đảm bảo idempotency

**Idempotency trong IAM — `coin_transactions` table:**

```sql
CREATE TABLE coin_transactions (
    id            BIGSERIAL PRIMARY KEY,
    operation_key VARCHAR(100) UNIQUE NOT NULL,  -- idempotency key từ outbox
    user_id       BIGINT NOT NULL,
    amount        NUMERIC(19,2) NOT NULL,
    direction     VARCHAR(10) NOT NULL,           -- CREDIT / DEBIT
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
```

```java
// UserServiceImpl.addCoins(userId, amount, operationKey)
@Transactional
void addCoins(Long userId, BigDecimal amount, String operationKey) {
    if (coinTransactionRepo.existsByOperationKey(operationKey)) {
        return; // đã xử lý rồi → bỏ qua (idempotent, không được cộng lần 2)
    }
    User user = userRepo.findById(userId).orElseThrow();
    user.setCoinBalance(user.getCoinBalance().add(amount));
    userRepo.save(user);
    coinTransactionRepo.save(new CoinTransaction(operationKey, userId, amount, CREDIT));
    // cả hai được commit trong cùng transaction → atomic
}
```

Cách này tốt hơn `lastCoinOperationKey` trong bảng `users` vì:
- Giữ đầy đủ lịch sử hoàn xu (audit trail)
- Không gây lock contention trên row User khi nhiều request cùng lúc
- `UNIQUE` constraint trên `operation_key` dựa vào DB engine, không cần application-level check

---

## 5. Tất cả luồng hoạt động chi tiết

### Luồng 1: User hủy booking (coin refund path)

```
User click "Hủy booking"
         │
         ▼
POST /api/bookings/cancel
         │
         ▼
BookingServiceImpl.cancelBooking()
         │
         ├── [VALIDATE] booking.status != CANCELLED ✓
         ├── [CALC]     refundableAmount, coinRefundAmount
         │
         ▼ @Transactional (một transaction duy nhất)
         ├── bookings SET status=CANCELLED, coin_refund_pending=900
         ├── outbox_events INSERT (type=COIN_REFUND, key=BK_CANCEL_123, payload={userId,900})
         ├── outbox_events INSERT (type=NOTIFICATION, key=BK_NOTIF_123, payload={...CANCELLED})
         └── COMMIT ← điểm an toàn duy nhất

         ▼ return response ngay (không chờ coin/notification)
User thấy: "Hủy thành công, coins sẽ được hoàn trong ít phút"

[Background — Outbox Relay, mỗi 30s]
         ├── Đọc COIN_REFUND pending
         │       └── iamClient.addCoins(userId, 900) → mark SENT
         │           nếu IAM down → retry lần 2, lần 3 → FAILED → alert
         │
         └── Đọc NOTIFICATION pending
                 └── rabbitTemplate.send(booking.notification, {CANCELLED})
                     → notification-service xử lý async

[notification-service nhận message]
         ├── mailService.sendCancellationCoinEmail(event)  → Email khách
         ├── mailService.sendCancellationAdminNotification() → Email admin
         ├── saveNotification(userId, BOOKING_CANCELLED)
         └── webSocketService.notifyUser(userId, event) → Badge update
```

### Luồng 2: Admin xác nhận PAID

```
Admin click "Xác nhận" (PENDING_CONFIRMATION → PAID)
         │
         ▼
POST /api/bookings/admin/update-status {bookingID, status:"PAID"}
         │
         ▼ @Transactional
         ├── bookings SET status=PAID
         ├── outbox_events INSERT (type=NOTIFICATION, key=BK_CONFIRMED_xxx)
         └── COMMIT

         ▼ return response

[Outbox Relay]
         └── rabbitTemplate.send("tourism.events", "booking.notification.confirmed", payload)

[notification-service]
         ├── mailService.sendPaymentConfirmationEmail() → Email khách "Đặt tour thành công!"
         ├── saveNotification(userId, BOOKING_CONFIRMED)
         └── webSocketService.notifyUser() + notifyAdmin()

[Frontend realtime]
         ├── Admin WS /topic/admin/bookings → BookingsPage auto-refresh
         └── User WS /topic/user/{id}/bookings → badge cập nhật
```

### Luồng 3: User submit refund request (bank path)

```
User gửi thông tin ngân hàng
         │
         ▼ @Transactional
         ├── RefundInformation INSERT
         ├── bookings SET status=PENDING_REFUND
         ├── outbox_events INSERT (type=NOTIFICATION, REFUND_REQUESTED)
         └── COMMIT

[Outbox Relay → notification-service]
         ├── mailService.sendRefundRequestNotification() → Email ADMIN
         │       "Khách BKxxx yêu cầu hoàn tiền 5,000,000đ vào MB ****1234"
         ├── saveNotification(null, BOOKING_REFUND_REQUESTED) ← admin notification
         ├── saveNotification(userId, BOOKING_REFUND_REQUESTED)
         └── webSocketService.notifyAdmin() → Admin thấy badge mới

[Admin xử lý hoàn tiền ngân hàng — SePay verify]
         ├── Admin chuyển khoản thực tế
         ├── Admin click "Xác nhận hoàn tiền"
         ├── SePay verify transaction ✓
         ├── @Transactional
         │       ├── RefundInformation SET status=COMPLETED
         │       ├── bookings SET status=CANCELLED
         │       └── outbox_events INSERT (type=NOTIFICATION, REFUND_COMPLETED)
         │
         └── [Outbox Relay → notification-service]
                 ├── mailService.sendRefundCompletedEmail() → Email khách "Hoàn tiền thành công"
                 └── webSocketService.notifyUser()
```

---

## 6. Recovery khi service down

### 6.1 Khi notification-service down

```
Timeline:
 T=0    booking bị hủy → outbox_events INSERT (PENDING)
 T=30s  Relay cố gửi MQ → thành công (message trong RabbitMQ queue)
 T=60s  notification-service down → message ở lại queue (durable=true, persistent)
 T=5m   notification-service restart → @RabbitListener tự reconnect
 T=5m+  message được consume → email gửi thành công
```

Message **không bao giờ mất** vì:
- Queue `durable=true` → survive RabbitMQ restart
- Message `persistent=true` (delivery mode 2) → survive RabbitMQ restart
- Consumer `autoAck=false` → message chỉ xóa sau khi listener return không throw

### 6.2 Khi RabbitMQ down

```
Timeline:
 T=0    booking bị hủy → outbox_events INSERT (PENDING)  ← DB commit ✓
 T=30s  Relay cố gửi MQ → ConnectionException → outbox status=PENDING (retry++)
 T=60s  Relay thử lần 2 → vẫn down → retry++
 T=90s  Relay thử lần 3 → vẫn down → status=FAILED nếu retries>=5
         (5 lần × 30s = 2.5 phút grace period)
 T=10m  RabbitMQ restart → Relay lần sau thành công → status=SENT
```

`status=FAILED` với `retries >= 5` → alert (scheduler riêng email admin hoặc log ERROR → monitoring nhìn thấy).

### 6.3 Khi IAM service down (coin refund)

```
Timeline:
 T=0    booking CANCELLED → outbox COIN_REFUND INSERT (PENDING)
 T=30s  CoinRelayJob.run() → iamClient.addCoins() → ConnectionException → retry++
 T=60s  Retry lần 2...
 T=xm   IAM service restart → addCoins thành công → outbox SENT
 User nhận coin muộn vài phút, nhưng ĐẢM BẢO NHẬN
```

Idempotency trong IAM: bảng `coin_transactions` (xem mục 4.2) — `UNIQUE(operation_key)` do DB engine đảm bảo, không phải field đơn trong User. IAM restart bao nhiêu lần cũng không cộng xu 2 lần với cùng `operation_key`.

---

### 6.4 Hai tầng retry — phân biệt rõ

> ⚠️ **Không nên trộn lẫn**: `DEAD` trong outbox ≠ DLQ trong RabbitMQ. Chúng độc lập và giải quyết 2 failure point khác nhau.

| Tầng | Xảy ra khi | Cơ chế | Kết quả cuối |
|------|-----------|---------|-------------|
| **Outbox publish fail** | booking-service → RabbitMQ lỗi (MQ down, routing sai, network) | `next_retry_at` + exponential backoff, tối đa `max_retries=5` lần | `DEAD` → alert admin/log ERROR → manual re-trigger |
| **Consumer process fail** | notification-service nhận message nhưng xử lý lỗi (SMTP down, DB lỗi, NullPointer) | `@RabbitListener` + Spring Retry (`@Retryable`), tối đa 3 lần | Vào `booking.notification.dlq` → monitor qua management UI |

```
Flow đầy đủ:

booking-service —[publish fail]→ outbox.status=NEW (retry backoff)
                               → outbox.status=DEAD (sau max_retries)

booking-service —[publish ok]→ RabbitMQ queue
                               → notification-service @RabbitListener
                                   —[process fail x3]→ booking.notification.dlq
                                   —[process ok]→ email/WS sent, ack()
```

---

## 7. Danh sách file cần tạo/sửa

### Backend — booking-service

| File | Hành động | Mô tả |
|---|---|---|
| `pom.xml` | Sửa | Thêm `spring-boot-starter-amqp` |
| `config/RabbitMQConfig.java` | Tạo mới | Exchange, queues, DLQ, bindings |
| `entity/OutboxEvent.java` | Tạo mới | Entity cho bảng outbox_events |
| `repository/OutboxEventRepository.java` | Tạo mới | `findByStatusAndRetriesLessThan` |
| `messaging/OutboxRelayScheduler.java` | Tạo mới | `@Scheduled` relay logic |
| `messaging/CoinRefundRelayScheduler.java` | Tạo mới | Relay COIN_REFUND type riêng |
| `service/impl/BookingServiceImpl.java` | Sửa | Thay 4 Feign calls hiện có → outboxRepository.save() |
| `entity/Booking.java` | Sửa | Thêm field `coinRefundPending` (nullable) |
| `application.yml` | Sửa | Verify `spring.rabbitmq` + scheduler config |

### Backend — notification-service

| File | Hành động | Mô tả |
|---|---|---|
| `pom.xml` | Sửa | Thêm `spring-boot-starter-amqp` |
| `config/RabbitMQConfig.java` | Tạo mới | Khai báo queue giống booking-service (idempotent) |
| `messaging/BookingEventListener.java` | Tạo mới | `@RabbitListener` dispatch theo `eventType` → map sang 3 handler hiện có |
| `service/NotificationService.java` | Sửa | Thêm `handleRefundCompleted` (admin cancel sau SePay verify) |
| `service/impl/NotificationServiceImpl.java` | Sửa | Implement `handleRefundCompleted` |
| `dto/BookingEventDTO.java` | Sửa | Thêm field `eventType`, `idempotencyKey` |
| `application.yml` | Sửa | Thêm `spring.rabbitmq` config |

### Backend — IAM service

| File | Hành động | Mô tả |
|---|---|---|
| `entity/CoinTransaction.java` | Tạo mới | Entity cho bảng `coin_transactions` (id, operation_key UNIQUE, user_id, amount, direction, created_at) |
| `repository/CoinTransactionRepository.java` | Tạo mới | `existsByOperationKey(String key)` |
| `service/impl/UserServiceImpl.addCoins()` | Sửa | Nhận thêm `operationKey` param; check `coin_transactions` trước khi cộng xu |

### Frontend — admin

| File | Hành động | Mô tả |
|---|---|---|
| `BookingItem.jsx` | Sửa | SLA badge cho PENDING_REFUND (màu theo giờ chờ) |
| `BookingsPage.jsx` | Sửa | Default sort ASC khi filter PENDING_REFUND |
| `AdminBookingModals.jsx` | Sửa | Toast notification sau xử lý refund thành công |
| `AdminNotificationBell.jsx` | Tạo mới | Badge + dropdown 10 notif mới nhất |
| `hook/useAdminNotifications.ts` | Tạo mới | WS subscribe + GET unread count |

### Frontend — user

| File | Hành động | Mô tả |
|---|---|---|
| `UserNotificationBell.jsx` | Tạo mới | Badge + dropdown ở navbar |
| `hook/useUserNotifications.ts` | Tạo mới | WS subscribe + polling |
| `TransactionListItem.jsx` | Sửa | Hiển thị "Coins sẽ hoàn trong ít phút" khi status CANCELLED + coinRefundPending > 0 |

---

## 8. Thứ tự triển khai (4 phase rõ ràng)

```
Phase 1 — Outbox + RabbitMQ cho notification booking  ← BLOCKING cho các phase sau
  1a. outbox_events migration SQL (schema mới với locking columns)
  1b. OutboxEvent entity + OutboxEventRepository (findAndLockPending nạtive query)
  1c. RabbitMQConfig (tourism.events exchange, queues, DLQs) — booking-service
  1d. RabbitMQConfig — notification-service
  1e. BookingEventDTO thêm eventType, idempotencyKey, occurredAt
  1f. OutboxRelayScheduler (FOR UPDATE SKIP LOCKED + publisher confirm)
  1g. BookingEventListener (@RabbitListener, dispatch theo eventType)
  1h. Sửa BookingServiceImpl: 4 Feign notification calls → outboxRepository.save()
  1i. Test: tắt notification-service → cancel booking → restart → email đến

Phase 2 — Coin refund idempotency  ← có thể song song với Phase 1 từ bước 1h
  2a. coin_transactions migration SQL (IAM DB)
  2b. CoinTransaction entity + CoinTransactionRepository — iam-service
  2c. Sửa UserServiceImpl.addCoins(): nhận operationKey, check coin_transactions
  2d. Sửa cancelBooking(): lưu outbox COIN_REFUND thay vì gọi IAM trực tiếp
  2e. CoinRefundRelayScheduler (cùng pattern FOR UPDATE SKIP LOCKED)
  2f. Sửa IamFeignClient.addCoins() signature: thêm @RequestParam operationKey
  2g. Test double-refund: mock IAM fail lần 1 → restart → coins cộng đúng 1 lần

Phase 3 (out-of-scope — do team khác phụ trách)
  BOOKING_CREATED event, auto-expire, departure reminder → không làm trong plan này

Phase 4 — Frontend notification center  ← sau Phase 1
  4a. AdminNotificationBell.jsx + useAdminNotifications.ts
  4b. UserNotificationBell.jsx + useUserNotifications.ts
  4c. GET /api/notifications/admin?unread=true + PATCH /api/notifications/{id}/read
  4d. SLA badge PENDING_REFUND (màu theo giờ chờ)
  4e. Default sort ASC khi filter PENDING_REFUND trong BookingsPage
  4f. TransactionListItem: hiển thị "Coins sẽ hoàn trong ít phút" khi coinRefundPending > 0
```

---

## 9. Verification sau triển khai

1. **Outbox test**: Tắt notification-service → cancel booking → restart notification-service → email đến trong < 1 phút
2. **RabbitMQ down test**: Tắt RabbitMQ → cancel booking → DB có outbox row PENDING → bật RabbitMQ → scheduler relay → email gửi
3. **Double coin test**: Mock IAM fail lần 1 → booking CANCELLED trong DB → IAM up → coin cộng đúng 1 lần (idempotency key không cho cộng lần 2)
4. **Kiểm tra queue**: Vào `http://localhost:15672` (user: `tourism`, pass: `tourism123`) → xem message rates + consumer count
6. **Kiểm tra DLQ**: Gửi event payload sai format → xem message vào DLQ sau 3 retries

---

## 10. Decisions

| Quyết định | Lý do |
|---|---|
| Outbox trong booking-service DB | Đơn giản hơn, booking-service là nguồn sự thật |
| Exchange: `tourism.events` (không phải `booking.events`) | Linh hoạt hơn — sau này có `tour.*`, `coupon.*`, `forum.*` chỉ cần thêm binding, không tạo exchange mới |
| Coins đi qua Outbox relay (Feign) thay vì queue riêng | Coins cần confirm từ IAM, không fire-and-forget — Feign + retry + idempotency đủ an toàn |
| Idempotency coins: `coin_transactions` table (không phải `lastCoinOperationKey` trong User) | Giữ audit trail, tránh lock contention trên row User, UNIQUE constraint do DB engine xử lý |
| Outbox locking: `FOR UPDATE SKIP LOCKED` | Chuẩn PostgreSQL cho multi-instance scheduler, không cần Redis/ZooKeeper |
| Publisher confirm (`publisher-confirm-type=correlated`) | Mark SENT chỉ khi broker ack — tránh mất message do routing fail giữa chừng |
| Status: NEW/SENDING/SENT/DEAD (không phải PENDING/SENT/FAILED) | Rõ hơn: SENDING = đang xử lý (tránh instance khác lấy trùng), DEAD = quá số retry (cần alert) |
| 2 tầng retry tách biệt (outbox DEAD ≠ consumer DLQ) | Mỗi tầng fail vì lý do khác nhau, xử lý khác nhau |
| Không dùng Spring Cloud Stream | Overhead không cần thiết; Spring AMQP trực tiếp rõ ràng hơn |
| Giữ REST endpoints `/api/notifications/` | Backward compatible, không xóa Feign client ngay — chạy song song trong giai đoạn migration |
| Topic exchange thay vì Direct | Linh hoạt — sau này thêm analytics, forum chỉ cần thêm binding |
| DLQ + retry 3 lần (consumer side) | Tránh mất email khi SMTP timeout, quan sát lỗi qua management UI |
| Analytics queue tách riêng | Notification và analytics có SLA khác nhau |
| Không dùng RabbitMQ Delayed Message Plugin | Plugin không có sẵn trong image `rabbitmq:3-management-alpine`; dùng `@Scheduled` thay thế |
| `BookingStatus.REFUNDED` không thêm | Dùng `CANCELLED + refundStatus=COMPLETED` — tránh phải migrate enum hiện có |
| `BOOKING_CREATED`, `PAYMENT_EXPIRED`, `DEPARTURE_REMINDER` out-of-scope | Không có Feign call nào cho chức năng này trong code hiện tại; team khác phụ trách |
| Không dùng RabbitMQ Delayed Message Plugin | Plugin không có sẵn trong image `rabbitmq:3-management-alpine`; nếu sau này cần auto-expire thì dùng `@Scheduled` |
