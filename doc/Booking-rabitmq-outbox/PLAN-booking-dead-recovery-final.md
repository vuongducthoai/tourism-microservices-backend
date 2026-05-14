# Plan: Booking Outbox DEAD Recovery + Coin Refund Status

> **Phiên bản:** Final v2 — 14/05/2026
> **Scope:** booking-service + frontend. Không làm dead_letters table, không Feign fallback, không JWT trong bản này.

---

## 1. Trạng Thái Hiện Tại

### Topology RabbitMQ

```
Exchange : tourism.events (topic, durable)
Queue    : booking.notification.queue  ← binding "booking.notification.*"
DLQ      : booking.notification.dlq   ← x-dead-letter-routing-key
```

### Hai loại outbox event

| routing_key | Scheduler đọc | Cơ chế gửi |
|-------------|---------------|------------|
| `booking.notification.event` | `OutboxRelayScheduler` | Publish lên RabbitMQ → notification-service consume |
| `booking.coin.refund` | `CoinRefundRelayScheduler` | Feign → `iamClient.addCoins()` — KHÔNG qua MQ |

### Gaps cần fix

| # | Vấn đề | File bị ảnh hưởng |
|---|--------|-------------------|
| 1 | `CoinRefundRelayScheduler.relay()` không gọi `resetStaleLocks()` → lock treo vĩnh viễn nếu instance crash | `CoinRefundRelayScheduler.java` |
| 2 | `OutboxEvent.incrementRetries()` không có backoff cap → `2^20 * 30s ≈ 364 ngày` | `OutboxEvent.java` |
| 3 | `OutboxEventFactory.coinRefund()` dùng `maxRetries=5` (default) → DEAD sau ~15 phút | `OutboxEventFactory.java` |
| 4 | `Booking` entity thiếu `coinRefundStatus` → user/admin không biết trạng thái hoàn xu | `Booking.java` |
| 5 | `BookingResponse` có `refundStatus` nhưng chưa map `coinRefundStatus` từ entity | `BookingResponse.java` |
| 6 | `OutboxEventRepository` thiếu `findByStatusAndRoutingKey()` cho Admin API | `OutboxEventRepository.java` |
| 7 | Không có Admin retry API → phải sửa DB tay khi có DEAD event | (chưa có) |
| 8 | Frontend không hiển thị trạng thái hoàn xu và thông tin hoàn tiền ngân hàng | `TransactionDetailModal.jsx` |
| 9 | `BookingResponseDTO.ts` thiếu `coinRefundStatus` | `BookingResponseDTO.ts` |

---

## 2. Kiến Trúc Sau Khi Sửa

```
cancelBooking() [@Transactional]
├── tính refundAmount, coinRefundAmount
├── booking.bookingStatus    = CANCELLED
├── booking.coinRefundStatus = PENDING    ← MỚI (chỉ khi paidByCoin > 0)
├── save booking
├── save outbox routing_key=booking.coin.refund   (nếu coinRefundAmount > 0)
└── save outbox routing_key=booking.notification.event
    └── return BookingResponse ngay — không chờ IAM hay RabbitMQ

CoinRefundRelayScheduler [fixedDelay=5s]
├── resetStaleLocks(now - 5min)               ← MỚI: fix stale lock bug
├── claimBatch() → mark SENDING
└── processOne()
    ├── [OK]  IAM.addCoins() thành công
    │         ├── outbox.markSent()
    │         └── booking.coinRefundStatus = COMPLETED   ← MỚI
    ├── [409] IAM duplicate operationKey → đã cộng rồi, mark SENT ngay   ← MỚI
    └── [FAIL] IAM exception khác
              ├── incrementRetries(error) — với backoff cap   ← MỚI
              ├── còn retry → outbox NEW, nextRetryAt = now + min(2^n*30, 3600)s
              └── hết retry → outbox DEAD
                             └── booking.coinRefundStatus = FAILED   ← MỚI

OutboxRelayScheduler [fixedDelay=5s — không đổi]
├── resetStaleLocks() (đã có)
├── claimBatch() → lọc ra booking.coin.refund (đã có)
└── publish to RabbitMQ → notification-service consume

Admin Retry API [no JWT — internal only]
├── GET  /api/admin/outbox/dead?page=0&size=20
├── GET  /api/admin/outbox/dead/count
├── POST /api/admin/outbox/retry/{id}
└── POST /api/admin/outbox/retry-all?routingKey=booking.coin.refund
```

### Backoff schedule — maxRetries=20, maxBackoffSecs=3600

| Retry lần | Formula | Backoff thực | Tổng elapsed |
|-----------|---------|-------------|--------------|
| 1 | min(2¹×30, 3600) | 60s | 1 phút |
| 2 | min(2²×30, 3600) | 120s | 3 phút |
| 3 | min(2³×30, 3600) | 240s | 7 phút |
| 4 | min(2⁴×30, 3600) | 480s | 15 phút |
| 5 | min(2⁵×30, 3600) | 960s | 31 phút |
| 6 | min(2⁶×30, 3600) = 1920 → **3600s** | 3600s | ~1.5h |
| 7–20 | capped 3600s/lần | 3600s | **~15 giờ** total |

> Grace period ≈ 54.180s ≈ **15 giờ** — đủ để restart IAM và xử lý mà không cần can thiệp tay.

---

## 3. Chi Tiết Từng Thay Đổi

### 3.1 `entity/OutboxEvent.java` — thêm `maxBackoffSecs`, fix `incrementRetries()`

**Thêm field:**

```java
@Column(name = "max_backoff_secs", nullable = false)
@Builder.Default
private long maxBackoffSecs = 3600L;   // default 1 giờ tối đa/lần retry
```

**Sửa `incrementRetries()` — thêm `Math.min` để cap backoff:**

```java
public void incrementRetries(String error) {
    this.retries++;
    this.errorMessage = error;
    if (this.retries >= this.maxRetries) {
        this.status = OutboxStatus.DEAD;
    } else {
        long backoffSecs = Math.min(
            (long) Math.pow(2, this.retries) * 30L,
            this.maxBackoffSecs               // ← THÊM: không tăng vô hạn
        );
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSecs);
        this.status = OutboxStatus.NEW;
    }
    this.lockedBy = null;
    this.lockedAt = null;
}
```

**DB migration:**

```sql
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS max_backoff_secs BIGINT NOT NULL DEFAULT 3600;
```

---

### 3.2 `messaging/OutboxEventFactory.java` — `coinRefund()` set maxRetries=20

```java
public static OutboxEvent coinRefund(BookingEventDTO dto, ObjectMapper mapper) {
    String key = buildKey(dto.getBookingCode(), "COIN_REFUND");
    dto.setIdempotencyKey(key);
    dto.setCoinRefundOperationKey(key);   // giữ operationKey gốc — IAM dùng để chống cộng trùng

    return OutboxEvent.builder()
            .idempotencyKey(key)
            .exchange(RabbitMQConfig.EXCHANGE)
            .routingKey(RabbitMQConfig.RK_COIN_REFUND)
            .payload(toJson(dto, mapper))
            .maxRetries(20)           // ← THÊM: coin refund cần nhiều cơ hội hơn notification
            .maxBackoffSecs(3600L)    // ← THÊM: cap 1 giờ
            .build();
}
```

---

### 3.3 `entity/Booking.java` — thêm `coinRefundStatus`

```java
/**
 * Trạng thái hoàn xu. Chỉ có giá trị khi booking bị cancel và paidByCoin > 0.
 *
 * null       – booking không dùng xu (paidByCoin = 0)
 * PENDING    – đã lưu outbox COIN_REFUND, chờ CoinRefundRelayScheduler
 * COMPLETED  – IAM đã cộng xu thành công (idempotent — dù retry bao nhiêu lần)
 * FAILED     – outbox DEAD sau maxRetries=20 lần, admin cần can thiệp
 */
@Column(name = "coin_refund_status", length = 20)
private String coinRefundStatus;
```

**DB migration:**

```sql
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS coin_refund_status VARCHAR(20);
```

---

### 3.4 `BookingService` (impl) — set `PENDING` khi cancel

Trong `cancelBooking()`, ngay trước khi `save(booking)`:

```java
// Sau khi tính coinRefundAmount:
if (coinRefundAmount != null && coinRefundAmount.compareTo(BigDecimal.ZERO) > 0) {
    booking.setCoinRefundStatus("PENDING");
    // outbox COIN_REFUND sẽ được lưu bên dưới trong cùng @Transactional
}
```

---

### 3.5 `messaging/CoinRefundRelayScheduler.java` — 3 thay đổi

#### a) Thêm `resetStaleLocks()` ở đầu `relay()`

```java
@Scheduled(fixedDelay = 5_000)
public void relay() {
    // ← THÊM: dọn stale locks trước khi claim batch mới
    // Query đã có trong OutboxEventRepository, idempotent, an toàn khi 2 scheduler gọi cùng lúc
    outboxRepo.resetStaleLocks(LocalDateTime.now().minusMinutes(STALE_MINUTES));

    List<OutboxEvent> batch = claimBatch();
    if (batch.isEmpty()) return;
    log.debug("Processing {} coin refund outbox events", batch.size());
    for (OutboxEvent event : batch) {
        processOne(event);
    }
}
```

#### b) Inject `BookingRepository`, xử lý 3 trường hợp trong `processOne()`

```java
// ← THÊM dependency injection
private final BookingRepository bookingRepo;

@Transactional
public void processOne(OutboxEvent event) {
    try {
        BookingEventDTO dto = objectMapper.readValue(event.getPayload(), BookingEventDTO.class);

        iamClient.addCoins(
                dto.getUserId(),
                dto.getCoinRefundAmount(),
                dto.getCoinRefundOperationKey());

        // [OK] IAM đã cộng xu thành công
        event.markSent();
        outboxRepo.save(event);

        // ← THÊM: cập nhật booking
        bookingRepo.findByBookingCode(dto.getBookingCode()).ifPresent(b -> {
            b.setCoinRefundStatus("COMPLETED");
            bookingRepo.save(b);
        });

        log.info("[COIN_REFUND] COMPLETED: {} coins → userId={}, booking={}",
                dto.getCoinRefundAmount(), dto.getUserId(), dto.getBookingCode());

    } catch (FeignException.Conflict e) {
        // [409] operationKey đã tồn tại trong IAM → đã cộng xu rồi, mark SENT
        log.warn("[COIN_REFUND] Duplicate operationKey for event={}, marking SENT (already credited)",
                event.getIdempotencyKey());
        event.markSent();
        outboxRepo.save(event);

        // ← THÊM
        BookingEventDTO dto = parseDto(event);
        if (dto != null) {
            bookingRepo.findByBookingCode(dto.getBookingCode()).ifPresent(b -> {
                b.setCoinRefundStatus("COMPLETED");
                bookingRepo.save(b);
            });
        }

    } catch (Exception e) {
        // [FAIL] Lỗi khác → retry hoặc DEAD
        log.error("Coin refund failed for event {}: {}", event.getIdempotencyKey(), e.getMessage());
        event.incrementRetries(e.getMessage());
        outboxRepo.save(event);

        if (event.getStatus() == OutboxStatus.DEAD) {
            // ← THÊM: log đầy đủ để admin grep
            BookingEventDTO dto = parseDto(event);
            String bookingCode = (dto != null) ? dto.getBookingCode() : "unknown";
            String userId      = (dto != null) ? String.valueOf(dto.getUserId()) : "unknown";
            String amount      = (dto != null) ? String.valueOf(dto.getCoinRefundAmount()) : "unknown";

            log.error("[DEAD] COIN_REFUND event={} booking={} userId={} amount={}",
                    event.getIdempotencyKey(), bookingCode, userId, amount);

            // ← THÊM: cập nhật booking
            if (dto != null) {
                bookingRepo.findByBookingCode(dto.getBookingCode()).ifPresent(b -> {
                    b.setCoinRefundStatus("FAILED");
                    bookingRepo.save(b);
                });
            }
        }
    }
}

// Helper — parse payload, trả null nếu lỗi (không throw để không che exception gốc)
private BookingEventDTO parseDto(OutboxEvent event) {
    try {
        return objectMapper.readValue(event.getPayload(), BookingEventDTO.class);
    } catch (Exception ex) {
        log.error("Cannot parse payload for event {}", event.getIdempotencyKey());
        return null;
    }
}
```

---

### 3.6 `repository/OutboxEventRepository.java` — thêm 2 query

```java
/** Dùng cho Admin API: list/count DEAD theo routingKey */
List<OutboxEvent> findByStatusAndRoutingKey(OutboxStatus status, String routingKey);

/** Dùng cho Admin API: paginated DEAD list, mới nhất lên trước */
@Query("SELECT o FROM OutboxEvent o WHERE o.status = 'DEAD' ORDER BY o.createdAt DESC")
Page<OutboxEvent> findDeadEvents(Pageable pageable);
```

---

### 3.7 `controller/DeadEventAdminController.java` — Admin API (no JWT)

```java
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
@Slf4j
public class DeadEventAdminController {

    private final DeadEventAdminService service;

    /** Danh sách DEAD events có phân trang */
    @GetMapping("/dead")
    public ResponseEntity<Page<OutboxEvent>> listDead(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listDead(page, size));
    }

    /**
     * Đếm DEAD theo loại — dùng cho badge Admin UI.
     * Response: { "coinRefund": 2, "notification": 1, "total": 3 }
     */
    @GetMapping("/dead/count")
    public ResponseEntity<Map<String, Long>> countDead() {
        return ResponseEntity.ok(service.countDead());
    }

    /** Reset 1 DEAD event về NEW để scheduler pick up */
    @PostMapping("/retry/{id}")
    public ResponseEntity<Void> retryOne(@PathVariable Long id) {
        service.retryOne(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Reset tất cả DEAD (hoặc theo routingKey) về NEW.
     * Ví dụ: POST /api/admin/outbox/retry-all?routingKey=booking.coin.refund
     */
    @PostMapping("/retry-all")
    public ResponseEntity<Map<String, Integer>> retryAll(
            @RequestParam(required = false) String routingKey) {
        int count = service.retryAll(routingKey);
        return ResponseEntity.ok(Map.of("retried", count));
    }
}
```

---

### 3.8 `service/DeadEventAdminService.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadEventAdminService {

    private final OutboxEventRepository outboxRepo;

    public Page<OutboxEvent> listDead(int page, int size) {
        return outboxRepo.findDeadEvents(PageRequest.of(page, size));
    }

    public Map<String, Long> countDead() {
        long coin  = outboxRepo.findByStatusAndRoutingKey(
                         OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND).size();
        long total = outboxRepo.countByStatus(OutboxStatus.DEAD);
        return Map.of("coinRefund", coin, "notification", total - coin, "total", total);
    }

    @Transactional
    public void retryOne(Long id) {
        OutboxEvent event = outboxRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        if (event.getStatus() != OutboxStatus.DEAD) {
            throw new IllegalStateException(
                "Event " + id + " is not DEAD (status=" + event.getStatus() + ")");
        }
        resetToNew(event);
        outboxRepo.save(event);
        log.info("[ADMIN] retryOne id={} routingKey={}", id, event.getRoutingKey());
    }

    @Transactional
    public int retryAll(String routingKey) {
        List<OutboxEvent> dead = (routingKey != null)
                ? outboxRepo.findByStatusAndRoutingKey(OutboxStatus.DEAD, routingKey)
                : outboxRepo.findByStatus(OutboxStatus.DEAD);
        dead.forEach(this::resetToNew);
        outboxRepo.saveAll(dead);
        log.info("[ADMIN] retryAll count={} routingKey={}", dead.size(), routingKey);
        return dead.size();
    }

    /**
     * Reset tất cả lock/retry fields.
     * QUAN TRỌNG: phải reset cả lockedBy/lockedAt/sentAt,
     * nếu không scheduler sẽ skip event này (tưởng đang SENDING).
     */
    private void resetToNew(OutboxEvent event) {
        event.setStatus(OutboxStatus.NEW);
        event.setRetries(0);
        event.setNextRetryAt(LocalDateTime.now());
        event.setErrorMessage(null);
        event.setLockedBy(null);    // bắt buộc
        event.setLockedAt(null);    // bắt buộc
        event.setSentAt(null);      // bắt buộc
    }
}
```

---

### 3.9 `dto/response/BookingResponse.java` — thêm `coinRefundStatus`

```java
// Thêm (rename từ refundStatus nếu chỗ nào khác không dùng refundStatus):
private String coinRefundStatus;   // null | PENDING | COMPLETED | FAILED
```

Map từ entity trong builder/mapper:

```java
response.setCoinRefundStatus(booking.getCoinRefundStatus());
```

---

### 3.10 `BookingResponseDTO.ts` (frontend) — thêm `coinRefundStatus`

```typescript
private _coinRefundStatus: string | null = null;
get coinRefundStatus(): string | null { return this._coinRefundStatus; }

// Trong fromApiResponse() hoặc constructor:
dto._coinRefundStatus = data.coinRefundStatus ?? null;
```

---

### 3.11 `TransactionDetailModal.jsx` — 2 section mới

#### Section A — Trạng thái hoàn xu (khi paidByCoin > 0)

```jsx
{booking.paidByCoin > 0 && (
    <div className={styles.coinRefundSection}>
        <h4>🪙 Hoàn xu</h4>
        {booking.coinRefundStatus === 'PENDING' && (
            <p className={styles.statusPending}>⏳ Đang xử lý hoàn xu...</p>
        )}
        {booking.coinRefundStatus === 'COMPLETED' && (
            <p className={styles.statusDone}>✅ Đã hoàn xu thành công</p>
        )}
        {booking.coinRefundStatus === 'FAILED' && (
            <p className={styles.statusFailed}>
                ⚠️ Hoàn xu gặp sự cố — vui lòng liên hệ hỗ trợ
            </p>
        )}
    </div>
)}
```

#### Section B — Thông tin hoàn tiền ngân hàng (khi refundAmount > 0)

```jsx
{booking.refundAmount > 0 && (
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
                <span>****{booking.refundAccountNumber.slice(-4)}</span>
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
                booking.bookingStatus === 'PENDING_REFUND'
                    ? styles.statusPending : styles.statusDone
            }>
                {booking.bookingStatus === 'PENDING_REFUND' ? '⏳ Đang xử lý' : '✅ Đã hoàn'}
            </span>
        </div>
    </div>
)}
```

---

## 4. DB Migrations (booking_db)

```sql
-- 1. Coin refund status trên booking
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS coin_refund_status VARCHAR(20);

-- 2. Backoff cap trên outbox_events
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS max_backoff_secs BIGINT NOT NULL DEFAULT 3600;

-- 3. Index tối ưu Admin DEAD query
CREATE INDEX IF NOT EXISTS idx_outbox_dead
    ON outbox_events (status, routing_key)
    WHERE status = 'DEAD';
```

---

## 5. Thứ Tự Implement

| # | File | Loại | Lý do thứ tự |
|---|------|------|-------------|
| 1 | `entity/OutboxEvent.java` | Sửa | Cơ sở — mọi thứ sau phụ thuộc vào `maxBackoffSecs` |
| 2 | DB migration | Script | Phải có column trước khi Hibernate map |
| 3 | `entity/Booking.java` | Sửa | Entity trước, service sau |
| 4 | `BookingService` impl | Sửa | Set PENDING khi cancel — cần Booking entity |
| 5 | `messaging/OutboxEventFactory.java` | Sửa | Độc lập, không phụ thuộc |
| 6 | `messaging/CoinRefundRelayScheduler.java` | Sửa | Cần BookingRepository + OutboxEvent đã có cap |
| 7 | `repository/OutboxEventRepository.java` | Sửa | Cần cho Admin API |
| 8 | `service/DeadEventAdminService.java` | Tạo mới | Cần repository |
| 9 | `controller/DeadEventAdminController.java` | Tạo mới | Cần service |
| 10 | `dto/response/BookingResponse.java` | Sửa | Cần entity đã có field |
| 11 | `BookingResponseDTO.ts` | Sửa | Cần backend đã expose |
| 12 | `TransactionDetailModal.jsx` | Sửa | Cần TS DTO đã có field |

---

## 6. Test Plan

### Test 1 — Happy path: cancel booking có xu, IAM bình thường

```
1. POST /api/bookings/{id}/cancel (booking có paidByCoin > 0)
   ✓ response.bookingStatus   = CANCELLED
   ✓ response.coinRefundStatus = PENDING

2. Chờ ≤ 5s (scheduler chạy)
   GET /api/bookings/{id}
   ✓ coinRefundStatus = COMPLETED
   ✓ IAM user balance tăng đúng coinRefundAmount

3. Retry IAM với cùng operationKey → balance KHÔNG thay đổi (idempotency)
```

### Test 2 — IAM down: retry backoff → DEAD → FAILED

```
1. Tắt iam-service
2. POST /api/bookings/{id}/cancel
   ✓ coinRefundStatus = PENDING

3. Đặt maxRetries=2 tạm thời trong test để chạy nhanh
4. Quan sát log:
   ✓ [COIN_REFUND] retry 1 sau 60s
   ✓ [COIN_REFUND] retry 2 sau 120s
   ✓ [DEAD] COIN_REFUND event=... booking=... userId=... amount=...

5. GET /api/bookings/{id}
   ✓ coinRefundStatus = FAILED

6. Tắt scheduler ≥ 5 phút, bật lại:
   ✓ resetStaleLocks không xử lý lại event DEAD (status=DEAD, không phải SENDING)
```

### Test 3 — Admin retry sau khi IAM lên lại

```
1. Có DEAD event từ Test 2
2. Bật iam-service lại

3. GET /api/admin/outbox/dead/count
   ✓ { coinRefund: 1, notification: 0, total: 1 }

4. POST /api/admin/outbox/retry/{id}
   ✓ 200 OK

5. Chờ ≤ 5s
   GET /api/bookings/{id}
   ✓ coinRefundStatus = COMPLETED
   GET /api/admin/outbox/dead/count
   ✓ { total: 0 }
   ✓ IAM user balance tăng đúng
```

### Test 4 — retry-all không cộng trùng (idempotency)

```
1. Event đã SENT từ Test 3 (operationKey đã trong IAM coin_transactions)

2. POST /api/admin/outbox/retry-all?routingKey=booking.coin.refund
   ✓ { retried: 1 }  (event được reset NEW vì đã SENT không phải DEAD → thực ra sẽ retried: 0)
   → Thực ra Test 4 nên dùng event DEAD mới chưa xử lý, gọi retry-all,
     rồi verify IAM chỉ cộng 1 lần khi có 409

3. Scheduler chạy → IAM trả 409 → processOne() bắt FeignException.Conflict → markSent()
   ✓ outbox SENT, coinRefundStatus COMPLETED
   ✓ IAM balance KHÔNG thay đổi lần 2
```

### Test 5 — RabbitMQ down: notification outbox retry, tự phục hồi

```
1. Tắt rabbitmq container
2. POST /api/bookings/{id}/cancel

3. OutboxRelayScheduler ghi nhận lỗi, retry theo backoff
   ✓ booking vẫn CANCELLED (booking đã save trong DB trước)

4. Bật rabbitmq lại
5. Chờ nextRetryAt — scheduler publish thành công
   ✓ notification-service nhận → email + WebSocket
```

### Test 6 — notification-service down: message nằm trong queue

```
1. Message đã published lên booking.notification.queue (outbox SENT)
2. Tắt notification-service container

3. Kiểm tra RabbitMQ UI: http://localhost:15672
   ✓ booking.notification.queue → messages ready > 0

4. Bật notification-service lại
5. Kiểm tra RabbitMQ UI: queue depth về 0
   ✓ notification-service log: Received booking event: type=...
   ✓ User nhận email, WebSocket push
   ✓ Message KHÔNG mất (durable queue + persistent message)
```

### Test 7 — Frontend display

```
1. booking.coinRefundStatus = PENDING    → "⏳ Đang xử lý hoàn xu..."
2. booking.coinRefundStatus = COMPLETED  → "✅ Đã hoàn xu thành công"
3. booking.coinRefundStatus = FAILED     → "⚠️ Hoàn xu gặp sự cố — vui lòng liên hệ hỗ trợ"
4. booking.refundAmount > 0              → section hoàn tiền hiển thị
5. refundAccountNumber = "1234567890"    → hiển thị "****7890"
6. User KHÔNG thấy: DEAD, DLQ, outbox, scheduler, SENDING, FAILED (outbox)
```

---

## 7. Scope KHÔNG Làm (bản này)

| Feature | Lý do |
|---------|-------|
| `dead_letters` table + DLQ consumer | Admin kiểm tra DLQ qua RabbitMQ UI là đủ cho demo |
| Feign fallback khi notification DEAD | Notification lỗi là vấn đề vận hành; booking status đúng là đủ |
| JWT / `@PreAuthorize` cho Admin API | IAM chưa có role check; TODO sau khi có auth |
| Admin Dead Events Dashboard (frontend) | Dùng Postman / curl trong giai đoạn đầu |

---

## 8. Danh Sách Files

### booking-service

| File | Thao tác |
|------|----------|
| `entity/OutboxEvent.java` | Sửa — `maxBackoffSecs` + fix `incrementRetries()` |
| `messaging/OutboxEventFactory.java` | Sửa — `.maxRetries(20).maxBackoffSecs(3600L)` |
| `entity/Booking.java` | Sửa — `coinRefundStatus` field |
| `service/impl/BookingServiceImpl.java` | Sửa — set PENDING khi cancel |
| `messaging/CoinRefundRelayScheduler.java` | Sửa — stale lock + booking status update |
| `repository/OutboxEventRepository.java` | Sửa — 2 query mới |
| `dto/response/BookingResponse.java` | Sửa — `coinRefundStatus` |
| `controller/DeadEventAdminController.java` | Tạo mới |
| `service/DeadEventAdminService.java` | Tạo mới |

### Frontend (`tourism_frontend/client-side/src`)

| File | Thao tác |
|------|----------|
| `dto/responseDTO/BookingResponseDTO.ts` | Sửa — `coinRefundStatus` |
| `components/TransactionDetailModal/TransactionDetailModal.jsx` | Sửa — 2 section mới |

### Database (booking_db)

| Migration | Nội dung |
|-----------|----------|
| `ALTER TABLE bookings ADD coin_refund_status` | Column mới |
| `ALTER TABLE outbox_events ADD max_backoff_secs` | Column mới |
| `CREATE INDEX idx_outbox_dead` | Tối ưu Admin query |
