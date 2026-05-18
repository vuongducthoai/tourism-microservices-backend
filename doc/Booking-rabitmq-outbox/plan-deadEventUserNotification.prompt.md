# Plan: User Notification cho DEAD Events + Admin Giải Pháp Xử Lý Sự Cố

> File này bổ sung cho `plan-bookingDeadRecovery.prompt.md`.
> Tập trung vào: (1) notify user khi coin/email gặp sự cố, (2) notify lại khi đã xử lý xong,
> (3) hướng dẫn admin xử lý tình huống không cần sửa code, (4) scenario notification-service down.

---

## 1. Phân Tích 4 Tình Huống DEAD

### Tình huống 1 — COIN_REFUND outbox → DEAD (IAM service down)

```
User hủy booking PAID
        │
        ▼  @Transactional
bookings → CANCELLED
outbox_events → COIN_REFUND (NEW)
        │
        ▼ CoinRefundRelayScheduler (mỗi 5s)
Feign → iamClient.addCoins()  ← IAM service DOWN
        │ FeignException (retry++)
        ▼ sau max_retries lần
outbox_events → DEAD
        │
        ▼ HiỆN TẠI: chỉ log.error()
```

**User đang thấy:** booking = CANCELLED, coins chưa vào, không biết gì.
**Cần thêm khi DEAD:** Thông báo user → "Đơn BKxxx: hoàn xu đang gặp sự cố, nhóm hỗ trợ đang xử lý."
**Khi retry thành công:** Thông báo user → "Đã hoàn [N] xu vào tài khoản cho đơn BKxxx."

> **Lưu ý quan trọng:** COIN_REFUND DEAD xảy ra do IAM down, **không phải** RabbitMQ down.
> Tại thời điểm DEAD, RabbitMQ vẫn hoạt động bình thường.
> → booking-service CÓ THỂ publish notification event lên MQ an toàn.

---

### Tình huống 2 — NOTIFICATION outbox → DEAD (RabbitMQ connection down)

```
User hủy booking
        │
        ▼  @Transactional
bookings → CANCELLED
outbox_events → NOTIFICATION (NEW)
        │
        ▼ OutboxRelayScheduler (mỗi 5s)
rabbitTemplate.send()  ← RabbitMQ DOWN / connection timeout
        │ AmqpException (retry++)
        ▼ sau max_retries lần
outbox_events → DEAD
        │
        ▼ HIỆN TẠI: chỉ log.error()
```

**User đang thấy:** booking đã CANCELLED (DB đã lưu đúng), nhưng không nhận được email.
**Vấn đề:** RabbitMQ đang DOWN → không thể publish thêm message lên MQ.
**Giải pháp fallback:** Gọi notification-service trực tiếp qua **Feign** để lưu in-app notification (không gửi email, chỉ lưu record trong DB).

---

### Tình huống 3 — Message đã vào RabbitMQ queue, notification-service DOWN

```
Outbox SENT → message trong booking.notification.queue
                              │
                    notification-service DOWN
                              │
              message ở lại queue (durable=true, persistent)
                              │
                    (chờ service restart)
                              │
                    notification-service restart
                              │
              @RabbitListener auto-reconnect (Spring AMQP)
                              │
              consume message → Spring Retry 3 lần → SUCCESS
```

**Đây là normal flow** — RabbitMQ + durable queue đảm bảo message KHÔNG mất.
**Không cần code thêm.** Message được xử lý tự động khi service restart.

**Edge case:** notification-service restart nhưng vẫn fail (bug code) → sau 3 Spring Retry → DLQ:
```
booking.notification.queue (3 lần fail) → booking.notification.dlq
```
→ Xem Tình huống 4.

---

### Tình huống 4 — Message trong DLQ (notification-service fail sau 3 Spring Retry)

```
Message trong booking.notification.queue
        │ BookingEventListener.onBookingEvent() throws (lần 1)
        │ Spring Retry: wait 2s, retry (lần 2)
        │ Spring Retry: wait 4s, retry (lần 3)
        │ Vẫn fail → re-throw
        ▼
booking.notification.dlq  ← KHÔNG có consumer hiện tại
```

**User đang thấy:** booking updated nhưng không nhận email, không có in-app notification.
**Cần:** DLQ consumer lưu vào `dead_letters` table → admin xem và replay.

---

## 2. Solution cho từng Tình Huống

### Solution 1 — COIN_REFUND DEAD: notify user + notify khi resolved

**Khi DEAD (trong CoinRefundRelayScheduler.processOne):**

```java
// Sau event.incrementRetries(error) và save → kiểm tra nếu vừa trở thành DEAD
if (event.getStatus() == OutboxStatus.DEAD) {
    log.error("COIN REFUND DEAD — event {} needs manual intervention", event.getIdempotencyKey());

    // [MỚI] Publish in-app notification đến user qua RabbitMQ (MQ vẫn hoạt động)
    BookingEventDTO deadNotif = buildDeadCoinNotification(dto); // userId, bookingCode, coinAmount
    deadNotif.setEventType("COIN_REFUND_DEAD");                 // notification-service xử lý type này
    outboxRepository.save(OutboxEventFactory.notification(deadNotif, "COIN_REFUND_DEAD", objectMapper));
    // → notification-service nhận → lưu in-app notification cho user
    // → (optional) WebSocket push ngay cho user đang online
}
```

**Nội dung thông báo user:**
```
Tiêu đề: ⚠️ Gặp sự cố khi hoàn xu
Nội dung: Đơn hàng [BKxxx] của bạn đã hủy thành công, nhưng quá trình hoàn [N] xu
          vào tài khoản đang gặp sự cố kỹ thuật.
          Nhóm hỗ trợ đang xử lý và sẽ cộng xu cho bạn sớm nhất có thể.
```

**Khi retry thành công (trong CoinRefundRelayScheduler.processOne):**

```java
// Sau event.markSent() và save
// [MỚI] Notify user coins đã được cộng
BookingEventDTO successNotif = buildCoinRefundSuccessNotification(dto);
successNotif.setEventType("COIN_REFUND_SUCCESS");
outboxRepository.save(OutboxEventFactory.notification(successNotif, "COIN_REFUND_SUCCESS", objectMapper));
```

**Nội dung thông báo user:**
```
Tiêu đề: ✅ Hoàn xu thành công
Nội dung: Đã hoàn [N] xu vào tài khoản của bạn cho đơn hàng [BKxxx].
          Số dư xu hiện tại được cập nhật trong tài khoản.
```

**notification-service — thêm handler cho 2 event type mới:**

```java
// BookingEventListener.java — thêm 2 case trong switch:
case "COIN_REFUND_DEAD" ->
        notificationService.handleCoinRefundDead(event);

case "COIN_REFUND_SUCCESS" ->
        notificationService.handleCoinRefundSuccess(event);
```

```java
// NotificationServiceImpl.java — 2 method mới:
void handleCoinRefundDead(BookingEventDTO event) {
    // Lưu in-app notification cho user
    saveNotification(event.getUserId(), NotificationType.COIN_REFUND_FAILED,
            "⚠️ Gặp sự cố khi hoàn xu",
            String.format("Đơn %s: hoàn %s xu đang gặp sự cố, đang xử lý.",
                    event.getBookingCode(), event.getCoinRefundAmount()));
    // WebSocket push nếu user đang online
    webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
}

void handleCoinRefundSuccess(BookingEventDTO event) {
    // Lưu in-app notification cho user
    saveNotification(event.getUserId(), NotificationType.COIN_REFUND_COMPLETED,
            "✅ Hoàn xu thành công",
            String.format("Đã hoàn %s xu cho đơn %s.",
                    event.getCoinRefundAmount(), event.getBookingCode()));
    // WebSocket push
    webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
}
```

**Cần thêm vào NotificationType enum:**
```java
COIN_REFUND_FAILED,
COIN_REFUND_COMPLETED,
NOTIFICATION_FAILED    // dùng cho Tình huống 2
```

---

### Solution 2 — NOTIFICATION DEAD: Feign fallback lưu in-app notification

**Khi NOTIFICATION DEAD (trong OutboxRelayScheduler.scheduleRetry):**

RabbitMQ đang DOWN nên KHÔNG publish thêm MQ event. Gọi thẳng notification-service qua Feign:

```java
// Thêm vào OutboxRelayScheduler, inject NotificationFeignClient
private final NotificationFeignClient notificationClient;

private void scheduleRetry(OutboxEvent event, String error) {
    event.incrementRetries(error);
    outboxRepo.save(event);
    if (event.getStatus() == OutboxStatus.DEAD) {
        log.error("OUTBOX DEAD — event {} exhausted retries.", event.getIdempotencyKey());

        // [MỚI] Feign fallback: lưu in-app notification trực tiếp (không qua MQ)
        tryNotifyUserDirectly(event);
    }
}

private void tryNotifyUserDirectly(OutboxEvent event) {
    try {
        // Parse userId từ payload để gọi Feign
        BookingEventDTO dto = objectMapper.readValue(event.getPayload(), BookingEventDTO.class);
        if (dto.getUserId() != null) {
            notificationClient.saveDirectNotification(
                    dto.getUserId(),
                    "NOTIFICATION_FAILED",
                    "ℹ️ Email xác nhận gặp sự cố",
                    String.format("Đơn hàng %s đã được cập nhật. Email xác nhận gặp sự cố gửi, " +
                            "thông tin đơn hàng vẫn được lưu đầy đủ.", dto.getBookingCode()));
        }
    } catch (Exception e) {
        // notification-service cũng có thể down — chỉ log, không throw
        log.error("Feign fallback also failed for event {}: {}", event.getIdempotencyKey(), e.getMessage());
    }
}
```

**Nội dung thông báo user:**
```
Tiêu đề: ℹ️ Email xác nhận gặp sự cố
Nội dung: Đơn hàng [BKxxx] đã được cập nhật thành công trong hệ thống.
          Email xác nhận gặp sự cố khi gửi do lỗi kỹ thuật tạm thời.
          Thông tin đơn hàng của bạn vẫn được lưu đầy đủ.
```

**Thêm endpoint vào notification-service:**
```java
// NotificationController.java
@PostMapping("/internal/direct-notification")
public ResponseEntity<Void> saveDirectNotification(
        @RequestParam Integer userId,
        @RequestParam String type,
        @RequestParam String title,
        @RequestParam String message) {
    notificationService.saveDirectNotification(userId, type, title, message);
    return ResponseEntity.ok().build();
}
```

```java
// NotificationFeignClient.java (booking-service)
@FeignClient(name = "notification-service")
public interface NotificationFeignClient {
    @PostMapping("/api/notifications/internal/direct-notification")
    void saveDirectNotification(
            @RequestParam Integer userId,
            @RequestParam String type,
            @RequestParam String title,
            @RequestParam String message);
}
```

---

### Solution 3 — Message trong queue, notification-service DOWN

**Không cần code thêm.** Cơ chế tự xử lý:

```
booking.notification.queue:
├── durable = true          → queue tồn tại sau RabbitMQ restart
├── message persistent = 2  → message tồn tại sau RabbitMQ restart
└── consumer autoAck = false → message chỉ xóa sau khi ack thành công

notification-service restart:
├── Spring AMQP auto-reconnect (thử lại mỗi 5s theo config)
└── Khi reconnect → tiếp tục consume tất cả message còn lại trong queue
```

**Admin cần làm:** Chỉ cần restart notification-service (xem Section 4).

---

### Solution 4 — DLQ Consumer: lưu dead_letters + notify user

**Khi message vào DLQ, notification-service ĐÃ restart và hoạt động bình thường:**

```java
// DlqConsumer.java (notification-service) — mới
@RabbitListener(queues = RabbitMQConfig.DLQ_NOTIFICATION)
public void onDeadLetter(Message rawMessage) {
    String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);

    // 1. Lưu dead_letter record để admin replay sau
    DeadLetter dl = deadLetterRepo.save(DeadLetter.builder()
            .payload(payload)
            .errorReason(extractDeathReason(rawMessage))
            .replayCount(0)
            .build());

    // 2. Parse event để notify user
    try {
        BookingEventDTO dto = objectMapper.readValue(payload, BookingEventDTO.class);
        if (dto.getUserId() != null) {
            saveNotification(dto.getUserId(), NotificationType.NOTIFICATION_FAILED,
                    "ℹ️ Gặp sự cố gửi email",
                    String.format("Đơn hàng %s đã cập nhật. Email thông báo gặp sự cố kỹ thuật.",
                            dto.getBookingCode()));
            webSocketService.notifyUserBookingUpdate(dto.getUserId(), dto);
        }
    } catch (Exception e) {
        log.error("DLQ: could not parse payload to notify user: {}", e.getMessage());
    }

    log.error("DLQ: message saved as dead_letter id={}", dl.getId());
}
```

**Khi admin replay DLQ message thành công:**
- notification-service xử lý bình thường → user nhận email
- Cần update `dead_letters.replayed_at = NOW()` sau khi re-queue thành công

---

## 3. Files Cần Thêm / Sửa

### booking-service

| File | Loại | Thay đổi |
|------|------|----------|
| `messaging/CoinRefundRelayScheduler.java` | **Sửa** | Khi DEAD: save COIN_REFUND_DEAD notification outbox; khi SENT: save COIN_REFUND_SUCCESS notification outbox |
| `messaging/OutboxRelayScheduler.java` | **Sửa** | Khi DEAD: gọi `notificationClient.saveDirectNotification()` qua Feign |
| `feign/NotificationFeignClient.java` | **Mới** | `POST /api/notifications/internal/direct-notification` |

### notification-service

| File | Loại | Thay đổi |
|------|------|----------|
| `listener/BookingEventListener.java` | **Sửa** | Thêm 2 case: `COIN_REFUND_DEAD`, `COIN_REFUND_SUCCESS` |
| `service/NotificationService.java` | **Sửa** | Thêm interface methods |
| `service/impl/NotificationServiceImpl.java` | **Sửa** | Implement `handleCoinRefundDead()`, `handleCoinRefundSuccess()`, `saveDirectNotification()` |
| `controller/NotificationController.java` | **Sửa** | Thêm `POST /internal/direct-notification` |
| `entity/NotificationType.java` | **Sửa** | Thêm `COIN_REFUND_FAILED`, `COIN_REFUND_COMPLETED`, `NOTIFICATION_FAILED` |
| `listener/DlqConsumer.java` | **Mới** | Consume DLQ → lưu dead_letter + notify user |
| `entity/DeadLetter.java` | **Mới** | Entity cho dead_letters table |
| `repository/DeadLetterRepository.java` | **Mới** | JPA repo |
| `controller/DeadLetterAdminController.java` | **Mới** | Admin list + replay endpoints |

### Frontend

| File | Loại | Thay đổi |
|------|------|----------|
| `TransactionDetailModal.jsx` | **Sửa** | Hiển thị coinRefundStatus (PENDING/COMPLETED/FAILED) |
| `BookingResponseDTO.ts` | **Sửa** | Thêm `coinRefundStatus` field |
| `NotificationBell` component | **Sửa (nếu có)** | Hiển thị in-app notification từ bảng notifications |

---

## 4. Admin Hướng Dẫn Xử Lý Sự Cố (Không Cần Sửa Code)

### 4.1 Scenario: COIN_REFUND events bị DEAD

**Triệu chứng:** User báo hủy booking nhưng chưa nhận xu sau > 20 phút.

**Bước 1 — Xác nhận DEAD events trong DB:**
```sql
-- Kết nối vào booking_db
SELECT id, idempotency_key, routing_key, retries, max_retries, error_message, created_at
FROM outbox_events
WHERE status = 'DEAD' AND routing_key = 'booking.coin.refund'
ORDER BY created_at DESC;
```

**Bước 2 — Kiểm tra IAM service:**
```bash
docker compose ps iam-service
docker compose logs --tail=50 iam-service
curl http://localhost:8081/actuator/health
```

**Bước 3a — Nếu IAM đã khôi phục, retry via Admin API:**
```bash
# Retry từng event
POST /api/bookings/admin/outbox/retry/{id}

# Retry tất cả DEAD events
POST /api/bookings/admin/outbox/retry-all
```

**Bước 3b — Nếu IAM CHƯA khôi phục, cộng xu thủ công:**
```bash
# Gọi trực tiếp IAM endpoint (bypass outbox)
POST http://localhost:8081/api/users/{userId}/coins?amount={coinAmount}&operationKey=MANUAL_{bookingCode}
```
Lấy `userId` và `coinAmount` từ payload trong `outbox_events.payload` (JSON).

**Bước 4 — Sau khi cộng thủ công, đánh dấu event là SENT để tránh retry:**
```sql
UPDATE outbox_events
SET status = 'SENT', sent_at = NOW(), error_message = 'Manually credited by admin'
WHERE id = {event_id};
```

---

### 4.2 Scenario: NOTIFICATION events bị DEAD (email không gửi được)

**Triệu chứng:** User báo không nhận email sau khi hủy/xác nhận booking.

**Bước 1 — Xác nhận DEAD events:**
```sql
SELECT id, idempotency_key, routing_key, error_message, created_at
FROM outbox_events
WHERE status = 'DEAD' AND routing_key != 'booking.coin.refund'
ORDER BY created_at DESC;
```

**Bước 2 — Kiểm tra RabbitMQ:**
```bash
# Mở RabbitMQ Management UI
http://localhost:15672  (guest/guest)
# Kiểm tra: Overview → Connections, Channels
# Kiểm tra queue depth: booking.notification.queue
```

**Bước 3 — Nếu RabbitMQ đã khôi phục:**
```bash
# Retry events — scheduler sẽ gửi lại email
POST /api/bookings/admin/outbox/retry-all
```

**Bước 4 — Gửi email thủ công nếu cần ngay:**
Hiện chưa có endpoint gửi email thủ công — cần làm thủ công qua admin email client.

---

### 4.3 Scenario: notification-service DOWN (messages đang chờ trong queue)

**Triệu chứng:** Emails bị delay nhiều phút, queue depth tăng cao.

**Bước 1 — Kiểm tra:**
```bash
docker compose ps notification-service
docker compose logs --tail=100 notification-service
# Kiểm tra queue depth tại http://localhost:15672
# booking.notification.queue → messages ready count cao
```

**Bước 2 — Restart service:**
```bash
docker compose restart notification-service
# Sau khi restart, Spring AMQP tự reconnect và consume hết queue
```

**Bước 3 — Verify:**
```bash
docker compose logs -f notification-service
# Tìm log: "Received booking event: type=..." xác nhận đang consume
# Kiểm tra queue depth giảm về 0 trên Management UI
```

**Lưu ý:** Không cần làm gì thêm. Messages KHÔNG bị mất nhờ durable queue.

---

### 4.4 Scenario: Messages vào DLQ (booking.notification.dlq)

**Triệu chứng:** notification-service restart nhưng vẫn fail xử lý một số message cụ thể.

**Bước 1 — Kiểm tra DLQ:**
```bash
# RabbitMQ Management UI → Queues → booking.notification.dlq
# Xem message count và message content (click "Get messages")
```

**Bước 2 — Xem dead_letters trong DB (sau khi DLQ consumer được deploy):**
```sql
-- Kết nối notification_db
SELECT id, payload, error_reason, received_at, replayed_at, replay_count
FROM dead_letters
WHERE replayed_at IS NULL
ORDER BY received_at DESC;
```

**Bước 3 — Replay via Admin API:**
```bash
POST /api/admin/notifications/dead-letters/{id}/replay
```
Giới hạn: chỉ replay nếu `replay_count < 3` để tránh loop.

**Bước 4 — Nếu payload bị corrupt / không thể replay:**
```sql
-- Đánh dấu đã xử lý thủ công
UPDATE dead_letters
SET replayed_at = NOW(), replay_count = 99  -- 99 = manual, không auto-replay nữa
WHERE id = {id};
```

---

### 4.5 Quick Reference — Trạng Thái DEAD và Hành Động

| Loại DEAD | Nguyên nhân thường gặp | Hành động nhanh |
|-----------|----------------------|----------------|
| COIN_REFUND DEAD | IAM service down | Restart IAM → retry API |
| NOTIFICATION DEAD | RabbitMQ down | Restart RabbitMQ → retry API |
| Message vào DLQ | Bug trong notification-service | Xem log → fix → deploy → replay |
| notification-service down | OOM / crash | `docker compose restart` |

---

## 5. Luồng Tổng Hợp Sau Khi Implement

```
User hủy booking
        │
        ▼ @Transactional
booking → CANCELLED
outbox COIN_REFUND (NEW)
outbox NOTIFICATION (NEW)
        │
   ┌────┴────────────────────────────────────────────────┐
   │ CoinRefundRelayScheduler              OutboxRelayScheduler │
   │                                                      │
   │ IAM OK → SENT                          MQ OK → SENT  │
   │    └─ outbox COIN_REFUND_SUCCESS          └─ notif gửi email   │
   │         → user: "✅ Đã hoàn xu"              → user nhận email │
   │                                                      │
   │ IAM DOWN → DEAD                        MQ DOWN → DEAD │
   │    ├─ outbox COIN_REFUND_DEAD          ├─ Feign fallback:     │
   │    │    → user: "⚠️ Hoàn xu sự cố"    │   in-app notification │
   │    └─ admin thấy trong Dead Events    └─ admin retry khi MQ up│
   └─────────────────────────────────────────────────────┘

Notification-service down:
booking.notification.queue (durable)
        │ messages persist
        ▼ service restart
consume → process → email sent (tự động)
        │ nếu vẫn fail 3 lần
        ▼
booking.notification.dlq → DlqConsumer
        ├── lưu dead_letters
        └── in-app notification user: "ℹ️ Email gặp sự cố"
```

---

## 6. Bảng Ưu Tiên Implementation

| # | Feature | Ưu tiên | Service | Effort |
|---|---------|---------|---------|--------|
| 1 | COIN_REFUND_DEAD → notify user (in-app + WS) | 🔴 Cao | booking + notif | 2h |
| 2 | COIN_REFUND_SUCCESS → notify user khi retry thành công | 🔴 Cao | booking + notif | 1h |
| 3 | DLQ Consumer → lưu dead_letters + notify user | 🟠 Cao | notification | 2h |
| 4 | Feign fallback khi NOTIFICATION DEAD | 🟡 Medium | booking + notif | 1.5h |
| 5 | Admin retry API (không JWT, chỉ check header X-Admin) | 🟡 Medium | booking | 2h |
| 6 | Admin dead_letters replay endpoint | 🟡 Medium | notification | 1h |
| 7 | Frontend: coinRefundStatus display | 🟡 Medium | frontend | 1h |
| 8 | Frontend: Admin Dead Events dashboard | 🟢 Low | frontend | 3h |
