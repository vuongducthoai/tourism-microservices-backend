# Sprint 0 — Vá lỗ hổng gấp: AI moderation cho comment + Soft delete + Rate limit

> **Trạng thái**: ✅ Hoàn tất
> **Thời gian thực hiện**: 1.5 ngày
> **Phạm vi**: `forum-service` (backend) + `client-side/src/components/ForumComponent/PostDetail` (frontend)
> **Mục tiêu**: Vá 2 lỗ hổng nghiêm trọng nhất trước khi làm các sprint tiếp theo:
> 1. Comment bị AI đánh dấu **TOXIC** vẫn hiển thị công khai (AI moderation comment vô tác dụng ở tầng hiển thị).
> 2. Không có rate-limit → user có thể spam vô hạn comment/bài viết.
>
> Sprint 0 cũng đặt nền cho **soft delete** dùng trong Sprint 3 (Trash + Audit log).

---

## 1. Tổng quan các thay đổi

| Hạng mục | Loại | File chính |
|---|---|---|
| 1.1 Filter status PUBLISHED cho query comment | Backend (repo + service) | `PostCommentRepository.java`, `ForumServiceImpl.java` |
| 1.2 Lưu kết quả AI moderation cho comment | Backend (service) | `ForumServiceImpl.addComment()` |
| 1.3 Soft delete cho post & comment | Backend (entity + service) | `ForumPost.java`, `PostComment.java`, `BaseEntity.java` |
| 1.4 Rate-limit Redis 3 tầng | Backend (service mới) | `ForumRateLimitService.java` |
| 1.5 Endpoint `/quota` cho FE | Backend (controller) | `ForumPostController.java` |
| 1.6 Modal kết quả moderation + cảnh báo quota | Frontend | `PostDetailPage.jsx`, `PostDetailPage.module.scss` |

---

## 2. Phần 1 — Lọc comment TOXIC/PENDING khỏi tầng hiển thị

### 2.1. Vấn đề gốc

Lỗ hổng đã xác minh trong code: AI đã set `status = HIDDEN` (TOXIC) và `status = PENDING_REVIEW` (BORDERLINE), nhưng query hiển thị comment chỉ filter `isDeleted` — bỏ qua hoàn toàn cột `status`. Kết quả: comment độc hại vẫn hiện cho mọi user trên trang chi tiết bài viết.

So sánh: **bài viết** thì hiển thị qua `getPosts()` đã có filter `status = PUBLISHED` (chặn đúng). **Comment** thì không có filter tương đương → bất nhất giữa 2 luồng.

### 2.2. Fix #1 — Query top-level comments

**File**: [PostCommentRepository.java](../../forum-service/src/main/java/com/"feat(forum): admin forum management - Sprints 0 to 5tourism/forum/repository/PostCommentRepository.java) dòng 63–71

```java
@Query("""
    SELECT c FROM PostComment c
    WHERE c.post = :post
      AND c.parentComment IS NULL
      AND c.status = com.tourism.forum.entity.ContentStatus.PUBLISHED
      AND (c.isDeleted IS NULL OR c.isDeleted = false)
    ORDER BY c.createdAt ASC
    """)
List<PostComment> findTopLevelByPost(@Param("post") ForumPost post);
```

**Giải thích từng dòng:**
- `c.parentComment IS NULL` — chỉ lấy comment gốc, replies sẽ được nạp lazy qua quan hệ JPA và lọc riêng (xem 2.3).
- `c.status = ContentStatus.PUBLISHED` — **dòng vá lỗ hổng chính**: TOXIC/PENDING_REVIEW không lọt qua.
- `(c.isDeleted IS NULL OR c.isDeleted = false)` — vẫn giữ filter `isDeleted` cũ, dùng `IS NULL` để tương thích với hàng cũ chưa có cột (DB migrate).
- Dùng FQN `com.tourism.forum.entity.ContentStatus.PUBLISHED` để JPQL tham chiếu chính xác enum, tránh ambiguity khi import wildcard.

### 2.3. Fix #2 — Filter replies trong mapper

**File**: [ForumServiceImpl.java](../../forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java) dòng 134–144

```java
private CommentResponse mapToCommentResponse(PostComment comment, Integer currentUserId) {
    UserBriefResponse author = getUserSafe(comment.getUserId());
    // Replies: Facebook hiển thị theo thứ tự thời gian cũ → mới
    List<CommentResponse> replies = comment.getReplies() == null ? List.of() :
        comment.getReplies().stream()
            .filter(r -> r.getIsDeleted() == null || !r.getIsDeleted())
            .filter(r -> ContentStatus.PUBLISHED.equals(r.getStatus()))   // ← THÊM ở Sprint 0
            .sorted(Comparator.comparing(
                r -> r.getCreatedAt() == null ? LocalDateTime.MIN : r.getCreatedAt()))
            .map(r -> mapToCommentResponse(r, currentUserId))
            .collect(Collectors.toList());
    ...
}
```

**Tại sao phải filter cả ở Java?** `comment.getReplies()` là collection JPA lazy-load — JPA trả về **mọi reply** chưa qua query custom. Nếu chỉ vá query top-level (2.2) mà bỏ qua replies, reply TOXIC vẫn hiện. Filter ở Java ngăn chính xác điều đó.

`ContentStatus.PUBLISHED.equals(r.getStatus())` viết theo thứ tự `enum.equals(field)` để tránh NullPointerException nếu `r.getStatus()` null.

### 2.4. Quy ước count

`commentCount` của post chỉ được tăng khi comment **PUBLISHED** thật sự. Khi AI label TOXIC hoặc BORDERLINE: lưu comment với status tương ứng, **không** tăng count, **không** publish event notification. Lý do: số đếm phải khớp số comment hiển thị, nếu không user sẽ thấy "5 bình luận" nhưng chỉ render 3 → bug UX.

Code tại [ForumServiceImpl.addComment()](../../forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java) dòng 535–545:

```java
if("TOXIC".equals(mod.getLabel())){
    comment.setStatus(ContentStatus.HIDDEN);
    // Không tăng commentCount, không gửi notification
    commentRepository.save(comment);
    return withCommentModeration(mapToDetailResponse(post, request.getUserId()), mod);
} else if ("BORDERLINE".equals(mod.getLabel())) {
    // Chờ admin duyệt — chưa hiển thị công khai, không tăng count, không notify
    comment.setStatus(ContentStatus.PENDING_REVIEW);
    commentRepository.save(comment);
    return withCommentModeration(mapToDetailResponse(post, request.getUserId()), mod);
}
// CLEAN → bình thường, tăng count, gửi notification
PostComment savedComment = commentRepository.save(comment);
post.setCommentCount(post.getCommentCount() + 1);
forumPostRepository.save(post);
```

---

## 3. Phần 2 — Lưu kết quả AI moderation lên comment

### 3.1. Lý do

Trước Sprint 0, comment chỉ có cờ `status` thô. Để admin (Sprint 2/3) và user (Sprint 0 modal) hiểu **tại sao** AI ẩn → cần lưu score + label + reason giống như post.

### 3.2. Code thực hiện

Tại [ForumServiceImpl.addComment()](../../forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java) dòng 528–533:

```java
ModerationResult mod = moderationService.analyze(stripHtml(request.getContent()), "comment");

comment.setModerationScore(mod.getScore());
comment.setModerationLabel(mod.getLabel());
comment.setModerationReason(mod.getReason());
comment.setModeratedAt(LocalDateTime.now());
```

- `stripHtml(...)` — comment có thể chứa tag (rich-text), AI chỉ cần plain text để chấm điểm. Helper đã có sẵn dùng regex `<[^>]*>`.
- `"comment"` — context để AI biết đây là comment (prompt khác với post).
- 4 setter tương ứng 4 cột mới: `moderation_score` (0.0–1.0), `moderation_label` (CLEAN/BORDERLINE/TOXIC), `moderation_reason` (text vì sao), `moderated_at` (timestamp).

### 3.3. Trả về cờ moderation cho FE

Khi comment bị block (TOXIC/BORDERLINE), service vẫn trả `PostDetailResponse` nhưng kèm 3 trường bổ sung:

```java
private PostDetailResponse withCommentModeration(PostDetailResponse resp, ModerationResult mod) {
    resp.setCommentModerationLabel(mod.getLabel());
    resp.setCommentModerationReason(mod.getReason());
    resp.setCommentModerationScore(mod.getScore());
    return resp;
}
```

→ FE đọc các trường này để bật modal cảnh báo cho user (Phần 5).

---

## 4. Phần 3 — Rate-limit Redis 3 tầng

### 4.1. Thiết kế

3 tầng phòng thủ độc lập, mỗi tầng giải quyết một dạng spam khác nhau:

| Tầng | Mục tiêu | Cơ chế Redis | Key pattern | TTL |
|---|---|---|---|---|
| **1. Cooldown** | Chặn burst | `SETNX` (set-if-absent) | `forum:cd:comment:{userId}` | 15s (comment) / 60s (post) |
| **2. Quota ngày** | Giới hạn tổng | `INCR` + `EXPIRE` | `forum:daily:comment:{userId}:{YYYY-MM-DD}` | hết ngày |
| **3. Duplicate** | Chặn copy-paste | `SETNX` với hash content | `forum:dup:{userId}:{hash}` | 5 phút |

### 4.2. File: `ForumRateLimitService.java`

**Tầng 1 — Cooldown:**
```java
String cdKey = "forum:cd:comment:" + userId;
Boolean ok = redis.opsForValue().setIfAbsent(cdKey, "1", Duration.ofSeconds(commentCooldown));
if (Boolean.FALSE.equals(ok)) {
    throw new RateLimitException("Bạn bình luận quá nhanh. Vui lòng đợi vài giây rồi thử lại.");
}
```

`setIfAbsent` là atomic Redis SETNX: nếu key đã tồn tại (TTL chưa hết) → trả `false` → user đang trong cooldown. Nếu trống → set với TTL `commentCooldown` giây.

**Tầng 2 — Quota ngày:**
```java
String dayKey = "forum:daily:comment:" + userId + ":" + LocalDate.now();
Long count = redis.opsForValue().increment(dayKey);
if (count != null && count == 1L) {
    redis.expire(dayKey, Duration.ofDays(1));
}
if (count != null && count > maxCommentsPerDay) {
    throw new RateLimitException(
        "Bạn đã đạt giới hạn " + maxCommentsPerDay + " bình luận/ngày. Vui lòng quay lại vào ngày mai.");
}
```

- `INCR` atomic, không race condition kể cả khi nhiều request song song.
- Chỉ `EXPIRE` ở lần đầu (`count == 1L`) — Redis không reset TTL khi INCR tiếp theo, nên TTL ban đầu giữ nguyên đến hết ngày.
- So sánh `> maxCommentsPerDay` không phải `>=` vì INCR đã trả counter **sau** khi tăng. Comment thứ 30 (limit=30) cho qua, comment thứ 31 mới chặn.

**Tầng 3 — Chống duplicate:**
```java
String hash = Integer.toHexString(content.trim().toLowerCase().hashCode());
String key = "forum:dup:" + userId + ":" + hash;
Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(5));
if (Boolean.FALSE.equals(ok)) {
    throw new RateLimitException("Bạn vừa gửi nội dung này rồi. Vui lòng không gửi trùng lặp.");
}
```

`trim().toLowerCase().hashCode()` cho phép chuẩn hóa: "Hay quá!" và "  hay quá!  " được coi là trùng. Hash hex để key Redis ngắn gọn. Lưu ý: `String.hashCode()` không cryptographic — chấp nhận được vì collision cùng user trong 5 phút rất hiếm và không gây bảo mật.

### 4.3. Fail-open

```java
try {
    ...
} catch (RateLimitException e) {
    throw e;
} catch (Exception e) {
    log.warn("Rate-limit Redis error (fail-open) for user {}: {}", userId, e.getMessage());
}
```

Triết lý: nếu Redis chết → cho qua, không chặn user. Tốt hơn là **không** từ chối hợp lệ do hạ tầng. Trade-off: tạm thời mở spam khi Redis down — chấp nhận được vì Sprint 0 có cảnh báo log và service-discovery sẽ alert.

### 4.4. Endpoint `/quota` để hiển thị minh bạch

```java
public QuotaStatus getQuotaStatus(Integer userId) {
    int commentsUsed = readDailyCount("forum:daily:comment:" + userId + ":" + LocalDate.now());
    int postsUsed = readDailyCount("forum:daily:post:" + userId + ":" + LocalDate.now());
    return new QuotaStatus(
        maxCommentsPerDay,
        Math.max(0, maxCommentsPerDay - commentsUsed),
        maxPostsPerDay,
        Math.max(0, maxPostsPerDay - postsUsed)
    );
}
```

Phương thức **không tăng counter** (dùng `GET` thay vì `INCR`). FE poll endpoint này để hiển thị "Còn 23/30 lượt hôm nay" trong UI — giúp user biết trước khi bị chặn.

### 4.5. Tích hợp vào `addComment()` / `createPost()`

```java
public PostDetailResponse addComment(Integer postId, CommentRequest request) {
    checkForumBan(request.getUserId());                                  // Sprint 4
    rateLimitService.checkCommentLimit(request.getUserId());             // Sprint 0
    rateLimitService.checkDuplicate(request.getUserId(), request.getContent());  // Sprint 0
    ...
}
```

Thứ tự: ban → rate-limit → duplicate → AI moderation. Mỗi bước throw `RuntimeException` riêng, controller có `@ExceptionHandler` map sang HTTP 429 (rate limit) hoặc 400 (ban/duplicate).

### 4.6. Cấu hình

Các giá trị mặc định nằm trong `application.yml`:

```yaml
forum:
  ratelimit:
    enabled: true
    comment-cooldown-sec: 15
    post-cooldown-sec: 60
    max-comments-per-day: 30   # Sau Sprint 1 tăng lên 100
    max-posts-per-day: 5       # Sau Sprint 1 tăng lên 10
```

---

## 5. Phần 4 — Soft delete làm nền cho Sprint 3

### 5.1. Thiết kế

Mọi entity forum extends `BaseEntity` có sẵn cờ `isDeleted` + `deletedAt`. Sprint 0 chuẩn hóa:
- **Không bao giờ** `repository.delete(...)` (hard delete) cho post/comment.
- Set `isDeleted = true`, `deletedAt = now()` thay thế.
- Mọi query đọc cộng thêm `(isDeleted IS NULL OR isDeleted = false)`.

### 5.2. Lý do

- **Bảo toàn FK**: post bị xóa nhưng vẫn còn likes/comments/tags trỏ tới → hard delete sẽ cascade hỏng dữ liệu liên kết.
- **Restore được**: Sprint 3 sẽ thêm thùng rác cho admin khôi phục — chỉ khả thi nếu data còn.
- **Audit**: lưu lại "ai xóa, khi nào, lý do gì" — Sprint 3 sẽ ghi vào `ModerationAuditLog`.

### 5.3. Quy ước query

JPQL chuẩn dùng:
```jpql
AND (c.isDeleted IS NULL OR c.isDeleted = false)
```
- `IS NULL` cho hàng cũ chưa có giá trị (DB migrate Hibernate `ddl-auto: update` mặc định để null).
- `= false` cho hàng mới.

Mọi repo hiển thị (list post, list comment, count, search) đều áp filter này. Mọi query của admin trash thì ngược lại: `WHERE isDeleted = true`.

---

## 6. Phần 5 — Frontend: modal kết quả moderation cho user

### 6.1. Vấn đề UX

Trước Sprint 0, khi comment của user bị AI ẩn → user **không** thấy comment hiện ra, không biết tại sao (BE thì biết, FE không hiển thị). User nghĩ là bug → spam thêm.

### 6.2. State trong PostDetailPage

[PostDetailPage.jsx](../../d:/fronend-new/tourism_frontend/client-side/src/components/ForumComponent/PostDetail/PostDetailPage.jsx) dòng 47–48:

```jsx
// Modal kết quả kiểm duyệt comment (TOXIC / BORDERLINE) + policy
const [commentModResult, setCommentModResult] = useState(null); // { label, reason, score }
const [showCommentPolicy, setShowCommentPolicy] = useState(false);
```

Khi gọi `POST /forum/posts/{id}/comments`, BE trả về `PostDetailResponse` kèm 3 trường `commentModerationLabel/Reason/Score` nếu comment bị block. FE check:

```jsx
const res = await axios.post(`/forum/posts/${postId}/comments`, payload);
const updated = res.data?.data;
if (updated?.commentModerationLabel === 'TOXIC' || updated?.commentModerationLabel === 'BORDERLINE') {
    setCommentModResult({
        label: updated.commentModerationLabel,
        reason: updated.commentModerationReason,
        score: updated.commentModerationScore,
    });
}
```

### 6.3. Modal render

Modal có 2 biến thể:
- **TOXIC** (đỏ): "Bình luận đã bị ẩn do vi phạm tiêu chuẩn cộng đồng"
- **BORDERLINE** (vàng): "Bình luận đang chờ admin duyệt"

Bar điểm số: `width: ${score * 100}%` với màu đỏ/vàng tương ứng. Reason hiển thị nguyên văn từ AI (vd "Chứa từ ngữ xúc phạm").

### 6.4. Cảnh báo quota minh bạch

FE gọi `GET /forum/posts/quota` khi load trang để hiển thị badge:

```jsx
{quota && (
    <span className={`${styles.quotaBadge} ${quota.remainingComments <= 10 ? styles.quotaBadgeLow : ''}`}>
        <MessageCircle size={12} />
        Còn {quota.remainingComments}/{quota.maxCommentsPerDay} lượt hôm nay
    </span>
)}
```

Khi `remainingComments <= 10` → badge đổi sang style cảnh báo (vàng).

### 6.5. Xử lý lỗi rate-limit

Khi BE trả 429:
```jsx
} catch (err) {
    const status = err.response?.status;
    const msg = err.response?.data?.message;
    if (status === 429) {
        showNotice('warning', 'Thao tác quá nhanh', msg || 'Bạn thao tác quá nhanh. Vui lòng thử lại sau.');
    } else if (msg) {
        showNotice('error', 'Không thể gửi trả lời', msg);
    } else {
        showNotice('error', 'Lỗi', 'Không thể gửi trả lời. Vui lòng thử lại.');
    }
}
```

`showNotice(...)` là modal thông báo chung — thay cho `alert()` thô, đồng nhất UI.

---

## 7. Kết quả & Verification

### 7.1. Kiểm thử thủ công

| Kịch bản | Kết quả mong đợi | Trạng thái |
|---|---|---|
| User comment "Đồ ngu!" | AI label TOXIC → comment không hiện ra cho ai → modal đỏ cho user | ✅ |
| User comment có từ borderline | AI label BORDERLINE → comment status PENDING_REVIEW → modal vàng | ✅ |
| User comment 2 lần liên tiếp trong 15s | Lần 2 nhận HTTP 429 "Bạn bình luận quá nhanh" | ✅ |
| User comment đúng nội dung 2 lần trong 5 phút | Lần 2 nhận HTTP 400 "Bạn vừa gửi nội dung này rồi" | ✅ |
| User comment lần 31 trong ngày | HTTP 429 "Bạn đã đạt giới hạn 30 bình luận/ngày" | ✅ |
| Redis tắt → user comment | Cho qua (fail-open) + log warn | ✅ |

### 7.2. Verify trên DB

```sql
SELECT comment_id, status, moderation_label, moderation_score, is_deleted
FROM post_comments
ORDER BY created_at DESC LIMIT 10;
```

Comment TOXIC: `status='HIDDEN', moderation_label='TOXIC', moderation_score>=0.7, is_deleted=false` (vẫn còn để admin xem trong Sprint 3).

### 7.3. Verify Redis

```bash
redis-cli KEYS "forum:*"
# Có thấy: forum:cd:comment:{id}, forum:daily:comment:{id}:{date}, forum:dup:{id}:{hash}
redis-cli TTL "forum:cd:comment:1"   # ~ 15 hoặc thấp hơn
redis-cli GET "forum:daily:comment:1:2026-05-30"   # số counter
```

---

## 8. Tác động lên các sprint sau

| Sprint sau | Phụ thuộc Sprint 0 |
|---|---|
| Sprint 1 (quota minh bạch + tăng limit) | Dùng `getQuotaStatus()` đã có; chỉ chỉnh config `max-comments-per-day: 100`, `max-posts-per-day: 10` |
| Sprint 2 (group view + thread) | Filter `status = PUBLISHED` đã áp dụng → group view của admin **phải dùng query riêng** không filter status để xem được TOXIC/PENDING |
| Sprint 3 (Trash) | Soft delete đã chuẩn hóa → query trash chỉ cần `WHERE is_deleted = true` |
| Sprint 4 (Ban + Report) | `checkForumBan()` gọi **trước** `checkCommentLimit` trong addComment → ban user không tiêu quota |

---

## 9. Files đã chạm

**Backend:**
- `forum-service/src/main/java/com/tourism/forum/repository/PostCommentRepository.java` — query filter status
- `forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java` — addComment + mapToCommentResponse + withCommentModeration + integration rate-limit
- `forum-service/src/main/java/com/tourism/forum/service/ForumRateLimitService.java` — **NEW** — toàn bộ logic Redis
- `forum-service/src/main/java/com/tourism/forum/controller/ForumPostController.java` — endpoint `/quota`, exception handler 429
- `forum-service/src/main/resources/application.yml` — config `forum.ratelimit.*`

**Frontend:**
- `client-side/src/components/ForumComponent/PostDetail/PostDetailPage.jsx` — modal `commentModResult` + badge quota + xử lý 429
- `client-side/src/components/ForumComponent/PostDetail/PostDetailPage.module.scss` — style modHeader (đỏ/vàng) + quotaBadge + modScoreFill

---

## 10. Bài học rút ra

1. **Đối xứng query post vs comment**: nếu post lọc `status=PUBLISHED` thì comment cũng phải lọc — bất nhất là nguồn lỗi bảo mật. Khi đọc code, mọi lệnh "list X" cần được audit cùng một bộ filter.
2. **Fail-open cho rate-limit hợp lý** với hạ tầng phụ trợ (Redis) — tốt hơn fail-closed làm hỏng UX cả site khi Redis chết.
3. **Modal > alert**: ngay từ Sprint 0 đã chuẩn hóa dùng `showNotice` thay `alert()` — giúp các sprint sau (Sprint 4: report modal) tái sử dụng cùng style.
4. **Soft delete là nền móng**: quyết định "không bao giờ hard delete" ở Sprint 0 cho phép Sprint 3 làm Trash + Sprint 3 ghi audit log mà không phải migrate dữ liệu.
