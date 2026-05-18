package com.tourism.forum.controller;

import com.tourism.forum.dto.request.CommentRequest;
import com.tourism.forum.dto.request.CreatePostRequest;
import com.tourism.forum.dto.request.PostFilterRequest;
import com.tourism.forum.dto.response.PostDetailResponse;
import com.tourism.forum.dto.response.PostListResponse;
import com.tourism.forum.service.ForumService;
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
            @RequestParam(required = false) String search
    ) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        PostFilterRequest filter = PostFilterRequest.builder()
                .categoryId(categoryId).tagId(tagId)
                .postType(postType).search(search)
                .build();
        Page<PostListResponse> posts = forumService.getPosts(filter, pageable);
        return ResponseEntity.ok(Map.of("success", true, "data", posts));
    }

    @GetMapping("/trending")
    public ResponseEntity<?> getTrendingPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(Map.of("success", true, "data", forumService.getTrendingPosts(pageable)));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getPostsByUser(
            @PathVariable Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(Map.of("success", true, "data", forumService.getPostsByUser(userId, pageable)));
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
}
