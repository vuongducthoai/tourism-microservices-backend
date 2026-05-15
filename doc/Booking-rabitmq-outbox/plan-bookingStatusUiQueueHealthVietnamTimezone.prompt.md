# Plan: Sự cố xử lý nền + Queue Health + Giờ Docker Việt Nam

## TL;DR
3 phần độc lập: (1) Chỉ sửa `docker-compose.yml` để containers chạy đúng giờ Việt Nam — không đụng Java code; (2) Thêm Queue Health API vào booking-service cho admin biết tình trạng gửi thông báo; (3) Frontend: đổi trang "DEAD Events" thành tiếng Việt nghiệp vụ, thêm Queue Health widget, coinRefundStatus badge trong admin booking list, cải thiện TransactionDetailModal.

---

## Xác nhận trước khi làm

| Mục | Trạng thái |
|---|---|
| `lucide-react@0.542.0` trong package.json | ✅ Có sẵn |
| `react-icons@5.5.0` trong package.json | ✅ Có sẵn |
| `docker-compose.yml` chưa có `TZ` hay `JAVA_TOOL_OPTIONS` | ⚠️ Cần thêm |
| `TransactionDetailModal.jsx` dùng emoji (⏳✅⚠️🪙💰) | ⚠️ Cần đổi sang icon |
| `BookingItem.jsx` admin chưa hiển thị `coinRefundStatus` | ⚠️ Cần thêm mini badge |
| coin refund là Feign call trực tiếp đến IAM, không qua RabbitMQ | ✅ Queue Health chỉ check notification queue |
| RabbitMQ Management port 15672 accessible trong Docker internal network | ✅ Không cần expose |

---

## Phần 1 — Giờ Docker Việt Nam (chỉ sửa docker-compose.yml)

**Không tạo VnTime.java. Không sửa LocalDateTime.now() trong Java. Không sửa CoinTransaction hay ProcessedEvent.**
Docker `TZ` env var làm `LocalDateTime.now()` trong JVM trả về giờ Việt Nam tự động.

### Sửa `docker-compose.yml`

**PostgreSQL** — thêm vào block `environment` (giữ nguyên các dòng cũ, thêm 2 dòng):
```yaml
postgres:
  environment:
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: postgres
    POSTGRES_DB: postgres
    TZ: Asia/Ho_Chi_Minh
    PGTZ: Asia/Ho_Chi_Minh
```

**Các service Java** — thêm 2 dòng vào `environment` của **tất cả** service:
`booking-service`, `iam-service`, `notification-service`, `payment-service`,
`tour-catalog-service`, `analytics-service`, `forum-service`, `api-gateway`,
`service-discovery`, `config-server`
```yaml
environment:
  - TZ=Asia/Ho_Chi_Minh
  - JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Ho_Chi_Minh
  # ... giữ nguyên các dòng env cũ
```

### Kiểm tra sau rebuild
```bash
docker exec tourism-booking-service date
# Kỳ vọng: Thu May 15 15:40:00 +07 2026

docker exec -it tourism-postgres psql -U postgres -d booking_db -c "show timezone; select now();"
# Kỳ vọng: timezone = Asia/Ho_Chi_Minh, now() hiện giờ VN
```

---

## Phần 2 — Queue Health API (booking-service)

### 2a. Tạo DTO `QueueHealthResponse.java`
File: `booking-service/src/main/java/com/tourism/booking/dto/response/QueueHealthResponse.java`
```java
@Data @Builder
public class QueueHealthResponse {
    private String queue;       // tên queue kỹ thuật (dùng cho log, không show trên UI chính)
    private int    ready;       // số thông báo đang chờ gửi
    private int    unacked;     // số đang được xử lý
    private int    consumers;   // số consumer đang kết nối
    private int    dlqReady;    // số thông báo lỗi nhiều lần
    private String status;      // HEALTHY | BACKLOG | CONSUMER_DOWN | DLQ_ATTENTION | BROKER_DOWN
    private String message;     // mô tả nghiệp vụ tiếng Việt (hiển thị cho admin)
    private String checkedAt;   // LocalDateTime.now() — Docker TZ đã set nên tự ra giờ VN
}
```

### 2b. `application.yml` (booking-service)
Thêm vào cuối file:
```yaml
rabbitmq:
  management:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_MANAGEMENT_PORT:15672}
    username: ${RABBITMQ_USERNAME:tourism}
    password: ${RABBITMQ_PASSWORD:tourism123}
```

### 2c. Tạo `QueueHealthService.java`
File: `booking-service/src/main/java/com/tourism/booking/service/QueueHealthService.java`

```
Quy trình:
1. Gọi GET http://{host}:{port}/api/queues/%2F/booking.notification.queue  (Basic Auth)
2. Gọi GET http://{host}:{port}/api/queues/%2F/booking.notification.dlq
3. Parse: messages_ready → ready, messages_unacknowledged → unacked, consumers → consumers
4. Xác định status (ưu tiên cao → thấp):
   BROKER_DOWN   : exception khi gọi Management API
   DLQ_ATTENTION : dlqReady > 0
   CONSUMER_DOWN : ready > 0 && consumers == 0
   BACKLOG       : ready > 0 && consumers > 0
   HEALTHY       : ready == 0 && dlqReady == 0
5. message tiếng Việt nghiệp vụ theo bảng 2d
6. checkedAt = LocalDateTime.now().toString()  ← Docker TZ đã set, không cần ZoneId
7. Dùng RestTemplate (đã có sẵn trong Spring Boot Web)
```

### 2d. Message tiếng Việt cho từng status
| Status | Message hiển thị |
|---|---|
| HEALTHY | Hệ thống gửi thông báo đang hoạt động bình thường. |
| BACKLOG | Đang có {N} thông báo chờ gửi. Hệ thống sẽ tự xử lý. |
| CONSUMER_DOWN | Dịch vụ thông báo đang tạm dừng. Email và thông báo sẽ được gửi khi hệ thống hoạt động lại. |
| DLQ_ATTENTION | Có thông báo gửi thất bại nhiều lần. Cần kỹ thuật kiểm tra. |
| BROKER_DOWN | Không kiểm tra được hệ thống hàng đợi thông báo. |

### 2e. Thêm endpoint vào `DeadEventAdminController.java`
```java
@GetMapping("/rabbitmq-health")
public ResponseEntity<QueueHealthResponse> queueHealth() {
    return ResponseEntity.ok(queueHealthService.checkNotificationQueue());
}
```
Inject `QueueHealthService queueHealthService` qua constructor (`@RequiredArgsConstructor` đã có).

---

## Phần 3 — Frontend

### 3a. `services/booking/booking.ts` — thêm Queue Health API
```typescript
export interface QueueHealthResponse {
    queue: string;
    ready: number;
    unacked: number;
    consumers: number;
    dlqReady: number;
    status: 'HEALTHY' | 'BACKLOG' | 'CONSUMER_DOWN' | 'DLQ_ATTENTION' | 'BROKER_DOWN';
    message: string;
    checkedAt: string;
}

export const getQueueHealthApi = async (): Promise<QueueHealthResponse> => {
    const response = await api.get('/bookings/admin/outbox/rabbitmq-health');
    return response.data;
};
```

### 3b. Tạo `hook/useQueueHealth.ts`
```typescript
import { useState, useEffect, useCallback } from 'react';
import { getQueueHealthApi, type QueueHealthResponse } from '../services/booking/booking.ts';

const useQueueHealth = () => {
    const [health, setHealth]   = useState<QueueHealthResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError]     = useState<string | null>(null);

    const refresh = useCallback(async () => {
        setLoading(true);
        try {
            setHealth(await getQueueHealthApi());
            setError(null);
        } catch (e: any) {
            setError(e?.response?.data?.message ?? e?.message ?? 'Lỗi kết nối');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        refresh();
        const id = setInterval(refresh, 30_000);
        return () => clearInterval(id);
    }, [refresh]);

    return { health, loading, error, refresh };
};

export default useQueueHealth;
```

### 3c. `DeadEventsPage.jsx` + SCSS — đổi tên + Việt hoá + thêm Queue Health

**Đổi tên trang & subtitle:**
- Tiêu đề trang: `Sự cố xử lý nền`
- Subtitle: `Theo dõi các tác vụ gửi thông báo và hoàn xu bị lỗi, hoặc đang chờ hệ thống xử lý.`
- Menu sidebar: `Sự cố xử lý nền` (thay cho `DEAD Events`)

**Đổi summary cards:**
| Cũ | Mới |
|---|---|
| Tổng DEAD Events | Tác vụ cần xử lý |
| Coin Refund lỗi | Hoàn xu lỗi |
| Notification lỗi | Thông báo lỗi |

**Thêm section "Tình trạng gửi thông báo"** ngay dưới summary cards, trên filter bar:

4 thẻ số liệu (icon từ `lucide-react`):
| Thẻ | Giá trị | Icon |
|---|---|---|
| Thông báo đang chờ gửi | `ready` | `Mail` |
| Đang được xử lý | `unacked` | `RefreshCw` |
| Dịch vụ đang hoạt động | `consumers > 0 ? "Có" : "Không"` | `Server` |
| Thông báo lỗi | `dlqReady` | `AlertTriangle` |

Banner theo trạng thái (hiện ngay dưới 4 thẻ):
| Status | Màu | Text |
|---|---|---|
| HEALTHY | Xanh nhạt (hoặc ẩn) | Hệ thống thông báo hoạt động bình thường. |
| BACKLOG | Vàng | Đang có {N} thông báo chờ gửi. Hệ thống sẽ tự xử lý trong ít phút. |
| CONSUMER_DOWN | Vàng | Dịch vụ thông báo đang tạm dừng. Email và thông báo sẽ được gửi khi hệ thống hoạt động lại. |
| DLQ_ATTENTION | Đỏ | Có thông báo gửi thất bại nhiều lần. Vui lòng báo kỹ thuật kiểm tra. |
| BROKER_DOWN | Đỏ | Không kiểm tra được hệ thống gửi thông báo. Vui lòng báo kỹ thuật. |

**Đổi filter labels:**
| Cũ | Mới |
|---|---|
| Coin Refund | Hoàn xu |
| Notification | Gửi email/thông báo |

**Đổi table headers:**
| Cũ | Mới |
|---|---|
| ID | Mã sự cố |
| Routing Key | Loại tác vụ |
| Retries | Số lần thử |
| Created At | Thời điểm phát sinh |
| Next Retry At | Lần thử tiếp theo |
| Lỗi cuối | Nguyên nhân gần nhất |

**Đổi action buttons:**
| Cũ | Mới |
|---|---|
| Chi tiết | Xem chi tiết |
| Retry | Thử xử lý lại |
| Retry All (N) | Thử lại tất cả (N) |

**Ghi chú nhỏ** (tooltip hoặc text `<small>` dưới nút "Thử lại tất cả"):
> Thử xử lý lại chỉ đưa tác vụ về hàng chờ. Nếu nguyên nhân lỗi chưa được khắc phục, tác vụ có thể lỗi lại.

**Không hiển thị** các từ sau trên UI chính: `RabbitMQ`, `DLQ`, `routing key`, `consumer`, `outbox`, `DEAD`

SCSS: thêm `.queueHealthSection`, `.queueCards`, `.queueCard`, `.healthBanner`, `.bannerHealthy`, `.bannerWarning`, `.bannerDanger`

### 3d. `DeadEventItem.jsx` — đổi cột routingKey → tên nghiệp vụ
```jsx
const TASK_LABELS = {
    'booking.coin.refund':        'Hoàn xu cho khách',
    'booking.notification.event': 'Gửi email/thông báo',
};
// Trong JSX (thay span routingKey hiện tại):
<span className={styles.taskLabel} title={event.routingKey}>
    {TASK_LABELS[event.routingKey] ?? event.routingKey}
</span>
```
*(routingKey kỹ thuật vẫn show trong `DeadEventDetailModal` để kỹ thuật viên xem)*

### 3e. `BookingItem.jsx` + `BookingItem.module.scss` — mini badge coinRefundStatus
Trong ô status, thêm ngay dưới status badge chính:
```jsx
import { Coins, CheckCircle2, AlertTriangle } from 'lucide-react';

{booking.coinRefundStatus && (
    <div className={`${styles.coinBadge} ${styles[`coin_${booking.coinRefundStatus}`]}`}>
        {booking.coinRefundStatus === 'PENDING'   && <><Coins size={11} /> Đang hoàn xu</>}
        {booking.coinRefundStatus === 'COMPLETED' && <><CheckCircle2 size={11} /> Đã hoàn xu</>}
        {booking.coinRefundStatus === 'FAILED'    && <><AlertTriangle size={11} /> Hoàn xu lỗi</>}
    </div>
)}
```
SCSS:
```scss
.coinBadge {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 0.7rem;
    padding: 2px 7px;
    border-radius: 10px;
    margin-top: 4px;
    font-weight: 500;
    white-space: nowrap;
}
.coin_PENDING   { background: #fef3c7; color: #92400e; }
.coin_COMPLETED { background: #d1fae5; color: #065f46; }
.coin_FAILED    { background: #fee2e2; color: #991b1b; }
```

### 3f. `TransactionDetailModal.jsx` — cải thiện section hoàn xu + hoàn tiền

**Import thêm:**
```jsx
import { Coins, Clock3, CheckCircle2, AlertTriangle, DollarSign, Bell } from 'lucide-react';
```

**Đổi section header (thay emoji bằng icon):**
| Cũ | Mới |
|---|---|
| `🪙 Hoàn xu` | `<Coins size={16} /> Hoàn xu` |
| `💰 Thông tin hoàn tiền` | `<DollarSign size={16} /> Thông tin hoàn tiền` |

**Cải thiện layout section "Hoàn xu"** — thay `<p>` rời rạc bằng dạng row như phần bank info:
```jsx
{/* Section hoàn xu — chỉ hiện khi có coinRefundStatus */}
{booking.coinRefundStatus && (
    <div className={styles.section}>
        <h3 className={styles.sectionTitle}>
            <Coins size={16} /> Hoàn xu
        </h3>
        <div className={styles.paymentSummary}>
            {booking.paidByCoin > 0 && (
                <div className={styles.paymentItem}>
                    <span>Số xu hoàn:</span>
                    <strong>{booking.paidByCoin} xu</strong>
                </div>
            )}
            <div className={styles.paymentItem}>
                <span>Trạng thái hoàn xu:</span>
                <strong className={styles[
                    booking.coinRefundStatus === 'COMPLETED' ? 'statusDone'
                    : booking.coinRefundStatus === 'FAILED'  ? 'statusFailed'
                    : 'statusPending'
                ]}>
                    {booking.coinRefundStatus === 'PENDING' && (
                        <><Clock3 size={13} /> Đang xử lý hoàn xu</>
                    )}
                    {booking.coinRefundStatus === 'COMPLETED' && (
                        <><CheckCircle2 size={13} /> Đã hoàn xu thành công</>
                    )}
                    {booking.coinRefundStatus === 'FAILED' && (
                        <><AlertTriangle size={13} /> Hoàn xu gặp sự cố — vui lòng liên hệ hỗ trợ</>
                    )}
                </strong>
            </div>
        </div>
    </div>
)}
```

**Ghi chú email** — thêm ngay sau section hoàn xu, chỉ khi `bookingStatus === 'CANCELLED'`:
```jsx
{booking.bookingStatus === 'CANCELLED' && (
    <div className={styles.notificationHint}>
        <Bell size={13} />
        <span>
            Email xác nhận hủy đã được gửi đến hòm thư của bạn.
            Nếu chưa nhận được sau 5 phút, hãy kiểm tra thư mục spam.
        </span>
    </div>
)}
```

**SCSS thêm vào `TransactionDetailModal.module.scss`:**
```scss
.notificationHint {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    font-size: 0.82rem;
    color: #0369a1;
    background: #f0f9ff;
    border: 1px solid #bae6fd;
    border-radius: 6px;
    padding: 8px 12px;
    margin-top: 8px;

    span { line-height: 1.5; }
}

.statusPending  { color: #92400e; display: inline-flex; gap: 4px; align-items: center; font-weight: 500; }
.statusDone     { color: #065f46; display: inline-flex; gap: 4px; align-items: center; font-weight: 500; }
.statusFailed   { color: #991b1b; display: inline-flex; gap: 4px; align-items: center; font-weight: 500; }
```
*(Nếu `.statusDone` / `.statusPending` / `.statusFailed` đã có trong file thì merge thay vì tạo mới)*

---

## Danh sách files cần thay đổi

**Backend:**
| File | Loại thay đổi |
|---|---|
| `docker-compose.yml` | Thêm TZ + JAVA_TOOL_OPTIONS vào 10 services, PGTZ vào postgres |
| `booking-service/.../resources/application.yml` | Thêm rabbitmq.management.* |
| `booking-service/.../dto/response/QueueHealthResponse.java` | **TẠO MỚI** |
| `booking-service/.../service/QueueHealthService.java` | **TẠO MỚI** |
| `booking-service/.../controller/DeadEventAdminController.java` | Thêm 1 endpoint |

**Frontend:**
| File | Loại thay đổi |
|---|---|
| `src/hook/useQueueHealth.ts` | **TẠO MỚI** |
| `src/services/booking/booking.ts` | Thêm QueueHealthResponse + getQueueHealthApi() |
| `AdminSidebar.jsx` | Đổi label "DEAD Events" → "Sự cố xử lý nền" |
| `Pages/DeadEventsPage/DeadEventsPage.jsx` | Đổi tên + Việt hoá + Queue Health section |
| `Pages/DeadEventsPage/DeadEventsPage.module.scss` | Thêm queue health + banner styles |
| `Pages/DeadEventsPage/DeadEventItem.jsx` | Đổi cột routingKey → nhãn nghiệp vụ |
| `Pages/BookingsPage/BookingItem.jsx` | Thêm coinRefundStatus mini badge |
| `Pages/BookingsPage/BookingItem.module.scss` | Thêm .coinBadge styles |
| `TransactionList/.../TransactionDetailModal.jsx` | Thay emoji → lucide icons; cải thiện layout section hoàn xu; thêm notification hint |

**Không đụng đến:**
- Bất kỳ file `.java` nào ngoài 3 file backend mới/sửa ở trên
- `CoinTransaction.java`, `ProcessedEvent.java`, `OutboxEvent.java` — giữ nguyên
- `DeadEventDetailModal.jsx` — giữ nguyên (modal kỹ thuật cho dev, không cần Việt hoá)

---

## Kiểm thử

| Test case | Bước | Kỳ vọng |
|---|---|---|
| Giờ container | `docker exec tourism-booking-service date` | Giờ UTC+7 |
| Giờ Postgres | `SELECT NOW();` trong booking_db | Giờ UTC+7 |
| Queue Health bình thường | notification-service đang chạy, gọi `/rabbitmq-health` | `{status:"HEALTHY"}`, UI hiện xanh hoặc ẩn banner |
| Queue Health service down | `docker compose stop notification-service` → gọi API | `{status:"CONSUMER_DOWN"}`, banner vàng tiếng Việt |
| Queue Health service up lại | `docker compose start notification-service` | Banner biến mất, status HEALTHY |
| coinRefundStatus badge | Admin hủy booking có coin → xem list | Mini badge "Đang hoàn xu" dưới "Đã hủy" |
| TransactionDetailModal | Mở detail booking CANCELLED có hoàn xu | Không còn emoji, icon đẹp, row layout cho hoàn xu |
| Email hint | Mở detail booking CANCELLED | Ghi chú nhỏ màu xanh "Email xác nhận hủy đã được gửi..." |
| Trang Sự cố xử lý nền | Vào admin sidebar → click menu mới | Tiêu đề "Sự cố xử lý nền", bảng tiếng Việt, không thấy từ DEAD/DLQ/RabbitMQ |
| Routing key display | Xem bảng sự cố | Hiện "Hoàn xu cho khách" / "Gửi email/thông báo" thay routing key |

---

## Constraints

- Không thêm `emailStatus` field vào booking model
- Không callback từ notification-service về booking-service
- Không scan RabbitMQ messages để update booking
- Không JWT trên admin outbox endpoints (internal/ops)
- Không dùng emoji trong UI — chỉ lucide-react hoặc react-icons
- UI admin: không hiện từ kỹ thuật (RabbitMQ, DLQ, outbox, consumer, DEAD, routing key) trong phần chính
- `notification_delivery` tracking table → scope v2
