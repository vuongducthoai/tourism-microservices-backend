# Kế hoạch API — Trang `/information`

**Mục tiêu:** Implement đủ API cho trang `http://localhost:3000/information` trong microservices backend, giữ nguyên logic monolith `Tourism_Backend`, chỉ thay đổi kiến trúc.

---

## 1. Phân tích trang `/information`

Trang này gồm 3 phần:

```
/information
├── Sidebar (PersonalProfile)     ← tab profile (hiển thị cố định bên trái)
├── /information/transaction      ← tab Lịch sử giao dịch (mặc định)
└── /information/favorites        ← tab Tour yêu thích
```

### Các API cần có (tổng hợp từ frontend)

| # | Service cần implement | Method | Path | Mục đích |
|---|---|---|---|---|
| 1 | `iam-service` | GET | `/api/auth/profile` | Lấy thông tin user từ JWT (AuthContext) |
| 2 | `iam-service` | GET | `/api/users/{userID}` | Lấy profile user chi tiết |
| 3 | `iam-service` | PUT | `/api/users/{userID}` | Cập nhật profile (tên, SĐT, ngày sinh, avatar) |
| 4 | `booking-service` | GET | `/api/bookings/user/{userID}` | Lấy danh sách booking của user (có filter status) |
| 5 | `booking-service` | POST | `/api/bookings/cancel` | Huỷ booking |
| 6 | `booking-service` | POST | `/api/bookings/refund-request/{bookingID}` | Gửi yêu cầu hoàn tiền |
| 7 | `tour-catalog-service` | GET | `/api/favorite-tours/user/{userId}` | Lấy danh sách tour yêu thích |
| 8 | `tour-catalog-service` | POST | `/api/favorite-tours/add` | Thêm tour vào yêu thích |
| 9 | `tour-catalog-service` | DELETE | `/api/favorite-tours/remove` | Bỏ tour khỏi yêu thích |
| 10 | `tour-catalog-service` | POST | `/api/reviews` | Gửi đánh giá tour |
| 11 | `tour-catalog-service` | GET | `/api/reviews/{bookingID}` | Xem đánh giá đã gửi theo bookingID |

> API Gateway đã có sẵn các route: `/api/auth/**`, `/api/users/**` → iam-service; `/api/bookings/**` → booking-service; `/api/reviews/**`, `/api/favorite-tours/**` → tour-catalog-service. **Không cần sửa gateway.**

---

## 2. Giải pháp hardcode userId để test (chưa có auth)

### Vấn đề
`InformationComponent` lấy `user` từ `useAuth()` (AuthContext). AuthContext cần `accessToken` trong localStorage và gọi `GET /api/auth/profile`. Nếu chưa có login thật, trang redirect về `/login`.

### Giải pháp: Dev Mock trong AuthContext

**Bước 1:** Tạo file `.env.local` trong `D:\HK8\tourism_frontend\client-side`:
```env
REACT_APP_DEV_USER_ID=1
```

**Bước 2:** Sửa `AuthContext.jsx` — thêm mock user khi có env var:
```js
// Trong checkAuth():
const devUserId = process.env.REACT_APP_DEV_USER_ID;
if (devUserId) {
    // Mock user object khớp với cả 2 cách FE đọc userId
    const mockUser = {
        id: parseInt(devUserId),
        userId: parseInt(devUserId),
        userID: parseInt(devUserId),
        email: 'dev@test.com',
        fullName: 'Dev User',
        phoneNumber: '',
        phone: '',
        coinBalance: 0,
        role: 'CUSTOMER',
        avatar: null,
        dateOfBirth: null
    };
    setUser(mockUser);
    setIsAuthenticated(true);
    setLoading(false);
    return;
}
```

**Bước 3:** Tạm thời bỏ JWT check trong API Gateway cho `localhost` dev:  
Hoặc đơn giản hơn: trong `axiosCustomize.js`, nếu không có `accessToken` thì **không gửi Authorization header** (đã làm rồi). Các endpoint không require auth trên gateway sẽ hoạt động bình thường.

**Kết quả:** Frontend sẽ render trang `/information` với `userId=1` mà không cần login thật, gọi các API với `userId=1`.

> **Lưu ý:** Khi code xong auth (iam-service), xoá `.env.local` là trở về luồng bình thường.

---

## 3. Chi tiết từng API cần implement

---

### 3.1 `iam-service` — Auth & User APIs

**Service:** Port 8081 | DB: `iam_db`  
**Hiện trạng:** Có entity `User` + `RefreshToken`, **chưa có bất kỳ controller nào.**

---

#### API 1: `GET /api/auth/profile`

**Mục đích:** AuthContext gọi sau khi đọc token từ localStorage để lấy thông tin user mới nhất.

**Request:** Header `Authorization: Bearer {jwt}`

**Logic:**
```
JwtFilter xác thực token → lấy email từ claims
→ UserRepository.findByEmail(email) → User entity
→ Map sang UserProfileResponse
→ 200 OK
```

**Response DTO `UserProfileResponse`:**
```json
{
  "id": 1,
  "email": "user@gmail.com",
  "role": "CUSTOMER",
  "fullName": "Nguyễn Văn A",
  "phoneNumber": "0901234567",
  "avatar": "https://cloudinary.com/...",
  "coinBalance": 500,
  "dateOfBirth": "1995-08-15"
}
```

> ⚠️ Frontend đọc `userData.userId || userData.id` — cần trả về cả 2 field `id` và `userId` hoặc đảm bảo đủ field.

---

#### API 2: `GET /api/users/{userID}`

**Mục đích:** `PersonalProfile.jsx` gọi để lấy thông tin hiển thị (tên, SĐT, ngày sinh, xu).

**Logic:**
```
UserRepository.findById(userID)
→ Map sang UserDetailResponse
→ 404 nếu không tìm thấy
```

**Response DTO `UserDetailResponse`:**
```json
{
  "userID": 1,
  "fullName": "Nguyễn Văn A",
  "phone": "0901234567",
  "dateOfBirth": "1995-08-15",
  "email": "user@gmail.com",
  "coinBalance": 500,
  "avatar": "https://cloudinary.com/...",
  "status": "ACTIVE"
}
```

---

#### API 3: `PUT /api/users/{userID}`

**Mục đích:** Cập nhật profile. Frontend gửi `multipart/form-data` hoặc JSON.

**Request body (multipart/form-data):**
```
fullName: "Nguyễn Văn B"
phone: "0909999999"
dateOfBirth: "1995-08-15"   (ISO date string)
avatar: [file]               (tuỳ chọn — MultipartFile)
```

**Logic:**
```
Validate: phone không trùng với user khác
Nếu có avatar file → upload lên Cloudinary → lấy URL
UserRepository.save(updated user)
→ trả về UserDetailResponse
```

**Cloudinary folder:** `tourism_avatars`

**Lưu ý từ monolith:**
- Phone validation: 10–11 số, bắt đầu bằng 0
- DateOfBirth: parse từ ISO string `"yyyy-MM-dd"`
- Avatar upload: `CloudinaryService.upload(file, "tourism_avatars")`
- Không cho phép đổi email

---

### 3.2 `booking-service` — Booking APIs

**Service:** Port 8083 | DB: `booking_db`  
**Hiện trạng:** Có entity `Booking`, `BookingPassenger`, `Coupon`, `RefundInformation`, **chưa có controller.**

**Đặc điểm quan trọng:** Booking entity lưu `userId(xref)` và `departureId(xref)` nhưng **không join trực tiếp** sang iam-service hay tour-catalog-service. Cần dùng **Feign Client** để lấy thông tin tour/departure và payment.

---

#### API 4: `GET /api/bookings/user/{userID}`

**Mục đích:** `TransactionList.jsx` hiển thị danh sách booking.

**Request:** `?bookingStatus=PAID` (optional)

**Logic:**
```
BookingRepository.findByUserIdOrderByBookingDateDesc(userID)
  → filter status nếu có
  → với mỗi booking:
      [Feign] TourCatalogClient.getDepartureById(departureId)
                → lấy: tourID, tourCode, tourName, departureDate, image
      [Feign] PaymentClient.getPaymentByBookingId(bookingId)
                → lấy: paymentID, amount, timeLimit
      → Map sang BookingResponse
→ List<BookingResponse>
```

**Response DTO `BookingResponse` (fields frontend cần):**
```json
{
  "bookingID": 101,
  "bookingCode": "BK-2027-0001",
  "bookingDate": "2026-04-15T10:30:00",
  "contactEmail": "user@gmail.com",
  "contactFullName": "Nguyễn Văn A",
  "contactPhone": "0901234567",
  "contactAddress": "Hà Nội",
  "customerNote": "",
  "totalPassengers": 2,
  "subtotalPrice": 5600000,
  "surcharge": 0,
  "couponDiscount": 0,
  "paidByCoin": 0,
  "totalPrice": 5600000,
  "cancelReason": null,
  "bookingStatus": "PAID",
  "departureID": 1,
  "departureDate": "2027-03-10T07:00:00",
  "tourID": 1,
  "tourCode": "HN-HL-3N2D",
  "tourName": "Hà Nội - Hạ Long 3 Ngày 2 Đêm",
  "image": "https://...",
  "paymentID": 201,
  "amount": 5600000,
  "timeLimit": "2026-04-16T10:30:00",
  "passengers": [
    {
      "bookingPassengerID": 1,
      "fullName": "Nguyễn Văn A",
      "gender": "MALE",
      "dateOfBirth": "1995-08-15",
      "passengerType": "ADULT",
      "basePrice": 2800000,
      "requiresSingleRoom": false,
      "singleRoomSurcharge": 0
    }
  ],
  "bank": null,
  "accountNumber": null,
  "accountName": null,
  "refundBank": null,
  "refundAccountNumber": null,
  "refundAccountName": null,
  "refundAmount": null
}
```

**Feign Clients cần tạo:**
- `TourCatalogFeignClient` → gọi tour-catalog-service lấy departure info
- `PaymentFeignClient` → gọi payment-service lấy payment info

---

#### API 5: `POST /api/bookings/cancel`

**Mục đích:** Nút "Huỷ booking" trong TransactionListItem.

**Request body:**
```json
{ "bookingID": 101 }
```

**Logic (theo monolith):**
```
Booking booking = BookingRepository.findById(bookingID)
Validate: bookingStatus phải là PENDING_PAYMENT hoặc PENDING_CONFIRMATION hoặc PAID
Tính phí huỷ dựa vào ngày khởi hành:
  └─ departureDate - today > 15 ngày → phí 10% → hoàn 90%
  └─ > 5 ngày   → phí 50% → hoàn 50%
  └─ > 2 ngày   → phí 70% → hoàn 30%
  └─ >= 0 ngày  → phí 90% → hoàn 10%
  └─ đã khởi hành → phí 100% → không hoàn

Nếu bookingStatus = PENDING_PAYMENT:
  → chuyển status = CANCELLED (không hoàn tiền vì chưa thanh toán)

Nếu bookingStatus = PAID:
  → tính refundAmount = totalPrice * (1 - feePercent/100)
  → nếu hoàn xu (paidByCoin > 0) → hoàn xu về user [Feign IamClient]
  → chuyển status = PENDING_REFUND (nếu refundAmount > 0) hoặc CANCELLED

→ [RabbitMQ] publish event BOOKING_CANCELLED
→ trả về BookingResponse cập nhật
```

---

#### API 6: `POST /api/bookings/refund-request/{bookingID}`

**Mục đích:** Gửi thông tin tài khoản ngân hàng để nhận hoàn tiền.

**Request body:**
```json
{
  "accountName": "NGUYEN VAN A",
  "accountNumber": "1234567890",
  "bank": "Vietcombank"
}
```

**Logic:**
```
Validate: bookingStatus phải là PENDING_REFUND
Tạo RefundInformation entity với thông tin ngân hàng
Save → chuyển status = PENDING_REFUND (giữ nguyên, admin xử lý thủ công)
→ [RabbitMQ] publish event REFUND_REQUESTED
→ trả về BookingResponse cập nhật
```

---

### 3.3 `tour-catalog-service` — Favorite Tours & Reviews

**Service:** Port 8082 | DB: `tour_catalog_db`  
**Hiện trạng:** Đã có sẵn entity `FavoriteTour` và `Review`, **chưa có controller/service/repository cho 2 feature này.**

---

#### API 7: `GET /api/favorite-tours/user/{userId}`

**Mục đích:** `FavoriteTours.jsx` hiển thị danh sách tour yêu thích.

**Logic:**
```
FavoriteTourRepository.findByUserId(userId)
  → lấy list FavoriteTour → join Tour
  → với mỗi tour: map sang TourSearchResponse
     (dùng lại TourToSearchResponseConverter đã có)
→ List<TourSearchResponse>
```

**Response:** Mảng `TourSearchResponse` (giống endpoint `/api/tours/search` đã có).

---

#### API 8: `POST /api/favorite-tours/add`

**Request params:** `?userId=1&tourId=2`

**Logic:**
```
Validate: Tour tồn tại và status=true
Check: FavoriteTourRepository.existsByUserIdAndTour(userId, tour) → nếu đã có thì bỏ qua
Save FavoriteTour(userId, tour)
→ 200 OK "Tour added to favorites"
```

---

#### API 9: `DELETE /api/favorite-tours/remove`

**Request params:** `?userId=1&tourId=2`

**Logic:**
```
FavoriteTourRepository.deleteByUserIdAndTourId(userId, tourId)
→ 200 OK "Tour removed from favorites"
```

---

#### API 10: `POST /api/reviews`

**Mục đích:** Gửi đánh giá sau khi tour kết thúc. Gọi từ `ReviewComponent` trong TransactionListItem.

**Request (multipart/form-data):**
```
rating:    4          (Integer, 1–5, required)
comment:   "Tour rất tốt"  (String)
tourID:    1          (Integer, required)
bookingID: 101        (Integer, required)
images:    [file1, file2]  (tuỳ chọn — upload Cloudinary)
```

**Logic:**
```
Validate: bookingID tồn tại và bookingStatus = PAID
          (cần Feign BookingClient để check)
Validate: chưa có review cho bookingID này
Nếu có ảnh → upload lên Cloudinary folder "review_images"
Lưu Review(rating, comment, bookingId, tourId, userId)
Lưu ImageReview[] nếu có ảnh
→ [Feign] BookingClient.updateStatus(bookingID, REVIEWED)
→ [RabbitMQ] publish event REVIEW_CREATED (cộng xu cho user)
→ 201 Created ReviewResponse
```

**Response DTO `ReviewResponse`:**
```json
{
  "reviewID": 10,
  "rating": 4,
  "comment": "Tour rất tốt, hướng dẫn viên nhiệt tình",
  "bookingCode": "BK-2027-0001",
  "tourCode": "HN-HL-3N2D",
  "imageUrls": ["https://cloudinary.com/review_images/..."]
}
```

**Lưu ý:** `userId` lấy từ JWT token (khi có auth) hoặc từ request param (khi test dev).

---

#### API 11: `GET /api/reviews/{bookingID}`

**Mục đích:** `ViewReviewModal` hiển thị nội dung review đã viết.

**Logic:**
```
ReviewRepository.findByBookingId(bookingID)
→ 404 nếu không tìm thấy
→ Map sang ReviewResponse (kèm imageUrls)
```

---

## 4. Feign Clients cần tạo

| Feign Client | Service gọi | Gọi sang service | Mục đích |
|---|---|---|---|
| `TourCatalogFeignClient` | booking-service | tour-catalog-service | Lấy departure info, tour name/image khi build BookingResponse |
| `PaymentFeignClient` | booking-service | payment-service | Lấy timeLimit, paymentID cho PENDING_PAYMENT booking |
| `BookingFeignClient` | tour-catalog-service | booking-service | Validate bookingStatus khi submit review; cập nhật status → REVIEWED |
| `IamFeignClient` | booking-service | iam-service | Hoàn xu về user khi huỷ booking có paidByCoin |

> Tất cả Feign clients dùng service discovery name (ví dụ `BOOKING-SERVICE`) không hardcode port.

---

## 5. Cấu trúc file cần tạo mới

### iam-service
```
src/main/java/com/tourism/iam/
├── controller/
│   ├── AuthController.java        ← GET /auth/profile
│   └── UserController.java        ← GET/PUT /users/{id}
├── service/
│   ├── AuthService.java
│   ├── UserService.java
│   └── impl/
│       ├── AuthServiceImpl.java
│       └── UserServiceImpl.java
├── repository/
│   ├── UserRepository.java
│   └── RefreshTokenRepository.java
├── dto/
│   ├── request/UserUpdateRequest.java
│   └── response/UserProfileResponse.java
│       UserDetailResponse.java
├── security/
│   ├── JwtUtil.java               ← tạo/validate JWT
│   └── JwtFilter.java             ← Spring Security filter
└── config/
    ├── SecurityConfig.java        ← (đã có, cần sửa)
    └── CloudinaryConfig.java
```

### booking-service
```
src/main/java/com/tourism/booking/
├── controller/
│   └── BookingController.java     ← GET/POST endpoints
├── service/
│   ├── BookingService.java
│   └── impl/BookingServiceImpl.java
├── repository/
│   ├── BookingRepository.java
│   └── RefundInformationRepository.java
├── dto/
│   ├── request/
│   │   ├── CancelBookingRequest.java
│   │   └── RefundInformationRequest.java
│   └── response/
│       ├── BookingResponse.java
│       └── BookingPassengerResponse.java
└── feign/
    ├── TourCatalogFeignClient.java
    ├── PaymentFeignClient.java
    └── IamFeignClient.java
```

### tour-catalog-service (bổ sung)
```
src/main/java/com/tourism/tourcatalog/
├── controller/
│   ├── FavoriteTourController.java   ← 3 endpoints mới
│   └── ReviewController.java         ← 2 endpoints mới
├── service/
│   ├── FavoriteTourService.java
│   ├── ReviewService.java
│   └── impl/
│       ├── FavoriteTourServiceImpl.java
│       └── ReviewServiceImpl.java
├── repository/
│   ├── FavoriteTourRepository.java
│   └── ReviewRepository.java
├── dto/
│   ├── request/ReviewRequest.java
│   └── response/ReviewResponse.java
└── feign/
    └── BookingFeignClient.java       ← validate + update status
```

---

## 6. Thứ tự implement đề xuất

```
Bước 1: tour-catalog-service — FavoriteTour (3 API)
  → Đơn giản nhất, không cần Feign, test ngay được với userId hardcode

Bước 2: tour-catalog-service — Review (2 API)
  → Cần BookingFeignClient nhưng có thể mock khi dev

Bước 3: iam-service — User (2 API: GET + PUT /users/{id})
  → Không cần JWT, test với userId trực tiếp

Bước 4: booking-service — GET /bookings/user/{userID}
  → Cần TourCatalogFeignClient + PaymentFeignClient
  → Phức tạp nhất (phải build BookingResponse đầy đủ)

Bước 5: booking-service — POST /bookings/cancel + refund-request
  → Phụ thuộc vào bước 4

Bước 6: iam-service — GET /auth/profile (JWT)
  → Implement sau khi có booking/favorite hoạt động, tiện test e2e
```

---

## 7. Thứ tự API để test Dev (hardcode userId=1)

### Setup 1 lần:

1. Tạo file `D:\HK8\tourism_frontend\client-side\.env.local`:
   ```env
   REACT_APP_DEV_USER_ID=1
   ```

2. Sửa `AuthContext.jsx` — thêm dev mock (xem hướng dẫn mục 2).

3. Đảm bảo DB có user với ID=1 trong `iam_db.users` (INSERT thủ công qua psql).

### Test từng bước:
```
Test Bước 1: mở /information/favorites → thấy danh sách rỗng (userId=1)
Test Bước 2: gọi POST /favorite-tours/add → reload → thấy tour trong list
Test Bước 4: mở /information/transaction → thấy danh sách booking
Test Bước 3: sidebar PersonalProfile hiển thị tên/email/xu
```

---

## 8. Điểm khác biệt Monolith → Microservices

| Điểm | Monolith | Microservices |
|---|---|---|
| User entity | Cùng DB với Tour | `iam_db` riêng, truy cập qua Feign |
| Booking entity | Join trực tiếp với Tour/Departure | `departureId` là cross-service ref, cần Feign |
| Review validation | Gọi trực tiếp BookingRepository | Gọi qua `BookingFeignClient` |
| Avatar upload | Cloudinary trong monolith | Cloudinary trong iam-service |
| Coin cộng khi review | Gọi trực tiếp UserService | Publish RabbitMQ event → iam-service listen |
| Coin hoàn khi huỷ | Gọi trực tiếp UserService | `IamFeignClient.addCoins(userId, amount)` |
| WebSocket bookings | Trực tiếp | notification-service publish → frontend subscribe |
| Payment timeLimit | Join trực tiếp | Feign call payment-service |

---

## 9. Ghi chú DB

### Tạo user test trong iam_db:
```sql
-- Chạy trong container sau khi iam-service khởi động (tạo bảng)
INSERT INTO users (userid, email, full_name, phone, coin_balance, role, status, is_email_verified)
VALUES (1, 'dev@test.com', 'Dev User', '0901234567', 500, 'CUSTOMER', 'ACTIVE', true)
ON CONFLICT DO NOTHING;
```

### Tạo booking test trong booking_db:
```sql
INSERT INTO bookings (bookingcode, booking_date, contact_email, contact_full_name, 
    contact_phone, total_passengers, total_price, booking_status, user_id, departure_id)
VALUES ('BK-TEST-001', NOW(), 'dev@test.com', 'Dev User', '0901234567',
    2, 5600000, 'PAID', 1, 1);
```
