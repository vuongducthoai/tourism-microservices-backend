# BÁO CÁO SAI SÓT VÀ SỬA ĐỔI
## Dự án: tourism-microservices-backend
## Nguồn gốc chuẩn: Tourism_Backend (monolith)

---

## 1. TÓM TẮT

Sau khi đối chiếu toàn bộ enum và entity giữa dự án monolith `Tourism_Backend` và dự án microservices `tourism-microservices-backend`, đã phát hiện **9 enum sai** và **6 entity sai trường/bảng**. Tất cả đã được sửa để đảm bảo đồng bộ với monolith.

---

## 2. SỬA ĐỔI ENUM

### 2.1 BookingStatus *(booking-service)*

| Trạng thái | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `OVERDUE_PAYMENT` | Thiếu | **Thêm vào** |
| `PENDING_REVIEW` | Thiếu | **Thêm vào** |
| `REVIEWED` | Thiếu | **Thêm vào** |
| `REFUNDED` | Có (sai) | **Xóa bỏ** |

**Đúng:** `PENDING_PAYMENT, OVERDUE_PAYMENT, PENDING_CONFIRMATION, PAID, CANCELLED, PENDING_REVIEW, REVIEWED, PENDING_REFUND`

**Luồng nghiệp vụ:**
```
PENDING_PAYMENT → (hết hạn) → OVERDUE_PAYMENT
PENDING_PAYMENT → (thanh toán) → PENDING_CONFIRMATION → PAID
PAID → PENDING_REVIEW → REVIEWED
PAID → PENDING_REFUND → CANCELLED
```

---

### 2.2 CouponType *(booking-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `PERSONAL` | Có (sai) | **Xóa bỏ** |

**Đúng:** `GLOBAL, DEPARTURE`

---

### 2.3 PassengerType *(booking-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `SENIOR` | Có (sai) | **Xóa bỏ** |
| `SINGLE_SUPPLEMENT` | Thiếu | **Thêm vào** |

**Đúng:** `ADULT, CHILD, INFANT, SINGLE_SUPPLEMENT`

---

### 2.4 Role *(iam-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `STAFF` | Có (sai) | **Xóa bỏ** |
| `TOUR_OWNER` | Thiếu | **Thêm vào** |

**Đúng:** `CUSTOMER, ADMIN, TOUR_OWNER`

---

### 2.5 PaymentMethod *(payment-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `SEPAY` | Có (sai) | **Xóa bỏ** |

**Đúng:** `VNPAY, PAYOS`

---

### 2.6 PaymentStatus *(payment-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `COMPLETED` | Có (sai) | **Đổi thành `SUCCESS`** |

**Đúng:** `PENDING, SUCCESS, FAILED, REFUNDED`

---

### 2.7 Region *(tour-catalog-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `MIEN_BAC` | Sai (tiếng Việt) | **Đổi thành `NORTH`** |
| `MIEN_TRUNG` | Sai (tiếng Việt) | **Đổi thành `CENTRAL`** |
| `MIEN_NAM` | Sai (tiếng Việt) | **Đổi thành `SOUTH`** |
| `NUOC_NGOAI` | Sai (quốc tế) | **Xóa bỏ** (chỉ Việt Nam) |

**Đúng:** `NORTH, CENTRAL, SOUTH`

---

### 2.8 TransportType *(tour-catalog-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `RETURN` | Sai | **Đổi thành `INBOUND`** |

**Đúng:** `OUTBOUND, INBOUND`

---

### 2.9 VehicleType *(tour-catalog-service)*

| Giá trị | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `MAY_BAY` | Sai (tiếng Việt) | **Đổi thành `PLANE`** |
| `XE_KHACH` | Sai | **Đổi thành `BUS`** |
| `TAU_HOA` | Sai | **Đổi thành `TRAIN`** |
| `CA_NO` | Sai | **Đổi thành `SHIP`** |
| `XE_LIMOUSINE` | Sai | **Đổi thành `CAR`** |
| `TAU_CAO_TOC` | Sai | **Xóa bỏ** |
| `XE_TRUNG_CHUYEN` | Sai | **Xóa bỏ** |

**Đúng:** `PLANE, BUS, TRAIN, SHIP, CAR`

---

## 3. SỬA ĐỔI ENTITY

### 3.1 BookingPassenger.java *(booking-service)*

| Trường | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `phone` | Có (sai) | **Xóa bỏ** |
| `price` | Sai tên | **Đổi thành `basePrice` → cột `base_price`** |
| `gender` | Thiếu | **Thêm vào** |
| `dateOfBirth` | Thiếu | **Thêm vào (NOT NULL)** |
| `requiresSingleRoom` | Thiếu | **Thêm vào** |
| `singleRoomSurcharge` | Thiếu | **Thêm vào** |

---

### 3.2 DeparturePricing.java *(tour-catalog-service)*

| Mục | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| Tên bảng | `departure_pricings` (có 's') | **`departure_pricing` (không có 's')** |
| Cột `price` | Sai tên | **Đổi thành `sale_price`** |
| `ageDescription` | Thiếu | **Thêm vào `age_description` (NOT NULL)** |

---

### 3.3 DepartureTransport.java *(tour-catalog-service)*

| Trường | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `departureLocation` | Sai tên | **Đổi thành `startPoint` → `start_point`** |
| `arrivalLocation` | Sai tên | **Đổi thành `endPoint` → `end_point`** |
| `departureTime` (String) | Sai tên và kiểu | **Đổi thành `departTime` (LocalDateTime) → `depart_time`** |
| `arrivalTime` (String) | Sai kiểu | **Đổi thành LocalDateTime** |
| `transportCode` | Thừa | **Xóa bỏ** |
| `note` | Thừa | **Xóa bỏ** |
| `vehicleName` | Thiếu | **Thêm vào `vehicle_name`** |

---

### 3.4 TourDeparture.java *(tour-catalog-service)*

| Trường | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `departureCode` | Thừa | **Xóa bỏ** |
| `returnDate` | Thừa | **Xóa bỏ** |
| `totalSlots` | Thừa | **Xóa bỏ** |
| `departureDate` (LocalDate) | Sai kiểu | **Đổi thành LocalDateTime** |
| `tourGuideInfo` | Thiếu | **Thêm vào `tour_guide_info` (TEXT, NOT NULL)** |
| `couponId` | Thiếu | **Thêm vào `coupon_id` (Integer - cross-service)** |

---

### 3.5 Payment.java *(payment-service)*

| Trường | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `@Column(name = "bank_name")` | Sai column name | **Đổi thành `@Column(name = "bank")`** |

---

### 3.6 RefundInformation.java *(booking-service)*

| Trường | Trước (SAI) | Sau (ĐÚNG) |
|---|---|---|
| `bankName` | Tên trường sai → cột `bank_name` | **Đổi thành `bank` với `@Column(name = "bank")`** |

---

## 4. SỬA ĐỔI DỮ LIỆU SEED (doc/data)

### 4.1 Xóa bỏ dữ liệu quốc tế (chỉ giữ Việt Nam)

**Locations bị xóa:**
- Phuket (cũ ID 13), Bangkok (cũ ID 14), Singapore (cũ ID 15)

**Tours bị xóa:**
- Tour Phuket, Tour Bangkok, Tour Singapore

**Departures/Pricings/Transports tương ứng bị xóa.**

**Sau khi xóa:**
- 12 địa điểm (tất cả Việt Nam)
- 9 tour (tất cả Việt Nam)
- 15 departures
- Region chỉ dùng: `NORTH`, `CENTRAL`, `SOUTH`

### 4.2 01_iam_db_seed.sql

- Role `STAFF` → **`TOUR_OWNER`**
- Thêm các cột mới: `coin_balance`, `province_code`, `province_name`, `district_code`, `district_name`, `isemailverified`, `verification_token`, `verification_token_expiry`, `last_active_at`

### 4.3 02_tour_catalog_db_seed.sql

- Region: `MIEN_BAC/TRUNG/NAM/NUOC_NGOAI` → **`NORTH/CENTRAL/SOUTH`**
- Bảng `departure_pricing` (không có 's')
- Cột `sale_price`, `original_price`, `age_description`
- Bảng `departure_transports`: `start_point`, `end_point`, `depart_time`, `vehicle_name`
- VehicleType: `PLANE/BUS/TRAIN/SHIP/CAR`
- TransportType: `OUTBOUND/INBOUND`
- `tour_departures`: thêm `tour_guide_info`, xóa `departure_code`, `return_date`, `total_slots`

### 4.4 03_booking_db_seed.sql

- CouponType: xóa `PERSONAL`
- BookingStatus: dùng `PENDING_REVIEW`, `REVIEWED` thay vì `REFUNDED`
- BookingPassenger: xóa `phone`, thêm `gender`, `date_of_birth`, `requires_single_room`; đổi `price` → `base_price`
- Xóa booking quốc tế (Phuket, Bangkok, Singapore)
- Thêm `refund_information` table

### 4.5 04_payment_db_seed.sql

- `COMPLETED` → **`SUCCESS`**
- Xóa `SEPAY`, chỉ dùng `VNPAY`, `PAYOS`
- Cột `bank` (không phải `bank_name`)

### 4.6 06_notification_db_seed.sql

- Thêm cột `user_id` vào INSERT notifications
- Cập nhật `\c notification_db`
- Chỉ dùng user_id 1-8

### 4.7 07_analytics_db_seed.sql

- Tour IDs: chỉ 1-9 (Việt Nam)
- Xóa dữ liệu 3 tour quốc tế

---

## 5. FILE ĐÃ SỬA ĐỔI

### Java Enum Files (microservices):
- `booking-service/.../BookingStatus.java`
- `booking-service/.../CouponType.java`
- `booking-service/.../PassengerType.java`
- `iam-service/.../Role.java`
- `payment-service/.../PaymentMethod.java`
- `payment-service/.../PaymentStatus.java`
- `tour-catalog-service/.../Region.java`
- `tour-catalog-service/.../TransportType.java`
- `tour-catalog-service/.../VehicleType.java`

### Java Entity Files (microservices):
- `booking-service/.../BookingPassenger.java`
- `booking-service/.../RefundInformation.java`
- `payment-service/.../Payment.java`
- `tour-catalog-service/.../DeparturePricing.java`
- `tour-catalog-service/.../DepartureTransport.java`
- `tour-catalog-service/.../TourDeparture.java`

### SQL Seed Files:
- `doc/data/01_iam_db_seed.sql`
- `doc/data/02_tour_catalog_db_seed.sql`
- `doc/data/03_booking_db_seed.sql`
- `doc/data/04_payment_db_seed.sql`
- `doc/data/06_notification_db_seed.sql`
- `doc/data/07_analytics_db_seed.sql`