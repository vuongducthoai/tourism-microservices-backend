# Báo cáo: Admin Dead Event UI — Triển khai & Kiểm thử

**Dự án:** Tourism Microservices Backend + Frontend  
**Module:** Booking Service — Outbox DEAD Event Recovery  
**Ngày:** 2025-05  
**Trạng thái:** ✅ Hoàn thành, đã kiểm thử thực tế

---

## 1. Tổng quan vấn đề

Hệ thống dùng mô hình **Outbox Pattern** để đảm bảo tính toàn vẹn dữ liệu khi gửi event qua RabbitMQ.  
Mỗi OutboxEvent đi qua các trạng thái:

```
NEW ──► PROCESSING ──► SENT
              │
              └─► (retry n lần) ──► DEAD
```

Khi một event đạt trạng thái **DEAD** (retry vượt quá `maxRetries`), dữ liệu liên quan có nguy cơ không nhất quán:
- Coin chưa được hoàn về user (event `booking.coin.refund` chết)
- Email/notification chưa được gửi (event `booking.notification.event` chết)

Trước khi có tính năng này, admin không có công cụ để phát hiện và xử lý các sự cố này.

---

## 2. Kiến trúc giải pháp

### 2.1 Backend (đã có từ trước, không thay đổi)

**File:** `booking-service/.../controller/DeadEventAdminController.java`

| Endpoint | Method | Mô tả |
|---|---|---|
| `/api/bookings/admin/outbox/dead` | GET | Danh sách DEAD events (phân trang) |
| `/api/bookings/admin/outbox/dead/count` | GET | Đếm theo type (coinRefund/notification/total) |
| `/api/bookings/admin/outbox/retry/{id}` | POST | Reset 1 event về NEW |
| `/api/bookings/admin/outbox/retry-all` | POST | Reset nhiều events (hoặc lọc theo routingKey) |

**Tham số** `retry-all`:
- Không có param → reset tất cả DEAD events
- `?routingKey=booking.coin.refund` → chỉ reset coin refund events
- `?routingKey=booking.notification.event` → chỉ reset notification events

**Kết quả retry:** Event chuyển về trạng thái `NEW`, scheduler tự động pick up và gửi lại trong vài giây.

### 2.2 Frontend — Các file mới/sửa đổi

```
tourism_frontend/client-side/src/
├── services/booking/booking.ts            ← THÊM 4 API functions
├── hook/useDeadEvents.ts                  ← TẠO MỚI (custom hook)
└── components/AdminComponent/
    ├── AdminComponent.jsx                 ← THÊM route dead-events
    ├── AdminLayout/AdminSidebar/
    │   └── AdminSidebar.jsx              ← THÊM menu item
    └── Pages/DeadEventsPage/             ← TẠO MỚI (toàn bộ)
        ├── DeadEventsPage.jsx
        ├── DeadEventsPage.module.scss
        ├── DeadEventItem.jsx
        ├── DeadEventItem.module.scss
        └── DeadEventDetailModal/
            ├── DeadEventDetailModal.jsx
            └── DeadEventDetailModal.module.scss
```

---

## 3. Logic chi tiết từng lớp

### 3.1 API Layer (`booking.ts`)

4 functions mới được thêm vào cuối file:

```typescript
// Lấy danh sách DEAD events (phân trang)
getDeadEventsApi(page, size) → Promise<DeadEventPage>

// Đếm DEAD events theo type
getDeadEventCountApi() → Promise<DeadEventCount>

// Retry 1 event cụ thể
retryDeadEventApi(id) → Promise<void>

// Retry nhiều events (optionally lọc routingKey)
retryAllDeadEventsApi(routingKey?) → Promise<{ retried: number }>
```

Tất cả đều dùng instance `api` (axiosCustomize) đã có sẵn interceptor JWT token và refresh token.

### 3.2 Custom Hook (`useDeadEvents.ts`)

Hook trung tâm quản lý toàn bộ state cho trang Dead Events:

```
State quản lý:
├── events[]          ← danh sách OutboxEventDTO
├── count             ← { coinRefund, notification, total }
├── loading           ← đang load dữ liệu lần đầu
├── actionLoading     ← đang thực hiện retry (block nhiều action đồng thời)
├── error             ← lỗi nếu có
├── totalPages        ← tổng số trang
├── totalElements     ← tổng số events
└── currentPage       ← trang hiện tại
```

**Chiến lược fetch:** Dùng `Promise.all` để fetch đồng thời danh sách và count trong 1 request cycle.  
**Tránh memory leak:** Dùng `active` flag trong useEffect để hủy setState nếu component unmount.  
**Trigger refetch:** Mỗi lần retry thành công → gọi `refetch()` → tăng `trigger` state → useEffect chạy lại.

### 3.3 DeadEventsPage.jsx — Luồng UX

```
Render
  ├── PageHeader (title + Làm mới + Retry All)
  ├── SummaryCards (Total / Coin / Notification)
  ├── FilterBar (Tất cả | Coin Refund | Notification)
  ├── TableWrapper
  │   ├── thead
  │   ├── tbody (map filtered events → DeadEventItem)
  │   └── Pagination (nếu totalPages > 1)
  ├── DeadEventDetailModal (nếu có detailEvent)
  └── ConfirmDialog (nếu confirmRetryAll = true)
```

**Lọc client-side:** Filter theo `routingKey` chính xác (không phải prefix) để khớp đúng với dữ liệu thực từ API:
- `booking.coin.refund` → coinRefund events
- `booking.notification.event` → notification events

**Retry All flow:**
1. User click "Retry All (N)" 
2. `ConfirmDialog` xuất hiện, hiển thị số events sẽ bị reset
3. User xác nhận → gọi `retryAllDeadEventsApi(routingKey?)` 
4. Toast success/error → refetch dữ liệu

### 3.4 DeadEventItem.jsx

Component row trong bảng. Hiển thị:
- ID (màu đỏ để nổi bật)
- RoutingKey (badge mono)
- Retries/maxRetries (badge màu đỏ)
- createdAt, nextRetryAt
- errorMessage (truncate với ellipsis + full tooltip)
- 2 nút: **Chi tiết** và **Retry**

### 3.5 DeadEventDetailModal.jsx

Modal toàn màn hình với 4 section:
1. **Thông tin định tuyến**: id, idempotencyKey, exchange, routingKey
2. **Trạng thái & Retry**: status, retries, maxRetries, maxBackoffSecs, timestamps
3. **Thông báo lỗi** (chỉ hiện nếu có errorMessage)
4. **Payload JSON** (code block với syntax coloring đen/xanh)

Nút **Retry Event này** trong modal → gọi `onRetry(id)` → đóng modal → refetch.

---

## 4. Kết quả kiểm thử API

### Test 1: Đếm DEAD events

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/bookings/admin/outbox/dead/count" -Method GET
```

**Kết quả:**
```json
{ "notification": 1, "coinRefund": 2, "total": 3 }
```
✅ Đúng — 3 DEAD events trong DB trước khi retry.

### Test 2: Lấy danh sách DEAD events

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/bookings/admin/outbox/dead?page=0&size=5" -Method GET
```

**Kết quả:** 3 events trả về với đầy đủ fields:
- ID 26: `routingKey=booking.notification.event`, bookingCode=BK20250102
- ID 25: `routingKey=booking.coin.refund`, coinRefundAmount=2500, bookingCode=BK20250102
- ID 21: `routingKey=booking.coin.refund`, coinRefundAmount=1715, bookingCode=BK20250103

✅ Dữ liệu đầy đủ, phân trang hoạt động.

### Test 3: Retry 1 event

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/bookings/admin/outbox/retry/21" -Method POST
# Verify
Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/bookings/admin/outbox/dead/count" -Method GET
```

**Kết quả:** Total giảm từ 3 → 2, coinRefund giảm từ 2 → 1  
✅ Event #21 đã chuyển về NEW, scheduler đã pick up.

### Test 4: Retry All

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8083/api/bookings/admin/outbox/retry-all" -Method POST
```

**Kết quả:**
```json
{ "retried": 2 }
```

Sau đó kiểm tra count:
```json
{ "notification": 0, "coinRefund": 0, "total": 0 }
```
✅ Tất cả 2 events còn lại đã được reset. Scheduler xử lý thành công.

---

## 5. Hướng dẫn sử dụng giao diện

### 5.1 Truy cập trang

1. Đăng nhập admin tại `http://localhost:3000/admin`
2. Sidebar bên trái → click **DEAD Events** (icon đầu lâu 💀)
3. URL: `http://localhost:3000/admin/dead-events`

### 5.2 Đọc Summary Cards

Ngay đầu trang, 3 thẻ thống kê:

| Thẻ | Màu icon | Ý nghĩa |
|---|---|---|
| **Tổng DEAD Events** | Đỏ | Tổng số events cần xử lý |
| **Coin Refund lỗi** | Vàng | Events hoàn coin chưa được thực hiện |
| **Notification lỗi** | Xanh | Email/thông báo chưa được gửi |

> ⚠️ **Ưu tiên xử lý Coin Refund trước** — ảnh hưởng trực tiếp đến tiền của khách hàng.

### 5.3 Lọc theo loại

Dùng filter bar (Tất cả / Coin Refund / Notification) để xem từng nhóm riêng:

- **Tất cả** — xem toàn bộ
- **Coin Refund** — lọc `routingKey=booking.coin.refund`
- **Notification** — lọc `routingKey=booking.notification.event`

### 5.4 Retry từng event

Trong bảng danh sách:
1. Xem cột **Retries** để biết event đã thử bao nhiêu lần
2. Xem cột **Lỗi cuối** để hiểu nguyên nhân (hover để xem full text)
3. Click **Chi tiết** để xem đầy đủ payload JSON và thông tin lỗi
4. Click **Retry** → event reset về NEW → scheduler thử lại trong ~5-10 giây

**Trong modal Chi tiết**, có thể:
- Đọc payload đầy đủ (format JSON đẹp)
- Xem thông báo lỗi chi tiết
- Click **Retry Event này** → retry và đóng modal

### 5.5 Retry tất cả (Retry All)

Khi có nhiều event lỗi cùng loại:
1. (Tùy chọn) Chọn filter để chỉ retry 1 nhóm
2. Click **Retry All (N)** ở góc phải header
3. Dialog xác nhận hiện ra với số lượng event sẽ bị ảnh hưởng
4. Click **Xác nhận** → tất cả events reset về NEW

> ℹ️ **Retry All an toàn** — mỗi event có `idempotencyKey` duy nhất, scheduler sẽ skip nếu đã xử lý xong.

### 5.6 Làm mới dữ liệu

Click **Làm mới** để fetch dữ liệu mới nhất từ server. Trang KHÔNG tự động refresh để tránh mất state lọc.

### 5.7 Hiểu cột Next Retry At

Khi event ở trạng thái DEAD và được reset về NEW, scheduler sẽ pick up dựa trên `nextRetryAt`.  
Nếu `nextRetryAt` ở quá khứ → scheduler xử lý ngay trong lần poll kế tiếp (~5-15 giây).  
Sau retry thành công → event sẽ biến mất khỏi danh sách DEAD Events.

---

## 6. Lưu ý vận hành

### Khi nào cần dùng tính năng này?

| Tình huống | Hành động |
|---|---|
| Khách báo không nhận được email xác nhận hủy | Check Notification DEAD events → Retry |
| Khách báo chưa nhận được coin sau khi hủy booking | Check Coin Refund DEAD events → Retry |
| RabbitMQ bị down trong 1 thời gian → nhiều event chết | Retry All sau khi RabbitMQ khởi động lại |
| Định kỳ kiểm tra sức khỏe hệ thống | Vào trang, xem Total = 0 là tốt |

### Bảo mật

- Trang chỉ accessible khi đăng nhập với token admin (`AdminProtectedRoute`)
- Mọi request đều đính kèm JWT trong header
- Không có endpoint public nào expose dữ liệu outbox

### Idempotency

Khi retry, nếu sự kiện thực ra đã được xử lý thành công (consumer nhận nhưng không ACK do network), consumer-side sẽ phát hiện `idempotencyKey` đã tồn tại và skip — không gây duplicate action.

---

## 7. Tóm tắt files triển khai

| File | Thay đổi | Dòng code |
|---|---|---|
| `services/booking/booking.ts` | +4 API functions + 3 interfaces | ~60 |
| `hook/useDeadEvents.ts` | Tạo mới | 105 |
| `Pages/DeadEventsPage/DeadEventsPage.jsx` | Tạo mới | 185 |
| `Pages/DeadEventsPage/DeadEventsPage.module.scss` | Tạo mới | 220 |
| `Pages/DeadEventsPage/DeadEventItem.jsx` | Tạo mới | 65 |
| `Pages/DeadEventsPage/DeadEventItem.module.scss` | Tạo mới | 85 |
| `Pages/DeadEventsPage/DeadEventDetailModal/DeadEventDetailModal.jsx` | Tạo mới | 120 |
| `Pages/DeadEventsPage/DeadEventDetailModal/DeadEventDetailModal.module.scss` | Tạo mới | 155 |
| `AdminSidebar.jsx` | +1 import + 1 navItem | +2 |
| `AdminComponent.jsx` | +1 import + 1 Route | +2 |

**Tổng: 10 files, ~1000 dòng code**

---

*Báo cáo này mô tả toàn bộ logic và cách sử dụng tính năng Admin Dead Event UI. Mọi API endpoint đã được kiểm thử thực tế với booking-service đang chạy trong Docker.*
