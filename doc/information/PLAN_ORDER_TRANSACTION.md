# PLAN — Logic Danh Sách Giao Dịch (Transaction List)

> **Nguồn tham chiếu (monolith):** `D:\HK8\Tourism_Backend`  
> **Mục tiêu:** `D:\HK8\tourism-microservices-backend` (booking-service, iam-service)  
> **Frontend:** `http://localhost:3000/information/transaction` — **KHÔNG thay đổi**  
> **Ngày cập nhật:** 29/04/2026

---

## 1. Tổng Quan Trang Transaction

Trang `/information/transaction` hiển thị toàn bộ giao dịch đặt tour của người dùng đang đăng nhập.

### 1.1 Tabs hiển thị (frontend)

| Tab label | Giá trị `bookingStatus` gửi lên BE |
|---|---|
| Tất cả | *(không lọc)* |
| Chờ thanh toán | `PENDING_PAYMENT` |
| Chờ xác nhận | `PENDING_CONFIRMATION` |
| Đã thanh toán | `PAID` |
| Đã hủy | `CANCELLED` |
| Quá hạn | `OVERDUE_PAYMENT` |
| Chờ đánh giá | `PENDING_REVIEW` |
| Đã đánh giá | `REVIEWED` |
| Chờ hoàn tiền | `PENDING_REFUND` |

### 1.2 Luồng API từ frontend

```
GET  /api/bookings/user/{userID}?bookingStatus=<TAB_VALUE>
POST /api/bookings/cancel                       { bookingID, cancelReason }
POST /api/bookings/refund-request/{bookingID}   { accountName, accountNumber, bank }
```

---

## 2. Vòng Đời Booking (BookingStatus Enum)

```
PENDING_PAYMENT
    │
    ├─ (hủy) ──────────────────────────────────────────► CANCELLED
    │
    ├─ (thanh toán) ───────────────────────────────────► PENDING_CONFIRMATION
    │
    └─ (quá 24h) ──────────────────────────────────────► OVERDUE_PAYMENT

PENDING_CONFIRMATION
    │
    ├─ (hủy + hoàn xu) ────────────────────────────────► CANCELLED
    ├─ (hủy + hoàn ngân hàng) ─────────────────────────► PENDING_REFUND
    │
    └─ (admin xác nhận) ───────────────────────────────► PAID

PAID
    │
    ├─ (hủy + hoàn xu) ────────────────────────────────► CANCELLED
    ├─ (hủy + hoàn ngân hàng) ─────────────────────────► PENDING_REFUND
    │
    └─ (sau chuyến đi) ────────────────────────────────► PENDING_REVIEW

PENDING_REVIEW ──────────────────────────────────────► REVIEWED

PENDING_REFUND ──► (admin xử lý hoàn) ──────────────► CANCELLED (hoàn xong)
```

---

## 3. Logic Hủy Booking — Hai Nhánh

Frontend hiển thị modal `CancelOptionModal` với 2 lựa chọn:

### 3.1 Nhánh A — Hoàn xu (Coin Refund) → `cancelBooking`

**Frontend gọi:** `POST /api/bookings/cancel`  
**Body:** `{ bookingID: number, cancelReason?: string }`

**Logic đúng (theo monolith `BookingServiceImpl.cancelBooking`):**

```
1. Tìm booking theo bookingID
2. Nếu bookingStatus == CANCELLED → throw exception
3. Tính refundableAmount:
       totalPaid     = totalPrice + paidByCoin
       daysToDepart  = ChronoUnit.DAYS.between(now, departureDate)
       feePercent    = determineCancellationFeePercent(daysToDepart)
       refundable    = totalPaid × (1 - feePercent)  [RoundingMode.DOWN, scale=0]
4. Tính coinRefundAmount = refundable ÷ 1000 [RoundingMode.DOWN]
5. Nếu coinRefundAmount > 0 → cộng xu cho user (PATCH /api/users/{userId}/coins?amount=X)
6. booking.bookingStatus = CANCELLED   ← LUÔN là CANCELLED, không bao giờ PENDING_REFUND
7. booking.refundAmount  = refundable
8. Lưu và trả về BookingResponse
```

**Kết quả trạng thái:** `CANCELLED` (luôn luôn)

---

### 3.2 Nhánh B — Hoàn ngân hàng (Bank Refund) → `submitRefundRequest`

**Frontend gọi:** `POST /api/bookings/refund-request/{bookingID}`  
**Body:** `{ accountName, accountNumber, bank }`

**Logic đúng (theo monolith `BookingServiceImpl.requestRefund`):**

```
1. Tìm booking theo bookingID
2. Nếu bookingStatus == CANCELLED hoặc PENDING_REFUND → throw exception
   (Chấp nhận mọi status khác: PAID, PENDING_CONFIRMATION, ...)
3. Tính totalRefundAmount (cùng công thức calculateRefundableAmount)
4. Tạo RefundInformation:
       accountName, accountNumber, bank  ← từ request
       refundStatus = "PENDING"
       refundAmount = totalRefundAmount
   Nếu đã có refundInformation trước → cập nhật in-place (setRefundID)
5. booking.bookingStatus = PENDING_REFUND
6. booking.refundAmount  = totalRefundAmount
7. Lưu refundInformation, lưu booking, trả về BookingResponse
```

**Kết quả trạng thái:** `PENDING_REFUND`

---

## 4. Công Thức Tính Refundable Amount

### Monolith — `calculateRefundableAmount` (nguồn tham chiếu):

```java
private BigDecimal calculateRefundableAmount(Booking booking) {
    BigDecimal totalPrice = booking.getTotalPrice()  != null ? booking.getTotalPrice()  : ZERO;
    BigDecimal paidByCoin = booking.getPaidByCoin()  != null ? booking.getPaidByCoin()  : ZERO;
    BigDecimal totalPaid  = totalPrice.add(paidByCoin);  // BẮT BUỘC phải cộng paidByCoin

    if (totalPaid <= 0) return ZERO;

    BigDecimal feePercent      = determineCancellationFeePercent(booking.getTourDeparture().getDepartureDate());
    BigDecimal refundablePercent = ONE.subtract(feePercent);

    return totalPaid.multiply(refundablePercent).setScale(0, RoundingMode.DOWN);  // làm tròn xuống
}
```

### Bảng phí huỷ tour:

| Số ngày trước khởi hành | Phí huỷ | Hoàn lại |
|---|---|---|
| > 15 ngày | 10% | 90% |
| > 5 ngày  | 50% | 50% |
| > 2 ngày  | 70% | 30% |
| ≥ 0 ngày  | 90% | 10% |
| Đã qua ngày | 100% | 0% |

### Tỉ giá xu: `1 coin = 1.000 VND`

```
coinRefundAmount = refundableAmount ÷ 1000   [RoundingMode.DOWN]
```

**Ví dụ:**  
- `totalPrice = 5.000.000 VND`, `paidByCoin = 500.000 VND`, còn 20 ngày trước khởi hành  
- `totalPaid = 5.500.000`  
- `feePercent = 10% (0.10)`, `refundable = 5.500.000 × 0.90 = 4.950.000 VND`  
- `coinRefund = 4.950.000 ÷ 1000 = 4.950 coins`

---

## 5. Kiến Trúc Microservices — Luồng Gọi Service

### 5.1 Sơ đồ luồng `cancelBooking`

```
Frontend
  │  POST /api/bookings/cancel
  ▼
API Gateway (8080)
  │  route → booking-service (8083)
  ▼
booking-service: BookingController.cancelBooking()
  │
  ├─ [1] bookingRepository.findById(bookingID)        ← booking_db
  │
  ├─ [2] tourCatalogClient.getDepartureInfo(depId)    ← Feign → tour-catalog-service (8082)
  │       → trả về departureDate để tính phí
  │
  ├─ [3] iamClient.addCoins(userId, coinAmount)       ← Feign → iam-service (8081)
  │       PATCH /api/users/{userId}/coins?amount=X
  │       → cộng xu vào coin_balance trong iam_db
  │
  └─ [4] bookingRepository.save(booking)              ← booking_db
         status = CANCELLED, refundAmount = X
```

### 5.2 Sơ đồ luồng `submitRefundRequest`

```
Frontend
  │  POST /api/bookings/refund-request/{bookingID}
  ▼
API Gateway (8080)
  │  route → booking-service (8083)
  ▼
booking-service: BookingController.submitRefundRequest()
  │
  ├─ [1] bookingRepository.findById(bookingID)        ← booking_db
  │
  ├─ [2] tourCatalogClient.getDepartureInfo(depId)    ← Feign → tour-catalog-service (8082)
  │       → trả về departureDate để tính phí
  │
  ├─ [3] refundRepository.save(refundInformation)     ← booking_db (refund_information table)
  │
  └─ [4] bookingRepository.save(booking)              ← booking_db
         status = PENDING_REFUND, refundAmount = X
```

### 5.3 Sơ đồ luồng `getBookingsByUser`

```
Frontend
  │  GET /api/bookings/user/{userID}?bookingStatus=PAID
  ▼
API Gateway → booking-service
  │
  ├─ bookingRepository.findByUserId...               ← booking_db
  │
  └─ [per booking] toResponse():
       ├─ tourCatalogClient.getDepartureInfo()        ← Feign → tour-catalog-service
       └─ paymentClient.getPaymentByBooking()         ← Feign → payment-service (8084)
```

---

## 6. Các Feign Client trong booking-service

| Feign Client | Service | Endpoint |
|---|---|---|
| `TourCatalogFeignClient` | tour-catalog-service | `GET /api/departures/{departureId}` |
| `PaymentFeignClient` | payment-service | `GET /api/payments/booking/{bookingId}` |
| `IamFeignClient` | iam-service | `PATCH /api/users/{userId}/coins?amount=X` |

---

## 7. Response DTO — BookingResponse

```java
// booking-service: BookingResponse.java
bookingID, bookingCode, bookingDate
contactEmail, contactFullName, contactPhone, contactAddress, customerNote
totalPassengers, subtotalPrice, surcharge, couponDiscount, paidByCoin, totalPrice
cancelReason, refundAmount, bookingStatus, appliedCouponCodes
departureID, departureDate, tourID, tourCode, tourName, image, duration  ← từ tour-catalog
paymentID, paymentAmount, timeLimit, paymentMethod, paymentStatus         ← từ payment-service
passengers[]                                                               ← danh sách hành khách
refundBank, refundAccountNumber, refundAccountName, refundStatus          ← RefundInformation
```

---

## 8. Báo Cáo Lỗi Đã Phát Hiện và Sửa

### Bug 1 — `cancelBooking`: Sai hoàn toàn (CRITICAL)

| | Monolith (đúng) | Microservices trước fix (SAI) |
|---|---|---|
| Status sau khi huỷ | `CANCELLED` (luôn luôn) | `PENDING_REFUND` nếu refund > 0 |
| Cộng xu cho user | ✅ Có | ❌ Không bao giờ cộng |
| Formula tính refund | `(totalPrice + paidByCoin) × (1 - fee)` | `totalPrice × (100 - fee) / 100` |
| Làm tròn | `setScale(0, RoundingMode.DOWN)` | Không làm tròn |
| Điều kiện block | Chỉ block `CANCELLED` | Block nhiều status không cần thiết |

**Hậu quả:**  
- User huỷ đơn thì đơn chuyển sang `PENDING_REFUND` thay vì `CANCELLED` → tab frontend hiển thị sai  
- Xu không bao giờ được cộng cho user  
- Số tiền hoàn trả tính sai (thiếu `paidByCoin`, không làm tròn đúng)

---

### Bug 2 — `submitRefundRequest`: Sai điều kiện tiên quyết (CRITICAL)

| | Monolith (đúng) | Microservices trước fix (SAI) |
|---|---|---|
| Chấp nhận status | PAID, PENDING_CONFIRMATION (bất kỳ trừ CANCELLED/PENDING_REFUND) | Chỉ `PENDING_REFUND` |
| Kết quả status | Tự set `PENDING_REFUND` | Không thay đổi status |
| Tính refundAmount | Tự tính từ `calculateRefundableAmount` | Lấy từ `booking.getRefundAmount()` đã set sẵn |

**Hậu quả:**  
- Bug 1 khiến huỷ đơn → `PENDING_REFUND`, rồi Bug 2 yêu cầu đã `PENDING_REFUND` mới nhận request hoàn bank → hai bug "bù trừ" nhau nhưng cả hai đều sai logic  
- Sau khi fix Bug 1 (cancel → CANCELLED), Bug 2 bị vỡ hoàn toàn vì không còn trạng thái `PENDING_REFUND` đầu vào  

---

### Bug 3 — Không có `IamFeignClient` (CRITICAL)

- booking-service không có client để gọi iam-service
- Không thể cộng xu cho user sau khi hủy đơn

---

### Bug 4 — `calculateRefundableAmount` thiếu `paidByCoin`

```java
// SAI (trước fix):
BigDecimal refundAmount = booking.getTotalPrice()
        .multiply(BigDecimal.valueOf(100 - feePercent))
        .divide(BigDecimal.valueOf(100));
// → thiếu paidByCoin, không làm tròn DOWN

// ĐÚNG (sau fix, khớp monolith):
BigDecimal totalPaid = totalPrice.add(paidByCoin);
return totalPaid.multiply(refundablePercent).setScale(0, RoundingMode.DOWN);
```

---

## 9. Danh Sách File Đã Sửa

### iam-service

| File | Thay đổi |
|---|---|
| `service/UserService.java` | Thêm `void addCoins(Integer userId, BigDecimal amount)` vào interface |
| `service/impl/UserServiceImpl.java` | Implement `addCoins()`: tìm user, cộng vào `coinBalance`, save |
| `controller/UserController.java` | Thêm endpoint `PATCH /{userID}/coins?amount=X` |

### booking-service

| File | Thay đổi |
|---|---|
| `feign/IamFeignClient.java` | Tạo mới — Feign client gọi iam-service: `PATCH /api/users/{userId}/coins` |
| `service/impl/BookingServiceImpl.java` | Sửa `cancelBooking`, `submitRefundRequest`, thay `calculateCancellationFeePercent` bằng `calculateRefundableAmount` + `getDaysUntilDeparture` + `determineCancellationFeePercent` |

---

## 10. Test Thủ Công Sau Deploy

### Test cancelBooking (hoàn xu):
```bash
# Đặt booking, thanh toán (status = PAID), sau đó:
POST /api/bookings/cancel
Body: { "bookingID": 1 }

# Kỳ vọng:
# - booking.bookingStatus = CANCELLED
# - booking.refundAmount  = (totalPrice + paidByCoin) × (1 - fee)
# - user.coinBalance tăng thêm (refundAmount / 1000)
```

### Test submitRefundRequest (hoàn ngân hàng):
```bash
# Booking đang ở trạng thái PAID hoặc PENDING_CONFIRMATION:
POST /api/bookings/refund-request/1
Body: { "accountName": "Nguyen Van A", "accountNumber": "123456789", "bank": "VCB" }

# Kỳ vọng:
# - booking.bookingStatus = PENDING_REFUND
# - booking.refundAmount  = tính từ công thức
# - refund_information record được tạo với refundStatus = PENDING
```

### Test getBookingsByUser (danh sách):
```bash
GET /api/bookings/user/1
GET /api/bookings/user/1?bookingStatus=PAID
GET /api/bookings/user/1?bookingStatus=PENDING_REFUND
# Kỳ vọng: trả về đúng danh sách, có đủ thông tin tour, payment, refund
```
