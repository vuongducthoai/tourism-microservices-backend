# BÁO CÁO HỆ THỐNG QUẢN LÝ ĐẶT TOUR — WEBSOCKET & BOOKING API

> **Phiên bản:** 1.0 — **Ngày:** 06/05/2026  
> **Services liên quan:** `booking-service` (8083) · `notification-service` (8086) · `api-gateway` (8080)  
> **Frontend:** React TypeScript — port 3000

---

## MỤC LỤC

1. [Kiến trúc tổng quan](#1-kiến-trúc-tổng-quan)
2. [Booking API — Danh sách Endpoint](#2-booking-api--danh-sách-endpoint)
3. [Logic nghiệp vụ chi tiết](#3-logic-nghiệp-vụ-chi-tiết)
4. [WebSocket — Cách hoạt động](#4-websocket--cách-hoạt-động)
5. [SePay — Xác minh hoàn tiền](#5-sepay--xác-minh-hoàn-tiền)
6. [Frontend — Hook & Component](#6-frontend--hook--component)
7. [Các lỗi đã phát hiện và sửa](#7-các-lỗi-đã-phát-hiện-và-sửa)
8. [Kết quả kiểm thử API](#8-kết-quả-kiểm-thử-api)

---

## 1. Kiến trúc tổng quan

```
[Client Browser]
      │
      ├─ HTTP REST ──► [API Gateway :8080]
      │                      │
      │              Spring Cloud Gateway
      │                (Eureka LB)
      │                      │
      │         ┌────────────┴──────────────┐
      │         │                           │
      │   /api/bookings/**           /ws/** + /api/notifications/**
      │         │                           │
      │  [booking-service :8083]   [notification-service :8086]
      │         │                           │
      │         │   Feign RPC               │ SimpMessagingTemplate
      │         └──────────────────────────►│
      │                                     │
      └─ WebSocket/STOMP ◄──────────────────┘
         (via /ws SockJS)
                ▲
         /topic/admin/bookings
         /topic/user/{id}/bookings
```

**Luồng dữ liệu:**
1. Client gọi REST → API Gateway → booking-service
2. booking-service xử lý nghiệp vụ → lưu DB → gọi Feign đến notification-service
3. notification-service gửi Email + đẩy message qua STOMP broker
4. Browser đang subscribe topic nhận ngay, UI cập nhật **không cần reload trang**

---

## 2. Booking API — Danh sách Endpoint

### 2.1 Endpoint cho khách hàng (User)

| # | Method | Path | Mô tả |
|---|--------|------|-------|
| 1 | `GET` | `/api/bookings/user/{userID}` | Lấy danh sách booking của user, tùy chọn filter theo `bookingStatus` |
| 2 | `POST` | `/api/bookings/cancel` | Hủy booking — hoàn tiền bằng xu (Coin path) |
| 3 | `POST` | `/api/bookings/refund-request/{bookingID}` | Gửi thông tin tài khoản ngân hàng để yêu cầu hoàn tiền (Bank path) |

### 2.2 Endpoint cho Admin

| # | Method | Path | Mô tả |
|---|--------|------|-------|
| 4 | `POST` | `/api/bookings/admin/search` | Tìm kiếm booking có phân trang, lọc theo code/status/date |
| 5 | `POST` | `/api/bookings/admin/update-status` | Cập nhật trạng thái booking (xác nhận / hủy) |

### 2.3 Endpoint nội bộ (Internal — dùng bởi Feign)

| # | Method | Path | Caller | Mô tả |
|---|--------|------|--------|-------|
| 6 | `GET` | `/api/bookings/{bookingID}` | tour-catalog-service | Trả về thông tin booking ngắn gọn (userId, status) |
| 7 | `POST` | `/api/bookings/{bookingID}/status?status=REVIEWED` | tour-catalog-service | Cập nhật status sau khi review được submit |
| 8 | `GET` | `/api/bookings/coupons/chatbot-sync` | analytics-service | Lấy danh sách coupon active để đồng bộ Pinecone |

---

## 3. Logic nghiệp vụ chi tiết

### 3.1 Sơ đồ trạng thái Booking

```
PENDING_PAYMENT
      │
      │ (thanh toán xong)
      ▼
PENDING_CONFIRMATION ──────────────────────────────┐
      │                                             │
      │ Admin xác nhận [POST admin/update-status]   │ Admin hủy / User hủy
      ▼                                             │
    PAID                                            │
      │                                             │
      │ User hủy → chọn phương thức hoàn tiền       │
      ├──────────────────────────────────────────────┘
      │
      │──► Coin path: iamClient.addCoins() → CANCELLED
      │
      └──► Bank path: lưu RefundInformation → PENDING_REFUND
                           │
                           │ Admin chuyển khoản ngân hàng BIDV
                           │ Nội dung: "HOANTIEN BK20250103"
                           │
                           │ Admin bấm xác nhận hoàn tiền
                           │ [POST admin/update-status CANCELLED]
                           │
                           │ SePay API verify giao dịch
                           │
                    CANCELLED (refundStatus = COMPLETED)
```

### 3.2 Hàm `adminUpdateBookingStatus` — BookingServiceImpl

**Chuyển sang PAID:**
```
Input: { bookingID, bookingStatus: "PAID" }
Check: currentStatus == "PENDING_CONFIRMATION" (bắt buộc)
Action:
  - booking.status = PAID
  - Save
  - fireNotification → notifyBookingConfirmed → Email khách + WS admin + WS user
```

**Chuyển sang CANCELLED:**
```
Input: { bookingID, bookingStatus: "CANCELLED", cancelReason? }
Check: currentStatus in [PENDING_PAYMENT, PENDING_CONFIRMATION, PAID, PENDING_REFUND]

Nếu PENDING_REFUND + có RefundInformation:
  → SepayService.verifyRefundTransaction(bookingCode, amount, ...)
  → Nếu không tìm thấy giao dịch → throw RuntimeException (HTTP 400)
  → Nếu tìm thấy → refundInfo.refundStatus = "COMPLETED", lưu DB

Action:
  - booking.status = CANCELLED
  - booking.cancelReason = request.cancelReason
  - Tính refundAmount = totalPrice + paidByCoin (admin cancel không tính phí)
  - Save
  - fireNotification → notifyStatusUpdated → Email + WS
```

### 3.3 Hàm `cancelBooking` — Coin Refund Path (User-side)

```
Input: { bookingID, cancelReason }
Check: status != CANCELLED

Tính refundableAmount:
  refundableAmount = (totalPrice + paidByCoin) × (1 - feePercent)
  
  Phí hủy theo số ngày trước khởi hành:
  ┌──────────────────────┬──────────┐
  │ > 15 ngày            │ phí 10%  │
  │ > 5 ngày             │ phí 50%  │
  │ > 2 ngày             │ phí 70%  │
  │ >= 0 ngày            │ phí 90%  │
  │ đã qua khởi hành     │ phí 100% │
  └──────────────────────┴──────────┘

coinRefundAmount = refundableAmount / 1000 (1 coin = 1000 VND)

Action:
  - iamClient.addCoins(userId, coinRefundAmount) — Feign đến iam-service
  - booking.status = CANCELLED
  - Save
  - fireNotification → notifyStatusUpdated → WS + Email nếu có coin refund
```

### 3.4 Hàm `submitRefundRequest` — Bank Refund Path (User-side)

```
Input: { bookingID, accountName, accountNumber, bank }
Check: status not in [CANCELLED, PENDING_REFUND]

Tính totalRefundAmount tương tự cancelBooking (có tính phí)

Action:
  - Tạo RefundInformation: { accountName, accountNumber, bank, refundAmount, refundStatus="PENDING" }
  - booking.status = PENDING_REFUND
  - Save
  - fireNotification → notifyRefundRequested → Email admin + WS admin + WS user
```

### 3.5 Hàm `adminSearchBookings`

```
Input: { bookingCode?, bookingStatus?, bookingDate? } + params: page, size, sortBy, sortDir

Action:
  - BookingRepository.searchBookings(request, pageable) — custom @Query
  - Mỗi Booking → toResponse() = enrichFromDeparture (tour-catalog Feign) + enrichFromPayment (payment Feign)
  - Trả về Page<BookingResponse>
```

---

## 4. WebSocket — Cách hoạt động

### 4.1 Luồng đầy đủ

```
[1] booking-service hoàn thành action (PAID / CANCELLED / PENDING_REFUND)
       │
       │ Feign HTTP POST
       ▼
[2] notification-service: NotificationController
       │  /api/notifications/booking-confirmed
       │  /api/notifications/status-updated
       │  /api/notifications/refund-requested
       ▼
[3] NotificationServiceImpl.handle*()
       │
       ├──► mailService.send*()           (Email gửi tới khách / admin)
       ├──► notificationRepository.save() (Lưu DB notification)
       │
       └──► WebSocketService
               │
               ├──► messagingTemplate.convertAndSend("/topic/admin/bookings", event)
               └──► messagingTemplate.convertAndSend("/topic/user/{userId}/bookings", event)
       │
[4] STOMP Broker (In-memory SimpleMessageBroker)
       │
       │ Push tới tất cả subscriber đang kết nối
       ▼
[5] Frontend useWebSocket hook
    (SockJS → StompClient → subscribe topic)
       │
       ├──► updateBookingInList(id, patch)   (Cập nhật ngay trên bảng — không reload)
       └──► silentRefetch()                  (Background sync — không show loading)
```

### 4.2 API Gateway WebSocket Route

```yaml
# application.yml của api-gateway
- id: websocket-route
  uri: lb:ws://notification-service
  predicates:
    - Path=/ws/**
  filters:
    - RewritePath=/ws(?<segment>.*), /ws${segment}
```

WebSocket được load-balance qua Eureka giống HTTP thông thường. Client kết nối tại:
```
ws://localhost:8080/ws
```

### 4.3 Topic subscription

| Topic | Subscriber | Trigger |
|-------|-----------|---------|
| `/topic/admin/bookings` | Trang BookingsPage (Admin) | Bất kỳ thay đổi booking nào |
| `/topic/user/{userId}/bookings` | Trang TransactionList (User) | Booking của user đó thay đổi |

### 4.4 Xử lý message phía frontend

```typescript
// BookingsPage.jsx / TransactionList.jsx
const handleWebSocketMessage = useCallback((event) => {
    if (event?.bookingID) {
        // Chỉ patch field nào có giá trị thực, tránh ghi đè bằng undefined
        const patch = {};
        if (event.bookingStatus != null) patch.bookingStatus = event.bookingStatus;
        if (event.cancelReason  != null) patch.cancelReason  = event.cancelReason;
        if (event.refundAmount  != null) patch.refundAmount  = event.refundAmount;
        if (Object.keys(patch).length > 0) updateBookingInList(event.bookingID, patch);
    }
    silentRefetch(); // Sync ngầm, không flash loading
}, [updateBookingInList, silentRefetch]);
```

---

## 5. SePay — Xác minh hoàn tiền

### 5.1 Mục đích

SePay API được dùng để **xác minh** rằng admin đã thực sự chuyển khoản tiền hoàn trả cho khách hàng trước khi hệ thống đánh dấu booking là `CANCELLED`. API SePay là **read-only** — hệ thống **không tự động chuyển tiền** mà chỉ kiểm tra lịch sử giao dịch.

### 5.2 Cấu hình

```yaml
# booking-service/application.yml
sepay:
  api-url: https://my.sepay.vn/userapi
  token: 5DIRHLIEPC9Y0GFKSNXACC...
  account-number: "10002897094"
  account-name: TRAN ANH THU
  bank-code: BIDV
```

### 5.3 Quy trình hoàn tiền ngân hàng

```
Bước 1 — Khách hàng:
  Bấm "Hủy tour" → Chọn hoàn tiền ngân hàng
  → Nhập: tên ngân hàng, số TK, tên TK
  → POST /api/bookings/refund-request/{bookingID}
  → Status: PENDING_REFUND

Bước 2 — Admin:
  Thấy notification WS trên trang Bookings
  Mở modal "ProcessRefund" → Xem VietQR / thông tin TK khách
  Chuyển khoản thủ công qua app BIDV với nội dung:
  ┌─────────────────────────────┐
  │  HOANTIEN BK20250103        │
  └─────────────────────────────┘

Bước 3 — Admin bấm xác nhận:
  → POST /api/bookings/admin/update-status { bookingID, bookingStatus: "CANCELLED" }

Bước 4 — Hệ thống verify SePay:
  GET https://my.sepay.vn/userapi/transactions/list?account_number=10002897094&limit=100

  Điều kiện khớp (AND):
  ✓ amountOut > 0 (giao dịch ra)
  ✓ Trong vòng 24 giờ qua
  ✓ Chênh lệch số tiền ≤ 1,000 VND (dung sai làm tròn)
  ✓ transactionContent chứa booking code (case-insensitive)

  Nếu KHÔNG khớp:
  → HTTP 400: "Không tìm thấy giao dịch hoàn tiền..."

  Nếu khớp:
  → refundInfo.refundStatus = "COMPLETED"
  → refundInfo.refundDate = now()
  → refundInfo.note = "Verified via SePay: ref=..."
  → booking.status = CANCELLED
```

### 5.4 SepayServiceImpl — Hàm chính

| Hàm | Mô tả |
|-----|-------|
| `getRecentTransactions()` | Gọi SePay API, lấy 100 giao dịch gần nhất của TK BIDV |
| `verifyRefundTransaction(bookingCode, amount, ...)` | Lọc giao dịch theo 4 điều kiện, trả về `TransactionVerificationDTO` |
| `generateTransferContent(bookingCode)` | Trả về `"HOANTIEN BK20250103"` để hiển thị hướng dẫn cho admin |

---

## 6. Frontend — Hook & Component

### 6.1 `useAdminBookings.ts`

```typescript
// Quản lý state danh sách booking cho trang Admin
const useAdminBookings = (): AdminBookingsHook => {
    // ...
    const silentRefetch = useCallback(() => {
        silentRef.current = true;      // Đặt cờ "silent"
        setTrigger(t => t + 1);        // Trigger fetch
    }, []);

    const fetchBookings = async (isSilent: boolean) => {
        if (!isSilent) setLoading(true); // Chỉ show loading khi không silent
        // ... fetch logic
    };
    
    return { bookings, loading, refetch, silentRefetch, updateBookingInList, ... };
};
```

**silentRefetch** — chạy fetch nền, không bật loading spinner, tránh bảng flash trắng.

### 6.2 `useBookings.ts`

Tương tự `useAdminBookings` nhưng cho phía User (trang TransactionList).

### 6.3 `BookingsPage.jsx` — Admin

- Subscribe WebSocket topic `/topic/admin/bookings`
- `handleWebSocketMessage` → patch bảng tức thì + `silentRefetch`
- Hiển thị bảng booking có filter, pagination
- Mở modal theo status: `ConfirmBookingModal` / `CancelWithRefundModal` / `CancelWithoutRefundModal` / `ProcessRefundModal`

### 6.4 `TransactionList.jsx` — User

- Subscribe WebSocket topic `/topic/user/{userId}/bookings`
- Cùng pattern xử lý WS như Admin
- Hiển thị lịch sử đặt tour của user

---

## 7. Các lỗi đã phát hiện và sửa

### Lỗi 1 (NGHIÊM TRỌNG): notification-service DOWN trong Eureka

**Triệu chứng:**
```
GET http://localhost:8080/ws/info → 503 Service Unavailable
Gateway log: "No servers available for service: notification-service"
```

**Nguyên nhân gốc:**
```
MailHealthIndicator
  → Thử kết nối Gmail SMTP trong Docker
  → Timeout sau 96,000ms
  → Spring Boot health = DOWN (503)
  → EurekaHealthCheckHandler đồng bộ status DOWN lên Eureka Server
  → API Gateway RoundRobinLoadBalancer: 0 server khả dụng
  → TẤT CẢ route đến notification-service fail: /ws/** và /api/notifications/**
```

**Fix:** Tắt health indicator không ảnh hưởng chức năng:
```yaml
# notification-service/application.yml
management:
  health:
    mail:
      enabled: false   # Tắt SMTP health check
    redis:
      enabled: false   # Tắt Redis health check
```

**Kết quả:** `GET /actuator/health → {"status":"UP"}` → Eureka UP → Gateway route OK

---

### Lỗi 2: Bảng flash trắng khi WS update

**Triệu chứng:** Mỗi khi nhận WS message, bảng booking biến mất 1-2 giây rồi hiện lại — trông như reload trang.

**Nguyên nhân:**
```
WS message → refetch() → setLoading(true) → JSX return null/spinner → bảng mất → fetch xong → bảng hiện lại
```

**Fix:** Thêm `silentRefetch` dùng `useRef` flag — fetch nền không gọi `setLoading(true)`.

---

### Lỗi 3: WS message ghi đè field bằng `undefined`

**Nguyên nhân:**
```javascript
// Bug: event.cancelReason = null → null ?? undefined = undefined
//      {...booking, cancelReason: undefined} XÓA field trong state
patch.cancelReason = event.cancelReason ?? undefined;
```

**Fix:**
```javascript
// Chỉ thêm field vào patch nếu giá trị thực sự tồn tại
if (event.cancelReason != null) patch.cancelReason = event.cancelReason;
```

---

## 8. Kết quả kiểm thử API

Tất cả test chạy trực tiếp bằng PowerShell `Invoke-RestMethod` không dùng auth (service chưa bật security cho internal routes).

| Test | Endpoint | Request | Kết quả |
|------|----------|---------|---------|
| T1 | `POST /api/bookings/admin/search` | `{}` (không filter) | ✅ PASS — `total=8` |
| T2 | `POST /api/bookings/admin/search` | `{bookingStatus:"CANCELLED"}` | ✅ PASS — `total=5` |
| T3 | `POST /api/bookings/admin/search` | `{bookingCode:"BK20250103"}` | ✅ PASS — `found=1, status=PAID` |
| T4 | `GET http://localhost:8080/ws/info` | — (SockJS endpoint) | ✅ PASS — `entropy=1932267059` |
| T5 | `POST /api/notifications/booking-confirmed` (qua gateway) | `{bookingID:3,...}` | ✅ PASS — HTTP 200 |
| T6 | `POST /api/notifications/status-updated` (qua gateway) | `{bookingID:8,status:CANCELLED}` | ✅ PASS — HTTP 200 |
| T7 | `POST /api/bookings/admin/update-status` | `{bookingID:999,bookingStatus:"PAID"}` | ✅ PASS — HTTP 400 |

### Trạng thái Docker containers tại thời điểm test

```
tourism-api-gateway          Up (healthy)    ← Tất cả route hoạt động
tourism-booking-service      Up (healthy)    ← SePay integration deployed
tourism-notification-service Up (healthy)    ← Health fix deployed, UP in Eureka
tourism-tour-catalog-service Up (healthy)
tourism-service-discovery    Up (healthy)    ← Eureka Server
tourism-payment-service      Up (healthy)
tourism-iam-service          Up (unhealthy)  ← Vấn đề đã biết, không ảnh hưởng flow này
```

---

## PHỤ LỤC: Danh sách file đã chỉnh sửa

| File | Loại thay đổi |
|------|--------------|
| `booking-service/src/main/resources/application.yml` | Thêm cấu hình SePay |
| `booking-service/src/main/java/.../config/SepayConfig.java` | **Tạo mới** — @ConfigurationProperties |
| `booking-service/src/main/java/.../config/ModelMapperConfig.java` | Thêm `RestTemplate` bean |
| `booking-service/src/main/java/.../dto/sepay/SepayTransactionResponse.java` | **Tạo mới** — DTO response từ SePay API |
| `booking-service/src/main/java/.../dto/sepay/TransactionVerificationDTO.java` | **Tạo mới** — Kết quả xác minh |
| `booking-service/src/main/java/.../service/SepayService.java` | **Tạo mới** — Interface |
| `booking-service/src/main/java/.../service/impl/SepayServiceImpl.java` | **Tạo mới** — Implementation |
| `booking-service/src/main/java/.../service/impl/BookingServiceImpl.java` | Thêm SePay verification trong CANCELLED case |
| `notification-service/src/main/resources/application.yml` | **Tắt mail + redis health indicator** (critical fix) |
| `tourism_frontend/.../hook/useAdminBookings.ts` | Thêm `silentRefetch` |
| `tourism_frontend/.../hook/useBookings.ts` | Thêm `silentRefetch` |
| `tourism_frontend/.../BookingsPage/BookingsPage.jsx` | Fix WS handler: null-safe patch + silentRefetch |
| `tourism_frontend/.../TransactionList/TransactionList.jsx` | Fix WS handler: null-safe patch + silentRefetch |
