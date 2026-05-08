# Plan: Admin Quản Lý Bookings — Microservices Implementation

> **Mục tiêu**: Implement đầy đủ chức năng trang `/admin/bookings` trong microservices, 
> giữ nguyên 100% logic từ `Tourism_Backend` (monolith), không thay đổi giao diện frontend.

---

## 1. Phân Tích Hiện Trạng

### 1.1 Frontend gọi những API nào?

| Service | Endpoint | Method | Mô tả |
|---------|----------|--------|-------|
| `booking.ts` | `/bookings/admin/search` | POST | Tìm kiếm + phân trang (đang lỗi) |
| `booking.ts` | `/bookings/admin/update-status` | POST | Cập nhật trạng thái booking |

**Request body `/bookings/admin/search`:**
```json
{
  "bookingCode": "BK...",       // nullable, LIKE search
  "bookingStatus": "PAID",      // nullable, exact match
  "bookingDate": "2025-05-01T00:00:00"  // nullable, tìm theo ngày
}
```
Kèm **query params**: `page=0&size=5&sortBy=bookingDate&sortDir=DESC`

**Response** cần là Spring `Page<BookingResponse>`:
```json
{
  "content": [...],
  "totalPages": 3,
  "totalElements": 15,
  "number": 0,
  "size": 5
}
```

**Request body `/bookings/admin/update-status`:**
```json
{
  "bookingID": 123,
  "bookingStatus": "PAID" | "CANCELLED",
  "cancelReason": "..."  // chỉ khi CANCELLED
}
```

---

### 1.2 Frontend hiển thị gì?

**Bảng danh sách** (BookingsPage.jsx):
- Mã Booking, Tour (ảnh + tên + mã), Ngày Khởi Hành, Ngày Đặt, Trạng Thái, Hành Động
- Phân trang: 5 item/trang, prev/next
- Filter: bookingCode (search box) + bookingStatus (dropdown) + bookingDate (datepicker)
- WebSocket `/topic/admin/bookings` → refetch khi có event mới

**Action buttons** theo trạng thái (BookingItem.jsx):
| Status | Buttons |
|--------|---------|
| `PENDING_CONFIRMATION` | 👁 Xem, ✅ Xác nhận (→ PAID), ❌ Hủy + hoàn tiền |
| `PENDING_PAYMENT` | 👁 Xem, ❌ Hủy (không hoàn tiền) |
| `PAID` | 👁 Xem, ❌ Hủy + hoàn tiền |
| `PENDING_REFUND` | 👁 Xem, 💰 Hoàn tiền (→ CANCELLED) |
| `REVIEWED` | 👁 Xem, ⭐ Xem đánh giá |
| `CANCELLED`, `OVERDUE_PAYMENT` | 👁 Xem |

**Modals** (AdminBookingModals.jsx):
- `ConfirmBookingModal` — gọi `update-status` với status=`PAID`
- `CancelWithRefundModal` — hiển thị VietQR, gọi `update-status` với status=`CANCELLED` + cancelReason
- `CancelWithoutRefundModal` — gọi `update-status` với status=`CANCELLED` + cancelReason (PENDING_PAYMENT)
- `ProcessRefundModal` — hiển thị VietQR, gọi `update-status` với status=`CANCELLED` (PENDING_REFUND)

---

### 1.3 Monolith đã implement gì?

#### BookingController (monolith) — hai endpoint admin:
```java
POST /api/bookings/admin/search     → searchBookings(DTO, Pageable) → Page<BookingResponseDTO>
POST /api/bookings/admin/update-status → updateBookingStatus(DTO) → BookingResponseDTO
```

#### searchBookings logic (monolith `BookingRepositoryCustomImpl`):
- JPA Criteria API (dynamic query)
- Filter `bookingCode` → `LIKE '%...%'` (case insensitive)
- Filter `bookingStatus` → `EQUAL` (enum match)
- Filter `bookingDate` → `BETWEEN startOfDay AND endOfDay` (cả ngày)
- Pageable: `offset`, `maxResults`, `orderBy`

#### updateBookingStatus logic (monolith):
```
PENDING_CONFIRMATION → PAID:
  - Validate currentStatus == PENDING_CONFIRMATION
  - booking.status = PAID
  - mailService.sendPaymentConfirmationEmail(booking)
  - webSocketService.notifyAdminBookingUpdate(responseDTO)
  - webSocketService.notifyUserBookingUpdate(userId, responseDTO)

CANCELLED (từ PENDING_CONFIRMATION / PAID / PENDING_REFUND):
  - Validate currentStatus in allowed list
  - Tính refundAmount = totalPrice + paidByCoin (full amount, không áp fee)
  - Lấy tài khoản: ưu tiên RefundInformation, fallback Payment
  - SePay verify transaction (check lịch sử 24h)
  - Nếu không tìm thấy giao dịch → throw Exception (frontend retry)
  - booking.status = CANCELLED + cancelReason
  - mailService.sendCancellationWithRefundEmail (nếu có tiền)
  - mailService.sendCancellationEmail (nếu PENDING_PAYMENT)
  - webSocketService.notifyAdminBookingUpdate + notifyUserBookingUpdate
```

---

### 1.4 Microservices hiện tại thiếu gì?

| Thứ cần | Hiện trạng microservices |
|---------|--------------------------|
| `POST /bookings/admin/search` | ❌ Chưa có endpoint |
| `POST /bookings/admin/update-status` | ❌ Chưa có endpoint |
| Dynamic search (Criteria API / Specification) | ❌ `BookingRepository` chỉ có 2 findBy method đơn giản |
| `BookingSearchRequest` DTO | ❌ Chưa có |
| `BookingUpdateStatusRequest` DTO (admin) | ❌ Chưa có (chỉ có `CancelBookingRequest`) |
| Notification admin confirm/cancel | ⚠️ Có `NotificationFeignClient` nhưng chưa có event cho admin confirm PAID |
| WebSocket push `/topic/admin/bookings` | ⚠️ `WebSocketService` đã có trong notification-service nhưng chưa trigger từ admin update |

---

## 2. Kiến Trúc Luồng Dữ Liệu

```
Frontend (React)
    │
    ├─ POST /bookings/admin/search  ──────────────────►  api-gateway (8080)
    │                                                          │
    │                                                    booking-service (8083)
    │                                                    BookingController.adminSearch()
    │                                                    BookingServiceImpl.adminSearchBookings()
    │                                                    BookingRepositoryCustom (Criteria API)
    │                                                    → Page<BookingResponse>
    │                                                    Feign: tour-catalog, payment
    │◄──────────────────────────────────────────────────────────
    │
    ├─ POST /bookings/admin/update-status ───────────►  booking-service
    │                                                    BookingServiceImpl.adminUpdateBookingStatus()
    │                                                    Logic: PAID | CANCELLED + SePay verify
    │                                                    Feign: notification-service
    │                                                      → email (mail) + WebSocket push
    │◄──────────────────────────────────────────────────────────
    │
    └─ WebSocket /topic/admin/bookings ◄─────────────  notification-service
                                                          WebSocketService.notifyAdminBookingUpdate()
```

---

## 3. Danh Sách File Cần Tạo / Sửa

### 3.1 booking-service

#### Tạo mới:

| File | Mô tả |
|------|-------|
| `dto/request/AdminSearchBookingRequest.java` | DTO cho `admin/search`: bookingCode, bookingStatus, bookingDate (LocalDateTime) |
| `dto/request/AdminUpdateStatusRequest.java` | DTO cho `admin/update-status`: bookingID, bookingStatus, cancelReason |
| `repository/BookingRepositoryCustom.java` | Interface custom query |
| `repository/impl/BookingRepositoryCustomImpl.java` | Criteria API implementation (sao chép logic monolith) |

#### Sửa:

| File | Thay đổi |
|------|----------|
| `repository/BookingRepository.java` | Extend `BookingRepositoryCustom` |
| `service/BookingService.java` | Thêm 2 method: `adminSearchBookings()`, `adminUpdateBookingStatus()` |
| `service/impl/BookingServiceImpl.java` | Implement 2 method mới |
| `controller/BookingController.java` | Thêm 2 endpoint: `POST /admin/search`, `POST /admin/update-status` |
| `feign/NotificationFeignClient.java` | Thêm method `notifyAdminConfirmed()` (PAID status) |

### 3.2 notification-service

#### Tạo mới:

| File | Mô tả |
|------|-------|
| *(không cần file mới)* | Chỉ cần thêm endpoint + handler |

#### Sửa:

| File | Thay đổi |
|------|----------|
| `controller/NotificationController.java` | Thêm `POST /api/notifications/booking-confirmed` |
| `service/NotificationService.java` | Thêm `handleBookingConfirmed(event)` |
| `service/impl/NotificationServiceImpl.java` | Implement: email + WebSocket + lưu DB |
| `service/MailService.java` | Thêm method `sendPaymentConfirmationEmail(event)` |
| `service/impl/MailServiceImpl.java` | Implement email xác nhận thanh toán |

### 3.3 booking-service — SePay Integration

> **Lưu ý**: Monolith dùng `sepayService.verifyRefundTransaction()` để verify giao dịch trước khi CANCELLED. 
> Microservices cần implement tương đương.

#### Tùy chọn A (đơn giản — giống frontend flow):
- **Không verify SePay trong backend** — Frontend đã có QR + polling logic + manual confirm
- Admin click "Xác nhận" → gọi `update-status CANCELLED` trực tiếp (đã chuyển khoản thủ công)
- **Phù hợp nếu**: Không có SePay API key hoặc muốn đơn giản hóa

#### Tùy chọn B (giống hệt monolith):
- Tích hợp SePay API vào booking-service
- Tạo `SepayService.java` với method `verifyRefundTransaction()`
- Gọi SePay API verify giao dịch 24h gần đây khớp bookingCode + amount
- Nếu không tìm thấy → 422 Unprocessable Entity → frontend retry

**→ Plan này theo Tùy chọn A trước (đủ dùng), có thể nâng lên B sau.**

---

## 4. Logic Chi Tiết Cần Implement

### 4.1 `adminSearchBookings()` — Dynamic Query

```
Input: AdminSearchBookingRequest + Pageable (page, size, sortBy, sortDir)

Criteria API (giống monolith BookingRepositoryCustomImpl):
  predicates = []
  if (bookingCode != null && !blank):
    predicates.add( LIKE('%' + bookingCode.upper() + '%', field.upper()) )
  if (bookingStatus != null && !blank):
    predicates.add( EQUAL(BookingStatus.valueOf(bookingStatus)) )
  if (bookingDate != null):
    startOfDay = bookingDate.withHour(0).withMinute(0).withSecond(0)
    endOfDay   = bookingDate.withHour(23).withMinute(59).withSecond(59)
    predicates.add( BETWEEN(startOfDay, endOfDay) )

ORDER BY sortBy DESC/ASC
LIMIT size OFFSET page*size

Count query: same predicates, SELECT COUNT(*)

Return: PageImpl<Booking>(list, pageable, total)
   → map mỗi Booking → toResponse() (gọi Feign tour-catalog + payment)
   → Page<BookingResponse>
```

### 4.2 `adminUpdateBookingStatus()` — Status Machine

```
Input: AdminUpdateStatusRequest { bookingID, bookingStatus, cancelReason }

Find Booking by ID → 404 nếu không tìm thấy

switch (bookingStatus):

  case "PAID":
    Validate: currentStatus == PENDING_CONFIRMATION
    → throw nếu sai ("Chỉ xác nhận booking ở trạng thái Chờ xác nhận")
    booking.status = PAID
    → Feign notificationClient.notifyBookingConfirmed(event)
       (email khách xác nhận thanh toán + WebSocket admin)
    → return toResponse(booking)

  case "CANCELLED":
    Validate: currentStatus in [PENDING_PAYMENT, PENDING_CONFIRMATION, PAID, PENDING_REFUND]
    → throw nếu sai

    if currentStatus in [PENDING_CONFIRMATION, PAID, PENDING_REFUND]:
      refundAmount = totalPrice + paidByCoin
      lấy tài khoản: ưu tiên RefundInformation, fallback Payment
      (Tùy chọn B: SePay verify)

    booking.status = CANCELLED
    booking.cancelReason = cancelReason

    if currentStatus in [PENDING_CONFIRMATION, PAID, PENDING_REFUND]:
      → Feign notificationClient.notifyStatusUpdated(event) với refundAmount
    else:
      → Feign notificationClient.notifyStatusUpdated(event) không có refund

    → WebSocket admin + user
    → return toResponse(booking)

  default: throw "Trạng thái không hợp lệ"
```

### 4.3 Notification Event cho PAID (Confirm)

Thêm vào `BookingEventDTO` (booking-service và notification-service):
- Không cần field mới — các field hiện có đủ

Thêm endpoint mới notification-service:
```
POST /api/notifications/booking-confirmed
  → mailService.sendPaymentConfirmationEmail(event)
  → webSocketService.notifyAdminBookingUpdate(event)
  → webSocketService.notifyUserBookingUpdate(userId, event)
  → saveNotification(userId, BOOKING_CONFIRMED, ...)
```

---

## 5. Thứ Tự Implement

### Phase 1 — booking-service Admin Search (ưu tiên cao — fix trang ngay lập tức)

1. Tạo `AdminSearchBookingRequest.java` DTO
2. Tạo `BookingRepositoryCustom.java` interface  
3. Tạo `BookingRepositoryCustomImpl.java` với Criteria API
4. Extend `BookingRepository extends JpaRepository, BookingRepositoryCustom`
5. Thêm `adminSearchBookings()` vào `BookingService` interface
6. Implement trong `BookingServiceImpl`
7. Thêm `POST /api/bookings/admin/search` vào `BookingController`
8. **Build + deploy** → test trang load được danh sách

### Phase 2 — booking-service Admin Update Status

1. Tạo `AdminUpdateStatusRequest.java` DTO
2. Thêm `adminUpdateBookingStatus()` vào `BookingService` interface
3. Implement trong `BookingServiceImpl` (logic PAID + CANCELLED)
4. Thêm `POST /api/bookings/admin/update-status` vào `BookingController`
5. Thêm `notifyBookingConfirmed()` vào `NotificationFeignClient`

### Phase 3 — notification-service Booking Confirmed

1. Thêm endpoint `POST /api/notifications/booking-confirmed`
2. Thêm `handleBookingConfirmed()` vào service
3. Implement: email xác nhận + WebSocket push
4. **Build + deploy cả booking-service + notification-service**

---

## 6. BookingResponse Fields Cần Thiết (đã có sẵn)

Frontend `BookingResponseDTO.fromApiResponse()` cần các field sau — kiểm tra `BookingResponse.java` đã có đủ:

| Field | Có trong BookingResponse | Nguồn |
|-------|--------------------------|-------|
| `bookingID` | ✅ | entity |
| `bookingCode` | ✅ | entity |
| `bookingDate` | ✅ | entity |
| `contactFullName` | ✅ | entity |
| `contactEmail` | ✅ | entity |
| `contactPhone` | ✅ | entity |
| `totalPrice` | ✅ | entity |
| `paidByCoin` | ✅ | entity |
| `subtotalPrice` | ✅ | entity |
| `surcharge` | ✅ | entity |
| `couponDiscount` | ✅ | entity |
| `bookingStatus` | ✅ | entity (enum → string) |
| `cancelReason` | ✅ | entity |
| `refundAmount` | ✅ | entity |
| `departureDate` | ✅ | Feign tour-catalog |
| `tourName` | ✅ | Feign tour-catalog |
| `tourCode` | ✅ | Feign tour-catalog |
| `image` | ✅ | Feign tour-catalog |
| `paymentID` | ✅ | Feign payment |
| `bank` | ✅ | Feign payment |
| `accountNumber` | ✅ | Feign payment |
| `accountName` | ✅ | Feign payment |
| `refundBank` | ✅ | entity (RefundInformation) |
| `refundAccountNumber` | ✅ | entity (RefundInformation) |
| `refundAccountName` | ✅ | entity (RefundInformation) |
| `passengers` | ✅ | entity (list) |

**→ Tất cả fields đã có trong `BookingResponse.java` — không cần thêm field.**

---

## 7. Files Không Cần Thay Đổi

- ✅ Toàn bộ frontend (không chạm)
- ✅ `api-gateway/application.yml` — đã có route `/api/bookings/**` → booking-service
- ✅ `BookingConverter.java` — logic map entity → DTO đã đúng
- ✅ `BookingPassengerConverter.java`
- ✅ `Booking.java` entity
- ✅ `BookingStatus.java` enum
- ✅ `TourCatalogFeignClient.java`, `PaymentFeignClient.java`, `IamFeignClient.java`
- ✅ `NotificationController.java` (`/status-updated` đã xử lý CANCELLED)
- ✅ `BookingResponse.java`

---

## 8. Tổng Kết

**Việc cần làm**: Chỉ thêm code vào **booking-service** và **notification-service**, không sửa frontend, không sửa gateway.

**Số file mới tạo**: 4 file (2 DTO + 2 Repository)  
**Số file sửa**: 5 file (Service interface, ServiceImpl, Controller, FeignClient, NotificationService)  
**Thời gian estimate**: Phase 1 ~1h, Phase 2+3 ~2h
