package com.tourism.forum.service;

import com.tourism.forum.dto.request.*;
import com.tourism.forum.dto.response.*;
import com.tourism.forum.entity.ContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AdminForumService {

    // ── Posts ──────────────────────────────────────────────────────────────────
    Page<PostListResponse> getPosts(AdminPostFilterRequest filter, Pageable pageable);

    PostListResponse togglePin(Integer postId);

    PostListResponse toggleFeature(Integer postId);

    PostListResponse changeStatus(Integer postId, ContentStatus status);

    PostListResponse changeStatus(Integer postId, ContentStatus status, String rejectionReason);

    AdminPostDetailResponse getPostDetail(Integer postId);

    PostListResponse updatePostContent(Integer postId, PostUpdateRequest request);

    void softDeletePost(Integer postId);

    void softDeletePost(Integer postId, String reason);

    void bulkPostAction(BulkActionRequest request);

    // ── Trash & Audit ────────────────────────────────────────────────────────────
    java.util.List<TrashItemResponse> getTrash(String type);

    void restorePost(Integer postId);

    void restoreComment(Integer commentId);

    Page<com.tourism.forum.entity.ModerationAuditLog> getAuditLogs(String targetType, Integer targetId, Pageable pageable);

    // ── User ban (forum-only) ──────────────────────────────────────────────────
    void banUser(Integer userId, BanUserRequest request);

    void unbanUser(Integer userId);

    java.util.List<BannedUserResponse> getBannedUsers();

    // ── Content reports ────────────────────────────────────────────────────────
    Page<com.tourism.forum.entity.ContentReport> getReports(String status, Pageable pageable);

    void resolveReport(Long reportId, String action);   // RESOLVE | DISMISS

    // ── Comments ───────────────────────────────────────────────────────────────
    Page<AdminCommentResponse> getComments(AdminCommentFilterRequest filter, Pageable pageable);

    // Group view: danh sách bài có comment
    Page<AdminPostWithCommentsResponse> getPostsWithComments(Boolean onlyPending, Pageable pageable);

    // Cây comment của 1 bài (mọi trạng thái) cho admin
    List<AdminCommentResponse> getCommentsByPost(Integer postId);

    void softDeleteComment(Integer commentId);

    void bulkCommentAction(BulkActionRequest request);

    // ── Categories ───────────────────────────────────────────────────────────────
    List<AdminCategoryResponse> getCategories();

    AdminCategoryResponse createCategory(CategoryRequest request);

    AdminCategoryResponse updateCategory(Integer categoryId, CategoryRequest request);

    void deleteCategory(Integer categoryId);

    void reorderCategories(CategoryReorderRequest request);

    // ── Tags ─────────────────────────────────────────────────────────────────────
    Page<AdminTagResponse> getTags(String search, Pageable pageable);

    AdminTagResponse createTag(TagRequest request);

    AdminTagResponse updateTag(Integer tagId, TagRequest request);

    void deleteTag(Integer tagId);

    // ── Analytics ─────────────────────────────────────────────────────────────────
    AdminForumStatsResponse getStats();

    AdminAnalyticsResponse getAnalytics();

    /** Export CSV log kiểm duyệt theo khoảng ngày. */
    String exportModerationCsv(java.time.LocalDate from, java.time.LocalDate to);
}
