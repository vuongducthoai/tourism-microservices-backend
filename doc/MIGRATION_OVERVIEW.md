# Tourism System — Tổng Quan Migration Monolithic → Microservices

> Tài liệu phân tích toàn bộ hệ thống du lịch, so sánh kiến trúc monolithic cũ với kiến trúc microservices mới, và định hướng các bước triển khai tiếp theo.

---

## 1. Tổng Quan Dự Án

| Thành phần | Đường dẫn | Trạng thái |
|---|---|---|
| **Backend Monolithic** | `D:\HK8\Tourism_Backend` | ✅ Hoàn chỉnh |
| **Frontend (React/TS)** | `D:\HK8\tourism_frontend\client-side` | ✅ Hoàn chỉnh — KHÔNG thay đổi |
| **Backend Microservices** | `D:\HK8\tourism-microservices-backend` | 🔄 Đang xây dựng (đã cấu hình, đã định nghĩa entity) |

**Mục tiêu:** Giữ nguyên 100% frontend và toàn bộ API contract, chỉ thay thế backend từ monolithic sang microservices. Frontend không biết sự thay đổi này vì API Gateway vẫn tiếp nhận trên cổng `8080`.

---

## 2. Backend Monolithic (Tourism_Backend)

### 2.1 Thông tin kỹ thuật
- **Framework:** Spring Boot 3.3.0, Java 17
- **Database:** PostgreSQL (1 DB duy nhất: `tourism`)
- **Port:** `8080`
- **Build:** Maven

### 2.2 Các Entity chính (22 entity)

| Entity | Mô tả |
|---|---|
| `User` | Tài khoản người dùng, có role ADMIN/USER, coin balance, địa chỉ, xác minh email |
| `RefreshToken` | JWT refresh token |
| `Tour` | Thông tin tour du lịch |
| `TourImage` | Ảnh của tour |
| `TourMedia` | Media (video/ảnh) của tour |
| `ItineraryDay` | Lịch trình từng ngày của tour |
| `TourDeparture` | Chuyến khởi hành của tour (ngày, slot, giá) |
| `DeparturePricing` | Bảng giá theo loại hành khách |
| `DepartureTransport` | Phương tiện đi/về của chuyến |
| `Location` | Điểm đến/xuất phát |
| `FavoriteTour` | Tour yêu thích của user |
| `Booking` | Đơn đặt tour |
| `BookingPassenger` | Hành khách trong booking |
| `Coupon` | Mã giảm giá |
| `Payment` | Thông tin thanh toán |
| `Review` | Đánh giá tour |
| `ImageReview` | Ảnh trong đánh giá |
| `Notification` | Thông báo hệ thống |
| `UserNotification` | Thông báo tới từng user |
| `BranchContact` | Thông tin liên hệ chi nhánh |
| `PolicyTemplate` | Mẫu chính sách hủy tour |
| `RefundInformation` | Thông tin hoàn tiền |

### 2.3 Các Controller & API Routes

| Controller | Route | Chức năng |
|---|---|---|
| `AuthController` | `/api/auth/**` | Đăng ký, đăng nhập, refresh token, OAuth2 Google, xác minh email |
| `AdminAuthController` | `/api/admin/auth/**` | Đăng nhập admin |
| `UserController` | `/api/users/**` | CRUD user, profile, đổi mật khẩu, nạp coin |
| `AdminProfileController` | `/api/admin/profile/**` | Quản lý profile admin |
| `TourController` | `/api/tours/**` | Xem danh sách, chi tiết, tìm kiếm tour (public) |
| `TourManagementController` | `/api/admin/tours/**` | CRUD tour (admin) |
| `TourUploadController` | `/api/tours/upload/**` | Upload ảnh tour |
| `TourDepartureManagementController` | `/api/departures/**` | CRUD chuyến khởi hành |
| `TourMediaController` | `/api/tour-media/**` | Quản lý media tour |
| `LocationController` | `/api/locations/**` | Xem danh sách địa điểm (public) |
| `LocationAdminController` | `/api/admin/locations/**` | CRUD địa điểm (admin) |
| `BookingController` | `/api/bookings/**` | Tạo booking, xem lịch sử, hủy booking |
| `CouponController` | `/api/coupons/**` | CRUD coupon, áp dụng coupon |
| `PaymentController` | `/api/payment/**` | VNPay, PayOS, SePay callback và tạo link thanh toán |
| `ReviewController` | `/api/reviews/**` | Tạo, xem, xóa đánh giá |
| `FavoriteTourController` | `/api/favorite-tours/**` | Thêm/xóa/xem tour yêu thích |
| `NotificationController` | `/api/notifications/**` | Xem, đánh dấu đã đọc thông báo |
| `BranchContactController` | `/api/branch-contacts/**` | CRUD chi nhánh |
| `PolicyTemplateController` | `/api/policy-templates/**` | CRUD mẫu chính sách |
| `DashboardController` | `/api/dashboard/**` | Thống kê doanh thu, user, tour (admin) |
| `ChatbotController` | `/api/chatbot/**` | Chatbot Gemini AI |

### 2.4 Các Service hỗ trợ

| Service | Mô tả |
|---|---|
| `AuthService` | Xử lý xác thực JWT, OAuth2 |
| `EmailService` / `MailService` | Gửi email xác minh, thông báo |
| `CloudinaryService` | Upload/xóa ảnh lên Cloudinary |
| `GeminiAIService` | Tích hợp Gemini AI cho chatbot |
| `GoogleAuthService` | OAuth2 Google |
| `SepayService` | Tích hợp SePay banking |
| `BookingCleanupService` | Tự động hủy booking quá hạn |
| Chatbot subpackage | Vector DB, embedding |

### 2.5 Tích hợp bên ngoài (External)

| Tích hợp | Mục đích |
|---|---|
| **Cloudinary** | Lưu trữ ảnh |
| **VNPay** | Cổng thanh toán |
| **PayOS** | Cổng thanh toán QR |
| **SePay** | Banking API |
| **Google OAuth2** | Đăng nhập bằng Google |
| **Gmail SMTP** | Gửi email |
| **Gemini AI** | Chatbot AI |
| **WebSocket** | Thông báo realtime |

---

## 3. Frontend (tourism_frontend/client-side)

### 3.1 Thông tin kỹ thuật
- **Framework:** React 18 + TypeScript
- **Base URL:** `http://localhost:8080/api` ← **trỏ thẳng vào API Gateway, không thay đổi**
- **Auth:** JWT lưu trong `localStorage` (`accessToken`, `refreshToken`)
- **Auto refresh:** axios interceptor tự động refresh token khi 401

### 3.2 Cấu trúc các Component chính

```
src/components/
├── AdminComponent/          # Trang quản trị admin (tour, user, booking, dashboard)
├── BookingPaymentComponent/ # Luồng đặt tour và thanh toán
├── TourDetailComponent/     # Chi tiết tour, departure, đánh giá
├── toursPageComponent/      # Danh sách và tìm kiếm tour
├── homPageComponent/        # Trang chủ, banner, tour nổi bật
├── DestinationSearchComponent/ # Tìm kiếm điểm đến
├── InformationComponent/    # Thông tin cá nhân, lịch sử booking
├── Login/ + RegisterComponent/ # Xác thực
├── VerifyEmail/             # Xác minh email
├── ChatbotWidget/           # Chat với AI
├── HeaderComponent/ + FooterComponent/ # Layout
└── ProtectedRoute.jsx       # Bảo vệ route cần đăng nhập
```

### 3.3 Các Service gọi API

| Service | Route gọi |
|---|---|
| `tours/` | `/api/tours/**` |
| `booking/` | `/api/bookings/**` |
| `payment/` | `/api/payment/**` |
| `user/` | `/api/users/**`, `/api/auth/**` |
| `review/` | `/api/reviews/**` |
| `favoriteTour/` | `/api/favorite-tours/**` |
| `location/` | `/api/locations/**` |
| `dashboard/` | `/api/dashboard/**` |

**⚠️ Frontend KHÔNG cần thay đổi gì** vì API Gateway nhận tất cả request trên cổng `8080` — giống hệt monolithic.

---

## 4. Backend Microservices (tourism-microservices-backend)

### 4.1 Kiến trúc tổng thể

```
Internet
    │
    ▼
[API Gateway :8080]  ← Frontend gọi vào đây
    │
    ├── [Service Discovery / Eureka :8761]
    ├── [Config Server :8888]
    │
    ├── [IAM Service :8081]           ← iam_db
    ├── [Tour Catalog Service :8082]  ← tour_catalog_db
    ├── [Booking Service :8083]       ← booking_db
    ├── [Payment Service :8084]       ← payment_db
    ├── [Forum Service :8085]         ← forum_db  (tính năng MỚI)
    ├── [Notification Service :8086]  ← notification_db
    └── [Analytics Service :8087]     ← analytics_db

Infrastructure:
    ├── PostgreSQL :5433 (7 databases tách biệt)
    ├── Redis :6379 (caching)
    └── RabbitMQ :5672 (async messaging)
```

### 4.2 API Gateway Routing

| Route Pattern | Service đích |
|---|---|
| `/api/auth/**`, `/api/admin/auth/**` | `iam-service` |
| `/api/users/**`, `/api/admin/profile/**` | `iam-service` |
| `/api/tours/**`, `/api/admin/tours/**` | `tour-catalog-service` |
| `/api/locations/**`, `/api/admin/locations/**` | `tour-catalog-service` |
| `/api/departures/**`, `/api/reviews/**` | `tour-catalog-service` |
| `/api/favorite-tours/**`, `/api/branch-contacts/**` | `tour-catalog-service` |
| `/api/policy-templates/**`, `/api/tour-media/**` | `tour-catalog-service` |
| `/api/bookings/**`, `/api/coupons/**` | `booking-service` |
| `/api/payment/**` | `payment-service` |
| `/api/posts/**`, `/api/tags/**`, `/api/categories/**` | `forum-service` |
| `/api/bookmarks/**`, `/api/followers/**` | `forum-service` |
| `/api/notifications/**` | `notification-service` |
| `/api/dashboard/**`, `/api/chatbot/**` | `analytics-service` |

### 4.3 Chi tiết từng Service

#### IAM Service (`:8081`, DB: `iam_db`)
**Entity đã có:**
- `User` — đầy đủ các field giống monolithic (fullName, email, role, password, coin, address, emailVerified...)
- `RefreshToken`
- `Role` (enum)

**Trạng thái:** Entity ✅ | SecurityConfig skeleton ✅ | Controller/Service/Repository 🔲 chưa implement

---

#### Tour Catalog Service (`:8082`, DB: `tour_catalog_db`)
**Entity đã có:**
- `Tour`, `TourImage`, `TourMedia`, `ItineraryDay`
- `TourDeparture`, `DeparturePricing`, `DepartureTransport`
- `Location`, `Region` (mới — bổ sung so với mono)
- `FavoriteTour`, `Review`, `ImageReview`
- `BranchContact`, `PolicyTemplate`
- `TransportType`, `VehicleType` (enum)

**Tích hợp:** Cloudinary, Redis cache, RabbitMQ, Feign → IAM Service

**Trạng thái:** Entity ✅ | Convert/DTO/Service/Controller 🔲 chưa implement

---

#### Booking Service (`:8083`, DB: `booking_db`)
**Entity đã có:**
- `Booking` — dùng `userId` (Integer) thay vì FK sang User entity
- `Booking` — dùng `departureId` (Integer) thay vì FK sang TourDeparture entity
- `BookingPassenger`, `BookingStatus`, `PassengerType`
- `Coupon`, `CouponType`
- `RefundInformation`

**Tích hợp:** RabbitMQ, Feign → TourCatalog, IAM, Payment

**Trạng thái:** Entity ✅ | Service/Controller/Repository 🔲 chưa implement

---

#### Payment Service (`:8084`, DB: `payment_db`)
**Entity đã có:**
- `Payment` — dùng `bookingId` (Integer) thay vì FK cross-service
- `PaymentMethod`, `PaymentStatus` (enum)

**Tích hợp:** VNPay, PayOS, SePay, RabbitMQ

**Trạng thái:** Entity ✅ | Service/Controller 🔲 chưa implement

---

#### Forum Service (`:8085`, DB: `forum_db`) — ✨ TÍNH NĂNG MỚI
**Entity đã có:**
- `ForumPost` — tham chiếu `userId` và `tourId` qua ID
- `PostComment`, `PostLike`, `CommentLike`
- `PostImage`, `PostTag`, `PostCategory`, `Tag`
- `PostView`, `PostBookmark`, `Follower`
- `ContentStatus`, `ContentType`, `PostType`, `ReportReason`, `ReportStatus`

**Tích hợp:** Cloudinary, Redis cache, RabbitMQ, Feign → IAM

**Trạng thái:** Entity ✅ | Service/Controller 🔲 chưa implement

---

#### Notification Service (`:8086`, DB: `notification_db`)
**Entity đã có:**
- `Notification`, `UserNotification`, `NotificationType`

**Tích hợp:** RabbitMQ (nhận event), Mail, Redis, WebSocket

**Trạng thái:** Entity ✅ | Service/Controller 🔲 chưa implement

---

#### Analytics Service (`:8087`, DB: `analytics_db`)
**Entity đã có:**
- `DailyRevenueStat`, `TourPerformanceStat`, `UserGrowthStat`

**Tích hợp:** RabbitMQ (nhận event thống kê), Gemini AI, Pinecone vector DB

**Trạng thái:** Entity ✅ | Service/Controller 🔲 chưa implement

---

### 4.4 Infrastructure (Docker Compose)

| Service | Image | Port |
|---|---|---|
| PostgreSQL | `postgres:16-alpine` | `5433:5432` |
| Redis | `redis:7-alpine` | `6379:6379` |
| RabbitMQ | `rabbitmq:3-management-alpine` | `5672`, management `15672` |

### 4.5 Shared Library
Module dùng chung giữa các service (DTO, Exception, Response wrapper, v.v.)

---

## 5. Nguyên tắc Thiết kế Microservices

### 5.1 Tách biệt database
Mỗi service có database riêng → **không dùng JPA Foreign Key cross-service**. Tham chiếu giữa service thực hiện qua **ID field** (Integer).

```java
// Thay vì @ManyToOne User user → dùng:
@Column(name = "user_id")
private Integer userId;

// Thay vì @ManyToOne TourDeparture departure → dùng:
@Column(name = "departure_id")
private Integer departureId;
```

### 5.2 Giao tiếp giữa các service
| Loại | Công nghệ | Dùng khi |
|---|---|---|
| **Sync (đồng bộ)** | OpenFeign | Cần dữ liệu trả về ngay (validate, enrich data) |
| **Async (bất đồng bộ)** | RabbitMQ | Sự kiện không cần phản hồi ngay (booking tạo → gửi email, cập nhật analytics) |

### 5.3 Authentication
- **API Gateway** xác thực JWT trước khi forward request
- Các service nội bộ nhận `userId` qua header hoặc JWT claim
- **IAM Service** là nguồn duy nhất phát hành token

---

## 6. So Sánh Mono vs Micro

| Khía cạnh | Monolithic | Microservices |
|---|---|---|
| Số database | 1 (`tourism`) | 7 DB tách biệt |
| Port dịch vụ | `8080` (1 server) | `8080` Gateway + 7 services |
| Deploy | 1 JAR file | Docker Compose (11 container) |
| Scale | Scale toàn bộ | Scale từng service độc lập |
| Team | 1 team | Mỗi service 1 team |
| Fault isolation | 1 lỗi crash toàn bộ | Lỗi service A không ảnh hưởng B |
| Giao tiếp internal | Method call trong JVM | HTTP (Feign) + Message Queue |
| Tính năng mới | — | Forum Service (blog, post, comment) |

---

## 7. Trạng Thái Hiện Tại (2026-04-28)

### Đã hoàn thành ✅
- [x] Thiết kế kiến trúc tổng thể
- [x] Cấu hình `docker-compose.yml` đầy đủ
- [x] API Gateway routing configuration
- [x] Service Discovery (Eureka)
- [x] Config Server
- [x] Định nghĩa 7 databases trong `init-databases.sql`
- [x] **Toàn bộ Entity** cho tất cả 7 business service
- [x] `application.yml` cho từng service (port, DB, Eureka, Feign, Redis, RabbitMQ)
- [x] Cấu trúc package cho từng service (controller/, service/, repository/, dto/, convert/, client/)
- [x] IAM Service: `SecurityConfig` skeleton

### Cần làm tiếp 🔲

#### Ưu tiên cao — Core flow
1. **IAM Service** — Controller, Service, Repository (Auth, User)
2. **Tour Catalog Service** — Controller, Service, Repository (Tour, Location, Departure, Review)
3. **Booking Service** — Controller, Service, Repository
4. **Payment Service** — Controller, Service (VNPay, PayOS, SePay)

#### Ưu tiên trung bình
5. **Notification Service** — Consumer RabbitMQ, WebSocket, Email
6. **Analytics Service** — Dashboard API, Chatbot/Gemini AI
7. **Feign Clients** — giao tiếp giữa service (IAMClient, TourCatalogClient, v.v.)

#### Ưu tiên thấp — Tính năng mới
8. **Forum Service** — Blog, Post, Comment, Follow

#### DevOps
9. Kiểm tra Dockerfile từng service
10. CI/CD pipeline (nếu cần)

---

## 8. Luồng Gọi API Điển Hình

### Luồng Đặt Tour (Booking)
```
FE (localhost:3000)
  → POST /api/bookings/create
  → [API Gateway :8080]
  → [Booking Service :8083]
      ├─ Feign → [Tour Catalog :8082] : validate departureId, lấy thông tin tour/giá
      ├─ Feign → [IAM Service :8081] : lấy thông tin user (coin, email)
      ├─ Lưu Booking vào booking_db
      └─ Publish event → [RabbitMQ]
            ├─ [Notification Service] consume → gửi email xác nhận
            └─ [Analytics Service] consume → cập nhật thống kê
```

### Luồng Thanh Toán
```
FE
  → POST /api/payment/create
  → [API Gateway :8080]
  → [Payment Service :8084]
      ├─ Feign → [Booking Service :8083] : validate booking
      ├─ Gọi VNPay/PayOS/SePay API
      ├─ Lưu Payment vào payment_db
      └─ Publish event "PAYMENT_SUCCESS" → [RabbitMQ]
            ├─ [Booking Service] consume → cập nhật bookingStatus = PAID
            └─ [Notification Service] consume → gửi email xác nhận thanh toán
```

---

## 9. Cấu Trúc Thư Mục Microservices

```
tourism-microservices-backend/
├── docker-compose.yml          # Orchestrate toàn bộ hệ thống
├── init-databases.sql          # Script tạo 7 databases
├── pom.xml                     # Parent POM (modules)
│
├── shared-library/             # DTO, Exception, Response dùng chung
├── service-discovery/          # Eureka Server :8761
├── config-server/              # Spring Cloud Config :8888
├── api-gateway/                # Spring Cloud Gateway :8080
│
├── iam-service/                # :8081 → iam_db
├── tour-catalog-service/       # :8082 → tour_catalog_db
├── booking-service/            # :8083 → booking_db
├── payment-service/            # :8084 → payment_db
├── forum-service/              # :8085 → forum_db (tính năng mới)
├── notification-service/       # :8086 → notification_db
└── analytics-service/          # :8087 → analytics_db
```

---

## 10. Ghi Chú Quan Trọng

> **Frontend tương thích hoàn toàn:** Frontend gọi `http://localhost:8080/api` → trỏ vào API Gateway. Gateway forward đúng route sang service tương ứng. Frontend không cần sửa bất kỳ dòng code nào.

> **Port PostgreSQL Docker:** Trong docker-compose, PostgreSQL expose ra host ở cổng `5433` (map sang `5432` trong container). Khi dev local không dùng Docker, connect thẳng vào `5432`.

> **Forum Service là tính năng MỚI** không có trong monolithic backend. Đây là cơ hội mở rộng sản phẩm trong kiến trúc microservices.

> **Security model:** API Gateway validate JWT. Các service internal nên nhận `X-User-Id` header từ Gateway thay vì tự validate JWT, để giảm coupling.
