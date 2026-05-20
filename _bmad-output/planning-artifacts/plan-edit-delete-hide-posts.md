# Plan: Edit, Delete, Hide/Publish Posts - Forum Management

## 📋 Tổng quan
Fix các route không hoạt động `/posts/{postId}/edit` và thiết kế chiến lược xử lý delete/hide posts:
- **Edit Post**: Tạo route `/posts/:postId/edit` và component EditPost
- **Delete Post**: Xóa luôn hay soft delete (cập nhật status)
- **Hide/Publish Post**: Toggle status PUBLISHED ↔ DRAFT

---

## 🔍 Hiện trạng

### Frontend
**App.tsx (src/App.tsx)**
```tsx
<Route path="/forum/my-posts" element={<UserPostsManagement />} />
// ❌ Không có route: /posts/:postId/edit
```

**UserPostsManagement.jsx** (Line 237)
```jsx
onClick={() => window.location.href = `/posts/${post.postID}/edit`}
```
Redirect đến `/posts/{id}/edit` nhưng route này chưa tồn tại.

**API Calls hiện tại:**
- `DELETE /forum/posts/{postId}` → Xóa thực
- `PATCH /forum/posts/{postId}/status` → Toggle status

### Backend - Forum Service
**Endpoints hiện có:**
- `DELETE /api/forum/posts/{postId}` → Xóa post
- `PATCH /api/forum/posts/{postId}/status` → Cập nhật status

**Không có endpoint:**
- Update post (sửa bài viết)

---

## 🏗️ Chiến lược thiết kế

### 1️⃣ DELETE vs SOFT DELETE
**Quyết định: SOFT DELETE (Cập nhật status = DELETED)**

**Lý do:**
- Giữ lại dữ liệu cho audit trail
- Người khác vẫn có thể xem bài viết đã được xóa (nếu cần)
- Dễ restore sau này
- Thống kê stats chính xác

**Implementation:**
```sql
-- Thêm field vào ForumPost
isDeleted: Boolean = false
deletedAt: LocalDateTime = null

-- Logic query: WHERE isDeleted = false
```

### 2️⃣ POST STATUS
```
DRAFT       → Bài nháp, chỉ tác giả thấy
PUBLISHED   → Bài viết công khai
DELETED     → Bài đã xóa (soft delete)
ARCHIVED    → Lưu trữ (optional)
```

---

## 📝 Chi tiết các task

### TASK 1: Backend - Thêm soft delete & update post endpoint

#### 1.1 Sửa Entity ForumPost
**File:** `forum-service/.../entity/ForumPost.java`

```java
@Entity
public class ForumPost extends BaseEntity {
    // ... hiện có ...
    
    @Column(name = "is_deleted", nullable = false, columnDefinition = "BOOLEAN DEFAULT false")
    @Builder.Default
    private Boolean isDeleted = false;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
```

#### 1.2 Tạo PostUpdateRequest DTO
**File:** `forum-service/.../dto/request/PostUpdateRequest.java`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostUpdateRequest {
    @NotBlank(message = "Tiêu đề không được để trống")
    @Length(min = 5, max = 200)
    private String title;
    
    @NotBlank(message = "Nội dung không được để trống")
    @Length(min = 20, max = 5000)
    private String content;
    
    private String summary;
    private String thumbnailUrl;
    
    @NotNull(message = "Danh mục không được để trống")
    private Integer categoryId;
    
    private List<Integer> tagIds;
}
```

#### 1.3 Thêm Query vào ForumPostRepository
**File:** `forum-service/.../repository/ForumPostRepository.java`

```java
@Query("SELECT p FROM ForumPost p WHERE p.postID = :postId AND p.isDeleted = false")
Optional<ForumPost> findByIdAndNotDeleted(Integer postId);

@Query("SELECT p FROM ForumPost p WHERE p.userId = :userId AND p.isDeleted = false")
Page<ForumPost> findByUserIdAndNotDeleted(Integer userId, Pageable pageable);
```

#### 1.4 Thêm Methods vào ForumService
**File:** `forum-service/.../service/ForumService.java`

```java
// Update post
PostDetailResponse updatePost(Integer postId, PostUpdateRequest request);

// Soft delete
void deletePost(Integer postId);

// Delete check
void checkPostOwner(Integer postId, Integer userId);
```

#### 1.5 Implement trong ForumServiceImpl
**File:** `forum-service/.../service/impl/ForumServiceImpl.java`

```java
@Override
public PostDetailResponse updatePost(Integer postId, PostUpdateRequest request) {
    ForumPost post = forumPostRepository.findByIdAndNotDeleted(postId)
        .orElseThrow(() -> new RuntimeException("Post not found"));
    
    // Check ownership
    if (!post.getUserId().equals(getCurrentUserId())) {
        throw new UnauthorizedException("Bạn không có quyền sửa bài viết này");
    }
    
    // Update fields
    post.setTitle(request.getTitle());
    post.setContent(request.getContent());
    post.setSummary(request.getSummary());
    post.setThumbnailUrl(request.getThumbnailUrl());
    post.setUpdatedAt(LocalDateTime.now());
    
    // Update category
    if (request.getCategoryId() != null) {
        PostCategory category = categoryRepository.findById(request.getCategoryId())
            .orElseThrow();
        post.setCategory(category);
    }
    
    // Update tags
    if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
        postTagRepository.deleteByPostId(postId);
        request.getTagIds().forEach(tagId -> {
            PostTag postTag = new PostTag();
            postTag.setPost(post);
            postTag.setTag(tagRepository.findById(tagId).orElseThrow());
            postTagRepository.save(postTag);
        });
    }
    
    ForumPost updated = forumPostRepository.save(post);
    return mapToDetailResponse(updated);
}

@Override
public void deletePost(Integer postId) {
    ForumPost post = forumPostRepository.findById(postId)
        .orElseThrow(() -> new RuntimeException("Post not found"));
    
    // Check ownership
    if (!post.getUserId().equals(getCurrentUserId())) {
        throw new UnauthorizedException("Bạn không có quyền xóa bài viết này");
    }
    
    // Soft delete
    post.setIsDeleted(true);
    post.setDeletedAt(LocalDateTime.now());
    forumPostRepository.save(post);
}

private Integer getCurrentUserId() {
    // Lấy từ SecurityContext
    return ((User) SecurityContextHolder.getContext()
        .getAuthentication().getPrincipal()).getUserId();
}
```

#### 1.6 Sửa query hiện có (soft delete)
**Tất cả queries cần add:** `AND p.isDeleted = false`

#### 1.7 Thêm Endpoints vào ForumPostController
**File:** `forum-service/.../controller/ForumPostController.java`

```java
@PutMapping("/{postId}")
public ResponseEntity<?> updatePost(
    @PathVariable Integer postId,
    @Valid @RequestBody PostUpdateRequest request
) {
    PostDetailResponse response = forumService.updatePost(postId, request);
    return ResponseEntity.ok(Map.of("success", true, "data", response));
}

@DeleteMapping("/{postId}")
public ResponseEntity<?> deletePost(@PathVariable Integer postId) {
    forumService.deletePost(postId);
    return ResponseEntity.ok(Map.of("success", true, "message", "Xóa bài viết thành công"));
}
```

---

### TASK 2: Frontend - Tạo Edit Post Page

#### 2.1 Tạo EditPost Component
**File:** `frontend/.../components/ForumComponent/EditPost/EditPost.jsx`

```jsx
import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import axios from '../../../utils/axiosCustomize';
import CreatePost from '../CreatePost/CreatePost'; // Reuse component
import { toast } from 'react-toastify';

const EditPost = () => {
  const { postId } = useParams();
  const navigate = useNavigate();
  const [post, setPost] = useState(null);
  const [loading, setLoading] = useState(true);
  const [categories, setCategories] = useState([]);

  useEffect(() => {
    fetchPost();
    fetchCategories();
  }, [postId]);

  const fetchPost = async () => {
    try {
      const response = await axios.get(`/forum/posts/${postId}`);
      setPost(response.data?.data);
    } catch (error) {
      toast.error('Không thể tải bài viết');
      navigate('/forum/my-posts');
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const res = await axios.get('/forum/categories');
      setCategories(res.data?.data || []);
    } catch (error) {
      console.error('Error:', error);
    }
  };

  const handleUpdateSuccess = () => {
    toast.success('Cập nhật bài viết thành công!');
    navigate('/forum/my-posts');
  };

  if (loading) return <div>Đang tải...</div>;
  if (!post) return <div>Bài viết không tồn tại</div>;

  return (
    <CreatePost
      isEditing={true}
      initialPost={post}
      categories={categories}
      onSuccess={handleUpdateSuccess}
    />
  );
};

export default EditPost;
```

#### 2.2 Sửa CreatePost Component
**Thêm prop `isEditing` và logic update:**

```jsx
const CreatePost = ({ 
  isOpen = true, 
  onClose, 
  categories, 
  onSuccess,
  isEditing = false,
  initialPost = null 
}) => {
  // ... state ...
  
  const handleSubmit = async () => {
    try {
      if (isEditing) {
        // UPDATE
        await axios.put(`/forum/posts/${initialPost.postID}`, formData);
        toast.success('Cập nhật bài viết thành công!');
      } else {
        // CREATE
        await axios.post('/forum/posts', formData);
        toast.success('Tạo bài viết thành công!');
      }
      onSuccess?.();
    } catch (error) {
      toast.error('Có lỗi xảy ra');
    }
  };
  
  // Populate fields khi edit
  useEffect(() => {
    if (isEditing && initialPost) {
      setFormData({
        title: initialPost.title,
        content: initialPost.content,
        // ... fields khác
      });
    }
  }, [isEditing, initialPost]);
};
```

#### 2.3 Thêm Route vào App.tsx
**File:** `src/App.tsx`

```tsx
import EditPost from './components/ForumComponent/EditPost/EditPost';

// ... routes ...
<Route path="/posts/:postId/edit" element={<EditPost />} />
```

---

### TASK 3: Update Frontend API Calls

#### 3.1 Sửa UserPostsManagement.jsx
**Delete logic:**

```jsx
const handleDeletePost = async (postId) => {
  if (!window.confirm('Bạn chắc chắn muốn xóa bài viết này?')) return;
  
  try {
    await axios.delete(`/forum/posts/${postId}`);
    setPosts(posts.filter(p => p.postID !== postId));
    toast.success('Xóa bài viết thành công!');
    fetchUserPosts(); // Refresh stats
  } catch (error) {
    toast.error('Không thể xóa bài viết');
  }
};
```

**Edit button:**
```jsx
<button
  onClick={() => navigate(`/posts/${post.postID}/edit`)}
>
  Sửa
</button>
```

---

## 🔄 Quy trình triển khai

### Phase 1: Backend (2-3 giờ)
1. ✅ Thêm isDeleted, deletedAt vào ForumPost entity
2. ✅ Tạo PostUpdateRequest DTO
3. ✅ Thêm queries vào repository
4. ✅ Implement service methods (updatePost, deletePost)
5. ✅ Thêm endpoints vào controller
6. ✅ Fix tất cả queries có `SELECT * FROM post` → add isDeleted=false filter
7. ✅ Database migration (nếu dùng Flyway/Liquibase)
8. ✅ mvn package + docker rebuild

### Phase 2: Frontend (1.5-2 giờ)
1. ✅ Tạo EditPost component
2. ✅ Sửa CreatePost để support editing
3. ✅ Thêm route /posts/:postId/edit
4. ✅ Update UserPostsManagement delete handler
5. ✅ Test flow: edit → submit → redirect to my-posts
6. ✅ Build + verify

### Phase 3: Testing & Verification (30 min)
- [ ] Test edit post (title, content, category, tags)
- [ ] Test soft delete (kiểm tra data vẫn còn DB nhưng không hiển thị)
- [ ] Test hide/publish toggle
- [ ] Test permissions (không được edit/delete post người khác)
- [ ] Verify stats update chính xác

---

## 📊 Thay đổi dự kiến

### Database
```sql
ALTER TABLE forum_posts ADD COLUMN is_deleted BOOLEAN DEFAULT false;
ALTER TABLE forum_posts ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_posts_deleted ON forum_posts(is_deleted);
```

### API Endpoints mới
| Method | Endpoint | Purpose |
|--------|----------|---------|
| PUT | `/api/forum/posts/{id}` | Cập nhật bài viết |
| DELETE | `/api/forum/posts/{id}` | Xóa (soft) bài viết |
| PATCH | `/api/forum/posts/{id}/status` | Toggle PUBLISHED/DRAFT |

### Frontend Routes mới
| Path | Component | Purpose |
|------|-----------|---------|
| `/posts/:postId/edit` | EditPost | Sửa bài viết |

---

## ⚠️ Lưu ý quan trọng

1. **Permission Check**: Chỉ tác giả mới được edit/delete bài của mình
2. **Soft Delete**: Không xóa thực, chỉ cập nhật isDeleted=true
3. **Stats**: Bài deleted không tính vào stats người dùng
4. **Comments**: Giữ lại comments của bài deleted (vì comments vẫn belong to deleted post)
5. **Search**: Không hiển thị bài deleted trong danh sách forum

---

## 🎯 Definition of Done
- ✅ Backend: update & delete endpoints working
- ✅ Frontend: Edit page hoạt động đầy đủ
- ✅ Soft delete: data lưu trong DB, không hiển thị
- ✅ Permissions: enforce ownership check
- ✅ UI/UX: confirm delete dialog, validation
- ✅ Testing: all flows tested
- ✅ Docker: rebuilt & deployed
