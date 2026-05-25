package com.tourism.forum.controller;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostComment;
import com.tourism.forum.repository.CommentRepository;
import com.tourism.forum.repository.PostRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {
    private final PostRepository    postRepository;
    private final CommentRepository commentRepository;

     /** Lấy danh sách bài đang chờ duyệt */
    @GetMapping("/posts/pending")
    public ResponseEntity<Page<ForumPost>> getPendingPosts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            postRepository.findByStatusAndIsDeletedFalse(
                ContentStatus.PENDING_REVIEW, PageRequest.of(page, size))
        );
    }

    
    /** Tổng số cần duyệt (hiển thị badge cho admin) */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats() {
        return ResponseEntity.ok(Map.of(
            "pendingPosts",    postRepository.countByStatus(ContentStatus.PENDING_REVIEW),
            "pendingComments", commentRepository.countByStatus(ContentStatus.PENDING_REVIEW)
        ));
    }

    @PutMapping("/posts/{postId}/approve")
    @Transactional
    public ResponseEntity<Void> approvePost(@PathVariable Integer postId) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setStatus(ContentStatus.PUBLISHED);
            post.setPublishedAt(LocalDateTime.now());
            postRepository.save(post);
        });
        return ResponseEntity.ok().build();
    }

    @PutMapping("/posts/{postId}/reject")
    @Transactional
    public ResponseEntity<Void> rejectPost(@PathVariable Integer postId) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setStatus(ContentStatus.HIDDEN);
            postRepository.save(post);
        });
        return ResponseEntity.ok().build();
    }

     /** Lấy danh sách comment đang chờ duyệt */
    @GetMapping("/comments/pending")
    public ResponseEntity<Page<PostComment>> getPendingComments(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            commentRepository.findByStatusAndIsDeletedFalse(
                ContentStatus.PENDING_REVIEW, PageRequest.of(page, size))
        );
    }

    @PutMapping("/comments/{commentId}/approve")
    @Transactional
    public ResponseEntity<Void> approveComment(@PathVariable Integer commentId) {
        commentRepository.findById(commentId).ifPresent(c -> {
            c.setStatus(ContentStatus.PUBLISHED);
            commentRepository.save(c);
        });
        return ResponseEntity.ok().build();
    }

    @PutMapping("/comments/{commentId}/reject")
    @Transactional
    public ResponseEntity<Void> rejectComment(@PathVariable Integer commentId) {
        commentRepository.findById(commentId).ifPresent(c -> {
            c.setStatus(ContentStatus.HIDDEN);
            commentRepository.save(c);
        });
        return ResponseEntity.ok().build();
    }
}
