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

**3 vấn đề nghiêm trọng:**
1. **Email mất vĩnh viễn**: notification-service down → `catch (Exception e) { log.error(...) }` → email không bao giờ gửi lại
2. **Double refund risk**: `addCoins` thành công + booking save fail → user nhận coins nhưng booking vẫn PAID → user cancel lại → coins 2 lần
3. **Không có `booking.created` event**: user đặt tour xong không nhận email xác nhận ngay, chỉ nhận khi admin confirm (PAID)

### 1.2 Tại sao RabbitMQ là giải pháp đúng

| Vấn đề | Feign (hiện tại) | RabbitMQ + Outbox |
|---|---|---|
| notification-service down | Email mất vĩnh viễn | Message nằm queue, khi service up lại → tự gửi |
| RabbitMQ down | N/A | Outbox trong DB → scheduler relay khi MQ up |
| Double refund coins | Race condition | Outbox idempotency key → đúng 1 lần |
| Không có email đặt tour | Không có | Event `BOOKING_CREATED` mới |
| Auto-cancel quá hạn | Không có | Scheduler + event |
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
│  │ createBooking() │                        │  │outbox_events │◀──┤    │
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
    │  Exchange: booking.events (topic)     │
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
processOutbox() {
    List<OutboxEvent> pending = outboxRepo.findByStatus(PENDING);
    for (OutboxEvent event : pending) {
        try {
            rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), event.getPayload());
            event.setStatus(SENT);  // mark sent
        } catch (Exception e) {
            event.incrementRetries();
            if (event.getRetries() >= 3) event.setStatus(FAILED); // → DLQ manual review
        }
        outboxRepo.save(event);
    }
}
```

### 3.2 Schema bảng `outbox_events`

```sql
CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,  -- bookingCode_eventType_timestamp
    exchange        VARCHAR(100) NOT NULL,
    routing_key     VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,          -- JSON của BookingEventDTO
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING|SENT|FAILED
    retries         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP,
    error_message   TEXT
);
```

**Idempotency key**: `BK12345678_CANCELLED_1715577600000` → nếu scheduler relay 2 lần cùng key, notification-service nhận 2 lần nhưng check `idempotency_key` và bỏ qua lần 2.

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
Lý do: `addCoins()` là **financial operation** cần confirm ngay, không phải notification. Nhưng với Outbox, thứ tự thực hiện được đảm bảo:
- Booking CANCELLED trong DB trước
- Coin relay chạy sau (tối đa 30s delay — chấp nhận được)
- Idempotency key đảm bảo không double-credit

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
         └── rabbitTemplate.send("booking.events", "booking.notification.confirmed", payload)

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

### Luồng 4: booking.created (MỚI — hiện tại không có)

```
User hoàn tất booking mới (sau payment)
         │
         ▼ @Transactional
         ├── bookings INSERT (status=PENDING_PAYMENT)
         ├── outbox_events INSERT (type=NOTIFICATION, BOOKING_CREATED)
         └── COMMIT

[Outbox Relay → notification-service]
         ├── mailService.sendBookingCreatedEmail()
         │       Subject: "Đặt tour thành công! Vui lòng thanh toán trước [HH:mm DD/MM]"
         │       Body: mã booking, tour, ngày đi, hạn thanh toán, link thanh toán
         ├── saveNotification(userId, BOOKING_CREATED)
         └── webSocketService.notifyUser(userId, {PENDING_PAYMENT})
```

### Luồng 5: Auto-expiry PENDING_PAYMENT (MỚI)

```
@Scheduled(fixedRate = 60_000) // mỗi 1 phút
BookingExpiryScheduler.run() {
    List<Booking> expired = bookingRepository
        .findExpiredPendingPayments(LocalDateTime.now());
        // WHERE status='PENDING_PAYMENT' AND timeLimit < NOW()

    for (Booking b : expired) {
        @Transactional {
            b.setBookingStatus(OVERDUE_PAYMENT);
            bookingRepository.save(b);
            outboxRepository.save(OutboxEvent.notification(b, "PAYMENT_EXPIRED"));
        }
    }
}

[notification-service nhận PAYMENT_EXPIRED]
         ├── mailService.sendPaymentExpiredEmail()
         │       "Booking BKxxx đã hết hạn thanh toán và bị hủy tự động"
         └── webSocketService.notifyUser()
```

### Luồng 6: Departure Reminder (MỚI — nice-to-have)

```
@Scheduled(cron = "0 0 8 * * ?") // mỗi ngày 8h sáng
DepartureReminderScheduler.run() {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    List<Booking> departing = bookingRepository
        .findPaidBookingsDepartingOn(tomorrow);

    for (Booking b : departing) {
        outboxRepository.save(OutboxEvent.notification(b, "DEPARTURE_REMINDER"));
    }
}

[notification-service]
    mailService.sendDepartureReminderEmail()
        "Nhắc nhở: Ngày mai [Tour X] khởi hành. Điểm tập kết: ..."
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

Idempotency trong IAM: thêm field `last_coin_operation_key` vào User entity → nếu key giống nhau → bỏ qua → tránh double credit.

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
| `service/impl/BookingServiceImpl.java` | Sửa | Thay Feign calls → outboxRepository.save() |
| `scheduler/BookingExpiryScheduler.java` | Tạo mới | Auto-expiry PENDING_PAYMENT |
| `repository/BookingRepository.java` | Sửa | Thêm `findExpiredPendingPayments` |
| `entity/Booking.java` | Sửa | Thêm field `coinRefundPending` (nullable) |
| `application.yml` | Sửa | Verify `spring.rabbitmq` + scheduler config |

### Backend — notification-service

| File | Hành động | Mô tả |
|---|---|---|
| `pom.xml` | Sửa | Thêm `spring-boot-starter-amqp` |
| `config/RabbitMQConfig.java` | Tạo mới | Khai báo queue giống booking-service (idempotent) |
| `messaging/BookingEventListener.java` | Tạo mới | `@RabbitListener` dispatch theo `eventType` |
| `service/NotificationService.java` | Sửa | Thêm `handleBookingCreated`, `handleRefundCompleted`, `handlePaymentExpired`, `handleDepartureReminder` |
| `service/impl/NotificationServiceImpl.java` | Sửa | Implement 4 methods mới |
| `service/MailService.java` | Sửa | Thêm 3 method mới |
| `service/impl/MailServiceImpl.java` | Sửa | Email templates cho event mới |
| `dto/BookingEventDTO.java` | Sửa | Thêm field `eventType`, `idempotencyKey` |
| `application.yml` | Sửa | Thêm `spring.rabbitmq` config |

### Backend — IAM service

| File | Hành động | Mô tả |
|---|---|---|
| `entity/User.java` | Sửa | Thêm `lastCoinOperationKey` (String) |
| `service/impl/UserServiceImpl.addCoins()` | Sửa | Check `operationKey` trước khi cộng → idempotent |

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

## 8. Thứ tự triển khai (dependency order)

```
Phase A — Foundation (blocking cho tất cả)
  A1. Outbox entity + repository + migration SQL
  A2. RabbitMQConfig (booking-service + notification-service)
  A3. BookingEventDTO thêm eventType + idempotencyKey

Phase B — Core messaging (sau A)
  B1. OutboxRelayScheduler (booking-service)
  B2. BookingEventListener (notification-service)
  B3. Sửa BookingServiceImpl: thay Feign → outbox.save()

Phase C — Coin reliability (parallel với B)
  C1. Sửa cancelBooking() → outbox COIN_REFUND
  C2. CoinRefundRelayScheduler
  C3. Idempotency trong UserServiceImpl.addCoins()

Phase D — New events (parallel sau B)
  D1. booking.created event
  D2. Auto-expiry scheduler
  D3. Departure reminder scheduler

Phase E — Frontend (parallel sau B)
  E1. AdminNotificationBell + useAdminNotifications
  E2. UserNotificationBell
  E3. SLA badge + sort PENDING_REFUND
  E4. TransactionListItem coin pending message
```

---

## 9. Verification sau triển khai

1. **Outbox test**: Tắt notification-service → cancel booking → restart notification-service → email đến trong < 1 phút
2. **RabbitMQ down test**: Tắt RabbitMQ → cancel booking → DB có outbox row PENDING → bật RabbitMQ → scheduler relay → email gửi
3. **Double coin test**: Mock IAM fail lần 1 → booking CANCELLED trong DB → IAM up → coin cộng đúng 1 lần (idempotency key không cho cộng lần 2)
4. **Auto-expiry test**: Tạo booking `timeLimit = NOW() - 1 phút` → chờ scheduler chạy → status = OVERDUE_PAYMENT
5. **Kiểm tra queue**: Vào `http://localhost:15672` (user: `tourism`, pass: `tourism123`) → xem message rates + consumer count
6. **Kiểm tra DLQ**: Gửi event payload sai format → xem message vào DLQ sau 3 retries

---

## 10. Decisions

| Quyết định | Lý do |
|---|---|
| Outbox trong booking-service DB | Đơn giản hơn, booking-service là nguồn sự thật |
| Coins đi qua Outbox relay (Feign) thay vì queue riêng | Coins cần confirm từ IAM, không fire-and-forget — Feign + retry + idempotency đủ an toàn |
| Không dùng Spring Cloud Stream | Overhead không cần thiết; Spring AMQP trực tiếp rõ ràng hơn |
| Giữ REST endpoints `/api/notifications/` | Backward compatible, không xóa Feign client ngay — chạy song song trong giai đoạn migration |
| Notification read/unread API | Cần thêm `GET /api/notifications/admin?unread=true` và `PATCH /api/notifications/{id}/read` vào notification-service |
| Topic exchange thay vì Direct | Linh hoạt — sau này thêm analytics, forum chỉ cần thêm binding |
| DLQ + retry 3 lần | Tránh mất email khi SMTP timeout, quan sát lỗi qua management UI |
| Analytics queue tách riêng | Notification và analytics có SLA khác nhau |
| Không dùng RabbitMQ Delayed Message Plugin | Plugin không có sẵn trong image `rabbitmq:3-management-alpine`; dùng `@Scheduled` thay thế |
