package com.tourism.forum.controller;

import com.tourism.forum.dto.request.CommentRequest;
import com.tourism.forum.dto.request.CreatePostRequest;
import com.tourism.forum.dto.request.PostFilterRequest;
import com.tourism.forum.dto.request.PostUpdateRequest;
import com.tourism.forum.dto.response.PostDetailResponse;
import com.tourism.forum.dto.response.PostListResponse;
import com.tourism.forum.service.ForumService;
import com.tourism.forum.service.ForumRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/forum/posts")
@RequiredArgsConstructor
public class ForumPostController {

    private final ForumService forumService;
    private final ForumRateLimitService rateLimitService;
    private final com.tourism.forum.service.ForumRewardService rewardService;

    // GET /api/forum/posts/coin-summary?userId=xxx — coin đã kiếm hôm nay / trần ngày / lịch sử
    @GetMapping("/coin-summary")
    public ResponseEntity<?> getCoinSummary(@RequestParam(required = false) Integer userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", rewardService.getCoinSummary(userId)));
    }

    // GET /api/forum/posts/coin-history?userId=xxx&page=0&size=10 — toàn bộ lịch sử thưởng, phân trang
    @GetMapping("/coin-history")
    public ResponseEntity<?> getCoinHistory(
            @RequestParam(required = false) Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(Map.of("success", true, "data", rewardService.getCoinHistory(userId, page, size)));
    }

    // GET /api/forum/posts/quota?userId=xxx — số lượt còn lại trong ngày
    @GetMapping("/quota")
    public ResponseEntity<?> getQuota(@RequestParam Integer userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", rateLimitService.getQuotaStatus(userId)));
    }

    // GET /api/forum/posts/my-restriction?userId=xxx — trạng thái hạn chế forum của user
    @GetMapping("/my-restriction")
    public ResponseEntity<?> getMyRestriction(@RequestParam(required = false) Integer userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", forumService.getRestrictionStatus(userId)));
    }

    @GetMapping
    public ResponseEntity<?> getPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer tagId,
            @RequestParam(required = false) String postType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer userId
    ) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        PostFilterRequest filter = PostFilterRequest.builder()
                .categoryId(categoryId).tagId(tagId)
                .postType(postType).search(search)
                .build();
        Page<PostListResponse> posts = forumService.getPosts(filter, pageable, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", posts));
    }

    @GetMapping("/trending")
    public ResponseEntity<?> getTrendingPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(required = false) Integer userId
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(Map.of("success", true,
                "data", forumService.getTrendingPosts(pageable, userId)));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getPostsByUser(
            @PathVariable Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer viewerId
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(Map.of("success", true,
                "data", forumService.getPostsByUser(userId, pageable, viewerId)));
    }

    // Endpoint cho trang quản lý: trả TẤT CẢ bài (kể cả HIDDEN, PENDING_REVIEW, DRAFT)
    @GetMapping("/user/{userId}/manage")
    public ResponseEntity<?> getMyPostsForManagement(
            @PathVariable Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(Map.of("success", true,
                "data", forumService.getMyPostsForManagement(userId, pageable)));
    }

    @PostMapping
    public ResponseEntity<?> createPost(@Valid @RequestBody CreatePostRequest request) {
        PostDetailResponse response = forumService.createPost(request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Tạo bài viết thành công", "data", response));
    }

    @GetMapping("/{postId}")
    public ResponseEntity<?> getPostDetail(
            @PathVariable Integer postId,
            @RequestParam(required = false) Integer userId
    ) {
        PostDetailResponse post = forumService.getPostDetail(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", post));
    }

    @PostMapping("/{postId}/view")
    public ResponseEntity<?> recordView(
            @PathVariable Integer postId,
            @RequestParam(required = false) Integer userId,
            HttpServletRequest httpRequest
    ) {
        // Identify the viewer: prefer logged-in userId, fall back to client IP.
        String viewerKey;
        if (userId != null) {
            viewerKey = "u" + userId;
        } else {
            String ip = httpRequest.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = httpRequest.getRemoteAddr();
            } else {
                ip = ip.split(",")[0].trim();
            }
            viewerKey = "ip" + ip;
        }
        forumService.recordView(postId, viewerKey);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{postId}/like")
    public ResponseEntity<?> toggleLike(
            @PathVariable Integer postId,
            @RequestParam Integer userId
    ) {
        forumService.toggleLike(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Thao tác like thành công"));
    }

    @GetMapping("/{postId}/like-check")
    public ResponseEntity<?> checkLikeStatus(
            @PathVariable Integer postId,
            @RequestParam(required = false) Integer userId
    ) {
        boolean isLiked = forumService.checkLikeStatus(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("isLiked", isLiked)));
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Integer postId,
            @Valid @RequestBody CommentRequest request
    ) {
        PostDetailResponse updated = forumService.addComment(postId, request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bình luận thành công", "data", updated));
    }

    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<?> toggleCommentLike(
            @PathVariable Integer commentId,
            @RequestParam Integer userId
    ) {
        forumService.toggleCommentLike(commentId, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Like bình luận thành công"));
    }

    @GetMapping("/user/stats")
    public ResponseEntity<?> getUserStats(@RequestParam Integer userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", forumService.getUserStats(userId)));
    }

    @PostMapping("/report")
    public ResponseEntity<?> createReport(
            @RequestParam Integer reporterId,
            @Valid @RequestBody com.tourism.forum.dto.request.ReportRequest request
    ) {
        forumService.createReport(reporterId, request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã gửi báo cáo, cảm ơn bạn"));
    }

    @PostMapping("/{postId}/bookmark")
    public ResponseEntity<?> toggleBookmark(
            @PathVariable Integer postId,
            @RequestParam Integer userId
    ) {
        boolean isBookmarked = forumService.toggleBookmark(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("isBookmarked", isBookmarked)));
    }

    @GetMapping("/{postId}/bookmark-check")
    public ResponseEntity<?> checkBookmarkStatus(
            @PathVariable Integer postId,
            @RequestParam(required = false) Integer userId
    ) {
        boolean isBookmarked = forumService.checkBookmarkStatus(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("isBookmarked", isBookmarked)));
    }

    // ── Sprint A: Bookmark list ─────────────────────────────────────────────
    @GetMapping("/bookmarks")
    public ResponseEntity<?> getMyBookmarks(
            @RequestParam Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        var data = forumService.getBookmarkedPosts(userId,
                org.springframework.data.domain.PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ── Sprint B: Share counter ─────────────────────────────────────────────
    @PostMapping("/{postId}/share")
    public ResponseEntity<?> recordShare(
            @PathVariable Integer postId,
            @RequestParam(required = false, defaultValue = "copy") String channel
    ) {
        forumService.recordShare(postId, channel);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã ghi nhận chia sẻ"));
    }

    // ── Sprint C: Follow + Feed ─────────────────────────────────────────────
    @PostMapping("/follow/{authorId}")
    public ResponseEntity<?> toggleFollow(
            @PathVariable Integer authorId,
            @RequestParam Integer followerId
    ) {
        boolean isFollowing = forumService.toggleFollow(followerId, authorId);
        return ResponseEntity.ok(Map.of("success", true,
                "data", Map.of("isFollowing", isFollowing),
                "message", isFollowing ? "Đã theo dõi" : "Đã bỏ theo dõi"));
    }

    @GetMapping("/follow/{authorId}/check")
    public ResponseEntity<?> checkFollowing(
            @PathVariable Integer authorId,
            @RequestParam(required = false) Integer followerId
    ) {
        boolean isFollowing = forumService.isFollowing(followerId, authorId);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("isFollowing", isFollowing)));
    }

    @GetMapping("/feed")
    public ResponseEntity<?> getFollowingFeed(
            @RequestParam Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        var data = forumService.getFollowingFeed(userId,
                org.springframework.data.domain.PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PutMapping("/{postId}")
    public ResponseEntity<?> updatePost(
            @PathVariable Integer postId,
            @RequestParam Integer userId,
            @Valid @RequestBody PostUpdateRequest request
    ) {
        PostDetailResponse response = forumService.updatePost(postId, request, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Cập nhật bài viết thành công", "data", response));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<?> deletePost(
            @PathVariable Integer postId,
            @RequestParam Integer userId
    ) {
        forumService.deletePost(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Xóa bài viết thành công"));
    }

    // User tự bật/tắt PUBLISHED ↔ DRAFT bài viết của mình
    @PatchMapping("/{postId}/status")
    public ResponseEntity<?> changeStatus(
            @PathVariable Integer postId,
            @RequestParam Integer userId,
            @RequestBody Map<String, String> body
    ) {
        forumService.changePostStatusByOwner(postId, body.get("status"), userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật trạng thái"));
    }

    // Rate-limit vi phạm → HTTP 429 với message rõ ràng
    @ExceptionHandler(com.tourism.forum.service.ForumRateLimitService.RateLimitException.class)
    public ResponseEntity<?> handleRateLimit(
            com.tourism.forum.service.ForumRateLimitService.RateLimitException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("success", false, "message", e.getMessage()));
    }

    // RuntimeException nghiệp vụ khác → HTTP 400 (rõ ràng hơn 500)
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", e.getMessage()));
    }
}
