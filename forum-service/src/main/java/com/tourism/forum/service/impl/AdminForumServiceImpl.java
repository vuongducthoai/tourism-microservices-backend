package com.tourism.forum.service.impl;

import com.tourism.forum.config.AdminContext;
import com.tourism.forum.dto.request.*;
import com.tourism.forum.dto.response.*;
import com.tourism.forum.entity.*;
import com.tourism.forum.feign.IamFeignClient;
import com.tourism.forum.feign.dto.UserBriefResponse;
import com.tourism.forum.repository.*;
import com.tourism.forum.repository.spec.ForumPostSpecifications;
import com.tourism.forum.repository.spec.PostCommentSpecifications;
import com.tourism.forum.service.AdminForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminForumServiceImpl implements AdminForumService {

    private final ForumPostRepository    forumPostRepository;
    private final PostCommentRepository  postCommentRepository;
    private final PostCategoryRepository postCategoryRepository;
    private final TagRepository          tagRepository;
    private final IamFeignClient         iamFeignClient;
    private final com.tourism.forum.service.AuditLogService auditLogService;
    private final com.tourism.forum.repository.ModerationAuditLogRepository auditLogRepository;
    private final com.tourism.forum.repository.ForumUserRestrictionRepository restrictionRepository;
    private final com.tourism.forum.repository.ContentReportRepository reportRepository;

    // ── Posts ──────────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getPosts(AdminPostFilterRequest filter, Pageable pageable) {
        Page<ForumPost> page = forumPostRepository.findAll(
                ForumPostSpecifications.withFilter(filter), pageable);
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        return page.map(p -> mapToListResponse(p, cache));
    }

    @Override
    public PostListResponse togglePin(Integer postId) {
        ForumPost post = getPostOrThrow(postId);
        boolean newPinned = !Boolean.TRUE.equals(post.getIsPinned());
        post.setIsPinned(newPinned);
        forumPostRepository.save(post);
        auditLogService.logPost(
                newPinned ? ModerationAuditLog.AuditAction.PIN : ModerationAuditLog.AuditAction.UNPIN,
                postId, post.getTitle(), null, null, null);
        return mapToListResponse(post, new HashMap<>());
    }

    @Override
    public PostListResponse toggleFeature(Integer postId) {
        ForumPost post = getPostOrThrow(postId);
        boolean newFeatured = !Boolean.TRUE.equals(post.getIsFeatured());
        post.setIsFeatured(newFeatured);
        forumPostRepository.save(post);
        auditLogService.logPost(
                newFeatured ? ModerationAuditLog.AuditAction.FEATURE : ModerationAuditLog.AuditAction.UNFEATURE,
                postId, post.getTitle(), null, null, null);
        return mapToListResponse(post, new HashMap<>());
    }

    @Override
    public PostListResponse changeStatus(Integer postId, ContentStatus status) {
        return changeStatus(postId, status, null);
    }

    @Override
    public PostListResponse changeStatus(Integer postId, ContentStatus status, String rejectionReason) {
        ForumPost post = getPostOrThrow(postId);
        String oldStatus = post.getStatus() != null ? post.getStatus().name() : null;
        applyPostStatus(post, status);
        // Lưu lý do admin khi ẩn; xóa lý do khi duyệt lại
        if (status == ContentStatus.HIDDEN) {
            post.setAdminRejectionReason(rejectionReason);
        } else if (status == ContentStatus.PUBLISHED) {
            post.setAdminRejectionReason(null);
        }
        forumPostRepository.save(post);

        ModerationAuditLog.AuditAction action = status == ContentStatus.PUBLISHED
                ? ModerationAuditLog.AuditAction.APPROVE
                : status == ContentStatus.HIDDEN
                    ? ModerationAuditLog.AuditAction.HIDE
                    : ModerationAuditLog.AuditAction.STATUS_CHANGE;
        auditLogService.logPost(action, postId, post.getTitle(), rejectionReason, oldStatus, status.name());

        return mapToListResponse(post, new HashMap<>());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPostDetailResponse getPostDetail(Integer postId) {
        ForumPost post = getPostOrThrow(postId);
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        UserBriefResponse author = getUserSafe(post.getUserId(), cache);

        List<String> imageUrls = new ArrayList<>();
        if (post.getImages() != null) {
            post.getImages().stream()
                    .sorted(java.util.Comparator.comparing(
                            i -> i.getDisplayOrder() == null ? 0 : i.getDisplayOrder()))
                    .forEach(i -> imageUrls.add(i.getImageUrl()));
        }

        List<PostListResponse.TagInfo> tags = new ArrayList<>();
        if (post.getPostTags() != null) {
            for (PostTag pt : post.getPostTags()) {
                Tag tag = pt.getTag();
                if (tag != null) {
                    tags.add(PostListResponse.TagInfo.builder()
                            .tagId(tag.getTagID()).tagName(tag.getName())
                            .usageCount(tag.getUsageCount()).build());
                }
            }
        }

        PostCategory category = post.getCategory();
        return AdminPostDetailResponse.builder()
                .postId(post.getPostID())
                .title(post.getTitle())
                .content(post.getContent())
                .summary(post.getSummary())
                .thumbnailUrl(post.getThumbnailUrl())
                .imageUrls(imageUrls)
                .tags(tags)
                .categoryId(category != null ? category.getCategoryID() : null)
                .categoryName(category != null ? category.getName() : null)
                .status(post.getStatus() != null ? post.getStatus().name() : null)
                .postType(post.getPostType() != null ? post.getPostType().name() : null)
                .authorId(post.getUserId())
                .authorName(author != null ? author.getFullName() : null)
                .authorEmail(author != null ? author.getEmail() : null)
                .authorAvatar(author != null ? author.getAvatar() : null)
                .moderationScore(post.getModerationScore())
                .moderationLabel(post.getModerationLabel())
                .moderationReason(post.getModerationReason())
                .adminRejectionReason(post.getAdminRejectionReason())
                .viewCount(post.getViewCount())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .bookmarkCount(post.getBookmarkCount())
                .isPinned(post.getIsPinned())
                .isFeatured(post.getIsFeatured())
                .createdAt(post.getCreatedAt())
                .publishedAt(post.getPublishedAt())
                .moderatedAt(post.getModeratedAt())
                .build();
    }

    @Override
    public PostListResponse updatePostContent(Integer postId, PostUpdateRequest request) {
        ForumPost post = getPostOrThrow(postId);
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        if (request.getSummary() != null) post.setSummary(request.getSummary());
        if (request.getThumbnailUrl() != null) post.setThumbnailUrl(request.getThumbnailUrl());
        if (request.getCategoryId() != null) {
            postCategoryRepository.findById(request.getCategoryId()).ifPresent(post::setCategory);
        }
        forumPostRepository.save(post);
        auditLogService.logPost(ModerationAuditLog.AuditAction.EDIT, postId, post.getTitle(),
                "Admin chỉnh sửa nội dung", null, null);
        return mapToListResponse(post, new HashMap<>());
    }

    @Override
    public void softDeletePost(Integer postId) {
        softDeletePost(postId, null);
    }

    @Override
    public void softDeletePost(Integer postId, String reason) {
        ForumPost post = getPostOrThrow(postId);
        post.setIsDeleted(true);
        post.setDeletedAt(LocalDateTime.now());
        post.setDeletedBy(AdminContext.currentUserId());
        post.setDeleteReason(reason);
        forumPostRepository.save(post);
        auditLogService.logPost(ModerationAuditLog.AuditAction.DELETE, postId, post.getTitle(), reason, null, null);
    }

    @Override
    public void bulkPostAction(BulkActionRequest request) {
        List<ForumPost> posts = forumPostRepository.findAllById(request.getIds());
        String action = request.getAction() == null ? "" : request.getAction().toLowerCase();
        for (ForumPost post : posts) {
            switch (action) {
                case "approve", "publish" -> applyPostStatus(post, ContentStatus.PUBLISHED);
                case "reject", "hide"     -> applyPostStatus(post, ContentStatus.HIDDEN);
                case "delete" -> {
                    post.setIsDeleted(true);
                    post.setDeletedAt(LocalDateTime.now());
                }
                default -> throw new IllegalArgumentException("Hành động không hợp lệ: " + request.getAction());
            }
        }
        forumPostRepository.saveAll(posts);
    }

    private void applyPostStatus(ForumPost post, ContentStatus status) {
        post.setStatus(status);
        if (status == ContentStatus.PUBLISHED && post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
    }

    // ── Comments ───────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<AdminCommentResponse> getComments(AdminCommentFilterRequest filter, Pageable pageable) {
        Page<PostComment> page = postCommentRepository.findAll(
                PostCommentSpecifications.withFilter(filter), pageable);
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        return page.map(c -> mapToCommentResponse(c, cache));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminPostWithCommentsResponse> getPostsWithComments(Boolean onlyPending, Pageable pageable) {
        return Boolean.TRUE.equals(onlyPending)
                ? postCommentRepository.findPostsWithPendingComments(pageable)
                : postCommentRepository.findPostsWithComments(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminCommentResponse> getCommentsByPost(Integer postId) {
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        return postCommentRepository.findAllByPostIdForAdmin(postId).stream()
                .map(c -> mapToCommentResponse(c, cache))
                .collect(Collectors.toList());
    }

    @Override
    public void softDeleteComment(Integer commentId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bình luận: " + commentId));
        comment.setIsDeleted(true);
        comment.setDeletedAt(LocalDateTime.now());
        comment.setDeletedBy(AdminContext.currentUserId());
        postCommentRepository.save(comment);
        String preview = comment.getContent() != null && comment.getContent().length() > 120
                ? comment.getContent().substring(0, 120) : comment.getContent();
        auditLogService.logComment(ModerationAuditLog.AuditAction.DELETE, commentId, preview, null, null, null);
    }

    // ── Trash & Audit ────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public List<TrashItemResponse> getTrash(String type) {
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        List<TrashItemResponse> result = new ArrayList<>();

        boolean wantPost = type == null || "POST".equalsIgnoreCase(type) || type.isBlank();
        boolean wantComment = type == null || "COMMENT".equalsIgnoreCase(type) || type.isBlank();

        if (wantPost) {
            for (ForumPost p : forumPostRepository.findDeletedPosts()) {
                UserBriefResponse author = getUserSafe(p.getUserId(), cache);
                result.add(TrashItemResponse.builder()
                        .type("POST").id(p.getPostID()).title(p.getTitle())
                        .authorId(p.getUserId())
                        .authorName(author != null ? author.getFullName() : null)
                        .deletedBy(p.getDeletedBy()).deleteReason(p.getDeleteReason())
                        .deletedAt(p.getDeletedAt())
                        .build());
            }
        }
        if (wantComment) {
            for (PostComment c : postCommentRepository.findDeletedComments()) {
                UserBriefResponse author = getUserSafe(c.getUserId(), cache);
                String preview = c.getContent() != null && c.getContent().length() > 120
                        ? c.getContent().substring(0, 120) + "…" : c.getContent();
                result.add(TrashItemResponse.builder()
                        .type("COMMENT").id(c.getCommentID()).title(preview)
                        .authorId(c.getUserId())
                        .authorName(author != null ? author.getFullName() : null)
                        .deletedBy(c.getDeletedBy()).deleteReason(c.getDeleteReason())
                        .deletedAt(c.getDeletedAt())
                        .build());
            }
        }
        // mới xóa lên đầu
        result.sort((a, b) -> {
            if (a.getDeletedAt() == null) return 1;
            if (b.getDeletedAt() == null) return -1;
            return b.getDeletedAt().compareTo(a.getDeletedAt());
        });
        return result;
    }

    @Override
    public void restorePost(Integer postId) {
        ForumPost post = forumPostRepository.findById(postId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bài viết: " + postId));
        post.setIsDeleted(false);
        post.setDeletedAt(null);
        post.setDeletedBy(null);
        post.setDeleteReason(null);
        forumPostRepository.save(post);
        auditLogService.logPost(ModerationAuditLog.AuditAction.RESTORE, postId, post.getTitle(), null, null, null);
    }

    @Override
    public void restoreComment(Integer commentId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bình luận: " + commentId));
        comment.setIsDeleted(false);
        comment.setDeletedAt(null);
        comment.setDeletedBy(null);
        postCommentRepository.save(comment);
        String preview = comment.getContent() != null && comment.getContent().length() > 120
                ? comment.getContent().substring(0, 120) : comment.getContent();
        auditLogService.logComment(ModerationAuditLog.AuditAction.RESTORE, commentId, preview, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ModerationAuditLog> getAuditLogs(String targetType, Integer targetId, Pageable pageable) {
        if (targetType != null && targetId != null) {
            return auditLogRepository.findByTargetTypeAndTargetId(
                    ModerationAuditLog.TargetType.valueOf(targetType.toUpperCase()), targetId, pageable);
        }
        return auditLogRepository.findAll(pageable);
    }

    // ── User ban (forum-only) ──────────────────────────────────────────────────
    @Override
    public void banUser(Integer userId, BanUserRequest request) {
        // Gỡ ban cũ đang active (nếu có) để tránh trùng
        restrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(old -> { old.setActive(false); restrictionRepository.save(old); });

        LocalDateTime until = (request.getDurationDays() != null && request.getDurationDays() > 0)
                ? LocalDateTime.now().plusDays(request.getDurationDays())
                : null; // null = vĩnh viễn

        restrictionRepository.save(ForumUserRestriction.builder()
                .userId(userId)
                .reason(request.getReason())
                .bannedUntil(until)
                .bannedBy(AdminContext.currentUserId())
                .active(true)
                .build());
        log.info("Banned user {} from forum until {}", userId, until);
    }

    @Override
    public void unbanUser(Integer userId) {
        restrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(r -> { r.setActive(false); restrictionRepository.save(r); });
    }

    @Override
    @Transactional(readOnly = true)
    public List<BannedUserResponse> getBannedUsers() {
        Map<Integer, UserBriefResponse> cache = new HashMap<>();
        List<BannedUserResponse> result = new ArrayList<>();
        for (ForumUserRestriction r : restrictionRepository.findByActiveTrueOrderByCreatedAtDesc()) {
            // Bỏ qua ban đã hết hạn
            if (r.getBannedUntil() != null && r.getBannedUntil().isBefore(LocalDateTime.now())) continue;
            UserBriefResponse u = getUserSafe(r.getUserId(), cache);
            result.add(BannedUserResponse.builder()
                    .restrictionId(r.getId())
                    .userId(r.getUserId())
                    .userName(u != null ? u.getFullName() : null)
                    .userEmail(u != null ? u.getEmail() : null)
                    .reason(r.getReason())
                    .bannedBy(r.getBannedBy())
                    .bannedUntil(r.getBannedUntil())
                    .createdAt(r.getCreatedAt())
                    .build());
        }
        return result;
    }

    // ── Content reports ────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<ContentReport> getReports(String status, Pageable pageable) {
        if (status != null && !status.isBlank()) {
            return reportRepository.findByStatusOrderByCreatedAtDesc(
                    ReportStatus.valueOf(status.toUpperCase()), pageable);
        }
        return reportRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    public void resolveReport(Long reportId, String action) {
        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy báo cáo: " + reportId));
        if ("RESOLVE".equalsIgnoreCase(action)) {
            report.setStatus(ReportStatus.RESOLVED);
            // Ẩn luôn nội dung bị báo cáo
            if (report.getTargetType() == ModerationAuditLog.TargetType.POST) {
                forumPostRepository.findById(report.getTargetId()).ifPresent(p -> {
                    p.setStatus(ContentStatus.HIDDEN);
                    forumPostRepository.save(p);
                    auditLogService.logPost(ModerationAuditLog.AuditAction.HIDE, p.getPostID(),
                            p.getTitle(), "Ẩn do báo cáo vi phạm", null, null);
                });
            } else {
                postCommentRepository.findById(report.getTargetId()).ifPresent(c -> {
                    c.setStatus(ContentStatus.HIDDEN);
                    postCommentRepository.save(c);
                });
            }
        } else {
            report.setStatus(ReportStatus.DISMISSED);
        }
        reportRepository.save(report);
    }

    @Override
    public void bulkCommentAction(BulkActionRequest request) {
        List<PostComment> comments = postCommentRepository.findAllById(request.getIds());
        String action = request.getAction() == null ? "" : request.getAction().toLowerCase();
        for (PostComment comment : comments) {
            switch (action) {
                case "approve", "publish" -> comment.setStatus(ContentStatus.PUBLISHED);
                case "reject", "hide"     -> comment.setStatus(ContentStatus.HIDDEN);
                case "delete"             -> comment.setIsDeleted(true);
                default -> throw new IllegalArgumentException("Hành động không hợp lệ: " + request.getAction());
            }
        }
        postCommentRepository.saveAll(comments);
    }

    // ── Categories ───────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public List<AdminCategoryResponse> getCategories() {
        return postCategoryRepository.findAllActive().stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AdminCategoryResponse createCategory(CategoryRequest request) {
        PostCategory category = new PostCategory();
        applyCategoryFields(category, request);
        if (category.getDisplayOrder() == null) {
            category.setDisplayOrder(0);
        }
        if (category.getIsActive() == null) {
            category.setIsActive(true);
        }
        return mapToCategoryResponse(postCategoryRepository.save(category));
    }

    @Override
    public AdminCategoryResponse updateCategory(Integer categoryId, CategoryRequest request) {
        PostCategory category = postCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy danh mục: " + categoryId));
        applyCategoryFields(category, request);
        return mapToCategoryResponse(postCategoryRepository.save(category));
    }

    @Override
    public void deleteCategory(Integer categoryId) {
        PostCategory category = postCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy danh mục: " + categoryId));
        long postCount = forumPostRepository.countByCategoryCategoryIDAndIsDeletedFalse(categoryId);
        if (postCount > 0) {
            throw new IllegalStateException(
                    "Danh mục đang có " + postCount + " bài viết, không thể xóa.");
        }
        category.setIsDeleted(true);
        category.setIsActive(false);
        postCategoryRepository.save(category);
    }

    @Override
    public void reorderCategories(CategoryReorderRequest request) {
        Map<Integer, Integer> orderById = request.getItems().stream()
                .collect(Collectors.toMap(
                        CategoryReorderRequest.OrderItem::getCategoryId,
                        CategoryReorderRequest.OrderItem::getDisplayOrder));
        List<PostCategory> categories = postCategoryRepository.findAllById(orderById.keySet());
        categories.forEach(c -> c.setDisplayOrder(orderById.get(c.getCategoryID())));
        postCategoryRepository.saveAll(categories);
    }

    private void applyCategoryFields(PostCategory category, CategoryRequest req) {
        category.setName(req.getName());
        category.setSlug(req.getSlug());
        category.setDescription(req.getDescription());
        category.setIconUrl(req.getIconUrl());
        category.setIcon(req.getIcon());
        category.setColor(req.getColor());
        if (req.getDisplayOrder() != null) {
            category.setDisplayOrder(req.getDisplayOrder());
        }
        if (req.getIsActive() != null) {
            category.setIsActive(req.getIsActive());
        }
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public Page<AdminTagResponse> getTags(String search, Pageable pageable) {
        Page<Tag> page = (search == null || search.isBlank())
                ? tagRepository.findAll(pageable)
                : tagRepository.findByNameContainingIgnoreCase(search, pageable);
        return page.map(this::mapToTagResponse);
    }

    @Override
    public AdminTagResponse createTag(TagRequest request) {
        Tag tag = new Tag();
        applyTagFields(tag, request);
        if (tag.getIsActive() == null) {
            tag.setIsActive(true);
        }
        if (tag.getUsageCount() == null) {
            tag.setUsageCount(0);
        }
        return mapToTagResponse(tagRepository.save(tag));
    }

    @Override
    public AdminTagResponse updateTag(Integer tagId, TagRequest request) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy thẻ: " + tagId));
        applyTagFields(tag, request);
        return mapToTagResponse(tagRepository.save(tag));
    }

    @Override
    public void deleteTag(Integer tagId) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy thẻ: " + tagId));
        tag.setIsDeleted(true);
        tag.setIsActive(false);
        tagRepository.save(tag);
    }

    private void applyTagFields(Tag tag, TagRequest req) {
        tag.setName(req.getName());
        tag.setSlug(req.getSlug());
        tag.setColor(req.getColor());
        tag.setDescription(req.getDescription());
        if (req.getIsActive() != null) {
            tag.setIsActive(req.getIsActive());
        }
    }

    // ── Analytics ─────────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public AdminForumStatsResponse getStats() {
        return AdminForumStatsResponse.builder()
                .totalPosts(forumPostRepository.countByIsDeletedFalse())
                .publishedPosts(forumPostRepository.countByStatusAndIsDeletedFalse(ContentStatus.PUBLISHED))
                .pendingPosts(forumPostRepository.countByStatusAndIsDeletedFalse(ContentStatus.PENDING_REVIEW))
                .draftPosts(forumPostRepository.countByStatusAndIsDeletedFalse(ContentStatus.DRAFT))
                .hiddenPosts(forumPostRepository.countByStatusAndIsDeletedFalse(ContentStatus.HIDDEN))
                .totalComments(postCommentRepository.count())
                .pendingComments(postCommentRepository.countByStatus(ContentStatus.PENDING_REVIEW))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminAnalyticsResponse getAnalytics() {
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        // ── Top user vi phạm (30 ngày) ────────────────────────────────────
        Map<Integer, UserBriefResponse> userCache = new HashMap<>();
        List<AdminAnalyticsResponse.ViolatorCount> violators = forumPostRepository
                .topViolators(since, PageRequest.of(0, 10)).stream()
                .map(r -> {
                    Integer uid = ((Number) r[0]).intValue();
                    UserBriefResponse u = getUserSafe(uid, userCache);
                    return AdminAnalyticsResponse.ViolatorCount.builder()
                            .userId(uid)
                            .userName(u != null ? u.getFullName() : null)
                            .userEmail(u != null ? u.getEmail() : null)
                            .violationCount(((Number) r[1]).longValue())
                            .build();
                })
                .collect(Collectors.toList());

        // ── AI accuracy (30 ngày) ─────────────────────────────────────────
        long totalFlagged = 0, confirmed = 0, overruled = 0;
        for (Object[] row : forumPostRepository.aiLabelVsFinalStatus(since)) {
            String label = String.valueOf(row[0]);
            ContentStatus status = (ContentStatus) row[1];
            long cnt = ((Number) row[2]).longValue();
            // AI chỉ "flag" khi label TOXIC hoặc BORDERLINE; CLEAN không tính
            if (!"TOXIC".equalsIgnoreCase(label) && !"BORDERLINE".equalsIgnoreCase(label)) continue;
            totalFlagged += cnt;
            if (status == ContentStatus.HIDDEN) confirmed += cnt;
            else if (status == ContentStatus.PUBLISHED) overruled += cnt;
        }
        Double precision = totalFlagged == 0 ? null
                : Math.round((confirmed * 1000.0 / totalFlagged)) / 10.0;
        AdminAnalyticsResponse.AiAccuracy aiAccuracy = AdminAnalyticsResponse.AiAccuracy.builder()
                .totalFlagged(totalFlagged)
                .confirmedByAdmin(confirmed)
                .overruledByAdmin(overruled)
                .precisionPercent(precision)
                .build();

        return AdminAnalyticsResponse.builder()
                .postsByType(toCountMap(forumPostRepository.countGroupedByPostType()))
                .postsByStatus(toCountMap(forumPostRepository.countGroupedByStatus()))
                .moderationDistribution(toCountMap(forumPostRepository.countGroupedByModerationLabel()))
                .postsLast30Days(forumPostRepository.countDailySince(since).stream()
                        .map(r -> AdminAnalyticsResponse.DailyCount.builder()
                                .date(String.valueOf(r[0]))
                                .count(((Number) r[1]).longValue())
                                .build())
                        .collect(Collectors.toList()))
                .topCategories(postCategoryRepository.findTopCategoriesWithCount(PageRequest.of(0, 5)).stream()
                        .map(r -> AdminAnalyticsResponse.NameCount.builder()
                                .name(String.valueOf(r[0]))
                                .count(((Number) r[1]).longValue())
                                .build())
                        .collect(Collectors.toList()))
                .topTags(tagRepository.findPopularTagsWithCount(PageRequest.of(0, 5)).stream()
                        .map(r -> AdminAnalyticsResponse.NameCount.builder()
                                .name(String.valueOf(r[1]))
                                .count(((Number) r[2]).longValue())
                                .build())
                        .collect(Collectors.toList()))
                .topViolators(violators)
                .aiAccuracy(aiAccuracy)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public String exportModerationCsv(java.time.LocalDate from, java.time.LocalDate to) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate effFrom = from != null ? from : today.minusDays(30);
        java.time.LocalDate effTo = to != null ? to : today;
        LocalDateTime start = effFrom.atStartOfDay();
        LocalDateTime end = effTo.plusDays(1).atStartOfDay();

        List<ModerationAuditLog> logs = auditLogRepository
                .findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

        StringBuilder sb = new StringBuilder();
        sb.append("Thoi gian,Hanh dong,Loai,Target ID,Tieu de/Preview,Actor,Email,Ly do,Old,New\n");
        for (ModerationAuditLog l : logs) {
            sb.append(csv(l.getCreatedAt() != null ? l.getCreatedAt().toString() : ""))
              .append(',').append(csv(String.valueOf(l.getAction())))
              .append(',').append(csv(String.valueOf(l.getTargetType())))
              .append(',').append(csv(String.valueOf(l.getTargetId())))
              .append(',').append(csv(l.getTargetTitle()))
              .append(',').append(csv(String.valueOf(l.getActorType())))
              .append(',').append(csv(l.getActorEmail()))
              .append(',').append(csv(l.getReason()))
              .append(',').append(csv(l.getOldValue()))
              .append(',').append(csv(l.getNewValue()))
              .append('\n');
        }
        return sb.toString();
    }

    /** CSV-escape: nếu giá trị có dấu phẩy/ngoặc kép/xuống dòng thì bọc trong "" và escape "". */
    private String csv(String v) {
        if (v == null) return "";
        boolean needQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String escaped = v.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            map.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return map;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private ForumPost getPostOrThrow(Integer postId) {
        return forumPostRepository.findByIdAndNotDeleted(postId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bài viết: " + postId));
    }

    private UserBriefResponse getUserSafe(Integer userId, Map<Integer, UserBriefResponse> cache) {
        if (userId == null) {
            return null;
        }
        if (cache.containsKey(userId)) {
            return cache.get(userId);
        }
        UserBriefResponse user = null;
        try {
            user = iamFeignClient.getUserById(userId);
        } catch (Exception e) {
            log.warn("Không lấy được thông tin user {}: {}", userId, e.getMessage());
        }
        cache.put(userId, user);
        return user;
    }

    private PostListResponse mapToListResponse(ForumPost post, Map<Integer, UserBriefResponse> cache) {
        UserBriefResponse author = getUserSafe(post.getUserId(), cache);

        List<PostListResponse.TagInfo> tags = new ArrayList<>();
        if (post.getPostTags() != null) {
            for (PostTag pt : post.getPostTags()) {
                Tag tag = pt.getTag();
                if (tag != null) {
                    tags.add(PostListResponse.TagInfo.builder()
                            .tagId(tag.getTagID())
                            .tagName(tag.getName())
                            .usageCount(tag.getUsageCount())
                            .build());
                }
            }
        }

        PostCategory category = post.getCategory();
        return PostListResponse.builder()
                .postID(post.getPostID())
                .title(post.getTitle())
                .summary(post.getSummary())
                .thumbnailUrl(post.getThumbnailUrl())
                .postType(post.getPostType() != null ? post.getPostType().name() : null)
                .authorId(post.getUserId())
                .authorName(author != null ? author.getFullName() : null)
                .authorAvatar(author != null ? author.getAvatar() : null)
                .categoryId(category != null ? category.getCategoryID() : null)
                .categoryName(category != null ? category.getName() : null)
                .categorySlug(category != null ? category.getSlug() : null)
                .tags(tags)
                .viewCount(post.getViewCount())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .bookmarkCount(post.getBookmarkCount())
                .isPinned(post.getIsPinned())
                .isFeatured(post.getIsFeatured())
                .status(post.getStatus() != null ? post.getStatus().name() : null)
                .moderationLabel(post.getModerationLabel())
                .moderationReason(post.getModerationReason())
                .moderationScore(post.getModerationScore())
                .createdAt(post.getCreatedAt())
                .publishedAt(post.getPublishedAt())
                .build();
    }

    private AdminCommentResponse mapToCommentResponse(PostComment comment, Map<Integer, UserBriefResponse> cache) {
        UserBriefResponse author = getUserSafe(comment.getUserId(), cache);
        ForumPost post = comment.getPost();
        String content = comment.getContent();
        String preview = content != null && content.length() > 140
                ? content.substring(0, 140) + "…"
                : content;

        return AdminCommentResponse.builder()
                .commentId(comment.getCommentID())
                .content(content)
                .contentPreview(preview)
                .likeCount(comment.getLikeCount())
                .authorId(comment.getUserId())
                .authorName(author != null ? author.getFullName() : null)
                .authorAvatar(author != null ? author.getAvatar() : null)
                .postId(post != null ? post.getPostID() : null)
                .postTitle(post != null ? post.getTitle() : null)
                .parentCommentId(comment.getParentComment() != null ? comment.getParentComment().getCommentID() : null)
                .status(comment.getStatus() != null ? comment.getStatus().name() : null)
                .isEdited(comment.getIsEdited())
                .moderationLabel(comment.getModerationLabel())
                .moderationReason(comment.getModerationReason())
                .moderationScore(comment.getModerationScore())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private AdminCategoryResponse mapToCategoryResponse(PostCategory c) {
        return AdminCategoryResponse.builder()
                .categoryId(c.getCategoryID())
                .name(c.getName())
                .slug(c.getSlug())
                .description(c.getDescription())
                .iconUrl(c.getIconUrl())
                .icon(c.getIcon())
                .color(c.getColor())
                .displayOrder(c.getDisplayOrder())
                .isActive(c.getIsActive())
                .postCount(c.getPostCount())
                .createdAt(c.getCreatedAt())
                .build();
    }

    private AdminTagResponse mapToTagResponse(Tag t) {
        return AdminTagResponse.builder()
                .tagId(t.getTagID())
                .name(t.getName())
                .slug(t.getSlug())
                .color(t.getColor())
                .description(t.getDescription())
                .isActive(t.getIsActive())
                .usageCount(t.getUsageCount())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
