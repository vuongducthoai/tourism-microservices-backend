# Booking Management Flow — Microservices Architecture

> **Last updated:** Refactored `toResponse()` → `BookingConverter` (ModelMapper); replaced RabbitMQ → direct Feign notifications.

---

## 1. Tổng quan kiến trúc

```
Client (React)
    │
    ▼
api-gateway :8080
    │
    ├─► iam-service          :8081  (users, coins, auth)
    ├─► tour-catalog-service :8082  (tours, departures)
    ├─► booking-service      :8083  ← điều phối đặt tour
    ├─► payment-service      :8084  (thanh toán VNPay/chuyển khoản)
    └─► notification-service :8086  (email + WebSocket)
```

Mỗi service có **PostgreSQL riêng** (`booking_db`, `iam_db`, …).  
Giao tiếp giữa service dùng **OpenFeign** (đồng bộ, HTTP).

---

## 2. Các trạng thái của đơn hàng (BookingStatus)

```
PENDING_PAYMENT
    │  (user thanh toán xong)
    ▼
PAID
    │  (admin xác nhận)
    ▼
PENDING_CONFIRMATION  ──► (admin từ chối) ──► CANCELLED
    │  (admin duyệt)
    ▼
CONFIRMED
    │
    │   ┌── user hủy bằng xu ──► CANCELLED  (hoàn xu tức thì)
    └───┤
        └── user hủy chuyển khoản ──► PENDING_REFUND
                                            │  (admin duyệt hoàn tiền)
                                            ▼
                                         REFUNDED
```

---

## 3. Luồng đặt tour (Create Booking)

```
User → POST /api/bookings
         │
         ▼ booking-service
         1. Tạo Booking entity (status=PENDING_PAYMENT)
         2. Lưu BookingPassenger[]
         3. Lưu RefundInformation=null
         │
         ▼ return BookingResponse
```

Sau đó user chuyển hướng đến **payment-service** để thanh toán.  
Khi thanh toán xong, payment-service cập nhật booking status → `PAID`.

---

## 4. Luồng hủy đặt tour — Hoàn xu (Coin-Refund Path)

> User hủy trực tiếp, nhận lại xu ngay lập tức.

```
User → POST /api/bookings/{id}/cancel
              │
              ▼ BookingServiceImpl.cancelBooking()
              │
              1. Tìm Booking → phải tồn tại + chưa CANCELLED
              │
              2. Tính refundableAmount:
              │    base = totalPrice + paidByCoin
              │    fee%  = determineCancellationFeePercent(daysUntilDeparture)
              │    refundable = base × (1 - fee%)  [làm tròn xuống VND]
              │
              │    Bảng phí hủy:
              │    ┌────────────────┬──────────┬──────────────┐
              │    │ Ngày đến tour  │ Phí hủy  │ Hoàn lại     │
              │    ├────────────────┼──────────┼──────────────┤
              │    │ > 15 ngày      │   10%    │    90%       │
              │    │ 6–15 ngày      │   50%    │    50%       │
              │    │ 3–5 ngày       │   70%    │    30%       │
              │    │ 0–2 ngày       │   90%    │    10%       │
              │    │ Đã qua ngày    │  100%    │     0%       │
              │    └────────────────┴──────────┴──────────────┘
              │
              3. coinRefundAmount = floor(refundableAmount / 1000)
              │
              4. Feign → iam-service: POST /api/users/{userId}/coins/add
              │           body: { amount: coinRefundAmount }
              │           (nếu lỗi → rollback, không lưu)
              │
              5. booking.status = CANCELLED
                 booking.refundAmount = refundableAmount
                 booking.cancelReason = request.cancelReason
              │
              6. bookingRepository.save(booking)
              │
              7. [fire-and-forget] Feign → notification-service:
              │   POST /api/notifications/status-updated
              │   body: BookingEventDTO { coinRefundAmount, tourName, … }
              │       notification-service:
              │         ├── Email admin (22110431@student.hcmute.edu.vn)
              │         ├── Email customer (nếu coinRefundAmount > 0)
              │         └── WebSocket push
              │
              ▼ return BookingResponse
```

---

## 5. Luồng hủy đặt tour — Hoàn chuyển khoản (Bank-Refund Path)

> User điền thông tin ngân hàng, admin duyệt mới hoàn tiền.

```
User → POST /api/bookings/{id}/refund-request
              │  body: { bank, accountNumber, accountName }
              │
              ▼ BookingServiceImpl.submitRefundRequest()
              │
              1. Tìm Booking → phải tồn tại
              │  Chặn: CANCELLED hoặc đã PENDING_REFUND
              │
              2. Tính refundableAmount (cùng bảng phí như trên)
              │
              3. Tạo / cập nhật RefundInformation:
              │    bank, accountNumber, accountName
              │    refundStatus = "PENDING"
              │    refundAmount = refundableAmount
              │
              4. booking.status = PENDING_REFUND
                 booking.refundInformation = savedRefund
                 booking.refundAmount = refundableAmount
              │
              5. bookingRepository.save(booking)
              │
              6. [fire-and-forget] Feign → notification-service:
              │   POST /api/notifications/refund-requested
              │   body: BookingEventDTO { refundBank, refundAccountNumber, … }
              │       notification-service:
              │         ├── Email admin (22110431@student.hcmute.edu.vn)
              │         └── WebSocket push
              │
              ▼ return BookingResponse
```

---

## 6. Feign Calls từ booking-service

### 6.1 tour-catalog-service — Lấy thông tin chuyến đi

```
GET /api/departures/{departureId}/info
→ DepartureInfoResponse { departureDate, tourID, tourCode, tourName, image, duration }
```

Dùng trong:
- `cancelBooking()` — tính số ngày đến tour để xác định mức phí
- `submitRefundRequest()` — cùng mục đích
- `toResponse()` (qua `BookingConverter.enrichFromDeparture`) — gắn thông tin tour vào response

### 6.2 iam-service — Cộng xu

```
POST /api/users/{userId}/coins/add
body: BigDecimal (số xu)
```

Dùng trong: `cancelBooking()` — bắt buộc thành công trước khi lưu booking.

### 6.3 payment-service — Lấy thông tin thanh toán

```
GET /api/payments/booking/{bookingId}
→ PaymentInfoResponse { paymentID, amount, timeLimit, bank, accountNumber, accountName }
```

Dùng trong: `toResponse()` (qua `BookingConverter.enrichFromPayment`) — gắn thông tin thanh toán vào response.  
Trả về 404 nếu chưa thanh toán (PENDING_PAYMENT) — bắt `FeignException.NotFound`, bỏ qua.

### 6.4 notification-service — Gửi thông báo

```
POST /api/notifications/status-updated     ← khi huỷ (coin path)
POST /api/notifications/refund-requested   ← khi gửi yêu cầu hoàn tiền
body: BookingEventDTO
```

**Fire-and-forget**: luôn wrap trong `try/catch`, lỗi chỉ log, không throw.

---

## 7. BookingConverter — ModelMapper Pattern

### Mục đích

Tách logic mapping ra khỏi service để `BookingServiceImpl` ngắn gọn hơn, dễ test hơn.

### Cấu trúc

```
booking-service/convert/
├── BookingConverter.java           ← Booking entity → BookingResponse
└── BookingPassengerConverter.java  ← BookingPassenger entity → BookingPassengerResponse

booking-service/config/
└── ModelMapperConfig.java          ← @Bean ModelMapper (MatchingStrategy.STRICT)
```

### Quy tắc mapping

| Loại | Ví dụ | Cách xử lý |
|------|-------|------------|
| Cùng tên + cùng kiểu | `bookingCode`, `totalPrice`, `contactEmail`, … | ModelMapper tự động |
| Enum → String | `BookingStatus` → `String` | Map thủ công (`.name()`) |
| Tên khác nhau | `departureId` (entity) → `departureID` (DTO) | Map thủ công |
| Nested entity | `RefundInformation` → `refundBank`, `refundStatus`, … | Map thủ công |
| List entity → List DTO | `List<BookingPassenger>` → `List<BookingPassengerResponse>` | Dùng `BookingPassengerConverter` |
| Dữ liệu từ service khác | `DepartureInfoResponse`, `PaymentInfoResponse` | `enrichFromDeparture()`, `enrichFromPayment()` |

### API của BookingConverter

```java
// Tạo BookingResponse từ entity (không có Feign call)
BookingResponse toResponse(Booking booking)

// Gắn thông tin tour/chuyến đi (sau khi gọi tour-catalog-service)
void enrichFromDeparture(BookingResponse res, DepartureInfoResponse dep)

// Gắn thông tin thanh toán (sau khi gọi payment-service)
void enrichFromPayment(BookingResponse res, PaymentInfoResponse pay)
```

---

## 8. Notification Flow (sau khi RabbitMQ bị xóa)

```
booking-service
    │ (Feign call, fire-and-forget)
    ▼
notification-service /api/notifications/...
    │
    ▼ NotificationServiceImpl
    ├── MailServiceImpl.sendRefundRequestNotification()
    │       → Email đến ADMIN_EMAIL
    ├── MailServiceImpl.sendCancellationAdminNotification()
    │       → Email đến ADMIN_EMAIL (mọi lần hủy)
    ├── MailServiceImpl.sendCancellationCoinEmail()
    │       → Email đến customer (nếu coinRefundAmount > 0)
    └── WebSocketService.push()
            → /topic/admin/bookings
            → /topic/user/{userId}/bookings
```

**SMTP config (Gmail App Password):**
```
MAIL_USERNAME = trananhthu270904@gmail.com
MAIL_PASSWORD = kskw auzz nimm uybc
ADMIN_EMAIL   = 22110431@student.hcmute.edu.vn
```

---

## 9. Các file thay đổi trong đợt refactor này

| File | Thay đổi |
|------|----------|
| `booking-service/convert/BookingConverter.java` | **Tạo mới** — ModelMapper + manual mapping |
| `booking-service/convert/BookingPassengerConverter.java` | **Tạo mới** — passengerType enum→String |
| `booking-service/config/ModelMapperConfig.java` | **Tạo mới** — `@Bean ModelMapper` (STRICT) |
| `booking-service/service/impl/BookingServiceImpl.java` | Inject `BookingConverter`; xóa `toResponse()` 70 dòng → 20 dòng |
| `booking-service/test/.../BookingServiceImplTest.java` | Thêm `@Mock BookingConverter`; stub converter trong `@BeforeEach` |

---

## 10. Hằng số quan trọng

| Hằng số | Giá trị | Ý nghĩa |
|---------|---------|---------|
| `COIN_RATE` | `1000` | 1 xu = 1.000 VND |
| `ADMIN_EMAIL` | `22110431@student.hcmute.edu.vn` | Nhận email thông báo hủy/hoàn tiền |
| `notification-service` port | `8086` | Docker container |
| ModelMapper strategy | `STRICT` | Chỉ map field cùng tên tuyệt đối |

---

## 11. Lưu ý bất biến (không được thay đổi)

1. `cancelBooking()` → luôn đặt status = `CANCELLED` (KHÔNG phải `PENDING_REFUND`)
2. `submitRefundRequest()` → luôn đặt status = `PENDING_REFUND` (KHÔNG cộng xu)
3. `NotificationFeignClient` call luôn trong `try/catch` — lỗi notification KHÔNG làm rollback booking
4. Xu chỉ được cộng sau khi Feign `iamClient.addCoins()` thành công; nếu lỗi thì throw (booking không được save)
