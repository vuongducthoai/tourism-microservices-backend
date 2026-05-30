# Kế hoạch cải tiến Quản lý Forum (Admin)

> Tài liệu audit + đề xuất cải tiến cho module **Admin Forum Management**.
> Mục tiêu: vá các điểm bất hợp lý hiện tại, bổ sung tính năng còn thiếu cho một hệ thống kiểm duyệt forum hoàn chỉnh.
>
> **Phạm vi**: `forum-service` (backend) + `AdminComponent/Pages/ForumManagement` (frontend)

---

## Mục lục

1. [Tóm tắt vấn đề](#1-tóm-tắt-vấn-đề)
2. [Vấn đề CẦN SỬA GẤP (bugs / inconsistency)](#2-vấn-đề-cần-sửa-gấp)
3. [Tính năng còn THIẾU](#3-tính-năng-còn-thiếu)
4. [Vấn đề UX](#4-vấn-đề-ux)
5. [Bảo mật & toàn vẹn dữ liệu](#5-bảo-mật--toàn-vẹn-dữ-liệu)
6. [Roadmap đề xuất](#6-roadmap-đề-xuất)
7. [Chi tiết kỹ thuật từng hạng mục](#7-chi-tiết-kỹ-thuật-từng-hạng-mục)

---

## 1. Tóm tắt vấn đề

Hệ thống admin forum hiện tại đã có **nền tảng tốt**: CRUD posts/comments/categories/tags, filter mạnh, bulk action, dashboard analytics, AI moderation. **Nhưng** có nhiều điểm bất hợp lý:

| Nhóm | Mức độ | Số vấn đề |
|---|---|---|
| 🔴 Inconsistency / Bug | Cao | 5 |
| 🟡 Tính năng thiếu | Trung bình-Cao | 8 |
| 🔵 UX | Trung bình | 9 |
| 🟣 Bảo mật / Audit | Cao | 3 |
| 🐛 Lỗ hổng moderation / spam | RẤT CAO | 2 |

**5 vấn đề nghiêm trọng nhất cần ưu tiên:**
1. **🐛 Comment TOXIC/PENDING vẫn hiển thị công khai** → AI đã chấm toxic + set status HIDDEN/PENDING_REVIEW nhưng query hiển thị comment KHÔNG lọc theo status → comment độc hại vẫn hiện cho mọi user! (lỗ hổng nghiêm trọng)
2. **🐛 Không có rate-limit chống spam** → user có thể spam comment / bài viết không giới hạn trong 1 ngày
3. **Comment hiển thị phẳng, rời rạc** (phía admin) → toàn bộ comment đổ chung 1 bảng, không gom theo bài
4. **Không có nút quay về trang trước** ở quản lý bài viết & comment
5. **Hai hệ thống moderation song song** → logic duyệt bị trùng/xung đột

---

## 2. Vấn đề CẦN SỬA GẤP

### 2.1. 🔴 Hai controller moderation trùng lặp

**Hiện trạng:**
- `AdminForumController` (`/api/admin/forum/**`) có `PUT /posts/{id}/status` để duyệt/ẩn
- `AdminModerationController` (`/api/admin/moderation/**`) có `POST /posts/{id}/approve` riêng
- → 2 nơi cùng làm 1 việc → dễ lệch logic, khó maintain

**Giải pháp:**
- **Gộp về 1 controller duy nhất**: `AdminForumController`
- Deprecate `AdminModerationController` (giữ lại endpoint cũ redirect/forward sang controller mới trong 1 sprint rồi xóa)
- Mọi action duyệt/ẩn/xóa đi qua 1 service method thống nhất `moderatePost(postId, action, adminId, reason)`

**Effort**: 1 ngày

---

### 2.2. 🔴 Comment hiển thị PHẲNG, RỜI RẠC — không gom theo bài viết (ưu tiên CAO)

**Hiện trạng (bất hợp lý lớn nhất của Comment Management):**
- `AdminCommentManagement.jsx` đổ **toàn bộ comment của TẤT CẢ bài viết** vào 1 bảng phẳng, phân trang theo thời gian
- Admin nhìn thấy: comment A của bài 1, comment B của bài 5, comment C của bài 2... lẫn lộn
- → **Mất ngữ cảnh hoàn toàn**: không biết comment thuộc bài nào, ai reply ai, cuộc hội thoại ra sao
- `AdminCommentFilterRequest` có field `postId` nhưng UI không expose → không lọc theo bài được
- Với forum nhiều comment → bảng dài vô tận, không thể kiểm duyệt hiệu quả

**Giải pháp — gom comment theo bài viết (2 cách, nên làm cả 2):**

**Cách A — Group view (mặc định):**
- Đổi UI comment thành **danh sách bài viết có comment**, mỗi bài là 1 accordion/card:
  ```
  ▼ 📄 "Kinh nghiệm du lịch Đà Lạt" — 12 bình luận (2 chờ duyệt)
       ├─ 💬 Nguyễn A: "Bài hay quá!"              [Duyệt][Ẩn]
       ├─ 💬 Trần B: "Cho hỏi giá phòng?"          [Duyệt][Ẩn]
       │    └─ ↳ Lê C (reply): "Khoảng 500k/đêm"   [Duyệt][Ẩn]
       └─ ...
  ▶ 📄 "Review tour Hạ Long" — 5 bình luận
  ▶ 📄 "Hỏi đáp Phú Quốc" — 8 bình luận (1 chờ duyệt)
  ```
- Click vào bài → expand hiện cây comment của bài đó (gồm reply lồng nhau)
- Mỗi bài hiện badge số comment + số comment chờ duyệt → admin biết bài nào cần xử lý

**Cách B — Filter theo postId (bổ trợ):**
- Thêm input/dropdown filter "Chọn bài viết" trong UI
- Từ trang quản lý bài viết: click số comment của 1 bài → nhảy sang comment management đã filter sẵn postId đó (`/admin/forum/comments?postId=123`)

**Backend cần thêm:**
- `GET /api/admin/forum/posts-with-comments` → trả danh sách bài + số comment + số chờ duyệt (cho group view)
- Endpoint comment hiện tại đã support `postId` filter — chỉ cần expose ra UI

**Lợi ích**: Admin kiểm duyệt theo ngữ cảnh từng bài, thấy rõ cuộc hội thoại, không bị ngợp

**Effort**: 2-3 ngày

---

### 2.3. 🔴 Thiếu nút "Quay về" / breadcrumb điều hướng

**Hiện trạng:**
- Vào `/admin/forum/posts` hoặc `/admin/forum/comments` → **không có nút quay về Dashboard forum**
- Admin phải dùng nút Back trình duyệt hoặc sidebar → trải nghiệm cụt
- Không có breadcrumb thể hiện đang ở đâu trong cây navigation

**Giải pháp:**
- Thêm **breadcrumb** ở đầu mỗi trang con:
  ```
  Forum  ›  Quản lý bài viết
  Forum  ›  Kiểm duyệt bình luận  ›  Bài "Kinh nghiệm Đà Lạt"
  ```
- Nút **"← Quay về tổng quan"** ở góc trái page header, link về `/admin/forum`
- Breadcrumb item click được để nhảy về cấp trên
- Áp dụng cho cả 5 trang con (posts, comments, categories, tags, audit-log)

**Effort**: 0.5 ngày (component breadcrumb dùng chung)

---

### 2.4. 🔴 Filter `postId` ở Comments không được expose

(Gộp vào giải pháp 2.2 — Cách B)

---

### 2.5. 🔴 Moderation badge không giải thích ý nghĩa score

**Hiện trạng:**
- Badge hiện `0.85 TOXIC` nhưng admin mới không biết 0.0 = an toàn, 1.0 = độc hại
- Không có tooltip giải thích

**Giải pháp:**
- Thêm tooltip hover: "Điểm vi phạm 0.0 (an toàn) → 1.0 (độc hại). AI đánh giá: {reason}"
- Color scale: xanh (< 0.3) / vàng (0.3-0.7) / đỏ (≥ 0.7)

**Effort**: 0.5 ngày

---

### 2.6. 🐛 Comment TOXIC / PENDING vẫn hiển thị công khai (LỖ HỔNG NGHIÊM TRỌNG — sửa ngay)

**Hiện trạng (đã xác minh trong code):**
- `addComment()` ĐÃ chạy AI moderation: TOXIC → `status = HIDDEN`, BORDERLINE → `status = PENDING_REVIEW` (đúng)
- **NHƯNG** query hiển thị comment **KHÔNG lọc theo status**:
  - `PostCommentRepository.findTopLevelByPost()` chỉ filter `isDeleted`, **bỏ qua status**
  - `mapToCommentResponse()` load `replies` cũng chỉ filter `isDeleted`, **bỏ qua status**
- → **Hậu quả**: comment bị AI đánh dấu TOXIC vẫn hiện cho TẤT CẢ user trên trang chi tiết bài viết. AI moderation comment **vô tác dụng** ở tầng hiển thị!

**Khác biệt với bài Post:** Post hiển thị qua `getPosts()` có filter `status = PUBLISHED` → bài toxic bị ẩn đúng. Comment thì KHÔNG có filter tương đương → đây là điểm bất nhất.

**Giải pháp:**

1. **Sửa query `findTopLevelByPost`** — chỉ lấy comment PUBLISHED:
```java
@Query("""
    SELECT c FROM PostComment c
    WHERE c.post = :post
      AND c.parentComment IS NULL
      AND c.status = 'PUBLISHED'
      AND (c.isDeleted IS NULL OR c.isDeleted = false)
    ORDER BY c.createdAt ASC
    """)
List<PostComment> findTopLevelByPost(@Param("post") ForumPost post);
```

2. **Sửa filter replies trong `mapToCommentResponse`** — bỏ comment không PUBLISHED:
```java
List<CommentResponse> replies = comment.getReplies() == null ? List.of() :
    comment.getReplies().stream()
        .filter(r -> r.getIsDeleted() == null || !r.getIsDeleted())
        .filter(r -> ContentStatus.PUBLISHED.equals(r.getStatus()))  // ⬅ THÊM
        .sorted(...)
        .map(r -> mapToCommentResponse(r, currentUserId))
        .collect(Collectors.toList());
```

3. **Comment PENDING_REVIEW**: ngoại lệ — cho chính tác giả comment thấy comment của mình (với badge "Đang chờ duyệt") nhưng user khác không thấy. (Tùy chọn, giống cơ chế bài viết của user). Nếu muốn đơn giản: PENDING cũng ẩn hoàn toàn cho đến khi admin duyệt.

4. **commentCount**: hiện `addComment` chỉ tăng count khi không TOXIC — đúng. Nhưng cần kiểm tra: khi comment PENDING_REVIEW có nên tăng commentCount không? → **Không**, chỉ tăng khi PUBLISHED để số đếm khớp số comment hiển thị.

**Lợi ích**: Comment độc hại thực sự bị chặn ở tầng hiển thị, AI moderation phát huy tác dụng

**Effort**: 0.5 ngày (sửa query + filter, test kỹ)

---

### 2.7. 🐛 Không có rate-limit / chống spam comment & bài viết

**Hiện trạng:**
- User có thể gọi `createPost` / `addComment` **không giới hạn** → spam hàng loạt trong vài giây
- AI moderation chỉ chặn nội dung độc hại, KHÔNG chặn spam nội dung "sạch" lặp lại (vd: comment "hay quá" 100 lần)
- Không có giới hạn số bài/comment mỗi ngày

**Giải pháp — Rate limiting nhiều tầng (dùng Redis đã có sẵn):**

**Tầng 1 — Throttle theo thời gian (chống spam burst):**
- Comment: tối thiểu cách nhau **15 giây** giữa 2 comment
- Bài viết: tối thiểu cách nhau **60 giây** giữa 2 bài
- Lưu timestamp comment/post cuối của user vào Redis (`forum:lastComment:{userId}`, TTL ngắn)

**Tầng 2 — Quota theo ngày:**
- Tối đa **30 comment/ngày**, **5 bài viết/ngày** cho user thường
- Redis counter key `forum:dailyComments:{userId}:{date}` với TTL hết ngày
- Vượt quota → trả lỗi rõ ràng: *"Bạn đã đạt giới hạn 30 bình luận/ngày. Vui lòng thử lại vào ngày mai."*

**Tầng 3 — Chống duplicate content:**
- Hash nội dung comment gần nhất → nếu user gửi comment **trùng y hệt** trong 5 phút → chặn ("Bạn vừa gửi nội dung này rồi")

**Implementation (Redis):**
```java
// ForumRateLimitService.java
@Service
@RequiredArgsConstructor
public class ForumRateLimitService {
    private final StringRedisTemplate redis;

    @Value("${forum.ratelimit.comment-cooldown-sec:15}")
    private long commentCooldown;
    @Value("${forum.ratelimit.post-cooldown-sec:60}")
    private long postCooldown;
    @Value("${forum.ratelimit.max-comments-per-day:30}")
    private int maxCommentsPerDay;
    @Value("${forum.ratelimit.max-posts-per-day:5}")
    private int maxPostsPerDay;

    /** Gọi đầu addComment(). Throw nếu vi phạm. */
    public void checkCommentLimit(Integer userId) {
        // 1. Cooldown
        String cdKey = "forum:cd:comment:" + userId;
        if (Boolean.FALSE.equals(redis.opsForValue()
                .setIfAbsent(cdKey, "1", Duration.ofSeconds(commentCooldown)))) {
            throw new RuntimeException("Bạn bình luận quá nhanh. Vui lòng đợi vài giây.");
        }
        // 2. Daily quota
        String dayKey = "forum:daily:comment:" + userId + ":" + LocalDate.now();
        Long count = redis.opsForValue().increment(dayKey);
        if (count != null && count == 1L) {
            redis.expire(dayKey, Duration.ofDays(1));
        }
        if (count != null && count > maxCommentsPerDay) {
            throw new RuntimeException(
                "Bạn đã đạt giới hạn " + maxCommentsPerDay + " bình luận/ngày.");
        }
    }

    public void checkPostLimit(Integer userId) {
        String cdKey = "forum:cd:post:" + userId;
        if (Boolean.FALSE.equals(redis.opsForValue()
                .setIfAbsent(cdKey, "1", Duration.ofSeconds(postCooldown)))) {
            throw new RuntimeException("Bạn đăng bài quá nhanh. Vui lòng đợi 1 phút.");
        }
        String dayKey = "forum:daily:post:" + userId + ":" + LocalDate.now();
        Long count = redis.opsForValue().increment(dayKey);
        if (count != null && count == 1L) redis.expire(dayKey, Duration.ofDays(1));
        if (count != null && count > maxPostsPerDay) {
            throw new RuntimeException(
                "Bạn đã đạt giới hạn " + maxPostsPerDay + " bài viết/ngày.");
        }
    }

    /** Chống duplicate: hash nội dung, chặn nếu trùng trong 5 phút */
    public void checkDuplicate(Integer userId, String content) {
        String hash = Integer.toHexString(content.trim().toLowerCase().hashCode());
        String key = "forum:dup:" + userId + ":" + hash;
        if (Boolean.FALSE.equals(redis.opsForValue()
                .setIfAbsent(key, "1", Duration.ofMinutes(5)))) {
            throw new RuntimeException("Bạn vừa gửi nội dung này rồi.");
        }
    }
}
```

**Gọi trong ForumServiceImpl:**
```java
public PostDetailResponse addComment(Integer postId, CommentRequest request) {
    rateLimitService.checkCommentLimit(request.getUserId());   // ⬅ đầu method
    rateLimitService.checkDuplicate(request.getUserId(), request.getContent());
    // ... logic cũ
}

public PostDetailResponse createPost(CreatePostRequest request) {
    if (!Boolean.TRUE.equals(request.getIsDraft())) {          // draft không tính quota
        rateLimitService.checkPostLimit(request.getUserId());
    }
    // ... logic cũ
}
```

**Lưu ý quota khác nhau theo tier** (nếu sau này có loyalty tier): Gold/Platinum có quota cao hơn. Hiện tại để mặc định.

**Config thêm `application.yml`:**
```yaml
forum:
  ratelimit:
    comment-cooldown-sec: 15
    post-cooldown-sec: 60
    max-comments-per-day: 30
    max-posts-per-day: 5
```

**Lợi ích**: Chặn spam hiệu quả, bảo vệ chất lượng forum + giảm tải AI moderation (mỗi comment đều tốn 1 lần gọi Groq)

**Effort**: 1 ngày

---

## 3. Tính năng còn THIẾU

### 3.1. 🟡 Post Detail / Preview Modal (ưu tiên CAO)

**Vấn đề**: Admin chỉ thấy tiêu đề trong bảng, muốn xem nội dung phải mở tab forum → nhưng bài HIDDEN/PENDING không hiển thị trên forum công khai → **không xem được nội dung cần duyệt!**

**Giải pháp:**
- Backend: `GET /api/admin/forum/posts/{postId}/detail` → trả full content + images + tags + tác giả + lịch sử moderation + comments count
- Frontend: Modal/drawer hiện:
  - Nội dung đầy đủ (render HTML như forum thật)
  - Ảnh, tags, danh mục
  - Thông tin tác giả (tên, email, số bài đã đăng, số bài bị ẩn trước đó)
  - **AI moderation panel**: score, label, reason
  - Action buttons ngay trong modal: Duyệt / Ẩn / Xóa / Sửa nội dung
- **Lợi ích**: Admin duyệt được ngay trong 1 màn hình, không cần rời trang

**Effort**: 2-3 ngày

---

### 3.2. 🟡 Comment Thread View (xem cây bình luận)

**Vấn đề**: Comment có `parentComment`/`replies` nhưng UI hiển thị phẳng → không thấy ngữ cảnh reply

**Giải pháp:**
- Backend: `GET /api/admin/forum/comments/{commentId}/thread` → trả comment + parent + tất cả replies
- Frontend: expand row hiện cây comment có indent rõ ràng (comment gốc → reply con)
- Hiện cả comment đã bị ẩn trong thread để admin thấy ngữ cảnh

**Effort**: 1.5 ngày

---

### 3.3. 🟡 Audit Log (nhật ký kiểm duyệt) — ưu tiên CAO

**Vấn đề**: Không biết AI hay admin nào đã duyệt/ẩn/xóa, khi nào, vì sao → không truy vết, không chống lạm quyền

**Giải pháp:**
- Entity mới `ModerationAuditLog`:
  ```
  id, targetType (POST|COMMENT), targetId,
  action (APPROVE|HIDE|DELETE|PIN|FEATURE|STATUS_CHANGE),
  actorType (AI|ADMIN), actorId, actorName,
  oldValue, newValue, reason, createdAt
  ```
- Mọi action admin/AI tự động ghi 1 dòng log
- Trang `/admin/forum/audit-log` filter theo actor/action/date/target
- **Lợi ích**: Compliance, truy vết, biết admin nào hoạt động nhiều

**Effort**: 3-4 ngày

---

### 3.4. 🟡 User Forum Management (quản lý người dùng trong forum)

**Vấn đề**: User spam/đăng bài độc hại liên tục nhưng admin chỉ xóa được từng bài, không cấm được user

**Giải pháp:**
- Backend:
  - `GET /api/admin/forum/users/{userId}/activity` → lịch sử bài/comment của user, số lần vi phạm
  - `POST /api/admin/forum/users/{userId}/ban` body `{ reason, durationDays }` → cấm đăng bài/comment trong X ngày
  - `POST /api/admin/forum/users/{userId}/unban`
- Entity `ForumUserRestriction` (userId, bannedUntil, reason, bannedBy)
- ForumServiceImpl check restriction trước khi cho createPost/addComment
- Frontend: nút "Cấm user" trong post detail modal + trang quản lý user bị cấm
- **Lợi ích**: Xử lý tận gốc user vi phạm thay vì xóa từng bài

**Effort**: 1 tuần

---

### 3.5. 🟡 Report / Flagging System (báo cáo từ user)

**Vấn đề**: Moderation hiện chỉ **reactive** — chờ AI hoặc admin tự phát hiện. Không có cơ chế user báo cáo bài vi phạm.

**Giải pháp:**
- User-facing: nút "Báo cáo" trên mỗi post/comment → chọn lý do (spam, quấy rối, sai sự thật...)
- Backend: entity `ContentReport` (targetType, targetId, reporterId, reason, status, createdAt)
- Admin: queue "Bài bị báo cáo" với số lượng report → ưu tiên xử lý bài nhiều report
- Tự động ẩn tạm nếu ≥ N report (configurable)
- **Lợi ích**: Community-driven moderation, giảm tải cho AI/admin

**Effort**: 1 tuần (cả user-facing + admin)

---

### 3.6. 🟡 Structured Rejection Reasons (lý do từ chối có cấu trúc)

**Vấn đề**: Khi ẩn/xóa bài, không lưu lý do → user không biết tại sao bài bị ẩn

**Giải pháp:**
- Khi admin ẩn/xóa → bắt buộc chọn lý do từ template:
  - Ngôn từ thô tục
  - Spam / quảng cáo
  - Nội dung sai sự thật
  - Vi phạm bản quyền
  - Khác (nhập tay)
- Lý do được gửi notification cho user (qua RabbitMQ → notification-service đã có sẵn)
- **Lợi ích**: Minh bạch, user biết cách sửa

**Effort**: 2 ngày

---

### 3.7. 🟡 Admin Edit Post Content

**Vấn đề**: Admin chỉ duyệt/ẩn/xóa, không sửa được nội dung (vd: bài tốt nhưng có 1 từ nhạy cảm)

**Giải pháp:**
- `PUT /api/admin/forum/posts/{postId}/content` → sửa title/content/category/tags
- Ghi audit log "admin đã chỉnh sửa nội dung"
- Frontend: form edit trong post detail modal
- **Lợi ích**: Giữ được bài chất lượng thay vì xóa bỏ

**Effort**: 2 ngày

---

### 3.8. 🟡 Export báo cáo kiểm duyệt

**Vấn đề**: Không xuất được dữ liệu cho báo cáo định kỳ

**Giải pháp:**
- `GET /api/admin/forum/export?type=moderation&from=&to=` → CSV
- Nội dung: số bài duyệt/ẩn/xóa theo ngày, theo admin, theo lý do
- **Effort**: 2 ngày

---

## 4. Vấn đề UX

| # | Vấn đề | Giải pháp | Effort |
|---|---|---|---|
| 4.1 | Title cell bị cắt, không xem được đầy đủ | Hover tooltip + click mở detail modal | 0.5d |
| 4.2 | Nút "Xem" mở tab forum ngoài (rời rạc) | Thay bằng detail modal in-place | (gộp 3.1) |
| 4.3 | Comment preview 140 ký tự, phải expand mới thấy đủ | Expand hiện full + ngữ cảnh post | (gộp 3.2) |
| 4.4 | Pagination không hiện tổng số kết quả | Thêm "Tìm thấy X mục" | 0.5d |
| 4.5 | Không hiện thời điểm moderation (chỉ createdAt) | Thêm cột "Duyệt lúc" từ moderatedAt | 0.5d |
| 4.6 | Bulk action confusing — không rõ "reject" khác "hide" gì | Tooltip giải thích + đổi label rõ ràng | 0.5d |
| 4.7 | Không có quick-filter "Cần xử lý ngay" | Tab nhanh: Tất cả / Chờ duyệt / Bị báo cáo / Bị ẩn | 1d |

---

## 5. Bảo mật & toàn vẹn dữ liệu

### 5.1. 🟣 Soft delete không lưu ai xóa

**Hiện trạng**: `isDeleted = true` nhưng không lưu `deletedBy`, `deleteReason`

**Giải pháp**: Thêm field `deletedBy`, `deleteReason` vào ForumPost/PostComment → gắn với audit log (3.3)

**Effort**: 0.5 ngày (gộp audit log)

---

### 5.2. 🟣 Phân quyền admin chưa chi tiết (RBAC)

**Hiện trạng**: Chỉ check role `ADMIN` — STAFF cũng vào được hết

**Giải pháp**:
- Phân quyền chi tiết: `MODERATOR` (chỉ duyệt/ẩn) vs `ADMIN` (full: xóa, quản lý category/tag, ban user)
- Check ở interceptor + ẩn UI button theo role
- **Effort**: 2-3 ngày

---

### 5.3. 🟣 Bulk delete không có undo / soft confirm

**Hiện trạng**: Bulk delete chỉ có confirm dialog, không undo được

**Giải pháp**:
- Soft delete + trang "Thùng rác" (`/admin/forum/trash`) cho phép khôi phục trong 30 ngày
- Hard delete chỉ thực hiện sau 30 ngày (cron) hoặc admin chủ động "Xóa vĩnh viễn"
- **Effort**: 2 ngày

---

## 6. Roadmap đề xuất

### Sprint 0 (1.5 ngày) — VÁ LỖ HỔNG GẤP (làm trước mọi thứ)
- 🐛 2.6 Sửa query để comment TOXIC/PENDING KHÔNG hiển thị công khai — **lỗ hổng nghiêm trọng nhất**
- 🐛 2.7 Rate-limit chống spam comment/bài viết (Redis)

### Sprint 1 (4-5 ngày) — Fix điều hướng + comment gom nhóm
- ✅ 2.2 Comment gom theo bài viết (group view + filter postId) — **pain point admin lớn nhất**
- ✅ 2.3 Breadcrumb + nút "Quay về tổng quan" cho mọi trang con
- ✅ 2.5 Tooltip giải thích score
- ✅ 4.1, 4.4, 4.5, 4.6 UX nhỏ
- ✅ 4.7 Quick-filter tabs

### Sprint 2 (1 tuần) — Detail & Thread (giá trị cao nhất)
- ✅ 2.1 Gộp moderation controller
- ✅ 3.1 Post Detail/Preview Modal
- ✅ 3.2 Comment Thread View (lồng vào group view của 2.2)
- ✅ 3.6 Structured rejection reasons
- ✅ 3.7 Admin edit content

### Sprint 3 (1 tuần) — Audit & Trust
- ✅ 3.3 Audit Log đầy đủ
- ✅ 5.1 deletedBy/deleteReason
- ✅ 5.3 Thùng rác + khôi phục

### Sprint 4 (1.5 tuần) — User governance
- ✅ 3.4 Ban/restrict user forum
- ✅ 3.5 Report/flagging system
- ✅ 5.2 RBAC moderator vs admin

### Sprint 5 (3 ngày) — Báo cáo
- ✅ 3.8 Export CSV
- ✅ Dashboard nâng cao: top user vi phạm, tỉ lệ AI đúng/sai

---

## 7. Chi tiết kỹ thuật từng hạng mục

### 7.0a. Comment gom theo bài viết — Group View

**Backend — endpoint mới cho group view:**

```java
// AdminPostWithCommentsResponse.java — dòng tóm tắt mỗi bài có comment
public class AdminPostWithCommentsResponse {
    private Integer postId;
    private String title;
    private String authorName;
    private Integer totalComments;
    private Integer pendingComments;   // số comment chờ duyệt → ưu tiên xử lý
    private LocalDateTime lastCommentAt;
}
```

```java
// AdminForumController.java
// Danh sách bài có comment (cho group view, phân trang theo bài)
@GetMapping("/posts-with-comments")
public ResponseEntity<Page<AdminPostWithCommentsResponse>> getPostsWithComments(
        @RequestParam(required = false) Boolean onlyPending,  // chỉ bài có comment chờ duyệt
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(adminForumService.getPostsWithComments(onlyPending, page, size));
}

// Cây comment của 1 bài (gọi khi expand 1 bài)
@GetMapping("/posts/{postId}/comments-thread")
public ResponseEntity<List<AdminCommentTreeResponse>> getCommentsTree(@PathVariable Integer postId) {
    return ResponseEntity.ok(adminForumService.getCommentsTree(postId));
}
```

**Query gợi ý (JPQL gom comment theo bài):**
```java
@Query("""
    SELECT new com.tourism.forum.dto.response.AdminPostWithCommentsResponse(
        p.postID, p.title, p.userId,
        COUNT(c), SUM(CASE WHEN c.status = 'PENDING_REVIEW' THEN 1 ELSE 0 END),
        MAX(c.createdAt))
    FROM ForumPost p JOIN PostComment c ON c.post = p
    WHERE (c.isDeleted = false OR c.isDeleted IS NULL)
    GROUP BY p.postID, p.title, p.userId
    HAVING COUNT(c) > 0
    ORDER BY MAX(c.createdAt) DESC
""")
Page<AdminPostWithCommentsResponse> findPostsWithComments(Pageable pageable);
```

**Frontend — AdminCommentManagement.jsx redesign:**
- State: `viewMode` = `'grouped'` (mặc định) | `'flat'` (giữ view cũ làm tùy chọn)
- Grouped: render danh sách accordion, mỗi bài 1 row có badge `{pending}/{total}` comment
- Click bài → gọi `getCommentsTree(postId)` → render cây comment indent (parent → reply)
- Action duyệt/ẩn/xóa ngay trên từng comment trong cây
- Filter "Chỉ bài có comment chờ duyệt" (toggle `onlyPending`)

**AdminCommentTreeResponse** — comment + replies lồng nhau:
```java
public class AdminCommentTreeResponse {
    private Integer commentId;
    private String content;
    private String authorName;
    private String status;
    private Double moderationScore;
    private String moderationLabel;
    private LocalDateTime createdAt;
    private List<AdminCommentTreeResponse> replies;  // đệ quy
}
```

---

### 7.0b. Breadcrumb + nút Quay về — component dùng chung

```jsx
// shared/ForumBreadcrumb.jsx
import { Link } from 'react-router-dom';
import { ChevronRight, ArrowLeft } from 'lucide-react';
import styles from './shared.module.scss';

/**
 * @param {Array<{label:string, to?:string}>} items
 *   item cuối không có `to` (trang hiện tại)
 */
const ForumBreadcrumb = ({ items, backTo = '/admin/forum' }) => (
  <div className={styles.breadcrumbBar}>
    <Link to={backTo} className={styles.backBtn}>
      <ArrowLeft size={15} /> Quay về tổng quan
    </Link>
    <nav className={styles.breadcrumb}>
      {items.map((it, i) => (
        <span key={i} className={styles.crumbItem}>
          {it.to ? <Link to={it.to}>{it.label}</Link> : <span>{it.label}</span>}
          {i < items.length - 1 && <ChevronRight size={13} className={styles.crumbSep} />}
        </span>
      ))}
    </nav>
  </div>
);

export default ForumBreadcrumb;
```

**Dùng trong mỗi trang con:**
```jsx
// AdminPostManagement.jsx
<ForumBreadcrumb items={[
  { label: 'Forum', to: '/admin/forum' },
  { label: 'Quản lý bài viết' }
]} />

// AdminCommentManagement.jsx (khi đang xem comment của 1 bài)
<ForumBreadcrumb items={[
  { label: 'Forum', to: '/admin/forum' },
  { label: 'Kiểm duyệt bình luận', to: '/admin/forum/comments' },
  { label: `Bài "${postTitle}"` }
]} />
```

**SCSS (thêm vào shared.module.scss):**
```scss
.breadcrumbBar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.backBtn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px; border-radius: 9px;
  border: 1.5px solid #e2e8f0; background: #fff;
  color: #475569; font-size: 13px; font-weight: 600;
  text-decoration: none; transition: all 0.18s;
  &:hover { border-color: #1a73e8; color: #1a73e8; background: #eff6ff; }
}
.breadcrumb { display: flex; align-items: center; font-size: 13px; color: #94a3b8; }
.crumbItem { display: inline-flex; align-items: center;
  a { color: #1a73e8; text-decoration: none; font-weight: 600; &:hover { text-decoration: underline; } }
}
.crumbSep { margin: 0 4px; color: #cbd5e1; }
```

---

### 7.1. Post Detail Modal — schema response

```java
// AdminPostDetailResponse.java
public class AdminPostDetailResponse {
    private Integer postId;
    private String title;
    private String content;       // full HTML
    private String summary;
    private List<String> imageUrls;
    private List<TagInfo> tags;
    private String categoryName;
    private String status;
    private String postType;

    // Author context
    private Integer authorId;
    private String authorName;
    private String authorEmail;
    private Integer authorTotalPosts;
    private Integer authorHiddenPosts;   // số bài đã bị ẩn → đánh giá độ tin cậy

    // Moderation
    private Double moderationScore;
    private String moderationLabel;
    private String moderationReason;
    private LocalDateTime moderatedAt;

    // Stats
    private Integer viewCount, likeCount, commentCount;
    private LocalDateTime createdAt, publishedAt;
}
```

```java
// AdminForumController.java — thêm
@GetMapping("/posts/{postId}/detail")
public ResponseEntity<AdminPostDetailResponse> getPostDetail(@PathVariable Integer postId) {
    return ResponseEntity.ok(adminForumService.getPostDetail(postId));
}
```

---

### 7.2. Audit Log — entity + flow

```java
// ModerationAuditLog.java
@Entity @Table(name = "moderation_audit_log")
public class ModerationAuditLog {
    @Id @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(STRING) private TargetType targetType;  // POST, COMMENT
    private Integer targetId;

    @Enumerated(STRING) private AuditAction action;     // APPROVE, HIDE, DELETE, PIN, ...
    @Enumerated(STRING) private ActorType actorType;    // AI, ADMIN
    private Integer actorId;        // null nếu AI
    private String actorName;

    private String oldValue;
    private String newValue;
    private String reason;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

**Flow**: Mọi method trong `AdminForumServiceImpl` (changeStatus, delete, pin...) sau khi thực thi → gọi `auditLogService.record(...)`. Dùng `@Aspect` AOP để tự động hóa, tránh lặp code.

---

### 7.3. User Forum Restriction

```java
// ForumUserRestriction.java
@Entity
public class ForumUserRestriction {
    @Id @GeneratedValue private Long id;
    private Integer userId;
    private LocalDateTime bannedUntil;   // null = vĩnh viễn
    private String reason;
    private Integer bannedBy;
    private LocalDateTime createdAt;
    private Boolean active;
}
```

**Check trong `ForumServiceImpl.createPost()` / `addComment()`:**
```java
forumUserRestrictionRepo.findActiveByUserId(userId).ifPresent(r -> {
    if (r.getBannedUntil() == null || r.getBannedUntil().isAfter(now())) {
        throw new RuntimeException("Tài khoản bị hạn chế đăng bài đến "
            + r.getBannedUntil() + ". Lý do: " + r.getReason());
    }
});
```

---

### 7.4. Report System — entity

```java
// ContentReport.java
@Entity
public class ContentReport {
    @Id @GeneratedValue private Long id;
    @Enumerated(STRING) private TargetType targetType;  // POST, COMMENT
    private Integer targetId;
    private Integer reporterId;
    @Enumerated(STRING) private ReportReason reason;    // SPAM, HARASSMENT, FALSE_INFO, COPYRIGHT, OTHER
    private String detail;
    @Enumerated(STRING) private ReportStatus status;    // PENDING, RESOLVED, DISMISSED
    private LocalDateTime createdAt;
}
```

**Auto-hide rule**: cron đếm report mỗi target, nếu ≥ `report.auto-hide-threshold` (config, vd 5) → tự set status `PENDING_REVIEW` + notify admin.

---

## 8. Cấu hình mới cần thêm (application.yml)

```yaml
forum:
  moderation:
    report-auto-hide-threshold: 5      # số report tự ẩn tạm
    trash-retention-days: 30           # giữ thùng rác 30 ngày
    default-ban-days: 7                # ban mặc định 7 ngày
```

---

## 9. Lưu ý migration

- Các entity mới (`ModerationAuditLog`, `ForumUserRestriction`, `ContentReport`) → Hibernate `ddl-auto: update` tự tạo bảng
- Field mới `deletedBy`, `deleteReason` trên ForumPost/PostComment → nullable, không cần backfill
- Khi gộp moderation controller → cần update `adminForumApi.js` frontend trỏ về endpoint mới, giữ backward-compat 1 sprint

---

## 10. Ước tính tổng

| Sprint | Nội dung | Effort |
|---|---|---|
| 1 | Fix gấp + UX | 3-4 ngày |
| 2 | Detail & Thread & Edit | 1 tuần |
| 3 | Audit & Trash | 1 tuần |
| 4 | User governance & Report | 1.5 tuần |
| 5 | Export & Dashboard+ | 3 ngày |
| **Tổng** | | **~5 tuần** |

**Ưu tiên nếu chỉ làm được ít**: Sprint 1 + Sprint 2 (fix bất hợp lý + detail modal) đã giải quyết 80% pain point hiện tại.

---

**Ngày tạo**: 2026-05-26
**Reference**: audit từ `AdminForumController.java`, `AdminPostManagement.jsx`, `AdminCommentManagement.jsx`, `AdminModerationController.java`
