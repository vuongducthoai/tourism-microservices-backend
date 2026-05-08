# KẾ HOẠCH TRIỂN KHAI: Quản Lý Người Dùng — Admin Users Page

> **Trang:** `localhost:3000/admin/users`  
> **Service backend:** `iam-service` (port 8081)  
> **Route qua:** API Gateway (port 8080) → `lb://iam-service`  
> **Nguồn logic:** `D:\HK8\Tourism_Backend` (monolith) — giữ nguyên 100%  
> **Frontend:** KHÔNG thay đổi file nào

---

## 1. PHÂN TÍCH HIỆN TRẠNG

### 1.1 Những gì IAM service ĐÃ có

| Endpoint | Method | Trạng thái |
|----------|--------|-----------|
| `/api/users/{userID}` | GET | ✅ Đã có |
| `/api/users/{userID}` | PUT (multipart) | ✅ Đã có |
| `/api/users/{userID}/coins` | POST | ✅ Đã có |

### 1.2 Những gì Frontend CẦN mà IAM service CHƯA có

| Endpoint | Method | Frontend gọi từ | Trạng thái |
|----------|--------|----------------|-----------|
| `/api/users/admin/search` | POST | `searchUsersApi()` | ❌ Chưa có |
| `/api/users/admin/update-status` | POST | `lockUnlockUserApi()` | ❌ Chưa có |

### 1.3 Những gì Response CHƯA trả về

`UserDetailResponse` hiện tại **thiếu các field sau** mà frontend đang dùng:
- `lastActiveAt` — hiển thị "vừa xong / 5 phút trước / offline"
- `activityStatus` — `"Online"` / `"Away"` / `"Offline"` (computed field)
- `dateOfBirth` — ✅ đã có trong entity, ✅ đã có trong response

### 1.4 WebSocket (từ frontend)

Frontend subscribe 2 topic:
```js
// UsersPage.jsx
useWebSocket({ topic: '/topic/user-activity', onMessage: (userData) => updateUserInList(userData) });
useWebSocket({ topic: '/topic/admin/users',   onMessage: () => refetch() });
```

Notification-service **chưa có** WebSocket push cho user management. Cần thêm sau khi `update-status`.

---

## 2. API CẦN TRIỂN KHAI

### API 1: Tìm kiếm người dùng (Admin Search)

```
POST /api/users/admin/search?page=0&size=6
```

**Request Body** (từ frontend `searchUsersApi`):
```json
{
  "fullName": "nguyen",   // nullable — filter theo tên (LIKE %...%)
  "phone":    "0901",     // nullable — filter theo số điện thoại
  "email":    null        // nullable — filter theo email
}
```

**Response** (Page<UserReaponseDTO>):
```json
{
  "content": [
    {
      "userID": 1,
      "fullName": "Nguyễn Văn A",
      "phone": "0901234567",
      "email": "a@gmail.com",
      "avatar": "https://...",
      "coinBalance": 5000,
      "dateOfBirth": "1999-05-01",
      "status": true,
      "lastActiveAt": "2026-05-08T13:45:00",
      "activityStatus": "Online"
    }
  ],
  "totalElements": 42,
  "totalPages": 7,
  "number": 0,
  "size": 6
}
```

**Logic theo monolith** (`UserServiceImpl.searchUsers`):
1. Query tất cả users với filter fullName/phone/email (LIKE, case-insensitive)
2. **Chỉ lấy role = CUSTOMER** (admin không hiển thị admin accounts)
3. Tính `activityStatus` cho từng user dựa vào `lastActiveAt`:
   - `lastActiveAt > now - 5 phút` → `"Online"`
   - `lastActiveAt > now - 30 phút` → `"Away"`
   - Còn lại hoặc null → `"Offline"`
4. **Sắp xếp: Online lên đầu → Away → Offline** (rồi mới apply pagination)
5. Trả về `Page<UserResponseDTO>` với pagination

**Lưu ý pagination:** Monolith dùng trick lấy unpaged → sắp xếp thủ công → sub-list theo page. Triển khai y hệt.

---

### API 2: Khóa / Mở khóa tài khoản

```
POST /api/users/admin/update-status
```

**Request Body** (từ frontend `lockUnlockUserApi`):
```json
{
  "userID": 5,
  "status": false,         // false = khóa, true = mở khóa
  "reason": "Vi phạm điều khoản sử dụng"
}
```

**Response** (UserReaponseDTO — đầy đủ bao gồm lastActiveAt, activityStatus):
```json
{
  "userID": 5,
  "fullName": "Trần B",
  "status": false,
  "activityStatus": "Offline",
  ...
}
```

**Logic theo monolith** (`UserServiceImpl.updateUserStatus`):
1. Tìm user theo `userID` → throw nếu không tồn tại
2. `user.setStatus(requestDTO.getStatus())`
3. Lưu vào DB
4. **Gửi email thông báo** cho user (MailService.sendAccountStatusEmail):
   - Nếu `status = false` → email "Tài khoản bị khóa" + lý do
   - Nếu `status = true` → email "Tài khoản đã được mở khóa" + lý do
5. **Push WebSocket** đến topic `/topic/admin/users` để admin list auto-refresh
6. Trả về `UserReaponseDTO` với status mới

---

## 3. CÁC FILE CẦN TẠO / SỬA

### 3.1 Files mới cần TẠO trong `iam-service`

```
iam-service/src/main/java/com/tourism/iam/
│
├── dto/
│   ├── request/
│   │   ├── UserSearchRequest.java          [MỚI] — fullName, phone, email
│   │   └── UserStatusUpdateRequest.java    [MỚI] — userID, status, reason
│   └── response/
│       └── UserAdminResponse.java          [MỚI] — đầy đủ fields: lastActiveAt, activityStatus
│
├── repository/
│   └── UserRepositoryCustom.java           [MỚI] — interface custom query
│   └── impl/
│       └── UserRepositoryCustomImpl.java   [MỚI] — JPQL/Criteria query filter + CUSTOMER role
│
└── service/
    └── (sửa UserService + UserServiceImpl)
        ├── + searchUsers(request, pageable)
        └── + updateUserStatus(request)
```

### 3.2 Files cần SỬA trong `iam-service`

| File | Thay đổi |
|------|----------|
| `UserController.java` | Thêm 2 endpoint `POST /admin/search` và `POST /admin/update-status` |
| `UserService.java` (interface) | Thêm 2 method signatures |
| `UserServiceImpl.java` | Implement 2 method mới + sendEmail + notifyWS |
| `UserDetailResponse.java` | Thêm field `lastActiveAt`, `activityStatus` |
| `UserRepository.java` | Extend `UserRepositoryCustom` |

### 3.3 Files cần SỬA trong `notification-service`

| File | Thay đổi |
|------|----------|
| `NotificationController.java` | Thêm `POST /api/notifications/user-status-updated` |
| `NotificationServiceImpl.java` | Thêm `handleUserStatusUpdated(event)` |
| `WebSocketService.java` | Thêm `notifyAdminUserUpdate(event)` → push `/topic/admin/users` |

### 3.4 Files cần SỬA trong `iam-service` (Feign → notification)

| File | Thay đổi |
|------|----------|
| `NotificationFeignClient.java` | [MỚI] Feign client gọi notification-service |
| `UserStatusEventDTO.java` | [MỚI] DTO gửi event qua Feign |

---

## 4. LUỒNG HOẠT ĐỘNG ĐẦY ĐỦ

### 4.1 Admin tìm kiếm user

```
Admin nhập tên/phone/email → bấm Tìm kiếm
    │
    ├─ Frontend: searchUsersApi(searchDTO, page=0, size=6)
    │      POST http://localhost:8080/api/users/admin/search
    │
    ├─ API Gateway → lb://iam-service
    │
    ├─ iam-service: UserController.searchUsers()
    │      → UserServiceImpl.searchUsers()
    │           → UserRepositoryCustomImpl.searchUsers()
    │                (WHERE role = 'CUSTOMER'
    │                  AND fullName ILIKE %...%
    │                  AND phone    ILIKE %...%
    │                  AND email    ILIKE %...%)
    │           → Tính activityStatus cho mỗi user
    │           → Sort: Online → Away → Offline
    │           → Sub-list theo page/size
    │
    └─ Response: Page<UserAdminResponse>
         Frontend: hiển thị grid user cards
```

### 4.2 Admin khóa / mở khóa tài khoản

```
Admin bấm icon khóa → chọn lý do → bấm Xác nhận
    │
    ├─ Frontend: lockUnlockUserApi(userID, false, "Vi phạm...")
    │      POST http://localhost:8080/api/users/admin/update-status
    │
    ├─ API Gateway → lb://iam-service
    │
    ├─ iam-service: UserController.updateUserStatus()
    │      → UserServiceImpl.updateUserStatus()
    │           → user.setStatus(false) → save DB
    │           │
    │           ├─ Feign → notification-service: POST /api/notifications/user-status-updated
    │           │      → MailService.sendAccountStatusEmail()
    │           │           Gửi email đến user.email:
    │           │           "Tài khoản của bạn đã bị khóa. Lý do: Vi phạm..."
    │           │
    │           └─ Feign → notification-service: notifyAdminUserUpdate()
    │                  → WebSocketService → /topic/admin/users
    │                       Frontend hook nhận → refetch() danh sách
    │
    └─ Response: UserAdminResponse (status=false)
         Frontend: cập nhật card user ngay lập tức (status badge đổi sang "Đã khóa")
```

### 4.3 Real-time activity status (đã có sẵn)

```
User đăng nhập / thao tác → iam-service cập nhật lastActiveAt
    │
    └─ WebSocket /topic/user-activity push UserAdminResponse
         Frontend hook nhận → updateUserInList(userData)
              → Badge Online/Away/Offline cập nhật ngay
```

---

## 5. CHI TIẾT LOGIC TÍNH ACTIVITY STATUS

```
Quy tắc (y hệt monolith):

lastActiveAt == null          → "Offline"
lastActiveAt > now - 5 phút  → "Online"  (màu xanh lá #10b981)
lastActiveAt > now - 30 phút → "Away"    (màu vàng #f59e0b)
còn lại                      → "Offline" (màu xám #6b7280)

Thứ tự sort trong searchUsers:
  Online = priority 1
  Away   = priority 2
  Offline = priority 3
  null   = priority 4
Sau đó sort theo lastActiveAt DESC (gần nhất lên đầu)
```

---

## 6. EMAIL TEMPLATE

Theo monolith `MailService.sendAccountStatusEmail()`:

**Khi khóa tài khoản (status = false):**
```
Subject: "Tài khoản của bạn đã bị tạm khóa"
Body: Thông báo tài khoản bị khóa, lý do, hướng dẫn liên hệ
```

**Khi mở khóa (status = true):**
```
Subject: "Tài khoản của bạn đã được kích hoạt trở lại"
Body: Thông báo mở khóa thành công, mời đăng nhập
```

---

## 7. DTO MAPPING

### UserAdminResponse (response cho admin)

```java
// Thêm vào UserDetailResponse hoặc tạo mới UserAdminResponse
{
  userID        : Integer         // PK
  fullName      : String
  phone         : String
  dateOfBirth   : LocalDate
  email         : String
  coinBalance   : BigDecimal
  avatar        : String
  status        : Boolean         // true=active, false=locked
  role          : String
  lastActiveAt  : LocalDateTime   // MỚI — cần thêm
  activityStatus: String          // MỚI — computed: Online/Away/Offline
}
```

### UserSearchRequest

```java
{
  fullName : String  // nullable
  phone    : String  // nullable
  email    : String  // nullable
}
```

### UserStatusUpdateRequest

```java
{
  userID : Integer  // required
  status : Boolean  // required: true=unlock, false=lock
  reason : String   // required: lý do để gửi email
}
```

---

## 8. QUERY TÌM KIẾM (JPQL)

```sql
SELECT u FROM User u
WHERE u.role = 'CUSTOMER'
  AND u.isDeleted = false
  AND (:fullName IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :fullName, '%')))
  AND (:phone IS NULL OR u.phone LIKE CONCAT('%', :phone, '%'))
  AND (:email IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%')))
ORDER BY u.createdAt DESC
```

*(Pagination xử lý thủ công sau sort activity — y hệt monolith)*

---

## 9. KIỂM TRA SẴN SÀNG TRƯỚC KHI CODE

| Hạng mục | Trạng thái | Ghi chú |
|----------|-----------|---------|
| iam-service đang chạy | ✅ | Port 8081 |
| notification-service đang chạy | ✅ | Port 8086 |
| API Gateway route `/api/users/**` → iam-service | ✅ | Đã config |
| Frontend UsersPage | ✅ | Không cần thay đổi |
| `lastActiveAt` column trong DB | ✅ | Đã có trong entity |
| Mail config trong iam-service | ✅ | Gmail SMTP |
| Cloudinary config | ✅ | Đã có |
| Feign client iam → notification | ❌ | Cần tạo mới |

---

## 10. THỨ TỰ TRIỂN KHAI

```
Bước 1: Thêm UserAdminResponse DTO (thêm lastActiveAt, activityStatus)
Bước 2: Thêm UserSearchRequest, UserStatusUpdateRequest DTOs
Bước 3: Tạo UserRepositoryCustom + UserRepositoryCustomImpl (searchUsers query)
Bước 4: Cập nhật UserRepository extends UserRepositoryCustom
Bước 5: Thêm searchUsers() vào UserServiceImpl (với sort logic)
Bước 6: Tạo NotificationFeignClient + UserStatusEventDTO trong iam-service
Bước 7: Thêm updateUserStatus() vào UserServiceImpl (email + WS via Feign)
Bước 8: Thêm 2 endpoint vào UserController
Bước 9: Thêm handler trong notification-service (email + WS push)
Bước 10: Build + deploy iam-service và notification-service
Bước 11: Test toàn bộ flow
```

---

## 11. CHECKLIST TEST

- [ ] `POST /api/users/admin/search` không filter → trả về tất cả CUSTOMER, paginated
- [ ] `POST /api/users/admin/search` filter fullName → trả đúng kết quả
- [ ] `POST /api/users/admin/search` filter phone → trả đúng kết quả
- [ ] Response có đủ fields: `lastActiveAt`, `activityStatus`
- [ ] Users Online hiển thị đầu danh sách
- [ ] `POST /api/users/admin/update-status` khóa → status=false, gửi email khóa
- [ ] `POST /api/users/admin/update-status` mở → status=true, gửi email mở khóa
- [ ] Sau update-status, frontend tự refresh list (WebSocket `/topic/admin/users`)
- [ ] `totalElements` đúng với số lượng thực tế trong DB
- [ ] Pagination hoạt động đúng (prev/next/page numbers)
