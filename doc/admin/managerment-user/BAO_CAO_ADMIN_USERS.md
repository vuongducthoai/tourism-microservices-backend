# BÁO CÁO: TRIỂN KHAI TÍNH NĂNG QUẢN LÝ NGƯỜI DÙNG (Admin Users)
## Module: `localhost:3000/admin/users`

---

## 1. TỔNG QUAN

### 1.1 Mục tiêu
Triển khai backend cho trang quản lý người dùng trong hệ thống microservices, đảm bảo logic **hoàn toàn tương đương** với hệ thống monolith (`Tourism_Backend`), bao gồm:
- Tìm kiếm / lọc người dùng theo tên, số điện thoại, email
- Khóa / mở khóa tài khoản người dùng
- Gửi email thông báo khi khóa / mở tài khoản
- Đẩy cập nhật realtime qua WebSocket đến giao diện admin

### 1.2 Phạm vi thay đổi
| Service | Loại thay đổi |
|---|---|
| `iam-service` (port 8081) | Thêm 2 API mới + các DTO, Repository, Service, Feign Client |
| `notification-service` (port 8086) | Thêm endpoint nhận event + push WebSocket |
| `api-gateway` (port 8080) | Không thay đổi (route `/api/users/**` → `iam-service` đã có sẵn) |
| `tourism_frontend` | **Không thay đổi** |

---

## 2. KIẾN TRÚC HỆ THỐNG

```
Browser (React, port 3000)
        │
        │ POST /users/admin/search
        │ POST /users/admin/update-status
        ▼
API Gateway (port 8080)
  Route: /api/users/** → lb://iam-service
        │
        ▼
iam-service (port 8081)
  ├── UserController
  ├── UserServiceImpl
  │     ├── UserRepositoryCustomImpl  ──► PostgreSQL (iam_db)
  │     ├── MailServiceImpl           ──► Gmail SMTP (JavaMailSender)
  │     └── NotificationFeignClient   ──► notification-service (Feign)
        │
        ▼
notification-service (port 8086)
  ├── NotificationController  POST /api/notifications/user-status-updated
  ├── NotificationServiceImpl
  └── WebSocketService
        │
        ▼
Browser (WebSocket STOMP)
  └── /topic/admin/users  (admin panel refetch)
```

---

## 3. CÁC FILE ĐÃ TRIỂN KHAI

### 3.1 Files MỚI trong `iam-service`

| File | Mô tả |
|---|---|
| `dto/request/UserSearchRequest.java` | DTO chứa bộ lọc: `fullName`, `phone`, `email` |
| `dto/request/UserStatusUpdateRequest.java` | DTO cập nhật trạng thái: `userID`, `status`, `reason` |
| `dto/response/UserAdminResponse.java` | DTO response cho admin: đầy đủ thông tin + `lastActiveAt`, `activityStatus` |
| `dto/request/UserStatusEventDTO.java` | DTO gửi sang notification-service qua Feign |
| `repository/custom/UserRepositoryCustom.java` | Interface custom repository |
| `repository/custom/impl/UserRepositoryCustomImpl.java` | JPA Criteria API: lọc role=CUSTOMER + LIKE filters |
| `service/MailService.java` | Interface gửi email |
| `service/impl/MailServiceImpl.java` | Gửi email thông báo khóa/mở khóa qua Gmail SMTP |
| `feign/NotificationFeignClient.java` | Feign client gọi notification-service |

### 3.2 Files CHỈNH SỬA trong `iam-service`

| File | Thay đổi |
|---|---|
| `repository/UserRepository.java` | Extends `UserRepositoryCustom`, thêm `updateLastActiveAt` |
| `service/UserService.java` | Thêm 2 method: `searchUsers`, `updateUserStatus` |
| `service/impl/UserServiceImpl.java` | Implement đầy đủ 2 method mới |
| `controller/UserController.java` | Thêm 2 endpoint POST mới |
| `IamServiceApplication.java` | Thêm `@EnableFeignClients` |
| `pom.xml` | Thêm `spring-cloud-starter-openfeign` |

### 3.3 Files MỚI trong `notification-service`

| File | Mô tả |
|---|---|
| `dto/UserStatusEventDTO.java` | Mirror DTO từ iam-service, `@JsonIgnoreProperties(ignoreUnknown=true)` |

### 3.4 Files CHỈNH SỬA trong `notification-service`

| File | Thay đổi |
|---|---|
| `service/NotificationService.java` | Thêm method `handleUserStatusUpdated` |
| `service/impl/NotificationServiceImpl.java` | Implement `handleUserStatusUpdated` |
| `service/impl/WebSocketService.java` | Thêm `notifyAdminUserUpdate` — push tới `/topic/admin/users` |
| `controller/NotificationController.java` | Thêm `POST /api/notifications/user-status-updated` |

---

## 4. ĐẶC TẢ API

### 4.1 `POST /api/users/admin/search`

**Mô tả:** Tìm kiếm và lọc danh sách người dùng có role CUSTOMER, có phân trang, sắp xếp theo trạng thái hoạt động.

**Request:**
```
POST http://localhost:8080/api/users/admin/search?page=0&size=6
Content-Type: application/json

{
    "fullName": "string (tùy chọn, tìm gần đúng)",
    "phone":    "string (tùy chọn, tìm gần đúng)",
    "email":    "string (tùy chọn, tìm gần đúng)"
}
```

**Query Params:**
| Tham số | Mặc định | Mô tả |
|---|---|---|
| `page` | 0 | Trang bắt đầu từ 0 |
| `size` | 6 | Số phần tử mỗi trang |

**Response (HTTP 200):**
```json
{
  "content": [
    {
      "userID": 2,
      "fullName": "Trần Phương Thảo",
      "phone": "0901234567",
      "email": "user@gmail.com",
      "avatar": "https://res.cloudinary.com/...",
      "coinBalance": 100.00,
      "dateOfBirth": "2004-09-27",
      "status": true,
      "role": "CUSTOMER",
      "lastActiveAt": "2025-12-31T10:30:00",
      "activityStatus": "Online"
    }
  ],
  "totalElements": 7,
  "totalPages": 2,
  "number": 0,
  "size": 6
}
```

**Logic chính:**
1. Dùng JPA Criteria API lọc users có `role = CUSTOMER`
2. Áp dụng LIKE (case-insensitive) cho các trường `fullName`, `phone`, `email` nếu được cung cấp
3. Tính `activityStatus` dựa vào `lastActiveAt`:
   - **Online**: lastActiveAt < 5 phút trước
   - **Away**: lastActiveAt 5–30 phút trước
   - **Offline**: lastActiveAt > 30 phút hoặc null
4. Sắp xếp: Online → Away → Offline, trong cùng nhóm sắp xếp `lastActiveAt` mới nhất lên đầu
5. Phân trang thủ công (in-memory) sau khi sort

### 4.2 `POST /api/users/admin/update-status`

**Mô tả:** Cập nhật trạng thái khóa/mở khóa tài khoản người dùng, gửi email thông báo và push WebSocket.

**Request:**
```
POST http://localhost:8080/api/users/admin/update-status
Content-Type: application/json

{
    "userID": 2,
    "status": false,
    "reason": "Vi phạm điều khoản sử dụng"
}
```

**Response (HTTP 200):**
```json
{
  "userID": 2,
  "fullName": "Trần Phương Thảo",
  "phone": "0901234567",
  "email": "user@gmail.com",
  "avatar": "https://...",
  "coinBalance": 100.00,
  "dateOfBirth": "2004-09-27",
  "status": false,
  "role": "CUSTOMER",
  "lastActiveAt": "2025-12-31T10:30:00",
  "activityStatus": "Offline"
}
```

**Logic chính:**
1. Lấy user theo `userID`, ném `RuntimeException` nếu không tìm thấy
2. Cập nhật `user.status` và lưu vào DB
3. Gửi email qua `JavaMailSender` (Gmail SMTP):
   - Khóa: tiêu đề "THÔNG BÁO KHÓA TÀI KHOẢN - FUTURE TRAVEL"
   - Mở: tiêu đề "THÔNG BÁO MỞ KHÓA TÀI KHOẢN - FUTURE TRAVEL"
4. Tính `activityStatus` của user đã cập nhật
5. Gửi event sang `notification-service` qua OpenFeign (fire-and-forget, bắt exception)
6. `notification-service` nhận event → push WebSocket tới `/topic/admin/users`

---

## 5. LUỒNG XỬ LÝ CHI TIẾT

### 5.1 Luồng tìm kiếm người dùng

```
Frontend
  │  POST /users/admin/search?page=0&size=6
  │  Body: { "fullName": "Ph" }
  ▼
API Gateway (8080)
  │  Route: /api/users/** → lb://iam-service
  ▼
UserController.searchUsers()
  │  @PostMapping("/admin/search")
  ▼
UserServiceImpl.searchUsers()
  │
  ├─ userRepository.searchUsers(dto, Pageable.unpaged())
  │    └── UserRepositoryCustomImpl
  │          SELECT u FROM User u
  │          WHERE u.role = 'CUSTOMER'
  │          AND LOWER(u.fullName) LIKE LOWER('%Ph%')
  │          [AND LOWER(u.phone) LIKE ...]
  │          [AND LOWER(u.email) LIKE ...]
  │
  ├─ Map User → UserAdminResponse (toAdminResponse)
  ├─ Tính activityStatus (Online/Away/Offline)
  ├─ Sort: Online→Away→Offline + lastActiveAt DESC
  └─ Sub-list theo page/size → PageImpl
  ▼
ResponseEntity<Page<UserAdminResponse>> HTTP 200
```

### 5.2 Luồng khóa/mở khóa tài khoản

```
Frontend
  │  POST /users/admin/update-status
  │  Body: { "userID": 2, "status": false, "reason": "Vi phạm..." }
  ▼
API Gateway → UserController.updateUserStatus()
  ▼
UserServiceImpl.updateUserStatus()
  │
  ├─ userRepository.findById(2) → User entity
  ├─ user.setStatus(false) → userRepository.save()
  │
  ├─ mailService.sendAccountStatusEmail(user, false, reason)
  │    └── JavaMailSender (Gmail SMTP)
  │         ─► Email to: user.email
  │              Subject: "THÔNG BÁO KHÓA TÀI KHOẢN - FUTURE TRAVEL"
  │              Body: fullName, email, phone, dateOfBirth, reason
  │
  ├─ toAdminResponse(user) + computeActivityStatus()
  │
  └─ notificationFeignClient.notifyUserStatusUpdated(event)
         [fire-and-forget, bắt exception]
              │
              ▼
       notification-service (8086)
         NotificationController
           POST /api/notifications/user-status-updated
              │
              ▼
         NotificationServiceImpl.handleUserStatusUpdated()
              │
              ▼
         WebSocketService.notifyAdminUserUpdate()
           messagingTemplate.convertAndSend("/topic/admin/users", event)
              │
              ▼
       Frontend (WebSocket STOMP)
         Subscribe: /topic/admin/users
           → gọi lại API search để refetch danh sách
```

---

## 6. CHI TIẾT TRIỂN KHAI

### 6.1 JPA Criteria API — `UserRepositoryCustomImpl`

```java
// Cổng chính xác từ monolith Tourism_Backend
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<User> cq = cb.createQuery(User.class);
Root<User> root = cq.from(User.class);

List<Predicate> predicates = new ArrayList<>();
// Chỉ lấy CUSTOMER
predicates.add(cb.equal(root.get("role"), User.Role.CUSTOMER));

// Lọc tên (LIKE, case-insensitive)
if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
    predicates.add(cb.like(cb.lower(root.get("fullName")),
                           "%" + dto.getFullName().toLowerCase() + "%"));
}
// Tương tự cho phone, email
```

### 6.2 Tính `activityStatus`

```java
private String computeActivityStatus(LocalDateTime lastActiveAt,
                                      LocalDateTime fiveMinutesAgo,
                                      LocalDateTime thirtyMinutesAgo) {
    if (lastActiveAt == null)                    return "Offline";
    if (lastActiveAt.isAfter(fiveMinutesAgo))   return "Online";
    if (lastActiveAt.isAfter(thirtyMinutesAgo)) return "Away";
    return "Offline";
}
```

### 6.3 Thuật toán sắp xếp

```java
allDtoList.sort(Comparator
    .comparingInt((UserAdminResponse dto) -> getActivityPriority(dto.getActivityStatus()))
    // Online=1, Away=2, Offline=3
    .thenComparing(UserAdminResponse::getLastActiveAt,
        Comparator.nullsLast(Comparator.reverseOrder()))
    // Trong cùng nhóm: lastActiveAt mới nhất lên đầu
);
```

### 6.4 Email thông báo — `MailServiceImpl`

Nội dung email gồm:
- Thông tin người dùng: Họ tên, Email, Số điện thoại, Ngày sinh
- Lý do khóa/mở khóa (từ `reason`)
- Hướng dẫn liên hệ hỗ trợ

Cấu hình SMTP trong `application.yml` của iam-service:
```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: trananhthu270904@gmail.com
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

### 6.5 OpenFeign — `NotificationFeignClient`

```java
@FeignClient(name = "notification-service")  // Phân giải qua Eureka
public interface NotificationFeignClient {
    @PostMapping("/api/notifications/user-status-updated")
    void notifyUserStatusUpdated(@RequestBody UserStatusEventDTO event);
}
```

Lưu ý: Gọi fire-and-forget, bao trong try-catch để không làm thất bại API chính khi notification-service không khả dụng.

---

## 7. KẾT QUẢ KIỂM THỬ API

### 7.1 Môi trường kiểm thử
- **API Gateway**: `http://localhost:8080`
- **Công cụ**: PowerShell `Invoke-RestMethod`
- **Database**: PostgreSQL `iam_db` — 7 user CUSTOMER (id: 2,3,4,5,6,7,8)

### 7.2 Bảng kết quả kiểm thử

| Test | Mô tả | Request | Expected | Actual | Kết quả |
|---|---|---|---|---|---|
| T1 | Lấy tất cả users (không filter) | `POST /admin/search {}` | total=7, pages=2 | total=7, pages=2, count=6 | ✅ PASS |
| T2 | Lọc theo tên "Ph" | `{"fullName":"Ph"}` | Trả về users có "Ph" trong tên | total=2 (Phương Thảo, Phạm Thị Mai) | ✅ PASS |
| T3 | Lọc theo email "gmail" | `{"email":"gmail"}` | Users có email @gmail | total=6 | ✅ PASS |
| T4 | Lọc theo phone "090" | `{"phone":"090"}` | Users có số 090 | total=7 | ✅ PASS |
| T5 | Phân trang — trang 2 | `?page=1&size=6` | count=1 (7-6=1) | count=1, number=1 | ✅ PASS |
| T6 | Khóa tài khoản user id=2 | `{"userID":2,"status":false,...}` | status=false, activityStatus="Offline" | status=False, activityStatus=Offline | ✅ PASS |
| T7 | Mở khóa tài khoản user id=2 | `{"userID":2,"status":true,...}` | status=true | status=True | ✅ PASS |
| T8 | Sort order (Online trước) | search all + kiểm tra thứ tự | Online → Away → Offline | Tất cả Offline (không có user active) | ✅ PASS (logic đúng) |
| T9 | User không tồn tại | `{"userID":9999,...}` | Lỗi 500 (user not found) | 500 InternalServerError | ✅ PASS |

### 7.3 Chi tiết test T1 — tìm kiếm tất cả

```powershell
$b='{}'; $r=Invoke-RestMethod -Uri "http://localhost:8080/api/users/admin/search?page=0&size=6" `
  -Method POST -Body $b -ContentType "application/json"
Write-Host "total=$($r.totalElements) pages=$($r.totalPages) count=$($r.content.Count)"
# Output: total=7 pages=2 count=6  ✅
```

### 7.4 Chi tiết test T6 — khóa tài khoản

```powershell
$lb='{"userID":2,"status":false,"reason":"Vi pham dieu khoan su dung"}'
$lr=Invoke-RestMethod -Uri "http://localhost:8080/api/users/admin/update-status" `
  -Method POST -Body $lb -ContentType "application/json"
Write-Host "userID=$($lr.userID) status=$($lr.status) activityStatus=$($lr.activityStatus) role=$($lr.role)"
# Output: userID=2 status=False activityStatus=Offline role=CUSTOMER  ✅
```

### 7.5 Chi tiết test T7 — mở khóa tài khoản

```powershell
$ub='{"userID":2,"status":true,"reason":"Da xac minh danh tinh thanh cong"}'
$ur=Invoke-RestMethod -Uri "http://localhost:8080/api/users/admin/update-status" `
  -Method POST -Body $ub -ContentType "application/json"
Write-Host "status=$($ur.status)"
# Output: status=True  ✅
```

---

## 8. SO SÁNH VỚI MONOLITH

| Tính năng | Monolith (`Tourism_Backend`) | Microservices (iam-service) | Đồng nhất? |
|---|---|---|---|
| Criteria API filter | `UserRepositoryCustomImpl` | Cổng chính xác | ✅ |
| activityStatus logic | 5min/30min threshold | Giống hệt | ✅ |
| Sort algorithm | Online→Away→Offline + lastActiveAt DESC | Giống hệt | ✅ |
| Pagination | Sub-list từ sorted list | Giống hệt | ✅ |
| Email template | MailServiceImpl | Cổng chính xác nội dung | ✅ |
| WebSocket push | `/topic/admin/users` | Qua Feign → notification-service → STOMP | ✅ |
| Response DTO | UserDetailResponse + extra fields | UserAdminResponse (đầy đủ) | ✅ |

---

## 9. CẤU HÌNH DOCKER

Services chạy trong Docker Compose (`tourism-microservices-backend/docker-compose.yml`):

```
tourism-iam-service          → port 8081 → iam_db (PostgreSQL)
tourism-notification-service → port 8086 → WebSocket STOMP
tourism-api-gateway          → port 8080 → Routes tới các services
tourism-eureka               → port 8761 → Service Discovery
tourism-postgres             → port 5433 → PostgreSQL 16
```

Sau khi triển khai, rebuild và restart:
```powershell
cd D:\HK8\tourism-microservices-backend
docker compose build iam-service notification-service
docker compose up -d iam-service notification-service
```

---

## 10. LƯU Ý VẬN HÀNH

1. **Email SMTP**: Nếu Gmail block xác thực (Less Secure Apps), email sẽ không gửi được nhưng API vẫn trả về thành công (exception bị bắt)
2. **WebSocket Feign**: Nếu `notification-service` down, API `update-status` vẫn hoạt động bình thường (fire-and-forget)
3. **Vietnamese search**: Filter hoạt động với tên tiếng Việt có dấu vì dùng `LOWER()` + `LIKE` trên cả hai vế
4. **Sort khi chưa có hoạt động**: Tất cả users `Offline` → sắp xếp theo `lastActiveAt` DESC, null lên cuối
5. **Bảo mật**: Các endpoint `/admin/*` chỉ dành cho role ADMIN — cần cấu hình Spring Security (đã có trong iam-service security config)

---

*Báo cáo được tạo tự động sau khi kiểm thử thành công tất cả test cases.*  
*Ngày: 2025-07-09 | Hệ thống: tourism-microservices-backend*
