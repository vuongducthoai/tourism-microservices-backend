# Plan: Booking Status Display + DEAD Outbox Recovery + DLQ Replay

**TL;DR:** Hệ thống hiện tại đã có Outbox Pattern tốt, nhưng thiếu 3 thứ quan trọng: (1) khi outbox → DEAD thì chỉ `log.error` — không có recovery, đặc biệt coin refund DEAD = mất tiền user; (2) RabbitMQ DLQ chưa có consumer — message nằm chết mãi; (3) Frontend không hiển thị thông tin hoàn tiền khi booking `PENDING_REFUND`. Plan này đề xuất giải pháp **phân tầng ưu tiên** từ khẩn cấp đến cải thiện dài hạn.

---

## Phân Tích Gap (Vấn Đề Hiện Tại)

```
OutboxEvent.incrementRetries(error)
    retries >= maxRetries(5) → status = DEAD
    → log.error("OUTBOX DEAD ... Manual intervention required")
    → KHÔNG GÌ XẢY RA THÊM
```

| Gap | Nghiêm trọng | Tác động |
|-----|-------------|----------|
| DEAD Coin Refund không recovery | 🔴 Rất cao | User hủy booking nhưng không nhận xu → mất tiền |
| DEAD Notification không alert | 🟡 Trung bình | User/admin không nhận email — trải nghiệm kém |
| DLQ không có consumer | 🟡 Trung bình | Message trong `booking.notification.dlq` chết mãi |
| `TransactionDetailModal` thiếu refund info | 🟠 Cao | User không biết số tiền hoàn/tài khoản nhận tiền |
| Không có Admin UI xem DEAD events | 🟡 Trung bình | DevOps mù — phải vào DB kiểm tra thủ công |

---

## Phase 1 — Khẩn Cấp: DEAD Coin Refund Recovery (Ưu tiên #1)

**Vấn đề cụ thể:**
```
Timeline nguy hiểm:
 T=0      User hủy booking → outbox COIN_REFUND (NEW)
 T=5s     CoinRefundRelayScheduler chạy → IAM down → retries++
 T=5*2^n  Exponential backoff, sau 5 lần → DEAD
 T=∞      User không bao giờ nhận xu — booking = CANCELLED, coins = 0
```

**Giải pháp 3 lớp bảo vệ:**

### Lớp 1 — Tăng `maxRetries` cho COIN_REFUND lên 20 (thay vì 5)

- Hiện tại max_retries=5, backoff: 30s, 60s, 120s, 240s, 480s → tổng ~15 phút rồi DEAD
- Với coin refund: tăng lên 20 → grace period ~3 giờ trước khi DEAD
- Thực hiện: `OutboxEventFactory.coinRefund()` set `.maxRetries(20)` trong builder

**File cần sửa:**
- `booking-service/.../messaging/OutboxEventFactory.java` — builder `.maxRetries(20)` cho coinRefund path

### Lớp 2 — Alert Email khi DEAD (mới)

Thêm `DeadEventAlertScheduler` trong booking-service, chạy mỗi 10 phút:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadEventAlertScheduler {

    private final OutboxEventRepository outboxRepo;
    private final MailService mailService; // inject hoặc gọi qua HTTP

    @Scheduled(fixedDelay = 600_000)
    public void checkDeadEvents() {
        List<OutboxEvent> coinDeadList = outboxRepo.findByStatusAndRoutingKey(
                OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND);
        long notifDead = outboxRepo.countByStatus(OutboxStatus.DEAD) - coinDeadList.size();

        if (!coinDeadList.isEmpty()) {
            log.error("ALERT: {} COIN REFUND events are DEAD — users missing coins!", coinDeadList.size());
            // TODO: gọi mail alert đến admin email
            // mailService.sendDeadCoinRefundAlert(coinDeadList);
        }
        if (notifDead > 0) {
            log.warn("ALERT: {} NOTIFICATION events are DEAD", notifDead);
        }
    }
}
```

**File cần thêm:**
- `booking-service/.../messaging/DeadEventAlertScheduler.java` (mới)

**File cần sửa:**
- `booking-service/.../repository/OutboxEventRepository.java` — thêm:
  ```java
  List<OutboxEvent> findByStatusAndRoutingKey(OutboxStatus status, String routingKey);
  ```

### Lớp 3 — Admin API + Manual Retry (mới)

Thêm `DeadEventAdminController` trong booking-service:

```
GET  /api/bookings/admin/outbox/dead          → list tất cả DEAD events (phân trang)
GET  /api/bookings/admin/outbox/dead/count    → đếm DEAD (cho badge UI)
POST /api/bookings/admin/outbox/retry/{id}    → reset 1 event: DEAD → NEW, retries=0
POST /api/bookings/admin/outbox/retry-all     → reset tất cả DEAD → NEW
```

Logic retry (trong service layer):
```java
public void retryDeadEvent(Long id) {
    OutboxEvent event = outboxRepo.findById(id).orElseThrow();
    if (event.getStatus() != OutboxStatus.DEAD) {
        throw new IllegalStateException("Event is not DEAD");
    }
    event.setStatus(OutboxStatus.NEW);
    event.setRetries(0);
    event.setNextRetryAt(LocalDateTime.now());
    event.setErrorMessage(null);
    outboxRepo.save(event);
    // Scheduler sẽ pick up trong ≤5s
}
```

**Files cần thêm:**
- `booking-service/.../controller/DeadEventAdminController.java` (mới)
- `booking-service/.../service/DeadEventAdminService.java` (mới)

---

## Phase 2 — Quan Trọng: RabbitMQ DLQ Consumer

**Vấn đề:** Khi `BookingEventListener.onBookingEvent()` ném Exception sau 3 Spring Retry → message vào `booking.notification.dlq`. Hiện không có consumer.

**Sơ đồ hiện tại:**
```
Outbox → MQ → notification.queue → BookingEventListener
                                        │ throw (3 lần)
                                        ▼
                              booking.notification.dlq  ← DEAD END (không có consumer)
```

**Giải pháp — 2 hướng:**

### Option A (Khuyến nghị): DLQ Consumer lưu vào bảng `dead_letters`

Thêm `DlqConsumer` trong notification-service:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DlqConsumer {

    private final DeadLetterRepository deadLetterRepo;
    private final MailService mailService;

    @RabbitListener(queues = RabbitMQConfig.DLQ_NOTIFICATION)
    public void onDeadLetter(Message rawMessage) {
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        // 1. Lưu vào bảng dead_letters
        DeadLetter dl = DeadLetter.builder()
                .queue(DLQ_NOTIFICATION)
                .payload(payload)
                .errorReason(extractDeathReason(rawMessage))
                .build();
        deadLetterRepo.save(dl);
        // 2. Log + alert
        log.error("DLQ: notification message dead-lettered, saved as id={}", dl.getId());
        // 3. (Optional) alert admin email
    }

    private String extractDeathReason(Message msg) {
        // x-death header từ RabbitMQ
        List<?> deaths = (List<?>) msg.getMessageProperties()
                .getHeaders().get("x-death");
        // parse reason từ deaths[0]
        return deaths != null ? deaths.toString() : "unknown";
    }
}
```

Bảng `dead_letters` trong notification_db:
```sql
CREATE TABLE dead_letters (
    id           BIGSERIAL PRIMARY KEY,
    queue        VARCHAR(100),
    payload      TEXT,
    error_reason TEXT,
    received_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    replayed_at  TIMESTAMP           -- null nếu chưa replay
);
```

Admin endpoint để replay:
```
GET  /api/admin/notifications/dead-letters          → list
POST /api/admin/notifications/dead-letters/{id}/replay → re-queue về main queue
```

**Files cần thêm (notification-service):**
- `notification-service/.../listener/DlqConsumer.java` (mới)
- `notification-service/.../entity/DeadLetter.java` (mới)
- `notification-service/.../repository/DeadLetterRepository.java` (mới)
- `notification-service/.../controller/DeadLetterAdminController.java` (mới)

### Option B (Đơn giản hơn): x-dead-letter-exchange tự replay

Cấu hình DLQ với TTL → auto reroute về main queue sau 30 phút:

```java
// Trong RabbitMQConfig.java
QueueBuilder.durable(DLQ_NOTIFICATION)
    .withArgument("x-message-ttl", 1_800_000)          // 30 phút
    .withArgument("x-dead-letter-exchange", EXCHANGE)
    .withArgument("x-dead-letter-routing-key", RK_NOTIFICATION)
    .build()
```

⚠️ **Rủi ro Option B**: nếu lỗi là permanent (bug code), message sẽ loop DLQ → main → DLQ vô hạn.

**Khuyến nghị: Option A** — control tốt hơn, có audit trail.

---

## Phase 3 — Frontend: Hiển Thị Thông Tin Hoàn Tiền

**Vấn đề:**
- `TransactionDetailModal.jsx`: không hiển thị `refundAmount`, `refundBank`, `refundAccountNumber`, `refundAccountName`
- `TransactionListItem.jsx`: trạng thái `PENDING_REFUND` không cho user biết thêm gì
- `BookingResponseDTO.ts`: thiếu field `duration` (backend có nhưng TS DTO chưa map)

### Fix 1 — TransactionDetailModal.jsx: thêm Section "Thông tin hoàn tiền"

Thêm section này **chỉ khi** `booking.bookingStatus === 'PENDING_REFUND' || booking.refundAmount > 0`:

```jsx
{/* Section: Thông tin hoàn tiền */}
{(booking.bookingStatus === 'PENDING_REFUND' || booking.refundAmount > 0) && (
    <div className={styles.refundSection}>
        <h4>💰 Thông tin hoàn tiền</h4>
        <div className={styles.refundRow}>
            <span>Số tiền hoàn:</span>
            <strong>{formatPrice(booking.refundAmount)}</strong>
        </div>
        {booking.refundBank && (
            <div className={styles.refundRow}>
                <span>Ngân hàng:</span>
                <span>{booking.refundBank}</span>
            </div>
        )}
        {booking.refundAccountNumber && (
            <div className={styles.refundRow}>
                <span>Số tài khoản:</span>
                <span>{maskAccountNumber(booking.refundAccountNumber)}</span>
            </div>
        )}
        {booking.refundAccountName && (
            <div className={styles.refundRow}>
                <span>Chủ tài khoản:</span>
                <span>{booking.refundAccountName}</span>
            </div>
        )}
        <div className={styles.refundRow}>
            <span>Trạng thái:</span>
            <span className={
                booking.bookingStatus === 'PENDING_REFUND' ? styles.statusPending : styles.statusDone
            }>
                {booking.bookingStatus === 'PENDING_REFUND' ? '⏳ Đang chờ xử lý' : '✅ Đã hoàn tiền'}
            </span>
        </div>
    </div>
)}
```

Helper `maskAccountNumber`: hiển thị `****1234` (chỉ 4 số cuối) để bảo mật.

**File cần sửa:**
- `tourism_frontend/.../TransactionDetailModal/TransactionDetailModal.jsx`

### Fix 2 — TransactionListItem.jsx: thêm info cho PENDING_REFUND

Trong `renderActionArea()` case `PENDING_REFUND`, thêm informational display:

```jsx
case 'PENDING_REFUND':
    return (
        <div className={styles.refundInfo}>
            <p>Yêu cầu hoàn tiền đang được xử lý bởi quản trị viên.</p>
            {booking.refundAmount > 0 && (
                <p>Số tiền hoàn: <strong>{formatPrice(booking.refundAmount)}</strong></p>
            )}
        </div>
    );
```

**File cần sửa:**
- `tourism_frontend/.../TransactionListItem/TransactionListItem.jsx`

### Fix 3 — BookingResponseDTO.ts: thêm field `duration`

`duration` dùng để tính `hasTourEnded` trong TransactionListItem — hiện tại fallback về `hasDeparted` gây ra bug khi tour kéo dài nhiều ngày nhưng chưa kết thúc.

```typescript
// Trong BookingResponseDTO.ts, thêm sau departureDate:
private _duration: string = "";
get duration(): string { return this._duration; }

// Trong fromApiResponse():
dto._duration = data.duration ?? "";
```

**File cần sửa:**
- `tourism_frontend/.../dto/responseDTO/BookingResponseDTO.ts`

---

## Phase 4 — Admin UI: Dead Events Dashboard (cải thiện dài hạn)

**Thêm trang Admin mới** để quản lý DEAD outbox events:

```
Quản Lý Hệ Thống → Dead Events
┌──────────────────────────────────────────────────────────────────┐
│ 🔴 3 COIN REFUND events thất bại — CẦN XỬ LÝ NGAY              │
│ 🟡 2 NOTIFICATION events thất bại                               │
├──────────────────────────────────────────────────────────────────┤
│ ID  │ Loại          │ Booking   │ Lỗi        │ Retries │ Action  │
│ 12  │ COIN_REFUND   │ BK20250   │ IAM 503    │ 20/20   │ [Retry] │
│ 15  │ NOTIFICATION  │ BK20251   │ MQ conn.   │ 5/5     │ [Retry] │
└──────────────────────────────────────────────────────────────────┘
         [Retry Tất Cả Coin Refund]    [Retry Tất Cả]
```

Badge số lượng DEAD trên sidebar admin — poll `GET /api/bookings/admin/outbox/dead/count` mỗi 60s.

**Files cần thêm (frontend):**
- `tourism_frontend/.../AdminComponent/Pages/DeadEventsPage/DeadEventsPage.jsx` (mới)
- `tourism_frontend/.../services/outboxAdminService.ts` (mới — gọi admin outbox APIs)

---

## Verification Steps

1. **Test DEAD Recovery**: Set `maxRetries=1` tạm thời → tạo booking cancel với IAM down → verify event → DEAD → gọi `POST /retry/{id}` → verify SENT và coins cộng vào user
2. **Test DLQ Consumer**: Inject Exception trong `onBookingEvent()` → verify message vào DLQ → verify `dead_letters` table có row
3. **Test Frontend Refund Display**: Booking status=PENDING_REFUND với `refundBank/refundAccountNumber` có dữ liệu → mở TransactionDetailModal → verify refund section hiển thị đúng
4. **Test Alert Scheduler**: Set schedule 10s trong test → create DEAD event → verify `log.error` với text "ALERT: N COIN REFUND events are DEAD"

---

## Bảng Độ Ưu Tiên Tổng Hợp

| # | Task | Mức độ | Layer | Effort |
|---|------|--------|-------|--------|
| 1 | DEAD Coin: tăng maxRetries → 20 | 🔴 Khẩn | Backend | 15 phút |
| 2 | DEAD Coin: Admin retry API | 🔴 Khẩn | Backend | 2 giờ |
| 3 | Frontend: refund info trong TransactionDetailModal | 🟠 Cao | Frontend | 1 giờ |
| 4 | DEAD Alert Scheduler (log + future email) | 🟠 Cao | Backend | 1 giờ |
| 5 | DLQ Consumer + dead_letters table | 🟡 Medium | Backend (notif) | 2 giờ |
| 6 | TransactionListItem: PENDING_REFUND info display | 🟡 Medium | Frontend | 30 phút |
| 7 | BookingResponseDTO.ts: thêm `duration` field | 🟡 Medium | Frontend | 20 phút |
| 8 | Admin Dead Events Dashboard UI | 🟢 Low | Frontend | 3 giờ |

---

## Notes & Open Questions

1. **Manual Coin Credit fallback**: Nếu IAM bị lỗi vĩnh viễn (không tạm thời), cần endpoint admin để credit coins thủ công theo bookingCode — nằm ngoài scope hiện tại, cần confirm.

2. **Idempotency khi Admin Retry**: Khi admin retry một DEAD event, IAM `coin_transactions` UNIQUE key bảo vệ không double-credit. An toàn để bấm retry nhiều lần.

3. **Monitoring tương lai**: `OutboxEventRepository.countByStatus(DEAD)` có thể expose qua Spring Boot Actuator custom health indicator để tích hợp Grafana/PagerDuty sau này.

4. **DLQ loop prevention (Option A)**: `dead_letters.replayed_at` phải set khi replay — nếu replay thất bại lần thứ 2 thì NOT replay lại (cần thêm `replayCount` field để chặn infinite loop).
