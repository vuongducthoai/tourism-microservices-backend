# Report: Review thay đổi sau khi pull ngày 18/05/2026

## Phạm vi kiểm tra

- Backend: `D:\HK8\tourism-microservices-backend`
- Frontend: `D:\HK8\tourism_frontend\client-side`
- Backend so sánh từ commit cũ `32405c6 - Done rabitmq booking update` đến HEAD `186b38c`
- Frontend so sánh từ commit cũ `e77e3c2 - Done rabitmq booking update` đến HEAD `a47ae12`

## Kết luận nhanh

Frontend build được, chỉ còn nhiều warning cũ/mới của ESLint. Backend chưa xác nhận compile được vì Maven bị chặn tải dependency trong sandbox và không có đủ cache offline.

Logic cũ RabbitMQ/Outbox booking vẫn còn, nhưng pull mới đã thêm khá nhiều chức năng lớn: Keycloak auth, booking create/order/payment detail, admin coupon, admin tour/departure, payment gateway, forum. Có vài điểm ảnh hưởng logic cần chú ý trước khi demo hoặc merge tiếp.

## Thay đổi lớn ở backend

### IAM/Auth

- Thêm Keycloak:
  - `KeycloackConfig`
  - `AuthController`
  - `AuthService`
  - `AuthServiceImpl`
  - `KeycloakAdminService`
- Thêm DTO login/register/token.
- `User` thêm:
  - `keycloakId`
  - `migratedToKeycloak`
- Xóa entity `RefreshToken`.

Ảnh hưởng:

- Luồng login/register đã đổi hướng sang Keycloak.
- Nếu Keycloak chưa chạy hoặc cấu hình chưa đúng, login/register có thể lỗi hoặc fallback dev-token.
- IAM vẫn giữ coin balance và API cộng/trừ xu.

### Booking

Thêm nhiều API mới:

- `GET /api/bookings/order`
- `POST /api/bookings/create`
- `GET /api/bookings/payment/{bookingCode}`
- Admin coupon CRUD/search.

Logic mới đáng chú ý:

- `createBooking()` gọi `tourCatalogClient.decreaseSlots(...)` để giảm slot.
- Sau đó mới xử lý coupon, coin, save booking.

Rủi ro:

- Nếu đã giảm slot rồi nhưng các bước sau lỗi, slot vẫn bị giảm vì đây là gọi sang service khác, transaction booking không rollback được slot ở tour-catalog.
- Hiện chưa thấy API/Feign tăng slot lại khi booking bị hủy.
- Hủy booking vẫn chưa trả chỗ cho người sau đặt lại.

### Tour Catalog

Thêm nhiều phần admin:

- Admin tour
- Admin location
- Admin departure
- Admin policy
- Branch/contact
- Booking info endpoint
- Decrease slots endpoint

Entity/schema thay đổi:

- `ImageReview`: thêm mapping `@Column(name = "image")`.
- `ItineraryDay`: đổi field `description` thành `details`.
- `TourImage`: thêm `isMainImage`.

Rủi ro:

- Nếu DB cũ đang có cột `description` trong `itinerary_days`, đổi sang `details` có thể làm dữ liệu mô tả lịch trình không được map đúng.
- Cần kiểm tra migration/schema thật trước khi deploy.

### Notification

- `Notification` thêm field `isRead`.
- Có thêm user notification API và email verification DTO.

Ảnh hưởng:

- Không thấy phá luồng Outbox notification cũ.
- Cần kiểm tra DB có thêm cột `is_read` chưa.

### Gateway

Thêm Java route config:

- `GatewayRoutesConfig`
- `ReactiveKeycloakJwtConverter`
- `SecurityConfig`
- `AuthHeaderFilter`

Rủi ro:

- Gateway hiện có cả route trong Java config và route trong `application.yml`.
- Một số route Java config có thể thiếu các API mới như admin coupon/forum hoặc trùng route với YAML.
- Nên giữ một nơi làm source of truth, tốt nhất là YAML hoặc cập nhật Java config cho đầy đủ.

### Docker compose

- Thêm Keycloak service.
- Postgres host port đổi sang `5433:5432`.
- Một số service vẫn dùng Docker network `postgres:5432`, nên chạy bằng Docker vẫn ổn.
- Chạy local ngoài Docker có thể bị đổi behavior vì default datasource ở một số service dùng `localhost:5433`.

## Thay đổi lớn ở frontend

### Auth/Login

- `AuthContext` đổi sang accessToken/refreshToken.
- Gọi các endpoint:
  - `/auth/login`
  - `/auth/google-login`
  - `/auth/refresh-token`
  - `/auth/logout`
- Thêm `GoogleCallback`.

Ảnh hưởng:

- Luồng auth frontend phụ thuộc backend IAM/Keycloak mới.
- Nếu backend chưa chạy Keycloak đúng, login có thể không ổn.

### Booking user

- `TourBooking.jsx` đã gọi API mới `/bookings/create`.
- Sau khi tạo booking điều hướng sang `/payment-booking?bookingCode=...`.

Ảnh hưởng:

- Luồng đặt tour giờ phụ thuộc `booking-service.createBooking()`.
- Vì backend create booking có giảm slot sớm, lỗi sau bước giảm slot có thể gây mất slot.

### Forum

Thêm nhiều UI forum:

- Forum page
- Post card
- Comment
- Create post
- Upload image
- Report post

### Build frontend

Đã chạy:

```text
npm run build
```

Kết quả:

- Build thành công.
- Có nhiều warning ESLint.
- Không có lỗi compile mới.
- Bundle JS/CSS tăng kích thước.

Warning đáng chú ý:

- Nhiều unused import/variable.
- Một số hook thiếu dependency.
- Một số file có ký tự Unicode BOM.
- `axiosCustomize.js` vẫn hardcode `http://localhost:8080/api`, dù có `.env`.
- Có log token trong interceptor, không nên để khi demo/public.

## Entity/schema thay đổi cần lưu ý

| Service | Entity | Thay đổi | Mức ảnh hưởng |
|---|---|---|---|
| booking-service | `BaseEntity` | thêm auditing listener | thấp |
| booking-service | `PassengerType` | thêm `TODDLER` | thấp/trung bình |
| iam-service | `User` | thêm `keycloakId`, `migratedToKeycloak` | cao |
| iam-service | `RefreshToken` | bị xóa | cao |
| notification-service | `Notification` | thêm `isRead` | trung bình |
| tour-catalog-service | `ImageReview` | map column `image` | trung bình |
| tour-catalog-service | `ItineraryDay` | `description` đổi thành `details` | cao nếu DB cũ có data |
| tour-catalog-service | `TourImage` | thêm `isMainImage` | trung bình |

## Vấn đề cần sửa trước

### 1. Slot booking chưa an toàn

Hiện tại:

```text
createBooking()
  -> decreaseSlots()
  -> validate coupon/coin
  -> save booking
```

Vấn đề:

- Nếu giảm slot xong mà bước sau lỗi, slot bị mất.
- Nếu user/admin hủy booking, chưa thấy tăng slot lại.

Khuyến nghị:

- Thêm API `increaseSlots` hoặc `releaseSlots` trong tour-catalog.
- Booking cancel/admin cancel gọi release slot.
- Khi create booking lỗi sau decrease slot, cần compensate tăng slot lại.
- Nên lưu `seatCount` thật sự vào booking, không dùng `totalPassengers` nếu infant không chiếm slot.

### 2. Gateway route dễ bị lệch

Hiện có cả Java route config và YAML route config.

Khuyến nghị:

- Chọn một nơi quản lý route.
- Nếu giữ Java config thì phải thêm đủ route mới.
- Nếu giữ YAML thì bỏ Java route config hoặc không khai báo trùng.

### 3. Keycloak cần kiểm thử riêng

Khuyến nghị:

- Test register/login/logout/refresh-token.
- Test gateway nhận JWT thật từ Keycloak.
- Test quyền admin/customer/tour_owner sau khi token đi qua gateway.

### 4. Entity `ItineraryDay.description -> details`

Khuyến nghị:

- Kiểm tra DB thật có cột nào.
- Nếu data cũ nằm ở `description`, cần migration sang `details` hoặc giữ mapping column cũ.

### 5. Frontend auth/API config

Khuyến nghị:

- Dùng `.env` cho base URL thay vì hardcode.
- Bỏ log token khỏi console.
- Dọn warning quan trọng sau khi ổn logic.

## Trạng thái kiểm thử

| Hạng mục | Kết quả |
|---|---|
| Git working tree backend | sạch |
| Git working tree frontend | sạch |
| Frontend build | thành công, có warning |
| Backend build | chưa xác nhận được do Maven bị chặn tải dependency |
| Kiểm tra slot cancel | chưa thấy code trả slot |
| Kiểm tra Outbox/RabbitMQ cũ | code vẫn còn |

## Kết luận

Pull mới thêm nhiều chức năng lớn, không chỉ chỉnh nhỏ. Phần đáng lo nhất là booking slot: tạo booking đã giảm slot nhưng hủy booking chưa trả slot, và lỗi giữa chừng có thể làm mất slot. Đây là điểm nên xử lý trước nếu muốn demo luồng đặt/hủy tour chính xác.

Frontend hiện build được, nên lỗi lớn nhất không nằm ở compile mà nằm ở tích hợp logic mới: auth Keycloak, booking create mới, gateway route và slot compensation.
