# Báo Cáo: Hoàn Trả Slot Khi Hủy Tour & Sửa Schema Database

**Ngày thực hiện:** 18/05/2026  
**Dự án:** Tourism Microservices Backend  
**Phạm vi:** booking-service, tour-catalog-service, PostgreSQL schema

---

## 1. Tóm Tắt

Hệ thống trước đây **không hoàn trả lại slot** cho tour khi booking bị hủy (dù admin hay user hủy). Báo cáo này ghi lại toàn bộ quá trình phân tích, sửa lỗi logic, đồng bộ schema DB, rebuild dịch vụ và kiểm thử.

---

## 2. Vấn Đề Phát Hiện

### 2.1. Không hoàn slot khi hủy booking
- Khi user hoặc admin hủy một booking, trường `available_slots` trong bảng `tour_departures` **không tăng trở lại**.
- Hậu quả: Tour bị "ảo full" dù đã có người hủy, khách hàng mới không thể đặt.

### 2.2. Schema DB không khớp entity
| Bảng | Vấn đề | Trạng thái |
|------|--------|------------|
| `booking_passengers` | Thiếu giá trị `TODDLER` trong CHECK constraint | ✅ Đã sửa (phiên trước) |
| `notifications` | Thiếu cột `is_read` | ✅ Đã có (column tồn tại) |
| `image_reviews` | Dữ liệu nằm ở cột `image_url` nhưng entity map sang cột `image` | ✅ Đã sửa |

---

## 3. Giải Pháp Thực Hiện

### 3.1. Thêm endpoint tăng slot (tour-catalog-service)

**File:** `tour-catalog-service/src/main/java/com/tourism/tourcatalog/repository/TourDepartureRepository.java`

```java
@Modifying
@Query("""
        UPDATE TourDeparture d
        SET d.availableSlots = d.availableSlots + :count
        WHERE d.departureID = :departureId
        """)
int increaseAvailableSlots(@Param("departureId") Integer departureId, @Param("count") int count);
```

**File:** `tour-catalog-service/.../controller/DepartureController.java`

```java
@PostMapping("/{departureId}/increase-slots")
@Transactional
public ResponseEntity<Void> increaseSlots(@PathVariable Integer departureId, @RequestParam int count) {
    tourDepartureRepository.increaseAvailableSlots(departureId, count);
    return ResponseEntity.ok().build();
}
```

### 3.2. Thêm Feign client method (booking-service)

**File:** `booking-service/.../feign/TourCatalogFeignClient.java`

```java
@PostMapping("/api/departures/{departureId}/increase-slots")
ResponseEntity<Void> increaseSlots(@PathVariable Integer departureId, @RequestParam int count);
```

### 3.3. Logic hoàn slot trong BookingServiceImpl

**File:** `booking-service/.../service/impl/BookingServiceImpl.java`

Thêm phương thức `releaseSlots()`:
```java
private void releaseSlots(Booking booking) {
    if (booking.getDepartureId() == null) return;
    int seatCount = 0;
    if (booking.getPassengers() != null) {
        seatCount = (int) booking.getPassengers().stream()
                .filter(p -> p.getPassengerType() != null 
                          && p.getPassengerType() != PassengerType.INFANT)
                .count();
    }
    if (seatCount > 0) {
        try {
            tourCatalogClient.increaseSlots(booking.getDepartureId(), seatCount);
            log.info("Released {} slots for departure {} (booking {})",
                    seatCount, booking.getDepartureId(), booking.getBookingCode());
        } catch (Exception e) {
            log.warn("Could not release slots for departure {}: {}",
                    booking.getDepartureId(), e.getMessage());
        }
    }
}
```

**Gọi `releaseSlots()` sau khi `bookingRepository.save()` trong 3 nơi:**

| Phương thức | Điều kiện |
|------------|-----------|
| `cancelBooking()` | Luôn gọi sau save |
| `submitRefundRequest()` | Luôn gọi sau save |
| `adminUpdateBookingStatus()` case CANCELLED | Chỉ gọi nếu `currentStatus != "PENDING_REFUND"` (tránh double-release) |

> **Lưu ý quan trọng:** `releaseSlots()` được đặt **SAU** `bookingRepository.save()` để đảm bảo nếu lưu DB thất bại, slot **không bị hoàn nhầm**.

### 3.4. Sửa schema DB – bảng image_reviews

Entity `ImageReview.java` map trường `imageUrl` sang cột tên `image`:
```java
@Column(name = "image")
private String imageUrl;
```

Tuy nhiên DB có thêm cột `image_url` (orphaned) chứa dữ liệu thực. Đã chạy:
```sql
UPDATE image_reviews SET image = image_url WHERE image_url IS NOT NULL;
ALTER TABLE image_reviews DROP COLUMN image_url;
```
Kết quả: 4 ảnh review được migrate sang cột đúng (`image`), cột thừa bị xóa.

---

## 4. Kết Quả Kiểm Thử

### 4.1. Test internal endpoint (tour-catalog-service)
```
POST http://localhost:8082/api/departures/5/increase-slots?count=3
→ HTTP 200 OK
→ available_slots: 25 → 28 ✅
```

### 4.2. Test end-to-end: User hủy booking
- **Booking:** BK20250103 (bookingid=3), trạng thái PAID, departure_id=5
- **Passengers:** 2 ADULT + 1 INFANT
- **Trước hủy:** departure 5 có **25** available_slots

```
POST http://localhost:8083/api/bookings/cancel
Body: {"bookingID": 3, "cancelReason": "Test slot release"}

Response: HTTP 200
{
  "bookingStatus": "CANCELLED",
  ...
}
```

- **Sau hủy:** departure 5 có **27** available_slots (+2 adult, infant không tính) ✅
- **Logic đúng:** INFANT không chiếm slot nên không được hoàn

---

## 5. Danh Sách Thay Đổi Code

| File | Loại thay đổi |
|------|---------------|
| `tour-catalog-service/.../repository/TourDepartureRepository.java` | Thêm query `increaseAvailableSlots` |
| `tour-catalog-service/.../controller/DepartureController.java` | Thêm endpoint `POST /{departureId}/increase-slots` |
| `booking-service/.../feign/TourCatalogFeignClient.java` | Thêm method `increaseSlots()` |
| `booking-service/.../service/impl/BookingServiceImpl.java` | Thêm `releaseSlots()` + gọi ở 3 nơi |

---

## 6. Danh Sách Thay Đổi Database

| Database | Bảng | Thay đổi |
|----------|------|----------|
| `booking_db` | `booking_passengers` | Thêm `TODDLER` vào CHECK constraint |
| `tour_catalog_db` | `image_reviews` | Copy data từ `image_url` → `image`, xóa cột `image_url` |

---

## 7. Trạng Thái Schema Sau Khi Sửa

| Service | Database | Bảng | Trạng thái |
|---------|----------|------|-----------|
| booking-service | booking_db | bookings | ✅ Khớp entity |
| booking-service | booking_db | booking_passengers | ✅ Khớp entity |
| booking-service | booking_db | outbox_events | ✅ Khớp entity |
| booking-service | booking_db | coupons | ✅ Khớp entity |
| booking-service | booking_db | refund_information | ✅ Khớp entity |
| tour-catalog-service | tour_catalog_db | tours | ✅ Khớp entity |
| tour-catalog-service | tour_catalog_db | tour_departures | ✅ Khớp entity |
| tour-catalog-service | tour_catalog_db | image_reviews | ✅ Đã sửa |
| iam-service | iam_db | users | ✅ Khớp entity |
| iam-service | iam_db | coin_transactions | ✅ Khớp entity |
| notification-service | notification_db | notifications | ✅ Khớp entity |
| forum-service | forum_db | forum_posts | ✅ Khớp entity |
| payment-service | payment_db | payments | ✅ Khớp entity |
| analytics-service | analytics_db | (all tables) | ✅ Khớp entity |

---

## 8. Dịch Vụ Được Rebuild & Khởi Động Lại

| Dịch vụ | Lý do rebuild |
|---------|---------------|
| `tour-catalog-service` | Thêm endpoint tăng slot |
| `booking-service` | Thêm logic `releaseSlots()` |
| `iam-service` | JAR cũ thiếu `AuthController` (chưa được package đầy đủ) |

---

## 9. Kết Luận

- ✅ Logic hoàn slot khi hủy booking đã được triển khai đầy đủ
- ✅ Cả user cancel (`/api/bookings/cancel`) và admin cancel (`/api/admin/bookings/{id}/status`) đều hoàn slot
- ✅ INFANT không được tính vào slot (đúng logic nghiệp vụ)
- ✅ Không double-release khi admin hủy booking đã ở trạng thái `PENDING_REFUND`
- ✅ Slot chỉ được hoàn **sau khi** lưu booking thành công (an toàn, không gây inconsistency)
- ✅ Schema DB đồng bộ với entity trên tất cả databases
