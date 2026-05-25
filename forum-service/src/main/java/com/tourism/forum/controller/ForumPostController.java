package com.tourism.forum.controller;

import com.tourism.forum.dto.request.CommentRequest;
import com.tourism.forum.dto.request.CreatePostRequest;
import com.tourism.forum.dto.request.PostFilterRequest;
import com.tourism.forum.dto.request.PostUpdateRequest;
import com.tourism.forum.dto.response.PostDetailResponse;
import com.tourism.forum.dto.response.PostListResponse;
import com.tourism.forum.service.ForumService;
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

    @PutMapping("/{postId}")
    public ResponseEntity<?> updatePost(
            @PathVariable Integer postId,
            @Valid @RequestBody PostUpdateRequest request,
            HttpServletRequest httpRequest
    ) {
        Integer userId = extractUserIdFromToken(httpRequest);
        PostDetailResponse response = forumService.updatePost(postId, request, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Cập nhật bài viết thành công", "data", response));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<?> deletePost(
            @PathVariable Integer postId,
            HttpServletRequest httpRequest
    ) {
        Integer userId = extractUserIdFromToken(httpRequest);
        forumService.deletePost(postId, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Xóa bài viết thành công"));
    }

    private Integer extractUserIdFromToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            // Simple extraction for dev-token format: "dev-token-{timestamp}..."
            // For actual userId, we need the current user context  from auth header
            // For now, extract userId 2 as default for dev/test token
            try {
                // Extract number after "dev-token-"
                String afterPrefix = token.substring(10); // Remove "dev-token-"
                String[] parts = afterPrefix.split("-");
                if (parts.length > 0) {
                    // Try to parse the first numeric part as userId
                    // If it's a timestamp, default to userId 2 for testing
                    Long num = Long.parseLong(parts[0]);
                    if (num > 1000000) {
                        // Likely a timestamp, use default user 2
                        return 2;
                    }
                    return num.intValue();
                }
            } catch (Exception e) {
                // Default to user 2 if extraction fails
                return 2;
            }
        }
        return null;
    }
}
