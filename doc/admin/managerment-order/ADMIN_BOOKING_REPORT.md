# BÁO CÁO TRIỂN KHAI: QUẢN LÝ ĐẶT TOUR (ADMIN BOOKINGS)

**Dự án:** Tourism Microservices  
**Module:** `booking-service` + `notification-service`  
**Ngày:** 2025-01-04  
**Thực hiện:** Admin Booking Management Feature  

---

## 1. VẤN ĐỀ GỐC

### Triệu chứng
Trang `/admin/bookings` trên frontend hiển thị lỗi:
> "Không thể tải danh sách bookings"

### Nguyên nhân
Frontend React gọi 2 endpoint không tồn tại trên `booking-service` (microservices):

| Endpoint | Mô tả | Trạng thái trước |
|----------|--------|-----------------|
| `POST /api/bookings/admin/search` | Tìm kiếm booking có phân trang + lọc | ❌ Chưa có |
| `POST /api/bookings/admin/update-status` | Cập nhật trạng thái booking | ❌ Chưa có |

Nguyên nhân gốc: Khi chuyển từ **monolith** (`Tourism_Backend`) sang **microservices** (`tourism-microservices-backend`), 2 endpoint admin này chưa được port sang `booking-service`.

---

## 2. THIẾT KẾ GIẢI PHÁP

### 2.1 Kiến trúc

```
React Admin UI
    │
    ▼
API Gateway (port 8080)
    │  /api/bookings/**
    ▼
booking-service (port 8083)
    │  Feign POST /api/notifications/booking-confirmed
    │  Feign POST /api/notifications/status-updated
    ▼
notification-service
    │  Email (JavaMailSender)
    │  WebSocket → /topic/admin/bookings
    │  WebSocket → /topic/user/{userId}/bookings
    ▼
Customer + Admin Browser
```

### 2.2 State Machine (Phần admin)

```
PENDING_PAYMENT  ──────→ CANCELLED (không hoàn tiền)
PENDING_CONFIRMATION ──→ PAID      (admin xác nhận thanh toán)
PENDING_CONFIRMATION ──→ CANCELLED (hoàn tiền đầy đủ)
PAID             ──────→ CANCELLED (hoàn tiền đầy đủ)
PENDING_REFUND   ──────→ CANCELLED (hoàn tiền đầy đủ)
```

**Lưu ý thiết kế:**  
- Phần admin **không** verify SePay (khác với customer path). Admin xác nhận thủ công qua giao diện.
- Refund = `totalPrice + paidByCoin` (hoàn toàn bộ, không trừ phí hủy — quyết định của admin)

---

## 3. CÁC FILE ĐÃ TẠO/SỬA ĐỔI

### 3.1 booking-service

| File | Trạng thái | Mô tả |
|------|-----------|-------|
| `dto/request/AdminSearchBookingRequest.java` | ✅ Tạo mới | DTO filter: bookingCode, bookingStatus, bookingDate |
| `dto/request/AdminUpdateStatusRequest.java` | ✅ Tạo mới | DTO update: bookingID, bookingStatus, cancelReason |
| `repository/BookingRepositoryCustom.java` | ✅ Tạo mới | Interface Criteria API search |
| `repository/impl/BookingRepositoryCustomImpl.java` | ✅ Tạo mới | Criteria API implementation (3 predicates) |
| `repository/BookingRepository.java` | ✅ Sửa | Extend `BookingRepositoryCustom` |
| `service/BookingService.java` | ✅ Sửa | Thêm 2 method mới |
| `service/impl/BookingServiceImpl.java` | ✅ Sửa | Implement 2 method + helper methods |
| `controller/BookingController.java` | ✅ Sửa | Thêm 2 endpoint mới |
| `feign/NotificationFeignClient.java` | ✅ Sửa | Thêm `notifyBookingConfirmed()` |

### 3.2 notification-service

| File | Trạng thái | Mô tả |
|------|-----------|-------|
| `service/NotificationService.java` | ✅ Sửa | Thêm `handleBookingConfirmed()` |
| `service/impl/NotificationServiceImpl.java` | ✅ Sửa | Implement: email + WebSocket + lưu DB |
| `service/MailService.java` | ✅ Sửa | Thêm `sendPaymentConfirmationEmail()` |
| `service/impl/MailServiceImpl.java` | ✅ Sửa | Implement: email xác nhận cho khách |
| `controller/NotificationController.java` | ✅ Sửa | Thêm endpoint `POST /booking-confirmed` |

---

## 4. LOGIC NGHIỆP VỤ

### 4.1 adminSearchBookings — Criteria API

```java
// 3 predicates (tất cả nullable — nếu null thì bỏ qua):
1. bookingCode   → LIKE '%...%' (case-insensitive, upper)
2. bookingStatus → EQUAL (BookingStatus enum)
3. bookingDate   → BETWEEN startOfDay(00:00:00) AND endOfDay(23:59:59)

// Kết quả: Page<BookingResponse> với pagination + sorting từ Pageable
```

### 4.2 adminUpdateBookingStatus — State Machine

#### PENDING_CONFIRMATION → PAID
```
1. Validate currentStatus == PENDING_CONFIRMATION
2. booking.status = PAID
3. save(booking)
4. [fire-and-forget] notificationClient.notifyBookingConfirmed(event)
   → email xác nhận cho khách
   → WebSocket admin
   → WebSocket user
```

#### * → CANCELLED
```
1. Validate currentStatus ∈ {PENDING_PAYMENT, PENDING_CONFIRMATION, PAID, PENDING_REFUND}
2. booking.status = CANCELLED
3. booking.cancelReason = request.cancelReason
4. Nếu từ {PENDING_CONFIRMATION, PAID, PENDING_REFUND}:
   refundAmount = totalPrice + paidByCoin
   booking.refundAmount = refundAmount
   Tìm bank account: RefundInformation → fallback BookingResponse (từ Payment)
5. save(booking)
6. [fire-and-forget] notificationClient.notifyStatusUpdated(event)
   → email hủy cho khách
   → WebSocket admin + user
```

### 4.3 Fire-and-Forget Pattern

```java
private void fireNotification(Runnable notifyCall) {
    try { notifyCall.run(); }
    catch (Exception e) { log.error(...); }
}
```
→ Notification failure **không bao giờ** rollback transaction booking.

---

## 5. API SPECIFICATION

### POST /api/bookings/admin/search

**Request Body:**
```json
{
  "bookingCode": "BK2025",
  "bookingStatus": "PAID",
  "bookingDate": "2025-01-15T00:00:00"
}
```
*(Tất cả fields đều nullable)*

**Query Params:**
| Param | Default | Mô tả |
|-------|---------|-------|
| `page` | 0 | Số trang (0-indexed) |
| `size` | 10 | Số bản ghi/trang |
| `sortBy` | bookingDate | Field sort |
| `sortDir` | DESC | ASC hoặc DESC |

**Response:** `200 OK` — `Page<BookingResponse>`
```json
{
  "content": [ { "bookingID": 1, "bookingCode": "BK20250101", ... } ],
  "totalElements": 8,
  "totalPages": 1,
  "size": 10,
  "number": 0
}
```

---

### POST /api/bookings/admin/update-status

**Request Body:**
```json
{
  "bookingID": 5,
  "bookingStatus": "PAID",
  "cancelReason": null
}
```

**Response — Success:** `200 OK` — `BookingResponse`
```json
{ "bookingID": 5, "bookingStatus": "PAID", ... }
```

**Response — Error:** `400 Bad Request`
```json
{ "message": "Chỉ có thể xác nhận booking ở trạng thái 'Chờ xác nhận'. Trạng thái hiện tại: REVIEWED" }
```

---

## 6. KẾT QUẢ KIỂM THỬ

### 6.1 Unit Tests — `AdminBookingServiceImplTest`

| Test Case | Kết quả |
|-----------|---------|
| `adminSearchBookings` — trả về page được map từ repository | ✅ PASS |
| `adminSearchBookings` — trả về trang rỗng khi không có kết quả | ✅ PASS |
| `adminUpdateBookingStatus` — PENDING_CONFIRMATION → PAID | ✅ PASS |
| `adminUpdateBookingStatus` — Từ chối PAID nếu status không phải PENDING_CONFIRMATION | ✅ PASS |
| `adminUpdateBookingStatus` — PENDING_PAYMENT → CANCELLED (không hoàn tiền) | ✅ PASS |
| `adminUpdateBookingStatus` — PAID → CANCELLED (có hoàn tiền) | ✅ PASS |
| `adminUpdateBookingStatus` — PENDING_CONFIRMATION → CANCELLED (có hoàn tiền) | ✅ PASS |
| `adminUpdateBookingStatus` — Từ chối hủy REVIEWED booking | ✅ PASS |
| `adminUpdateBookingStatus` — Từ chối hủy CANCELLED booking | ✅ PASS |
| `adminUpdateBookingStatus` — Từ chối status không hợp lệ | ✅ PASS |
| Notification failure không rollback transaction | ✅ PASS |

**Tổng: 11/11 tests PASS**

### 6.2 Integration Tests — API thực tế

| Test | Status | Kết quả |
|------|--------|---------|
| `POST /api/bookings/admin/search` (tất cả bookings) | 200 OK | Trả về 8 bookings |
| `POST /api/bookings/admin/search` (filter by status=PENDING_CONFIRMATION) | 200 OK | Trả về đúng |
| `POST /api/bookings/admin/update-status` (PENDING_PAYMENT → CANCELLED) | 200 OK | refundAmount=null ✅ |
| `POST /api/bookings/admin/update-status` (PAID → CANCELLED) | 200 OK | refundAmount=3,740,000 ✅ |
| `POST /api/bookings/admin/update-status` (REVIEWED → PAID, invalid) | 400 Bad Request | Message: "Chỉ có thể xác nhận..." ✅ |

---

## 7. QUY TRÌNH TRIỂN KHAI

### Build
```powershell
cd D:\HK8\tourism-microservices-backend
mvn clean package -pl booking-service -am -DskipTests      # BUILD SUCCESS
mvn clean package -pl notification-service -am -DskipTests  # BUILD SUCCESS
```

### Deploy
```powershell
docker cp booking-service/target/booking-service-1.0.0-SNAPSHOT.jar tourism-booking-service:/app.jar
docker restart tourism-booking-service

docker cp notification-service/target/notification-service-1.0.0-SNAPSHOT.jar tourism-notification-service:/app.jar
docker restart tourism-notification-service
```

---

## 8. CÁC VẤN ĐỀ ĐÃ GIẢI QUYẾT

| Vấn đề | Giải pháp |
|--------|-----------|
| Duplicate class body trong BookingController do replace_string_in_file | Xóa phần trùng lặp, giữ lại class hoàn chỉnh |
| BookingRepositoryCustomImpl — cần cả content query + count query cho Page | Tạo 2 query riêng, dùng helper `buildPredicates()` chung |
| Notification failure có thể rollback booking transaction | Dùng fire-and-forget pattern (`try-catch` bắt tất cả exception) |

---

## 9. TRẠNG THÁI CUỐI

✅ Frontend `/admin/bookings` hoạt động bình thường  
✅ Admin có thể tìm kiếm, lọc, phân trang danh sách bookings  
✅ Admin có thể xác nhận thanh toán (PENDING_CONFIRMATION → PAID)  
✅ Admin có thể hủy booking với tính toán hoàn tiền tự động  
✅ Email xác nhận gửi đến khách hàng khi admin confirm  
✅ WebSocket notification cập nhật real-time cho admin và user  
✅ Business rules được bảo vệ với error messages tiếng Việt rõ ràng  
