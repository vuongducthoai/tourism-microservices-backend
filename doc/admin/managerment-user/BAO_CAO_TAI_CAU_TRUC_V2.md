# BÁO CÁO: TÁI CẤU TRÚC MODULE QUẢN LÝ NGƯỜI DÙNG (Admin Users)

**Phiên bản:** 2.0  
**Ngày cập nhật:** 2026-05-08  
**Phạm vi:** `iam-service` · `notification-service`

---

## 1. MỤC TIÊU TÁI CẤU TRÚC

| # | Mục tiêu | Trạng thái |
|---|---|---|
| 1 | Tạo package `converter` trong iam-service, dùng **ModelMapper** thay cho mapping thủ công | ✅ Hoàn thành |
| 2 | Chuyển việc **gửi email** từ iam-service sang **notification-service** (tách trách nhiệm đúng kiến trúc) | ✅ Hoàn thành |
| 3 | Xóa `MailService` + `MailServiceImpl` khỏi iam-service (không còn phụ thuộc JavaMailSender ở tầng IAM) | ✅ Hoàn thành |
| 4 | Giữ nguyên logic nghiệp vụ (search/filter/sort/paginate/lock/unlock) | ✅ Giữ nguyên 100% |
| 5 | Build + Test + Kiểm tra log | ✅ Tất cả PASS |

---

## 2. KIẾN TRÚC TRƯỚC VÀ SAU

### 2.1 Trước (v1)

```
iam-service
  ├── UserServiceImpl
  │     ├── toResponse()          ← manual field-by-field copy
  │     ├── toAdminResponse()     ← manual field-by-field copy
  │     ├── toStatusEventDTO()    ← manual field-by-field copy
  │     ├── MailService           ← gửi email trực tiếp từ iam
  │     └── NotificationFeignClient → notification-service (chỉ WS)
  └── notification-service (chỉ push WebSocket)
```

### 2.2 Sau (v2)

```
iam-service
  ├── converter/UserConverter     ← ModelMapper (auto) + manual cho role
  ├── UserServiceImpl
  │     ├── UserConverter         ← thay thế toàn bộ manual mapping
  │     └── NotificationFeignClient → notification-service (email + WS)
  └── (không còn MailService)

notification-service
  ├── handleUserStatusUpdated()
  │     ├── mailService.sendAccountStatusEmail()  ← EMAIL được gửi tại đây
  │     └── webSocketService.notifyAdminUserUpdate()
```

---

## 3. THAY ĐỔI CHI TIẾT

### 3.1 Files MỚI trong `iam-service`

#### `config/ModelMapperConfig.java`
```java
@Configuration
public class ModelMapperConfig {
    @Bean
    public ModelMapper modelMapper() {
        ModelMapper mapper = new ModelMapper();
        mapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        return mapper;
    }
}
```
- Đăng ký `ModelMapper` bean với chiến lược **STRICT** (chỉ map các trường có cùng tên và kiểu dữ liệu chính xác).

#### `converter/UserConverter.java`

| Method | Input → Output | Tự động (ModelMapper) | Thủ công |
|---|---|---|---|
| `toDetailResponse(User)` | User → UserDetailResponse | userID, fullName, phone, dateOfBirth, email, coinBalance, avatar, status | role: `enum Role` → `String` |
| `toAdminResponse(User)` | User → UserAdminResponse | + lastActiveAt | role: `enum Role` → `String` |
| `toStatusEventDTO(UserAdminResponse, reason)` | UserAdminResponse → UserStatusEventDTO | userID, fullName, phone, email, avatar, coinBalance, dateOfBirth, status, role, lastActiveAt, activityStatus | reason: không có trong UserAdminResponse → truyền qua tham số |

### 3.2 Files XÓA khỏi `iam-service`

| File | Lý do xóa |
|---|---|
| `service/MailService.java` | Email đã chuyển sang notification-service |
| `service/impl/MailServiceImpl.java` | Email đã chuyển sang notification-service |

### 3.3 Files CHỈNH SỬA trong `iam-service`

#### `dto/request/UserStatusEventDTO.java`
- Thêm trường `reason: String` — chuyển lý do khóa/mở tới notification-service để ghi vào email.

#### `service/impl/UserServiceImpl.java`
- **Xóa** dependency `MailService mailService`
- **Thêm** dependency `UserConverter userConverter`
- Thay `toResponse(user)` → `userConverter.toDetailResponse(user)` 
- Thay `toAdminResponse(user)` → `userConverter.toAdminResponse(user)`
- Thay `toStatusEventDTO(dto)` → `userConverter.toStatusEventDTO(dto, reason)`
- **Xóa** `mailService.sendAccountStatusEmail(...)` call
- **Xóa** 3 private helper methods: `toResponse`, `toAdminResponse`, `toStatusEventDTO`
- Feign call bây giờ gửi event có `reason` → notification-service xử lý cả email lẫn WS

### 3.4 Files CHỈNH SỬA trong `notification-service`

#### `dto/UserStatusEventDTO.java`
- Thêm trường `reason: String` (mirror với iam-service DTO).

#### `service/MailService.java`
- Thêm method mới: `void sendAccountStatusEmail(UserStatusEventDTO event)`

#### `service/impl/MailServiceImpl.java`
Thêm implementation `sendAccountStatusEmail`:
```java
@Async
@Override
public void sendAccountStatusEmail(UserStatusEventDTO event) {
    // Gửi email đến event.getEmail()
    // Tiêu đề: "THÔNG BÁO KHÓA/MỞ KHÓA TÀI KHOẢN - FUTURE TRAVEL"
    // Nội dung: fullName, email, phone, dateOfBirth, reason
    // Gửi bất đồng bộ (@Async) — không block response
}
```

#### `service/impl/NotificationServiceImpl.java`
```java
@Override
public void handleUserStatusUpdated(UserStatusEventDTO event) {
    // 1. Gửi email (async qua @Async trong MailServiceImpl)
    mailService.sendAccountStatusEmail(event);
    // 2. Push WebSocket đến /topic/admin/users
    webSocketService.notifyAdminUserUpdate(event);
}
```

---

## 4. LUỒNG XỬ LÝ SAU TÁI CẤU TRÚC

### 4.1 Khóa / Mở khóa tài khoản

```
Admin (Frontend)
  │  POST /users/admin/update-status
  │  { "userID": 3, "status": false, "reason": "Vi phạm điều khoản" }
  ▼
API Gateway (8080) → lb://iam-service
  ▼
UserController.updateUserStatus()
  ▼
UserServiceImpl.updateUserStatus()
  │
  ├── userRepository.findById(3) → User
  ├── user.setStatus(false) → save
  ├── userConverter.toAdminResponse(user)         ← ModelMapper
  ├── computeActivityStatus(lastActiveAt, ...)
  ├── userConverter.toStatusEventDTO(dto, reason) ← ModelMapper + reason
  │
  └── notificationFeignClient.notifyUserStatusUpdated(event)  [Feign]
              │
              ▼
       notification-service (8086)
         NotificationController
           POST /api/notifications/user-status-updated
              │
              ▼
         NotificationServiceImpl.handleUserStatusUpdated()
              │
              ├── mailService.sendAccountStatusEmail(event)   [Async]
              │     └── JavaMailSender → Gmail SMTP
              │          → Email to: user@gmail.com
              │            Subject: "THÔNG BÁO KHÓA TÀI KHOẢN..."
              │
              └── webSocketService.notifyAdminUserUpdate(event)
                    └── messagingTemplate → /topic/admin/users
                          → Frontend refetch() danh sách
  ▼
ResponseEntity<UserAdminResponse> HTTP 200
```

### 4.2 Tìm kiếm người dùng (không thay đổi logic)

```
POST /api/users/admin/search?page=0&size=6
  ↓
UserServiceImpl.searchUsers()
  ├── userRepository.searchUsers(dto, Pageable.unpaged())  [Criteria API]
  ├── stream().map(user -> userConverter.toAdminResponse(user))  ← ModelMapper
  ├── compute activityStatus per user
  ├── sort: Online→Away→Offline + lastActiveAt DESC
  └── sub-list for pagination → PageImpl
  ↓
Page<UserAdminResponse>
```

---

## 5. NGUYÊN TẮC MAPPING (ModelMapper STRICT)

| Trường Entity | Trường DTO | Mapping |
|---|---|---|
| `userID` (Integer) | `userID` (Integer) | Tự động ✅ |
| `fullName` (String) | `fullName` (String) | Tự động ✅ |
| `phone` (String) | `phone` (String) | Tự động ✅ |
| `dateOfBirth` (LocalDate) | `dateOfBirth` (LocalDate) | Tự động ✅ |
| `email` (String) | `email` (String) | Tự động ✅ |
| `coinBalance` (BigDecimal) | `coinBalance` (BigDecimal) | Tự động ✅ |
| `avatar` (String) | `avatar` (String) | Tự động ✅ |
| `status` (Boolean) | `status` (Boolean) | Tự động ✅ |
| `lastActiveAt` (LocalDateTime) | `lastActiveAt` (LocalDateTime) | Tự động ✅ |
| `role` (Role enum) | `role` (String) | **Thủ công** — `.name()` |
| N/A | `activityStatus` (String) | **Tính toán** — set sau map |
| N/A | `reason` (String) | **Tham số** — truyền vào toStatusEventDTO |

---

## 6. KẾT QUẢ KIỂM THỬ

### 6.1 Build

| Service | Kết quả |
|---|---|
| iam-service | `BUILD SUCCESS` ✅ |
| notification-service | `BUILD SUCCESS` ✅ |
| Docker images | Built ✅ |
| Containers | `tourism-iam-service` Up · `tourism-notification-service` Up ✅ |

### 6.2 API Tests

| Test | Mô tả | Request | Expected | Actual | Kết quả |
|---|---|---|---|---|---|
| T1 | Search all (no filter) | `POST /admin/search {}` | total=7, pages=2, count=6 | total=7, pages=2, count=6 | ✅ PASS |
| T2 | Filter tên "Ph" | `{"fullName":"Ph"}` | total=2 | total=2 | ✅ PASS |
| T3 | Filter email "gmail" | `{"email":"gmail"}` | ≥1 | total=7 | ✅ PASS |
| T4 | Pagination page 2 | `?page=1&size=6` | count=1 | count=1 | ✅ PASS |
| T5 | getUserById + converter | `GET /users/3` | role="CUSTOMER" (String) | role=CUSTOMER ✅ | ✅ PASS |
| T6 | Khóa tài khoản | `{"userID":3,"status":false,"reason":"..."}` | status=false, email gửi từ notification-service | status=False, log: MailServiceImpl email sent | ✅ PASS |
| T7 | Mở khóa tài khoản | `{"userID":3,"status":true,"reason":"..."}` | status=true, email gửi từ notification-service | status=True, log: email sent ✅ | ✅ PASS |
| T8 | Non-existent user | `{"userID":9999,...}` | HTTP 500 | InternalServerError | ✅ PASS |
| T9 | Status persistence | Search after unlock | user3.status=true | status=True | ✅ PASS |
| T10 | Combined filter | `{"fullName":"a","email":"gmail"}` | Kết quả khớp cả 2 điều kiện AND | total=2 | ✅ PASS |

### 6.3 Email trong notification-service (Log xác nhận)

```
[notification-service] [cTaskExecutor-3] MailServiceImpl : 
  Account status (KHÓA) email sent to userId=3 <customer1@gmail.com>

[notification-service] [cTaskExecutor-2] MailServiceImpl :
  Account status (MỞ KHÓA) email sent to userId=3 <customer1@gmail.com>
```

→ Email được gửi **bất đồng bộ** (`@Async`) từ `notification-service.MailServiceImpl` ✅

---

## 7. SO SÁNH TRƯỚC/SAU

| Khía cạnh | Trước (v1) | Sau (v2) |
|---|---|---|
| Mapping Entity→DTO | 30+ dòng `set()` thủ công | ModelMapper (auto) + 1 dòng manual cho `role` |
| Email | iam-service gửi trực tiếp | notification-service gửi (tách trách nhiệm đúng) |
| Số lớp trong iam | MailService + MailServiceImpl | Xóa 2 file này |
| `reason` trong event | Không có | Thêm trường `reason` để notification-service dùng |
| Single Responsibility | iam-service gửi email + WS | iam-service chỉ cập nhật DB; notification-service lo email + WS |
| Testability | Khó mock mail trong iam tests | Có thể mock NotificationFeignClient dễ dàng |

---

## 8. CẤU TRÚC FILE SAU TÁI CẤU TRÚC

```
iam-service/src/main/java/com/tourism/iam/
  ├── config/
  │     ├── CloudinaryConfig.java
  │     ├── ModelMapperConfig.java        ← MỚI: đăng ký ModelMapper bean
  │     └── SecurityConfig.java
  ├── converter/
  │     └── UserConverter.java            ← MỚI: toDetailResponse / toAdminResponse / toStatusEventDTO
  ├── dto/
  │     ├── request/
  │     │     ├── UserSearchRequest.java
  │     │     ├── UserStatusEventDTO.java  ← SỬA: thêm reason
  │     │     ├── UserStatusUpdateRequest.java
  │     │     └── UserUpdateRequest.java
  │     └── response/
  │           ├── UserAdminResponse.java
  │           └── UserDetailResponse.java
  ├── feign/
  │     └── NotificationFeignClient.java
  ├── service/
  │     ├── UserService.java
  │     └── impl/
  │           └── UserServiceImpl.java    ← SỬA: dùng UserConverter, bỏ MailService
  └── (MailService.java + MailServiceImpl.java đã XÓA)

notification-service/src/main/java/com/tourism/notification/
  ├── dto/
  │     ├── BookingEventDTO.java
  │     └── UserStatusEventDTO.java       ← SỬA: thêm reason
  ├── service/
  │     ├── MailService.java              ← SỬA: thêm sendAccountStatusEmail()
  │     ├── NotificationService.java
  │     └── impl/
  │           ├── MailServiceImpl.java    ← SỬA: implement sendAccountStatusEmail()
  │           ├── NotificationServiceImpl.java ← SỬA: gọi mail trong handleUserStatusUpdated
  │           └── WebSocketService.java
```

---

*Báo cáo tái cấu trúc — 2026-05-08 · tourism-microservices-backend v2.0*
