# Tính năng "Gửi yêu cầu tư vấn tour" — Plan đầy đủ

> **Mục tiêu**: User click nút phone trên trang chi tiết tour → mở modal nhập (Họ tên, SĐT, Email, Thông tin cần tư vấn) → admin nhận, gọi lại, đánh dấu xử lý xong.
> **Phạm vi**: Backend (service nào host, schema, endpoint, notify), Frontend user (modal trên trang tour), Frontend admin (trang quản lý + real-time alert).
> **Phương pháp**: Phân tích codebase hiện có → đưa ra **3 phương án chọn host service** + đánh giá → khuyến nghị → kế hoạch chi tiết.

---

## 1. Phân tích quyết định: Service nào nên host?

Đây là quyết định kiến trúc quan trọng nhất. So sánh 3 option dựa trên những gì đã có sẵn trong codebase.

### Option A — `booking-service` ⭐ Khuyến nghị

**Lý do:**
- ✅ **Sẵn FeignClient** đến `iam-service` (lấy user info) và `tour-catalog-service` (lấy tour info) — yêu cầu tư vấn cần cả 2 → không phải thêm boilerplate.
- ✅ **Sẵn pattern `OutboxEvent`** → publish event "có yêu cầu tư vấn mới" cho `notification-service` push WebSocket cho admin (giống cách `BookingCreatedEvent` đang chạy).
- ✅ **Sẵn `BaseEntity`** (createdAt/updatedAt/isDeleted soft-delete) — chuẩn hóa entity mới mà không cần config thêm.
- ✅ **Cùng domain** với booking: yêu cầu tư vấn thường là "tiền-booking" — user hỏi tư vấn, admin call lại, chốt → tạo booking thật. Đặt cùng service giúp sau này dễ link `consultation_id → booking_id`.
- ⚠️ Nhược: booking-service hơi to lên, nhưng entity + 1 endpoint không phải gánh nặng.

**Khi nào KHÔNG chọn**: nếu bạn dự định scale consultation thành module riêng (vd có cuộc trò chuyện chat real-time, có CRM, có pipeline lead nurturing) → tách service mới hợp lý hơn. Trường hợp hiện tại chỉ là form đơn giản → Option A đủ tốt.

### Option B — Tạo `consultation-service` riêng

**Khi nào chọn**: scope mở rộng thành CRM (lead pipeline, chat realtime, follow-up tự động), team có nhân lực maintain service mới.

**Chi phí**:
- Setup mới: port (vd 8090), Eureka registration, Dockerfile, docker-compose entry, gateway route, application.yml, healthcheck.
- Phải duplicate FeignClient IAM/TourCatalog.
- Phải tạo riêng DB hoặc share `booking_db`.
- ~1-2 ngày overhead so với Option A.

→ **Không khuyến nghị cho scope hiện tại** (form tư vấn đơn giản, chưa cần CRM).

### Option C — Host ở `notification-service`

**Vì sao KHÔNG**: notification-service đúng vai trò là **fan-out event broker** (nhận event, push WebSocket/email/in-app). Lưu một entity domain (yêu cầu tư vấn) không thuộc domain của nó. Mixing 2 trách nhiệm sẽ khó test/maintain về sau.

### Kết luận

| Tiêu chí | A: booking-service ⭐ | B: service mới | C: notification-service |
|---|:---:|:---:|:---:|
| Tốc độ implement | Nhanh (0.5d) | Chậm (1-2d setup) | Nhanh nhưng sai domain |
| Sạch về boundary | ✓ | ✓✓ | ✗ |
| Tái dùng infrastructure | ✓✓ | ✗ (phải dup) | ✓ |
| Phù hợp scope hiện tại | ✓✓ | Over-engineer | ✗ |
| **Tổng** | **Khuyến nghị** | Sau này nếu scale | Loại |

**→ Plan dưới đây dùng `booking-service`.**

---

## 2. Luồng hoạt động end-to-end

```
[User trang chi tiết tour]
        │
        │ Click nút 📞 phone (compactCallBtn) hoặc "Gửi yêu cầu tư vấn"
        ▼
[Modal "Gửi thông tin tư vấn"]
   Họ tên *  | SĐT *  | Email *  | Thông tin cần tư vấn
        │
        │ Submit (validate FE)
        ▼
[POST /api/consultations]
        │
        ▼
[booking-service] ConsultationController
        │
        ├─ Validate (regex SĐT, email format)
        ├─ Save ConsultationRequest entity (status=PENDING)
        ├─ Generate requestCode "CR" + 6 ký tự
        ├─ Publish OutboxEvent → RabbitMQ topic: "consultation.created"
        │
        ▼  ←──── notification-service consume event
[notification-service]
        │
        ├─ WebSocket push topic /topic/admin/consultations
        │   → AdminLayout badge bell hiện "🔴 1" + toast
        ├─ (Optional) Email gửi admin chính (config admin_email)
        │
        ▼
[Admin Dashboard]
        │
        │ Click bell hoặc menu "Tư vấn"
        ▼
[/admin/consultations] Trang quản lý
   - Bảng list: requestCode | User | Tour | Status | Thời gian | Hành động
   - Filter: status (PENDING / IN_PROGRESS / RESOLVED / CLOSED)
   - Click row → modal detail xem nội dung đầy đủ
   - Nút action: "Đánh dấu đang xử lý" | "Đã xử lý" | "Đóng"
        │
        │ Admin gọi điện cho user qua SĐT trong form
        │ Sau khi gọi xong, click "Đã xử lý" + nhập notes
        ▼
[PATCH /api/admin/consultations/{id}/status]
        │ status: RESOLVED, notes: "Đã call, user chốt tour NDNHA7861"
        │ admin_id, admin_email lưu từ X-User-Id, X-User-Email
        ▼
   Row cập nhật → badge giảm
```

---

## 3. Schema DB

Bảng mới `consultation_requests` trong `booking_db`:

```sql
CREATE TABLE consultation_requests (
    consultation_id     SERIAL PRIMARY KEY,
    request_code        VARCHAR(20) UNIQUE NOT NULL,    -- "CR-A1B2C3" hiển thị user

    -- Người gửi
    full_name           VARCHAR(255) NOT NULL,
    phone               VARCHAR(20)  NOT NULL,
    email               VARCHAR(255) NOT NULL,
    user_id             INTEGER,                         -- nullable cho guest (chưa đăng nhập)

    -- Nội dung tư vấn
    tour_id             INTEGER,                         -- nullable: nếu inquiry chung không gắn tour
    tour_code           VARCHAR(50),                     -- denormalize để admin xem nhanh
    tour_name           VARCHAR(255),                    -- snapshot, tránh JOIN feign
    consultation_info   TEXT,                            -- "Thông tin cần tư vấn" (có thể trống)

    -- Trạng thái xử lý
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | IN_PROGRESS | RESOLVED | CLOSED
    admin_id            INTEGER,                         -- admin đã xử lý
    admin_email         VARCHAR(255),
    admin_notes         TEXT,                            -- ghi chú sau khi call
    resolved_at         TIMESTAMP,

    -- BaseEntity
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    is_deleted          BOOLEAN   NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_consultation_status ON consultation_requests(status);
CREATE INDEX idx_consultation_created ON consultation_requests(created_at DESC);
CREATE INDEX idx_consultation_phone   ON consultation_requests(phone);
```

**Lý do design:**
- `request_code` (vd `CR-A1B2C3`) → user nhận khi submit, hỏi lại "đơn yêu cầu của tôi đến đâu?"
- `tour_id` nullable → cho phép inquiry chung (vd user vào trang chủ click "Tư vấn miễn phí")
- `user_id` nullable → cho **guest gửi không cần đăng nhập** (giảm friction, tăng conversion)
- Denormalize `tour_code` + `tour_name` → admin xem list không phải gọi Feign cho từng row
- `admin_id` + `admin_email` + `admin_notes` + `resolved_at` → audit ai xử lý, khi nào, kết quả

**Status enum:**
- `PENDING` — vừa submit, chưa ai xử lý → admin nhận notification
- `IN_PROGRESS` — admin đã claim, đang gọi
- `RESOLVED` — đã xử lý xong (user chốt tour / không quan tâm / refer khác)
- `CLOSED` — đóng vĩnh viễn (spam, duplicate, không liên hệ được sau 3 lần)

---

## 4. Backend implementation chi tiết

### 4.1. Entity + Repository

**File mới**: `booking-service/src/main/java/com/tourism/booking/entity/ConsultationRequest.java`

```java
@Entity
@Table(name = "consultation_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationRequest extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "request_code", unique = true, nullable = false, length = 20)
    private String requestCode;

    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(nullable = false, length = 20)        private String phone;
    @Column(nullable = false)                     private String email;
    @Column(name = "user_id")                     private Integer userId;

    @Column(name = "tour_id")                     private Integer tourId;
    @Column(name = "tour_code", length = 50)      private String tourCode;
    @Column(name = "tour_name")                   private String tourName;
    @Column(name = "consultation_info", columnDefinition = "TEXT")
    private String consultationInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConsultationStatus status = ConsultationStatus.PENDING;

    @Column(name = "admin_id")            private Integer adminId;
    @Column(name = "admin_email")         private String adminEmail;
    @Column(name = "admin_notes", columnDefinition = "TEXT") private String adminNotes;
    @Column(name = "resolved_at")         private LocalDateTime resolvedAt;

    public enum ConsultationStatus { PENDING, IN_PROGRESS, RESOLVED, CLOSED }
}
```

**File mới**: `ConsultationRequestRepository.java`
```java
public interface ConsultationRequestRepository
        extends JpaRepository<ConsultationRequest, Integer>, JpaSpecificationExecutor<ConsultationRequest> {

    Page<ConsultationRequest> findByStatusOrderByCreatedAtDesc(
            ConsultationRequest.ConsultationStatus status, Pageable pageable);

    long countByStatus(ConsultationRequest.ConsultationStatus status);

    Optional<ConsultationRequest> findByRequestCode(String code);
}
```

### 4.2. Service + Controller

**`ConsultationService` (interface)** với 4 method chính:
```java
ConsultationResponse create(ConsultationCreateRequest req, Integer userIdFromHeader);
Page<ConsultationResponse> list(String status, Pageable pageable);     // Admin
ConsultationResponse updateStatus(Integer id, UpdateStatusRequest req); // Admin
long countPending();                                                    // Cho badge
```

**`ConsultationServiceImpl.create()`:**
1. Generate `requestCode` = "CR-" + 6 ký tự hex random
2. Nếu `tourId` có → gọi `tourCatalogFeignClient.getTourBrief(tourId)` lấy `tourCode + tourName` để denormalize
3. Save entity với status = PENDING
4. Publish event `ConsultationCreatedEvent { id, requestCode, fullName, phone, tourName }` qua RabbitMQ → notification-service nhận và push WebSocket
5. (Optional) Trả về `requestCode` cho FE để user biết mã yêu cầu

**`ConsultationServiceImpl.updateStatus()`:**
- Đọc `AdminContext.currentUserId()`, `currentEmail()` lưu vào `adminId`, `adminEmail`
- Set `resolvedAt = now()` khi chuyển sang `RESOLVED` hoặc `CLOSED`
- Update `adminNotes` từ request

### 4.3. Controllers

**`ConsultationController` (public — cho user)**

```java
@RestController
@RequestMapping("/api/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService service;

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody ConsultationCreateRequest req,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId) {
        // userId từ gateway nếu user đã login, null cho guest
        ConsultationResponse resp = service.create(req, userId);
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Đã gửi yêu cầu, chúng tôi sẽ liên hệ trong ít phút",
                "data", resp));
    }

    @GetMapping("/{requestCode}/status")
    public ResponseEntity<?> checkStatus(@PathVariable String requestCode) {
        // Cho user check trạng thái đơn của mình bằng mã CR-XXX
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.getByCode(requestCode)));
    }
}
```

**`AdminConsultationController`**

```java
@RestController
@RequestMapping("/api/admin/consultations")
@RequiredArgsConstructor
public class AdminConsultationController {

    private final ConsultationService service;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.list(status, p)));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "pending",     service.countByStatus("PENDING"),
                "inProgress",  service.countByStatus("IN_PROGRESS"),
                "resolved",    service.countByStatus("RESOLVED"),
                "closed",      service.countByStatus("CLOSED"))));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Integer id,
                                          @RequestBody UpdateStatusRequest req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.updateStatus(id, req),
                "message", "Đã cập nhật trạng thái"));
    }
}
```

**Bảo mật**: thêm `AdminAuthInterceptor` (clone pattern từ forum-service) trên `/api/admin/consultations/**` — chỉ ADMIN qua được.

### 4.4. Validation cho create

```java
@Data
public class ConsultationCreateRequest {
    @NotBlank(message = "Họ tên là bắt buộc")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(regexp = "^(0|\\+84)\\d{9,10}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @NotBlank(message = "Email là bắt buộc")
    @Email(message = "Email không hợp lệ")
    private String email;

    private Integer tourId;          // optional
    private String consultationInfo; // optional
}
```

### 4.5. Rate-limit chống spam

Yêu cầu tư vấn dễ bị spam (nhiều SĐT giả gửi liên tục) → thêm rate-limit Redis (clone pattern `ForumRateLimitService`):
- Cùng SĐT: tối đa **3 request/giờ**
- Cùng IP: tối đa **10 request/giờ**

Key Redis: `consult:phone:{phone}:hour` với INCR + EXPIRE 3600s.

---

## 5. Notification-service: nhận event và push admin

### 5.1. Mở rộng event listener

**Trong `notification-service`**:
- Thêm RabbitMQ queue listener cho topic `consultation.created`
- Khi nhận event → `webSocketService.notifyAdminConsultation(event)` push lên `/topic/admin/consultations`
- (Optional) Gửi email cho admin chính qua `mailService.sendAdmin(...)`

### 5.2. Frontend admin subscribe

`AdminLayout.jsx` đã có `useWebSocket` cho `/topic/admin/bookings` → clone:
```jsx
useWebSocket('/topic/admin/consultations', (event) => {
    setBadgeCount(c => c + 1);
    toast.info(`Yêu cầu tư vấn mới từ ${event.fullName}`);
});
```

---

## 6. Frontend USER: Modal tư vấn

### 6.1. Component mới

**File mới**: `client-side/src/components/TourDetailComponent/ConsultationModal/ConsultationModal.jsx`

```jsx
const ConsultationModal = ({ tourId, tourCode, tourName, isOpen, onClose }) => {
    const { user } = useAuth();
    const [form, setForm] = useState({
        fullName: user?.fullName || '',
        phone: user?.phoneNumber || '',
        email: user?.email || '',
        consultationInfo: '',
    });
    const [errors, setErrors] = useState({});
    const [submitting, setSubmitting] = useState(false);
    const [submitted, setSubmitted] = useState(null); // { requestCode } khi thành công

    const validate = () => { /* check fullName, phone regex, email format */ };

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!validate()) return;
        setSubmitting(true);
        try {
            const res = await axios.post('/consultations', { ...form, tourId });
            setSubmitted({ requestCode: res.data.data.requestCode });
        } catch (err) {
            toast.error(err.response?.data?.message || 'Gửi thất bại');
        } finally {
            setSubmitting(false);
        }
    };

    if (!isOpen) return null;

    return (
        <div className={styles.overlay} onClick={onClose}>
            <div className={styles.modal} onClick={e => e.stopPropagation()}>
                <button className={styles.closeBtn} onClick={onClose}><X /></button>
                {submitted ? (
                    <SuccessView requestCode={submitted.requestCode} onClose={onClose} />
                ) : (
                    <form onSubmit={handleSubmit}>
                        <h2>Gửi thông tin tư vấn</h2>
                        <p className={styles.subtitle}>
                            Quý khách vui lòng nhập thông tin bên dưới, chúng tôi sẽ liên hệ lại sau ít phút
                        </p>
                        {tourName && (
                            <div className={styles.tourHint}>
                                <FaMapMarkerAlt /> Tour: <strong>{tourName}</strong>
                            </div>
                        )}
                        {/* 4 input: fullName, phone, email, consultationInfo */}
                        <button type="submit" disabled={submitting} className={styles.submitBtn}>
                            {submitting ? 'Đang gửi...' : 'Gửi ngay'}
                        </button>
                    </form>
                )}
            </div>
        </div>
    );
};
```

### 6.2. Wire vào TourDetail.jsx

Thay onClick của nút phone:
```jsx
// Cũ
<button onClick={() => window.location.href = 'tel:19002045'}>

// Mới
const [showConsult, setShowConsult] = useState(false);
<button onClick={() => setShowConsult(true)} aria-label="Gửi yêu cầu tư vấn">
  <FaPhoneAlt />
</button>

<ConsultationModal
    tourId={tourData.tourID}
    tourCode={tourData.tourCode}
    tourName={tourData.tourName}
    isOpen={showConsult}
    onClose={() => setShowConsult(false)}
/>
```

### 6.3. Success view

Sau khi submit thành công:
```jsx
<div className={styles.successView}>
    <FaCheck size={48} className={styles.successIcon} />
    <h3>Cảm ơn quý khách!</h3>
    <p>Mã yêu cầu của bạn: <strong>{requestCode}</strong></p>
    <p>Chúng tôi sẽ liên hệ lại trong vòng 30 phút.</p>
    <button onClick={onClose}>Đóng</button>
</div>
```

User có thể check status bằng request code (Phase 2 nếu cần).

---

## 7. Frontend ADMIN: Trang quản lý

### 7.1. Trang mới `/admin/consultations`

**File mới**: `client-side/src/components/AdminComponent/Pages/ConsultationsPage/ConsultationsPage.jsx`

Layout:
```
┌─────────────────────────────────────────────────────────────────┐
│ Yêu cầu tư vấn                                                   │
├─────────────────────────────────────────────────────────────────┤
│ [Tất cả] [Chờ xử lý: 5] [Đang xử lý: 2] [Đã xử lý: 38] [Đóng]   │
├─────────────────────────────────────────────────────────────────┤
│ Mã   │ Khách     │ SĐT       │ Tour     │ Trạng thái │ Lúc    │
│ CR-A1│ Nguyễn A  │ 0901...   │ NDNHA... │ Chờ        │ 5m ago │
│ CR-B2│ Trần B    │ 0912...   │ —        │ Đang xử lý │ 1h ago │
│ ...                                                              │
├─────────────────────────────────────────────────────────────────┤
│ [Trang 1/5] ◀ ▶                                                  │
└─────────────────────────────────────────────────────────────────┘
```

Click row → modal detail:
- Hiển thị đầy đủ form user gửi
- Textarea nhập `adminNotes`
- 3 nút action: "Bắt đầu xử lý" (→ IN_PROGRESS), "Đã xử lý" (→ RESOLVED), "Đóng" (→ CLOSED)
- Nút "Gọi ngay" → `window.location.href='tel:{phone}'` mở app điện thoại

### 7.2. Sidebar nav

Thêm vào `AdminSidebar.jsx`:
```jsx
{
  to: '/admin/consultations',
  label: 'Yêu cầu tư vấn',
  icon: <FaHeadset />,
  badge: pendingCount,   // hiển thị số PENDING như notification dot
}
```

### 7.3. Real-time badge

`AdminLayout.jsx`:
```jsx
const [pendingConsults, setPendingConsults] = useState(0);

useEffect(() => {
    axios.get('/admin/consultations/stats').then(r => setPendingConsults(r.data.data.pending));
}, []);

useWebSocket('/topic/admin/consultations', () => {
    setPendingConsults(c => c + 1);
    toast('🔔 Có yêu cầu tư vấn mới', { type: 'info' });
});
```

---

## 8. Gateway route

**File**: `api-gateway/src/main/resources/application.yml`

Thêm 2 route (public + admin) cùng trỏ về booking-service:
```yaml
- id: consultation-public
  uri: lb://booking-service
  predicates:
    - Path=/api/consultations/**
  filters:
    - name: AuthHeaderFilter
      args: { required: false }   # public, optional auth

- id: consultation-admin
  uri: lb://booking-service
  predicates:
    - Path=/api/admin/consultations/**
  filters:
    - name: AuthHeaderFilter
      args: { required: true, roles: [ADMIN] }
```

---

## 9. Sprint plan (tổng cộng ~3 ngày)

### Sprint 1 (1 ngày) — Backend core

| Task | Effort |
|---|---|
| Entity + Repository + migration | 1h |
| Service + ConsultationController (public POST) | 2h |
| AdminConsultationController (list + update status) | 2h |
| Validation + rate-limit Redis | 1h |
| Gateway route | 0.5h |
| Build + test bằng Postman | 1.5h |

### Sprint 2 (1 ngày) — Notification + FE user

| Task | Effort |
|---|---|
| OutboxEvent `ConsultationCreatedEvent` + publisher | 1h |
| Notification-service consumer + WebSocket push | 1.5h |
| FE: `ConsultationModal` component + SCSS | 3h |
| FE: Wire vào nút phone trong TourDetail | 0.5h |
| FE: SuccessView + toast errors | 1h |
| Manual test luồng end-to-end | 1h |

### Sprint 3 (1 ngày) — FE admin

| Task | Effort |
|---|---|
| `ConsultationsPage` (list + filter tabs + pagination) | 2.5h |
| `ConsultationDetailModal` (xem detail + 3 action buttons) | 2h |
| Sidebar nav + badge count | 1h |
| Real-time WebSocket subscribe | 1h |
| (Optional) Email gửi admin chính | 1.5h |

---

## 10. Phase 2 (nice-to-have, không nằm trong scope hiện tại)

| Feature | Mô tả |
|---|---|
| User check status đơn | Trang public `/consultation/{code}` để user xem trạng thái xử lý |
| Convert thành Booking | Trong admin detail, có nút "Tạo booking từ yêu cầu này" — auto fill form booking với info đã có |
| Lịch sử trao đổi | Mỗi consultation có thread comment giữa admin (multiple admins phối hợp) |
| SLA alert | Cảnh báo PENDING > 30 phút chưa ai xử lý |
| Phân loại admin xử lý | Auto-assign theo phân khu / kỹ năng admin |
| Phân tích báo cáo | Dashboard tỉ lệ convert (consultation → booking), thời gian phản hồi trung bình |

---

## 11. Câu hỏi cần user xác nhận trước khi code

1. **Service host**: Confirm dùng **booking-service** (khuyến nghị) hay muốn tạo service mới?
2. **Guest gửi không cần login**: OK chứ? Hay bắt buộc đăng nhập?
3. **Notify admin**: WebSocket push (khuyến nghị) hay chỉ email là đủ?
4. **Sidebar badge count**: hiển thị số PENDING (3 vd) hay chỉ chấm đỏ?
5. **SĐT tư vấn cố định**: hiện đang là `19002045` — có cần config trong DB cho admin sửa được không, hay hardcode trong .env?
6. **Multilingual**: hiện text "Quý khách vui lòng..." là tiếng Việt — có cần i18n không?

---

## 12. Files sẽ chạm khi implement

**Backend** (`booking-service`):
- `entity/ConsultationRequest.java` — **NEW**
- `repository/ConsultationRequestRepository.java` — **NEW**
- `dto/request/ConsultationCreateRequest.java`, `UpdateStatusRequest.java` — **NEW**
- `dto/response/ConsultationResponse.java` — **NEW**
- `service/ConsultationService.java` + `impl/ConsultationServiceImpl.java` — **NEW**
- `controller/ConsultationController.java`, `AdminConsultationController.java` — **NEW**
- `config/AdminAuthInterceptor.java`, `AdminContext.java` — **NEW** (clone forum-service)
- `event/ConsultationCreatedEvent.java` + publisher — **NEW**

**Backend** (`notification-service`):
- Consumer mới cho topic `consultation.created`
- `WebSocketService.notifyAdminConsultation()` — extend

**Backend** (`api-gateway`):
- `application.yml` — thêm 2 route

**Frontend** (`client-side`):
- `components/TourDetailComponent/ConsultationModal/` — **NEW** (jsx + scss)
- `components/TourDetailComponent/TourDetail.jsx` — thay onClick nút phone
- `components/AdminComponent/Pages/ConsultationsPage/` — **NEW** (list + detail modal)
- `components/AdminComponent/AdminLayout/AdminSidebar/AdminSidebar.jsx` — thêm nav item + badge
- `components/AdminComponent/AdminLayout/AdminLayout.jsx` — subscribe WebSocket
- `App.tsx` — thêm route `/admin/consultations`
- `services/consultations/consultationApi.js` — **NEW** (axios wrapper)

---

## 13. Risk + Mitigation

| Risk | Mitigation |
|---|---|
| Spam SĐT giả | Rate-limit Redis 3 req/giờ/SĐT + Cloudflare Turnstile (phase 2) |
| Admin offline → user chờ lâu | SLA alert phase 2 + email tự động "đã nhận yêu cầu" cho user |
| Tour bị xóa nhưng consultation đang trỏ | Denormalize `tour_code + tour_name` snapshot vào row → không bị broken FK |
| Email/phone gõ sai | Validate FE + BE; gửi SMS OTP xác nhận phase 2 |
| WebSocket disconnect → miss notification | Khi admin reload, query `/admin/consultations?status=PENDING` để sync lại badge count |

---

## Tóm tắt

- **Host**: `booking-service` (Option A, tận dụng FeignClient + OutboxEvent đã có)
- **Database**: 1 bảng mới `consultation_requests` trong `booking_db`, BaseEntity soft-delete
- **API public**: `POST /api/consultations`, `GET /api/consultations/{code}/status` (cho guest và user)
- **API admin**: `GET/PATCH /api/admin/consultations` (list, update status)
- **Notify**: OutboxEvent → RabbitMQ → notification-service → WebSocket `/topic/admin/consultations`
- **FE user**: 1 modal trên TourDetail, thay onClick nút phone
- **FE admin**: trang mới `/admin/consultations`, sidebar badge real-time
- **Effort**: ~3 ngày (1 BE core + 1 notify+FE user + 1 FE admin)
- **Phase 2**: convert → booking, SLA alert, dashboard analytics
