# Forum — User-Side Features: Gaps & Improvement Plan

> **Phạm vi**: Các tính năng phía **user** trong forum (không phải admin) — share / bookmark / pin / điều hướng / theo dõi.
> **Mục đích**: Vá những tính năng đang **dở dang hoặc chỉ làm một nửa** trong code hiện tại.
> **Phương pháp**: Audit code thật → liệt kê gap → đề xuất giải pháp ngắn, đủ dùng (không over-engineer).

---

## 1. Tổng quan hiện trạng (từ audit code)

| Tính năng | BE Entity/Endpoint | FE UI | Trạng thái thực tế |
|---|---|---|---|
| **Bookmark** | ✅ `PostBookmark`, toggle + check endpoint | ⚠ Nút có ở PostCard, ❌ thiếu ở PostDetail, ❌ thiếu trang "Bookmark của tôi" | **Bookmark được nhưng không xem lại được** |
| **Pin** | ✅ field `isPinned`, admin pin/unpin + audit log | ✅ Badge "📌 Ghim" hiển thị | **Pin không ảnh hưởng thứ tự list công khai** — chỉ là cosmetic |
| **Share** | ⚠ Có cột `shareCount` nhưng không có entity/endpoint/UI | ❌ Không có nút Share đâu cả | **Stub bị bỏ giữa chừng** |
| **My posts / Drafts** | ✅ Có endpoint `/user/{id}/manage` | ⚠ Có page nhưng không link từ forum nav | **User khó tìm vào** |
| **Following / Notification feed** | ✅ `FollowerRepository` tồn tại | ❌ Không có UI follow user, không có feed riêng | **Backend tồn tại, FE chưa wire** |

---

## 2. Vấn đề 1 — Bookmark không xem lại được

### 2.1. Hiện trạng

- **Backend**: `PostBookmark` entity OK, `toggleBookmark()` + `checkBookmarkStatus()` OK ở `ForumPostController` line 173-187. **Nhưng** không có method `getBookmarkedPosts(userId)` — đây là điểm thiếu nghiêm trọng nhất.
- **Frontend**:
  - `PostCard` có nút bookmark (icon + toggle) — line 48-62, 164-170
  - `PostDetailPage` chỉ `import { Bookmark } from 'lucide-react'` ở line 9 nhưng **không render và không có handler** — user vào trang chi tiết không thấy nút bookmark
  - **Không có route** `/forum/bookmarks` hay tab "Đã lưu" trong `ForumPage` filter tabs

### 2.2. Giải pháp

**Backend (1 endpoint mới):**

```java
// ForumService.java
Page<PostListResponse> getBookmarkedPosts(Integer userId, Pageable pageable);

// ForumServiceImpl
public Page<PostListResponse> getBookmarkedPosts(Integer userId, Pageable pageable) {
    return postBookmarkRepository.findPostsByUserId(userId, pageable)
            .map(p -> mapToListResponse(p, /*currentUserId*/ userId, /*cache*/ new HashMap<>()));
}

// PostBookmarkRepository
@Query("SELECT b.post FROM PostBookmark b WHERE b.userId = :userId " +
       "AND b.post.isDeleted = false AND b.post.status = 'PUBLISHED' " +
       "ORDER BY b.createdAt DESC")
Page<ForumPost> findPostsByUserId(@Param("userId") Integer userId, Pageable pageable);

// ForumPostController
@GetMapping("/bookmarks")
public ResponseEntity<?> getMyBookmarks(@RequestParam Integer userId,
                                        @RequestParam(defaultValue="0") int page,
                                        @RequestParam(defaultValue="10") int size) {
    return ResponseEntity.ok(Map.of("success", true,
        "data", forumService.getBookmarkedPosts(userId, PageRequest.of(page, size))));
}
```

**Frontend (1 trang + 1 nav link + 1 nút):**

1. Tạo `BookmarkedPosts.jsx` clone `UserPostsManagement` layout, gọi `/forum/posts/bookmarks?userId=...`
2. Thêm route `/forum/bookmarks` trong router
3. Trong `ForumPage` filter tabs thêm tab **"Đã lưu"** (chỉ hiện nếu user logged-in)
4. **Thêm nút Bookmark trong `PostDetailPage`** ở `actionsBar` (cạnh nút Like + Báo cáo) — clone logic `handleLikeToggle` + state `bookmarked`/`bookmarkCount`

**Effort**: 0.5 ngày BE + 0.5 ngày FE = 1 ngày

---

## 3. Vấn đề 2 — Pin không ảnh hưởng thứ tự list công khai

### 3.1. Hiện trạng

- Admin pin được, có audit log PIN/UNPIN (Sprint 3) ✅
- `PostCard` hiển thị badge "📌 Ghim" ✅
- **Nhưng** `forumPostRepository.findAll(spec, pageable)` ở `getPosts()` chỉ sort theo `Pageable` từ FE (createdAt / viewCount / likeCount) — **không** đẩy pinned lên đầu
- → Admin pin xong, post pinned vẫn trộn lẫn dưới hàng → action pin **vô nghĩa với user**

### 3.2. Giải pháp

**Cách 1 — Patch tại service (đơn giản, không động JPA spec):**

```java
// ForumServiceImpl.getPosts()
public Page<PostListResponse> getPosts(PostFilterRequest filter, Pageable pageable, Integer currentUserId) {
    // Force isPinned DESC làm sort đầu tiên
    Sort sortWithPin = Sort.by(Sort.Direction.DESC, "isPinned").and(pageable.getSort());
    Pageable pageableWithPin = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortWithPin);
    Page<ForumPost> page = forumPostRepository.findAll(
        ForumPostSpecifications.publishedAndVisible(filter), pageableWithPin);
    ...
}
```

**Cách 2 — Cho admin chọn "Pin có expiry"** (nice-to-have, không bắt buộc Sprint này):
- Thêm cột `pinnedUntil` LocalDateTime, null = vĩnh viễn
- Cron job daily set `isPinned=false` cho các post `pinnedUntil < now()`

**Lưu ý UX**: chỉ pin **2-3 post tối đa** lên đầu để tránh ngộp. Có thể thêm `findByIsPinnedTrue()` riêng, FE render thành dải "Tin ghim" tách biệt khỏi list chính (giống Facebook group).

**Effort**: 0.5 ngày BE (cách 1) hoặc 1 ngày BE (cách 2)

---

## 4. Vấn đề 3 — Share không có gì cả

### 4.1. Hiện trạng

- `ForumPost.shareCount` field tồn tại nhưng không có entity `PostShare`, không có endpoint, không có nút trên UI
- → `shareCount` luôn = 0 → giá trị 0% cho user

### 4.2. Giải pháp — Share kiểu nhẹ (không cần entity)

Không cần entity `PostShare` riêng — share = action **client-side copy link + ping BE đếm counter**. Bookmark + Like đã có pattern entity rồi, share dùng counter thuần phù hợp hơn (vì share thường không cần biết "ai share").

**Backend (1 endpoint, không cần entity):**

```java
// ForumPostController
@PostMapping("/{postId}/share")
public ResponseEntity<?> recordShare(@PathVariable Integer postId,
                                     @RequestParam(required=false) String channel) {
    // channel: "copy" | "facebook" | "twitter" — để admin sau này phân tích kênh
    forumService.incrementShareCount(postId, channel);
    return ResponseEntity.ok(Map.of("success", true));
}

// ForumServiceImpl
public void incrementShareCount(Integer postId, String channel) {
    ForumPost post = forumPostRepository.findById(postId)
        .orElseThrow(() -> new RuntimeException("Post not found"));
    post.setShareCount((post.getShareCount() == null ? 0 : post.getShareCount()) + 1);
    forumPostRepository.save(post);
    log.info("Post {} shared via {}", postId, channel);
}
```

**Frontend — Share menu trên PostCard + PostDetailPage:**

```jsx
// Thêm icon Share2 từ lucide-react
const handleShare = async (channel) => {
    const url = `${window.location.origin}/forum/posts/${postId}`;
    if (channel === 'copy') {
        await navigator.clipboard.writeText(url);
        toast.success('Đã copy link');
    } else if (channel === 'facebook') {
        window.open(`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(url)}`, '_blank');
    } else if (channel === 'native' && navigator.share) {
        await navigator.share({ title: post.title, url });
    }
    // Ping BE đếm counter (không block UI)
    axios.post(`/forum/posts/${postId}/share`, null, { params: { channel } }).catch(() => {});
};
```

Render: 1 nút Share → dropdown 3 lựa chọn (Copy link / Facebook / Native share API nếu mobile).

**Effort**: 0.5 ngày BE + 0.5 ngày FE = 1 ngày

---

## 5. Vấn đề 4 — My Posts / Drafts khó tìm

### 5.1. Hiện trạng

- Backend có endpoint `/api/forum/posts/user/{userId}/manage` trả về cả PENDING/HIDDEN — OK ✅
- Frontend có `UserPostsManagement` page nhưng **không có link nào dẫn vào** từ `ForumPage`
- User phải gõ URL trực tiếp → không ai biết

### 5.2. Giải pháp

Thêm tab **"Bài của tôi"** trong `ForumPage` filter tabs (chỉ hiện nếu logged-in) → link sang trang `UserPostsManagement`.

Hoặc tốt hơn: thêm dropdown menu user góc trên `ForumPage` với 3 link:
- ✏️ Tạo bài mới
- 📝 Bài của tôi (gồm cả draft, pending, published, hidden)
- 🔖 Đã lưu (link sang trang bookmark ở vấn đề 2)

**Effort**: 0.5 ngày FE

---

## 6. Vấn đề 5 — Following / Feed riêng

### 6.1. Hiện trạng

- `FollowerRepository` tồn tại trong code → có table follower DB
- `ForumServiceImpl` có dùng `followerRepository` khi publish notification "có bài viết mới từ tác giả X" ✅
- **Nhưng** UI **không có nút Follow** trên profile/PostCard, **không có feed riêng** lọc bài từ người user theo dõi

### 6.2. Giải pháp

**Backend (2 endpoint):**

```java
@PostMapping("/follow/{authorId}")  // toggle follow
public ResponseEntity<?> toggleFollow(@PathVariable Integer authorId, @RequestParam Integer followerId);

@GetMapping("/feed")  // Bài từ người mình follow
public ResponseEntity<?> getFollowingFeed(@RequestParam Integer userId, ...);
```

Service: `findByFollowerIdIn(followerIds)` → trả các post của các tác giả mà user follow.

**Frontend:**
- Nút "Theo dõi" trên `PostCard` cạnh tên tác giả (giống Facebook)
- Tab "Đang theo dõi" trong `ForumPage` filter tabs (chỉ hiện nếu logged-in)

**Effort**: 1 ngày BE + 0.5 ngày FE = 1.5 ngày

---

## 7. Tổng hợp Sprint plan

### Sprint A (1.5 ngày) — Fix gap **nghiêm trọng nhất**, làm trước

| Task | BE | FE |
|---|:---:|:---:|
| 2.2 Bookmark — xem lại được | 0.5d | 0.5d |
| 3.2 Pin — ảnh hưởng thứ tự list | 0.5d | — |
| 5.2 Link "Bài của tôi" trên forum nav | — | 0.25d |
| 5.2 Link "Đã lưu" trên forum nav | — | (gộp 2.2) |

→ **Kết quả Sprint A**: bookmark hoạt động end-to-end, pin có ý nghĩa thật, user thấy "Bài của tôi" / "Đã lưu" ngay trong forum nav.

### Sprint B (1 ngày) — Share

| Task | BE | FE |
|---|:---:|:---:|
| 4.2 Share endpoint + counter | 0.5d | — |
| 4.2 Share menu (copy / FB / native) | — | 0.5d |

→ Sau Sprint B: counter share hoạt động, user copy link / share Facebook được.

### Sprint C (1.5 ngày) — Following + Feed (nice-to-have)

| Task | BE | FE |
|---|:---:|:---:|
| 6.2 Toggle follow + feed endpoint | 1d | — |
| 6.2 Nút Theo dõi + tab "Đang theo dõi" | — | 0.5d |

→ Sau Sprint C: forum có tính social cơ bản.

**Tổng**: 4 ngày làm hết. Sprint A bắt buộc (vá nửa vời), Sprint B + C có thể defer.

---

## 8. Các tính năng forum khác đang thiếu (chưa ưu tiên cao)

Ghi chú để track, không nằm trong plan này:

| Tính năng | Mức độ thiếu | Đề xuất |
|---|---|---|
| **Edit history** cho bài viết | ❌ Không có | Thêm `PostRevision` lưu snapshot mỗi lần edit (Sprint sau) |
| **Mentions** (@username trong comment) | ❌ Không có | Parse `@xxx` → highlight + notify (Sprint sau) |
| **Bài liên quan** ("Có thể bạn quan tâm") | ❌ Không có | Dùng category + tag match → top 3 (Sprint sau) |
| **Search bài viết** full-text | ⚠ Có search by title? | Cần verify, có thể bổ sung Postgres tsvector (Sprint sau) |
| **Image upload trong post** | ⚠ Có thumbnail URL nhưng không thấy uploader | Verify ImageUpload component có wire BE chưa |
| **Trending tag cloud** | ❌ Không có UI ngoài admin | FE render `getPopularTags` lên forum sidebar |

---

## 9. Files sẽ chạm khi implement Sprint A

**Backend (`forum-service`):**
- `repository/PostBookmarkRepository.java` — thêm `findPostsByUserId`
- `service/ForumService.java` — thêm method `getBookmarkedPosts`
- `service/impl/ForumServiceImpl.java` — implement + patch `getPosts` để sort pin đầu
- `controller/ForumPostController.java` — endpoint `GET /posts/bookmarks`

**Frontend (`client-side`):**
- `components/ForumComponent/MyBookmarks/MyBookmarks.jsx` — **NEW** trang bookmark
- `components/ForumComponent/PostDetail/PostDetailPage.jsx` — thêm state + handler + nút Bookmark trong actionsBar
- `components/ForumComponent/ForumPage.jsx` — thêm tab "Đã lưu" + "Bài của tôi"
- `App.tsx` (router) — thêm route `/forum/bookmarks`

---

## 10. Câu hỏi mở cần user quyết

1. **Pin**: muốn pin ảnh hưởng cả list "Mới nhất" và "Xu hướng" không, hay chỉ "Mới nhất"?
2. **Share**: cần track ai đã share (entity `PostShare`) hay chỉ cần counter tổng?
3. **Following**: forum có scope đủ lớn để cần social feed riêng, hay tập trung vào blog/Q&A là chính?
4. **Sprint A có làm ngay không**, hay defer chờ phản hồi user về Sprint 5 admin trước?
