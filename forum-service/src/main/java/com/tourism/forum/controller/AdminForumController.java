package com.tourism.forum.controller;

import com.tourism.forum.config.RequireAdmin;
import com.tourism.forum.dto.request.*;
import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.service.AdminForumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;

// Admin-only access is enforced by AdminAuthInterceptor (gateway X-User-Role header).
@RestController
@RequestMapping("/api/admin/forum")
@RequiredArgsConstructor
public class AdminForumController {

    private final AdminForumService adminForumService;

    // ── Posts ──────────────────────────────────────────────────────────────────
    @GetMapping("/posts")
    public ResponseEntity<?> getPosts(
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) String postType,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String moderationLabel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy));
        AdminPostFilterRequest filter = AdminPostFilterRequest.builder()
                .status(status).categoryId(categoryId).postType(postType)
                .userId(userId).search(search).moderationLabel(moderationLabel)
                .dateFrom(dateFrom).dateTo(dateTo)
                .build();
        return ok(adminForumService.getPosts(filter, pageable));
    }

    @PutMapping("/posts/{postId}/pin")
    public ResponseEntity<?> pinPost(@PathVariable Integer postId) {
        return ok(adminForumService.togglePin(postId));
    }

    @PutMapping("/posts/{postId}/feature")
    public ResponseEntity<?> featurePost(@PathVariable Integer postId) {
        return ok(adminForumService.toggleFeature(postId));
    }

    // Chi tiết bài viết cho admin (full content + ảnh + email tác giả)
    @GetMapping("/posts/{postId}/detail")
    public ResponseEntity<?> getPostDetail(@PathVariable Integer postId) {
        return ok(adminForumService.getPostDetail(postId));
    }

    @PutMapping("/posts/{postId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable Integer postId,
                                          @Valid @RequestBody StatusChangeRequest request) {
        return ok(adminForumService.changeStatus(postId, request.getStatus(), request.getRejectionReason()));
    }

    // Admin sửa nội dung bài viết
    @PutMapping("/posts/{postId}/content")
    public ResponseEntity<?> updatePostContent(@PathVariable Integer postId,
                                               @Valid @RequestBody PostUpdateRequest request) {
        return ok(adminForumService.updatePostContent(postId, request));
    }

    @RequireAdmin
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<?> deletePost(@PathVariable Integer postId,
                                        @RequestParam(required = false) String reason) {
        adminForumService.softDeletePost(postId, reason);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa bài viết"));
    }

    // ── Trash & Audit ────────────────────────────────────────────────────────────
    @GetMapping("/trash")
    public ResponseEntity<?> getTrash(@RequestParam(required = false) String type) {
        return ok(adminForumService.getTrash(type));
    }

    @PostMapping("/trash/posts/{postId}/restore")
    public ResponseEntity<?> restorePost(@PathVariable Integer postId) {
        adminForumService.restorePost(postId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã khôi phục bài viết"));
    }

    @PostMapping("/trash/comments/{commentId}/restore")
    public ResponseEntity<?> restoreComment(@PathVariable Integer commentId) {
        adminForumService.restoreComment(commentId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã khôi phục bình luận"));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ok(adminForumService.getAuditLogs(targetType, targetId, pageable));
    }

    // ── User ban (forum-only) ──────────────────────────────────────────────────
    @RequireAdmin
    @PostMapping("/users/{userId}/ban")
    public ResponseEntity<?> banUser(@PathVariable Integer userId,
                                     @RequestBody BanUserRequest request) {
        adminForumService.banUser(userId, request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã hạn chế người dùng trên diễn đàn"));
    }

    @RequireAdmin
    @PostMapping("/users/{userId}/unban")
    public ResponseEntity<?> unbanUser(@PathVariable Integer userId) {
        adminForumService.unbanUser(userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã gỡ hạn chế người dùng"));
    }

    @GetMapping("/banned-users")
    public ResponseEntity<?> getBannedUsers() {
        return ok(adminForumService.getBannedUsers());
    }

    // ── Content reports ────────────────────────────────────────────────────────
    @GetMapping("/reports")
    public ResponseEntity<?> getReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ok(adminForumService.getReports(status, pageable));
    }

    @PatchMapping("/reports/{reportId}")
    public ResponseEntity<?> resolveReport(@PathVariable Long reportId,
                                           @RequestParam String action) {
        adminForumService.resolveReport(reportId, action);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xử lý báo cáo"));
    }

    @PostMapping("/posts/bulk-action")
    public ResponseEntity<?> bulkPostAction(@Valid @RequestBody BulkActionRequest request) {
        adminForumService.bulkPostAction(request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xử lý hàng loạt"));
    }

    // ── Comments ───────────────────────────────────────────────────────────────
    @GetMapping("/comments")
    public ResponseEntity<?> getComments(
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(required = false) Integer postId,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String moderationLabel,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy));
        AdminCommentFilterRequest filter = AdminCommentFilterRequest.builder()
                .status(status).postId(postId).userId(userId)
                .moderationLabel(moderationLabel).search(search)
                .build();
        return ok(adminForumService.getComments(filter, pageable));
    }

    // Group view: danh sách bài có comment
    @GetMapping("/posts-with-comments")
    public ResponseEntity<?> getPostsWithComments(
            @RequestParam(required = false) Boolean onlyPending,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ok(adminForumService.getPostsWithComments(onlyPending, pageable));
    }

    // Cây comment của 1 bài (mọi trạng thái)
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<?> getCommentsByPost(@PathVariable Integer postId) {
        return ok(adminForumService.getCommentsByPost(postId));
    }

    @RequireAdmin
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable Integer commentId) {
        adminForumService.softDeleteComment(commentId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa bình luận"));
    }

    @PostMapping("/comments/bulk-action")
    public ResponseEntity<?> bulkCommentAction(@Valid @RequestBody BulkActionRequest request) {
        adminForumService.bulkCommentAction(request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xử lý hàng loạt"));
    }

    // ── Categories ───────────────────────────────────────────────────────────────
    @GetMapping("/categories")
    public ResponseEntity<?> getCategories() {
        return ok(adminForumService.getCategories());
    }

    @RequireAdmin
    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ok(adminForumService.createCategory(request));
    }

    @RequireAdmin
    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<?> updateCategory(@PathVariable Integer categoryId,
                                            @Valid @RequestBody CategoryRequest request) {
        return ok(adminForumService.updateCategory(categoryId, request));
    }

    @RequireAdmin
    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<?> deleteCategory(@PathVariable Integer categoryId) {
        adminForumService.deleteCategory(categoryId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa danh mục"));
    }

    @RequireAdmin
    @PutMapping("/categories/reorder")
    public ResponseEntity<?> reorderCategories(@Valid @RequestBody CategoryReorderRequest request) {
        adminForumService.reorderCategories(request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật thứ tự"));
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────
    @GetMapping("/tags")
    public ResponseEntity<?> getTags(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "usageCount") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy));
        return ok(adminForumService.getTags(search, pageable));
    }

    @RequireAdmin
    @PostMapping("/tags")
    public ResponseEntity<?> createTag(@Valid @RequestBody TagRequest request) {
        return ok(adminForumService.createTag(request));
    }

    @RequireAdmin
    @PutMapping("/tags/{tagId}")
    public ResponseEntity<?> updateTag(@PathVariable Integer tagId,
                                       @Valid @RequestBody TagRequest request) {
        return ok(adminForumService.updateTag(tagId, request));
    }

    @RequireAdmin
    @DeleteMapping("/tags/{tagId}")
    public ResponseEntity<?> deleteTag(@PathVariable Integer tagId) {
        adminForumService.deleteTag(tagId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa thẻ"));
    }

    // ── Analytics ─────────────────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ok(adminForumService.getStats());
    }

    @GetMapping(value = "/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<String> exportCsv(
            @RequestParam(defaultValue = "moderation") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (!"moderation".equalsIgnoreCase(type)) {
            return ResponseEntity.badRequest().body("Chỉ hỗ trợ type=moderation");
        }
        String csv = adminForumService.exportModerationCsv(from, to);
        // BOM để Excel mở UTF-8 chuẩn
        String body = "﻿" + csv;
        String filename = "moderation_" + (from != null ? from : "all") + "_" + (to != null ? to : "all") + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics() {
        return ok(adminForumService.getAnalytics());
    }

    // ── Helpers / local error handling ─────────────────────────────────────────
    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "message", ex.getMessage()));
    }
}
