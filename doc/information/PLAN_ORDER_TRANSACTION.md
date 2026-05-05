# PLAN — Logic Danh Sách Giao Dịch (Transaction List)

> **Nguồn tham chiếu (monolith):** `D:\HK8\Tourism_Backend`  
> **Mục tiêu:** `D:\HK8\tourism-microservices-backend` (booking-service, iam-service, payment-service)  
> **Frontend:** `http://localhost:3000/information/transaction` — **KHÔNG thay đổi**  
> **Lần cập nhật cuối:** 01/05/2026 — đọc lại toàn bộ monolith, sửa tất cả lỗi

---

## 1. Tổng Quan Trang Transaction

Trang `/information/transaction` hiển thị toàn bộ giao dịch đặt tour của người dùng đang đăng nhập.

### 1.1 Tabs hiển thị (frontend — KHÔNG thay đổi)

| Tab label | Giá trị `bookingStatus` gửi lên BE |
|---|---|
| Tất cả | *(không truyền tham số)* |
| Chờ thanh toán | `PENDING_PAYMENT` |
| Chờ xác nhận | `PENDING_CONFIRMATION` |
| Đã thanh toán | `PAID` |
| Đã hủy | `CANCELLED` |
| Quá hạn | `OVERDUE_PAYMENT` |
| Chờ đánh giá | `PENDING_REVIEW` |
| Đã đánh giá | `REVIEWED` |
| Chờ hoàn tiền | `PENDING_REFUND` |

### 1.2 API Endpoints — booking-service (qua API Gateway)

| Method | URL | Mô tả |
|---|---|---|
| GET | `/api/bookings/user/{userID}?bookingStatus=<status>` | Lấy danh sách giao dịch |
| POST | `/api/bookings/cancel` | Hủy + hoàn xu (tức thì, không cần admin) |
| POST | `/api/bookings/refund-request/{bookingID}` | Hủy + hoàn tiền ngân hàng (chờ admin) |

---

## 2. Vòng Đời Booking — BookingStatus Enum

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                   BOOKING LIFECYCLE                    │
                    └─────────────────────────────────────────────────────────┘

 [Đặt tour]
      │
      ▼
 PENDING_PAYMENT ──────(quá 24h)──────────────────► OVERDUE_PAYMENT
      │
      ├─(user hủy: cancelBooking)──────────────────► CANCELLED  [xu cộng ngay, KHÔNG cần admin]
      │
      └─(user thanh toán)──────────────────────────► PENDING_CONFIRMATION
                                │
                                ├─(user hủy: cancelBooking)──────────────────► CANCELLED  [xu cộng ngay]
                                ├─(user hủy: submitRefundRequest)────────────► PENDING_REFUND  [chờ admin]
                                │
                                └─(admin xác nhận thanh toán)────────────────► PAID
                                                    │
                                                    ├─(user hủy: cancelBooking)──────────────► CANCELLED  [xu cộng ngay]
                                                    ├─(user hủy: submitRefundRequest)────────► PENDING_REFUND  [chờ admin]
                                                    │
                                                    └─(sau ngày khởi hành xong)──────────────► PENDING_REVIEW
                                                                        │
                                                                        └─(user đánh giá)────► REVIEWED

 PENDING_REFUND ──────(admin xử lý hoàn tiền ngân hàng xong)────────────────► CANCELLED
```

> **⚠️ QUAN TRỌNG:**  
> - **`cancelBooking`** = hoàn xu → LUÔN ra `CANCELLED`, KHÔNG qua admin, xu cộng vào tài khoản ngay  
> - **`submitRefundRequest`** = hoàn tiền ngân hàng → ra `PENDING_REFUND`, chờ admin xử lý  
> - Hai luồng này hoàn toàn độc lập, không liên quan nhau về status

---

## 3. Chức Năng 1: Lấy Danh Sách Giao Dịch

### Endpoint
```
GET /api/bookings/user/{userID}?bookingStatus=PAID
```

### Logic (monolith `BookingServiceImpl.getAllBookingsByUser`)

```
1. Nếu bookingStatus != null → lọc theo status
2. Nếu bookingStatus == null → lấy tất cả
3. Sort: bookingDate DESC (mới nhất lên đầu)
4. Map từng Booking → BookingResponseDTO (xem section 5)
5. Trả về List<BookingResponseDTO>
```

### Microservices — `getBookingsByUser`

booking-service gọi:
- **booking_db** → lấy danh sách booking theo `userId`
- **tour-catalog-service** (Feign, per booking) → `GET /api/departures/{departureId}` → `departureDate`, `tourID`, `tourCode`, `tourName`, `image`, `duration`
- **payment-service** (Feign, per booking) → `GET /api/payment/by-booking/{bookingId}` → `paymentID`, `amount`, `timeLimit`, `bank`, `accountNumber`, `accountName`

---

## 4. Chức Năng 2: Hủy Booking + Hoàn Xu (cancelBooking)

> **Luồng này KHÔNG cần admin. Xu cộng tức thì vào tài khoản.**

### Endpoint
```
POST /api/bookings/cancel
Body: { "bookingID": 1 }
```

### Logic đầy đủ (monolith `BookingServiceImpl.cancelBooking` — NGUỒN SỰ THẬT)

```
Input: { bookingID, cancelReason? }

1. Tìm booking theo bookingID
   → Không tìm thấy: throw RuntimeException

2. Kiểm tra trạng thái:
   → Nếu bookingStatus == CANCELLED: throw "Booking is already cancelled."
   → Mọi status khác: tiếp tục

3. Đặt cancelReason (nếu có)

4. Tính refundableAmount:
   totalPaid         = booking.totalPrice + booking.paidByCoin
   daysToDepart      = ChronoUnit.DAYS.between(now, departureDate)
   feePercent        = determineCancellationFeePercent(daysToDepart)
   refundablePercent = 1 - feePercent
   refundableAmount  = totalPaid × refundablePercent  [setScale(0, RoundingMode.DOWN)]

5. Tính coinRefundAmount:
   coinRefundAmount = refundableAmount ÷ 1000  [RoundingMode.DOWN]

6. Cộng xu cho user (nếu coinRefundAmount > 0):
   user.coinBalance += coinRefundAmount
   → Monolith: userRepository.save(user)
   → Microservices: Feign → PATCH /api/users/{userId}/coins?amount=coinRefundAmount

7. Cập nhật booking:
   booking.bookingStatus = CANCELLED  ← LUÔN là CANCELLED
   booking.refundAmount  = refundableAmount

8. Lưu và trả về BookingResponseDTO
```

### Bảng phí hủy

| Số ngày trước khởi hành | Phí hủy | Hoàn lại |
|---|---|---|
| > 15 ngày | 10% | **90%** |
| > 5 ngày  | 50% | **50%** |
| > 2 ngày  | 70% | **30%** |
| ≥ 0 ngày  | 90% | **10%** |
| Đã qua ngày khởi hành | 100% | **0%** |

### Tỉ giá xu
```
1 coin = 1.000 VND
coinRefundAmount = floor(refundableAmount / 1000)
```

### Ví dụ

```
Tour PAID, totalPrice = 5.000.000₫, paidByCoin = 500.000₫, còn 20 ngày trước khởi hành

totalPaid        = 5.000.000 + 500.000         = 5.500.000₫
feePercent       = 10% (> 15 ngày)
refundableAmount = 5.500.000 × 0.90            = 4.950.000₫
coinRefundAmount = floor(4.950.000 / 1000)     = 4.950 coins

→ booking.status   = CANCELLED
→ booking.refundAmount = 4.950.000
→ user.coinBalance += 4.950  (tức thì, không cần admin)
```

---

## 5. Chức Năng 3: Hủy Booking + Hoàn Tiền Ngân Hàng (submitRefundRequest)

> **Luồng này cần admin. Booking chuyển sang PENDING_REFUND, chờ admin xác nhận chuyển khoản.**

### Endpoint
```
POST /api/bookings/refund-request/{bookingID}
Body: { "accountName": "...", "accountNumber": "...", "bank": "..." }
```

### Logic đầy đủ (monolith `BookingServiceImpl.requestRefund` — NGUỒN SỰ THẬT)

```
Input: bookingID, { accountName, accountNumber, bank }

1. Tìm booking theo bookingID
   → Không tìm thấy: throw RuntimeException

2. Kiểm tra trạng thái:
   → Nếu bookingStatus == CANCELLED:     throw "Already cancelled"
   → Nếu bookingStatus == PENDING_REFUND: throw "Already pending refund"
   → Chấp nhận: PENDING_CONFIRMATION, PAID (hoặc bất kỳ status nào khác)

3. Tính totalRefundAmount (cùng công thức như cancelBooking):
   totalPaid        = booking.totalPrice + booking.paidByCoin
   feePercent       = determineCancellationFeePercent(departureDate)
   totalRefundAmount = totalPaid × (1 - feePercent)  [scale(0, DOWN)]

4. Tạo/cập nhật RefundInformation:
   refundInformation.accountName   = request.accountName
   refundInformation.accountNumber = request.accountNumber
   refundInformation.bank          = request.bank
   refundInformation.refundAmount  = totalRefundAmount
   refundInformation.refundStatus  = "PENDING"
   
   Nếu booking đã có refundInformation:
     → cập nhật in-place (giữ nguyên refundID)
   Nếu chưa có:
     → tạo mới

5. Cập nhật booking:
   booking.refundInformation = savedRefundInfo
   booking.bookingStatus     = PENDING_REFUND
   booking.refundAmount      = totalRefundAmount

6. Monolith: gửi email thông báo admin (mailService)
   Microservices: bỏ qua email (không có mail service)

7. Lưu và trả về BookingResponseDTO
```

### Điểm khác biệt giữa 2 luồng

| | cancelBooking (hoàn xu) | submitRefundRequest (hoàn NH) |
|---|---|---|
| Status kết quả | `CANCELLED` | `PENDING_REFUND` |
| Cần admin? | ❌ Không, tức thì | ✅ Có, chờ admin |
| Xu cộng ngay? | ✅ Có | ❌ Không |
| Lưu bank info? | ❌ Không | ✅ Có |

---

## 6. Response DTO — Mapping giữa Monolith và Microservices

Frontend `BookingResponseDTO.ts` đọc các trường sau — microservices **phải** trả về đúng tên:

| Frontend `data.` | Monolith source | Microservices source |
|---|---|---|
| `bookingID` | `booking.bookingID` | `booking.bookingID` |
| `bookingCode` | `booking.bookingCode` | `booking.bookingCode` |
| `bookingDate` | `booking.bookingDate` | `booking.bookingDate` |
| `bookingStatus` | `booking.bookingStatus.name()` | `booking.bookingStatus.name()` |
| `totalPrice` | `booking.totalPrice` | `booking.totalPrice` |
| `subtotalPrice` | `booking.subtotalPrice` | `booking.subtotalPrice` |
| `surcharge` | `booking.surcharge` | `booking.surcharge` |
| `couponDiscount` | `booking.couponDiscount` | `booking.couponDiscount` |
| `paidByCoin` | `booking.paidByCoin` | `booking.paidByCoin` |
| `cancelReason` | `booking.cancelReason` | `booking.cancelReason` |
| `refundAmount` | `booking.refundAmount` | `booking.refundAmount` |
| `departureID` | `departure.departureID` | `booking.departureId` |
| `departureDate` | `departure.departureDate` | Feign → tour-catalog |
| `tourID` | `tour.tourID` | Feign → tour-catalog |
| `tourCode` | `tour.tourCode` | Feign → tour-catalog |
| `tourName` | `tour.tourName` | Feign → tour-catalog |
| `image` | main image URL | Feign → tour-catalog |
| `paymentID` | `payment.paymentID` | Feign → payment-service |
| `amount` ⚠️ | `payment.amount` | Feign → payment-service (field `amount`) |
| `timeLimit` | `payment.timeLimit` | Feign → payment-service |
| `bank` ⚠️ | `payment.bank` | Feign → payment-service (field `bank`) |
| `accountNumber` ⚠️ | `payment.accountNumber` | Feign → payment-service |
| `accountName` ⚠️ | `payment.accountName` | Feign → payment-service |
| `refundBank` | `refundInfo.bank` | `refundInfo.bank` |
| `refundAccountNumber` | `refundInfo.accountNumber` | `refundInfo.accountNumber` |
| `refundAccountName` | `refundInfo.accountName` | `refundInfo.accountName` |
| `passengers[]` | `booking.passengers` | `booking.passengers` |

> ⚠️ = Các trường đã bị sai trong microservices và đã sửa trong session này.

---

## 7. Kiến Trúc Gọi Service

### 7.1 cancelBooking

```
Frontend: POST /api/bookings/cancel  { bookingID }
    │
    ▼  [API Gateway → booking-service:8083]
    │
    ├─ [1] booking_db: findById(bookingID)
    ├─ [2] tour-catalog-service: getDepartureInfo(departureId) → departureDate (tính phí)
    ├─ [3] iam-service: PATCH /api/users/{userId}/coins?amount=X  (cộng xu)
    └─ [4] booking_db: save(booking)  status=CANCELLED, refundAmount=X
```

### 7.2 submitRefundRequest

```
Frontend: POST /api/bookings/refund-request/{bookingID}  { accountName, accountNumber, bank }
    │
    ▼  [API Gateway → booking-service:8083]
    │
    ├─ [1] booking_db: findById(bookingID)
    ├─ [2] tour-catalog-service: getDepartureInfo(departureId) → departureDate (tính phí)
    ├─ [3] booking_db: save(refundInformation)  accountName/Number/bank, refundStatus=PENDING
    └─ [4] booking_db: save(booking)  status=PENDING_REFUND, refundAmount=X
```

### 7.3 getBookingsByUser

```
Frontend: GET /api/bookings/user/{userID}?bookingStatus=PAID
    │
    ▼  [API Gateway → booking-service:8083]
    │
    ├─ [1] booking_db: findByUserIdAndBookingStatus (DESC by bookingDate)
    └─ [per booking] toResponse():
          ├─ [2] tour-catalog-service: getDepartureInfo(departureId)
          │       → departureDate, tourID, tourCode, tourName, image, duration
          └─ [3] payment-service: getPaymentByBooking(bookingID)
                  → paymentID, amount, timeLimit, bank, accountNumber, accountName
```

---

## 8. Toàn Bộ Lỗi Đã Phát Hiện và Sửa

### BUG 1 — `cancelBooking` sai hoàn toàn (CRITICAL) ✅ ĐÃ SỬA

| | Monolith (đúng) | Microservices trước fix (SAI) |
|---|---|---|
| Status sau khi hủy | `CANCELLED` luôn luôn | `PENDING_REFUND` nếu refund > 0 |
| Cộng xu cho user | ✅ Ngay lập tức | ❌ Không bao giờ cộng |
| Formula refund | `(totalPrice + paidByCoin) × (1 - fee)` | `totalPrice × (100 - fee) / 100` |
| Làm tròn | `setScale(0, RoundingMode.DOWN)` | Không làm tròn |
| Điều kiện block | Chỉ block `CANCELLED` | Block nhiều status thừa |

**Hậu quả trước khi sửa:**
- User hủy đơn đã thanh toán → đơn chuyển `PENDING_REFUND`, không xuất hiện ở tab "Đã hủy"
- Xu không bao giờ được cộng
- Số tiền hoàn trả tính thiếu (`paidByCoin` bị bỏ qua)

---

### BUG 2 — `submitRefundRequest` sai điều kiện (CRITICAL) ✅ ĐÃ SỬA

| | Monolith (đúng) | Microservices trước fix (SAI) |
|---|---|---|
| Chấp nhận status đầu vào | Bất kỳ (trừ CANCELLED/PENDING_REFUND) | Chỉ `PENDING_REFUND` |
| Kết quả status | Tự set `PENDING_REFUND` | Không thay đổi status |
| Tính refundAmount | Tự tính `calculateRefundableAmount` | Đọc từ `booking.getRefundAmount()` |

**Hậu quả trước khi sửa:** Bug 1 set booking → `PENDING_REFUND`, thì Bug 2 mới chạy được → hai bug bù trừ nhau tạo ra behavior SAI

---

### BUG 3 — Không có `IamFeignClient` (CRITICAL) ✅ ĐÃ SỬA

- booking-service không thể gọi iam-service để cộng xu
- **Fix:** Tạo `IamFeignClient.java` → `PATCH /api/users/{userId}/coins`

---

### BUG 4 — `calculateRefundableAmount` thiếu `paidByCoin` (CRITICAL) ✅ ĐÃ SỬA

```java
// SAI (trước fix):
booking.getTotalPrice().multiply(BigDecimal.valueOf(100 - feePercent)).divide(100)

// ĐÚNG (sau fix, khớp monolith):
(booking.getTotalPrice() + booking.getPaidByCoin()) × (1 - feePercent) [scale(0, DOWN)]
```

---

### BUG 5 — `BookingResponse.paymentAmount` sai tên field (CRITICAL) ✅ ĐÃ SỬA

- Frontend đọc `data.amount`, microservices trả về `paymentAmount`
- **Fix:** Đổi tên `paymentAmount` → `amount` trong `BookingResponse.java`

---

### BUG 6 — Response thiếu `bank`, `accountNumber`, `accountName` (CRITICAL) ✅ ĐÃ SỬA

- Monolith trả về `bank`, `accountNumber`, `accountName` từ Payment
- Frontend đọc 3 trường này để hiển thị thông tin thanh toán
- Microservices thiếu hoàn toàn 3 trường này
- **Fix:**
  - `payment-service/dto/PaymentInfoResponse.java` → thêm `bank`, `accountNumber`, `accountName`
  - `payment-service/controller/PaymentController.java` → map từ `payment.getBank()`, etc.
  - `booking-service/feign/dto/PaymentInfoResponse.java` → thêm 3 field
  - `booking-service/dto/response/BookingResponse.java` → thêm 3 field
  - `BookingServiceImpl.toResponse()` → map `bank`, `accountNumber`, `accountName`

---

## 9. Danh Sách File Đã Sửa (Tổng Kết)

### iam-service

| File | Thay đổi |
|---|---|
| `service/UserService.java` | Thêm `void addCoins(Integer userId, BigDecimal amount)` |
| `service/impl/UserServiceImpl.java` | Implement `addCoins()` |
| `controller/UserController.java` | Endpoint `PATCH /{userID}/coins?amount=X` |

### booking-service

| File | Thay đổi |
|---|---|
| `feign/IamFeignClient.java` | Tạo mới — `PATCH /api/users/{userId}/coins` |
| `feign/dto/PaymentInfoResponse.java` | Thêm `bank`, `accountNumber`, `accountName` |
| `dto/response/BookingResponse.java` | Đổi `paymentAmount` → `amount`; thêm `bank`, `accountNumber`, `accountName`; bỏ `paymentMethod`, `paymentStatus` (monolith không có) |
| `service/impl/BookingServiceImpl.java` | Sửa `cancelBooking` (luôn CANCELLED + addCoins), `submitRefundRequest` (nhận PAID/PENDING_CONFIRMATION → PENDING_REFUND), `calculateRefundableAmount` (+ paidByCoin, DOWN), `toResponse()` (map amount/bank/accountNumber/accountName đúng tên) |

### payment-service

| File | Thay đổi |
|---|---|
| `dto/PaymentInfoResponse.java` | Thêm `bank`, `accountNumber`, `accountName` |
| `controller/PaymentController.java` | Map `bank`, `accountNumber`, `accountName` từ entity |

---

## 10. Test Thủ Công Sau Deploy

### Test 1: Hủy đơn PENDING_PAYMENT (không mất phí)
```
Trạng thái ban đầu: PENDING_PAYMENT (chưa thanh toán)
totalPrice = 3.000.000, paidByCoin = 0

POST /api/bookings/cancel  { "bookingID": 1 }

Kỳ vọng:
- booking.status     = CANCELLED
- booking.refundAmount = 0  (chưa đặt cọc gì → totalPaid = 0)
- coinBalance không thay đổi
```

### Test 2: Hủy đơn PAID + hoàn xu (> 15 ngày)
```
Trạng thái ban đầu: PAID
totalPrice = 5.000.000, paidByCoin = 500.000
Ngày khởi hành: 20 ngày nữa

POST /api/bookings/cancel  { "bookingID": 2 }

Kỳ vọng:
- booking.status       = CANCELLED
- booking.refundAmount = (5.000.000 + 500.000) × 0.90 = 4.950.000₫
- user.coinBalance    += floor(4.950.000 / 1000) = 4.950 coins  (tức thì)
- Tab "Đã hủy" hiển thị đơn này
```

### Test 3: Hủy đơn PAID + hoàn ngân hàng
```
Trạng thái ban đầu: PAID
totalPrice = 5.000.000, paidByCoin = 0
Ngày khởi hành: 10 ngày nữa

POST /api/bookings/refund-request/3
Body: { "accountName": "Nguyen Van A", "accountNumber": "123456789", "bank": "VCB" }

Kỳ vọng:
- booking.status       = PENDING_REFUND
- booking.refundAmount = 5.000.000 × 0.50 = 2.500.000₫
- refund_information: accountName=Nguyen Van A, bank=VCB, refundStatus=PENDING
- Tab "Chờ hoàn tiền" hiển thị đơn này
- Xu KHÔNG được cộng
```

### Test 4: Lấy danh sách + kiểm tra fields response
```
GET /api/bookings/user/1

Kỳ vọng response mỗi item:
{
  "bookingID": ...,
  "bookingStatus": "PAID",
  "amount": 5000000,        ← KHÔNG phải paymentAmount
  "bank": "VCB",            ← từ payment
  "accountNumber": "...",   ← từ payment
  "accountName": "...",     ← từ payment
  "image": "https://...",   ← từ tour-catalog
  ...
}
```

---

## 11. Lưu Ý Quan Trọng

1. **`cancelBooking`** là hoàn xu — **KHÔNG CẦN ADMIN** — kết quả luôn là `CANCELLED`  
2. **`submitRefundRequest`** là hoàn ngân hàng — **CẦN ADMIN** — kết quả là `PENDING_REFUND`  
3. Hai luồng hoàn toàn độc lập, frontend chọn 1 trong 2 từ `CancelOptionModal`  
4. Monolith `updateBookingStatus` (admin) khi set `CANCELLED` từ `PENDING_REFUND` = admin xác nhận đã chuyển khoản xong → **Microservices cần implement endpoint admin này về sau**  
5. `paidByCoin` trong booking = số VND đã giảm từ điểm (≠ số coin dùng). Công thức: `paidByCoin = coinsUsed × 1000`  
