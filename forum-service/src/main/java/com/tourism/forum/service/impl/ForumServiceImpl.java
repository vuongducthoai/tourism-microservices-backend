package com.tourism.forum.service.impl;

import com.tourism.forum.dto.event.ForumNotificationEvent;
import com.tourism.forum.dto.moderation.ModerationResult;
import com.tourism.forum.dto.request.CommentRequest;
import com.tourism.forum.dto.request.CreatePostRequest;
import com.tourism.forum.dto.request.PostFilterRequest;
import com.tourism.forum.dto.request.PostUpdateRequest;
import com.tourism.forum.dto.response.CategoryResponse;
import com.tourism.forum.dto.response.CommentResponse;
import com.tourism.forum.dto.response.PostDetailResponse;
import com.tourism.forum.dto.response.PostListResponse;
import com.tourism.forum.entity.*;
import com.tourism.forum.feign.IamFeignClient;
import com.tourism.forum.feign.dto.UserBriefResponse;
import com.tourism.forum.messaging.ForumEventPublisher;
import com.tourism.forum.repository.*;
import com.tourism.forum.service.ForumService;
import com.tourism.forum.service.ModerationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ForumServiceImpl implements ForumService {

    private final ForumPostRepository forumPostRepository;
    private final PostCategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final PostCommentRepository commentRepository;
    private final PostLikeRepository postLikeRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final IamFeignClient iamFeignClient;
    private final ForumEventPublisher forumEventPublisher;
    private final FollowerRepository followerRepository;
    private final ModerationService moderationService;
    private final com.tourism.forum.service.ForumRateLimitService rateLimitService;
    private final com.tourism.forum.repository.ForumUserRestrictionRepository restrictionRepository;
    private final com.tourism.forum.repository.ContentReportRepository reportRepository;

    // Simple in-memory cache cho user info (tránh Feign call lặp lại)
    private final Map<Integer, UserBriefResponse> userCache = new ConcurrentHashMap<>();

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private UserBriefResponse getUserSafe(Integer userId) {
        if (userId == null) return null;
        return userCache.computeIfAbsent(userId, id -> {
            try {
                return iamFeignClient.getUserById(id);
            } catch (Exception e) {
                log.warn("Could not fetch user {}: {}", id, e.getMessage());
                return null;
            }
        });
    }

    private PostListResponse mapToListResponse(ForumPost post) {
        return mapToListResponse(post, null);
    }

    private PostListResponse mapToListResponse(ForumPost post, Integer currentUserId) {
        UserBriefResponse author = getUserSafe(post.getUserId());
        List<PostListResponse.TagInfo> tags = post.getPostTags() == null ? List.of() :
            post.getPostTags().stream()
                .filter(pt -> pt.getTag() != null)
                .map(pt -> PostListResponse.TagInfo.builder()
                    .tagId(pt.getTag().getTagID())
                    .tagName(pt.getTag().getName())
                    .build())
                .collect(Collectors.toList());

        boolean isLiked = currentUserId != null &&
            postLikeRepository.existsByPostPostIDAndUserId(post.getPostID(), currentUserId);
        boolean isBookmarked = currentUserId != null &&
            postBookmarkRepository.existsByPostIdAndUserId(post.getPostID(), currentUserId);

        return PostListResponse.builder()
            .postID(post.getPostID())
            .title(post.getTitle())
            .summary(post.getSummary())
            .thumbnailUrl(post.getThumbnailUrl())
            .postType(post.getPostType() != null ? post.getPostType().name() : null)
            .authorId(post.getUserId())
            .authorName(author != null ? author.getFullName() : "Ẩn danh")
            .authorAvatar(author != null ? author.getAvatar() : null)
            .categoryId(post.getCategory() != null ? post.getCategory().getCategoryID() : null)
            .categoryName(post.getCategory() != null ? post.getCategory().getName() : null)
            .categorySlug(post.getCategory() != null ? post.getCategory().getSlug() : null)
            .tags(tags)
            .viewCount(post.getViewCount())
            .likeCount(post.getLikeCount())
            .commentCount(post.getCommentCount())
            .bookmarkCount(post.getBookmarkCount())
            .isPinned(post.getIsPinned())
            .isFeatured(post.getIsFeatured())
            .status(post.getStatus() != null ? post.getStatus().name() : null)
            .isLikedByCurrentUser(isLiked)
            .isBookmarkedByCurrentUser(isBookmarked)
            .moderationLabel(post.getModerationLabel())
            .moderationReason(post.getModerationReason())
            .moderationScore(post.getModerationScore())
            .createdAt(post.getCreatedAt())
            .publishedAt(post.getPublishedAt())
            .build();
    }

    /** Điểm tương tác của một bình luận = số like + số phản hồi (chưa bị xóa). */
    private int engagementScore(PostComment c) {
        int likes = c.getLikeCount() == null ? 0 : c.getLikeCount();
        int replies = c.getReplies() == null ? 0 : (int) c.getReplies().stream()
            .filter(r -> r.getIsDeleted() == null || !r.getIsDeleted())
            .count();
        return likes + replies;
    }

    private CommentResponse mapToCommentResponse(PostComment comment, Integer currentUserId) {
        UserBriefResponse author = getUserSafe(comment.getUserId());
        // Replies: Facebook hiển thị theo thứ tự thời gian cũ → mới
        List<CommentResponse> replies = comment.getReplies() == null ? List.of() :
            comment.getReplies().stream()
                .filter(r -> r.getIsDeleted() == null || !r.getIsDeleted())
                .filter(r -> ContentStatus.PUBLISHED.equals(r.getStatus()))
                .sorted(Comparator.comparing(
                    r -> r.getCreatedAt() == null ? java.time.LocalDateTime.MIN : r.getCreatedAt()))
                .map(r -> mapToCommentResponse(r, currentUserId))
                .collect(Collectors.toList());

        boolean isLiked = currentUserId != null &&
            commentLikeRepository.existsByCommentCommentIDAndUserId(comment.getCommentID(), currentUserId);

        return CommentResponse.builder()
            .commentId(comment.getCommentID())
            .content(comment.getContent())
            .likeCount(comment.getLikeCount())
            .userId(comment.getUserId())
            .authorName(author != null ? author.getFullName() : "Ẩn danh")
            .authorAvatar(author != null ? author.getAvatar() : null)
            .isLikedByCurrentUser(isLiked)
            .createdAt(comment.getCreatedAt())
            .replies(replies)
            .build();
    }

    private PostDetailResponse mapToDetailResponse(ForumPost post, Integer currentUserId) {
        UserBriefResponse author = getUserSafe(post.getUserId());
        List<PostListResponse.TagInfo> tags = post.getPostTags() == null ? List.of() :
            post.getPostTags().stream()
                .filter(pt -> pt.getTag() != null)
                .map(pt -> PostListResponse.TagInfo.builder()
                    .tagId(pt.getTag().getTagID())
                    .tagName(pt.getTag().getName())
                    .build())
                .collect(Collectors.toList());

        // "Phù hợp nhất" kiểu Facebook: ưu tiên bình luận có nhiều tương tác
        // (số like + số phản hồi) lên đầu; cùng điểm thì bình luận MỚI hơn
        // hiển thị trước.
        List<CommentResponse> comments = commentRepository.findTopLevelByPost(post).stream()
            .sorted(Comparator
                .comparingInt(this::engagementScore).reversed()
                .thenComparing(c -> c.getCreatedAt() == null ? java.time.LocalDateTime.MIN : c.getCreatedAt(),
                    Comparator.reverseOrder()))
            .map(c -> mapToCommentResponse(c, currentUserId))
            .collect(Collectors.toList());

        boolean isLiked = currentUserId != null &&
            postLikeRepository.existsByPostPostIDAndUserId(post.getPostID(), currentUserId);

        return PostDetailResponse.builder()
            .postId(post.getPostID())
            .title(post.getTitle())
            .content(post.getContent())
            .summary(post.getSummary())
            .thumbnailUrl(post.getThumbnailUrl())
            .postType(post.getPostType() != null ? post.getPostType().name() : null)
            .status(post.getStatus() != null ? post.getStatus().name() : null)
            .authorId(post.getUserId())
            .authorName(author != null ? author.getFullName() : "Ẩn danh")
            .authorAvatar(author != null ? author.getAvatar() : null)
            .categoryId(post.getCategory() != null ? post.getCategory().getCategoryID() : null)
            .categoryName(post.getCategory() != null ? post.getCategory().getName() : null)
            .tags(tags)
            .viewCount(post.getViewCount())
            .likeCount(post.getLikeCount())
            .commentCount(post.getCommentCount())
            .bookmarkCount(post.getBookmarkCount())
            .shareCount(post.getShareCount())
            .isPinned(post.getIsPinned())
            .isFeatured(post.getIsFeatured())
            .isLikedByCurrentUser(isLiked)
            .isBookmarkedByCurrentUser(false)
            .comments(comments)
            .moderationLabel(post.getModerationLabel())
            .moderationReason(post.getModerationReason())
            .moderationScore(post.getModerationScore())
            .createdAt(post.getCreatedAt())
            .updatedAt(post.getUpdatedAt())
            .publishedAt(post.getPublishedAt())
            .build();
    }

    // ── Posts ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getPosts(PostFilterRequest filter, Pageable pageable) {
        return getPosts(filter, pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getPosts(PostFilterRequest filter, Pageable pageable, Integer currentUserId) {
        Specification<ForumPost> spec = Specification.where(
            (root, q, cb) -> cb.and(
                cb.equal(root.get("status"), ContentStatus.PUBLISHED),
                cb.or(cb.isNull(root.get("isDeleted")), cb.isFalse(root.get("isDeleted")))
            )
        );

        if (filter.getCategoryId() != null) {
            spec = spec.and((root, q, cb) ->
                cb.equal(root.get("category").get("categoryID"), filter.getCategoryId()));
        }
        if (filter.getTagId() != null) {
            spec = spec.and((root, q, cb) ->
                cb.equal(root.join("postTags").get("tag").get("tagID"), filter.getTagId()));
        }
        if (filter.getPostType() != null && !filter.getPostType().isEmpty()) {
            spec = spec.and((root, q, cb) -> {
                try {
                    PostType pt = PostType.valueOf(filter.getPostType().toUpperCase());
                    return cb.equal(root.get("postType"), pt);
                } catch (Exception e) {
                    return cb.conjunction();
                }
            });
        }
        if (filter.getSearch() != null && !filter.getSearch().isEmpty()) {
            String pattern = "%" + filter.getSearch().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("content")), pattern)
            ));
        }

        return forumPostRepository.findAll(spec, pageable)
            .map(p -> mapToListResponse(p, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getTrendingPosts(Pageable pageable) {
        return getTrendingPosts(pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getTrendingPosts(Pageable pageable, Integer currentUserId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return forumPostRepository.findTrendingPosts(since, pageable)
            .map(p -> mapToListResponse(p, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getPostsByUser(Integer userId, Pageable pageable) {
        return getPostsByUser(userId, pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getPostsByUser(Integer userId, Pageable pageable, Integer currentUserId) {
        return forumPostRepository.findByUserIdAndStatus(userId, ContentStatus.PUBLISHED, pageable)
            .map(p -> mapToListResponse(p, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostListResponse> getMyPostsForManagement(Integer userId, Pageable pageable) {
        return forumPostRepository.findByUserIdAndNotDeleted(userId, pageable)
            .map(p -> mapToListResponse(p, userId));
    }

    public PostListResponse mapPostToListResponse(ForumPost post, Integer currentUserId) {
        return mapToListResponse(post, currentUserId);
    }

    @Override
    public PostDetailResponse createPost(CreatePostRequest request) {
        // Chặn user bị cấm forum (không ảnh hưởng tính năng khác)
        checkForumBan(request.getUserId());
        // Anti-spam: draft không tính quota, chỉ giới hạn bài publish
        if (!Boolean.TRUE.equals(request.getIsDraft())) {
            rateLimitService.checkPostLimit(request.getUserId());
            rateLimitService.checkDuplicate(request.getUserId(), request.getTitle());
        }

        PostCategory category = categoryRepository.findById(request.getCategoryId())
            .orElseThrow(() -> new RuntimeException("Category not found: " + request.getCategoryId()));

        PostType postType;
        try {
            postType = PostType.valueOf(request.getPostType().toUpperCase());
        } catch (Exception e) {
            postType = PostType.BLOG;
        }

        String summary = request.getSummary();
        if (summary == null || summary.isBlank()) {
            String plain = request.getContent().replaceAll("<[^>]*>", "").trim();
            summary = plain.length() > 200 ? plain.substring(0, 197) + "..." : plain;
        }

        ForumPost post = ForumPost.builder()
            .userId(request.getUserId())
            .category(category)
            .title(request.getTitle())
            .content(request.getContent())
            .summary(summary)
            .postType(postType)
            .status(Boolean.TRUE.equals(request.getIsDraft()) ? ContentStatus.DRAFT : ContentStatus.PUBLISHED)
            .publishedAt(Boolean.TRUE.equals(request.getIsDraft()) ? null : LocalDateTime.now())
            .viewCount(0)
            .likeCount(0)
            .commentCount(0)
            .bookmarkCount(0)
            .shareCount(0)
            .isPinned(false)
            .isFeatured(false)
            .build();

        // ── AI Moderation ──────────────────────────────────────────────────────────
        // Chỉ moderate khi user muốn PUBLISH (không moderate bản nháp)
        if (ContentStatus.PUBLISHED.equals(post.getStatus())) {
            String textToAnalyze = post.getTitle() + "\n" + stripHtml(post.getContent());
            ModerationResult mod = moderationService.analyze(textToAnalyze, "post");

            post.setModerationScore(mod.getScore());
            post.setModerationLabel(mod.getLabel());
            post.setModerationReason(mod.getReason());
            post.setModeratedAt(LocalDateTime.now());

            switch (mod.getLabel()) {
                case "TOXIC" -> {
                    post.setStatus(ContentStatus.HIDDEN);
                    log.info("Post blocked by AI: score={}, reason={}", mod.getScore(), mod.getReason());
                }
                case "BORDERLINE" -> {
                    post.setStatus(ContentStatus.PENDING_REVIEW);
                    log.info("Post sent to review queue: score={}", mod.getScore());
                }
            }

            if("TOXIC".equals(mod.getLabel())){
                    forumEventPublisher.publishModerationEvent(
                        post.getUserId(),
                        "POST_REJECTED",
                        "Bài viết vi phạm tiêu chuẩn cộng đồng",
                        mod.getReason()
                    );
            }
            if ("BORDERLINE".equals(mod.getLabel())) {
                forumEventPublisher.publishModerationEvent(
                post.getUserId(),
                "POST_PENDING",
                "Bài viết đang chờ kiểm duyệt",
                "Chúng tôi sẽ xem xét và phản hồi sớm nhất có thể"
            );
        }   
    }
        // ──────────────────────────────────────────────────────────────────────────
        forumPostRepository.save(post);

        // Tags
        if (request.getTagNames() != null && !request.getTagNames().isEmpty()) {
            for (String tagName : request.getTagNames()) {
                if (tagName == null || tagName.isBlank()) continue;
                Tag tag = tagRepository.findByName(tagName.trim())
                    .orElseGet(() -> {
                        Tag t = new Tag();
                        t.setName(tagName.trim());
                        t.setSlug(tagName.trim().toLowerCase().replaceAll("\\s+", "-"));
                        return tagRepository.save(t);
                    });
                PostTag pt = new PostTag();
                pt.setPost(post);
                pt.setTag(tag);
                postTagRepository.save(pt);
            }
            post = forumPostRepository.findById(post.getPostID()).orElse(post);
        }

        // Thông báo cho tát cả follower về bài viết mới
        // (chỉ thông báo khi PUBLISHED, không thông báo DRAFT)
        if(!Boolean.TRUE.equals(request.getIsDraft())){
            List<Integer> followerIds = followerRepository.findFollowerIdsByFollowingUserId(request.getUserId());

            if(!followerIds.isEmpty()){
                UserBriefResponse author = getUserSafe(request.getUserId());
                String authorName = author != null ? author.getFullName() : "Ai đó";
                String authorAvatar = author != null ? author.getAvatar() : null;   

                ForumPost finalPost = post;
                for(Integer followerId : followerIds){
                    forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                        .idempotencyKey("NEW_POST-" + finalPost.getPostID() +  "-follower-" + followerId)
                        .eventType("NEW_POST_FROM_FOLLOWING")
                        .recipientUserId(followerId)
                        .actorUserId(request.getUserId())
                        .actorName(authorName)
                        .actorAvatar(authorAvatar)
                        .postId(post.getPostID())
                        .postTitle(post.getTitle())
                        .build());
                }
            }
        }

        return mapToDetailResponse(post, request.getUserId());
    }

    @Override
    public PostDetailResponse getPostDetail(Integer postId, Integer currentUserId) {
        ForumPost post = forumPostRepository.findById(postId)
            .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        // View count is no longer incremented here — refetching the post
        // (like/comment/refresh) must NOT inflate views. A dedicated
        // recordView() endpoint handles this with per-viewer dedupe.
        return mapToDetailResponse(post, currentUserId);
    }

    @Override
    public void recordView(Integer postId, String viewerKey) {
        // Dedupe: count at most one view per viewer per 30-minute window.
        String redisKey = "forum:view:" + postId + ":" + viewerKey;
        Boolean firstView = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, "1", java.time.Duration.ofMinutes(30));

        if (Boolean.TRUE.equals(firstView)) {
            ForumPost post = forumPostRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));
            post.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
            forumPostRepository.save(post);
        }
    }

    @Override
    public void toggleLike(Integer postId, Integer userId) {
        ForumPost post = forumPostRepository.findById(postId)
            .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        Optional<PostLike> existing = postLikeRepository.findByPostPostIDAndUserId(postId, userId);
        if (existing.isPresent()) {
            postLikeRepository.delete(existing.get());
            post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
        } else {
            PostLike like = new PostLike();
            like.setPost(post);
            like.setUserId(userId);
            postLikeRepository.save(like);
            post.setLikeCount(post.getLikeCount() + 1);

            // -- Gửi notification (chỉ khi THÊM like, không gửi khi bỏ like) --
            UserBriefResponse actor = getUserSafe(userId);
             forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("POST_LIKED-" + postId + "-" + userId + "-" + System.currentTimeMillis())
                .eventType("POST_LIKED")
                .recipientUserId(post.getUserId())
                .actorUserId(userId)
                .actorName(actor != null ? actor.getFullName() : "Ai đó")
                .actorAvatar(actor != null ? actor.getAvatar() : null)
                .postId(postId)
                .postTitle(post.getTitle())
                .build());
        }
        forumPostRepository.save(post);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkLikeStatus(Integer postId, Integer userId) {
        if (userId == null) return false;
        return postLikeRepository.existsByPostPostIDAndUserId(postId, userId);
    }

    @Override
    public PostDetailResponse addComment(Integer postId, CommentRequest request) {
        // Chặn user bị cấm forum
        checkForumBan(request.getUserId());
        // Anti-spam: cooldown + quota ngày + chống duplicate
        rateLimitService.checkCommentLimit(request.getUserId());
        rateLimitService.checkDuplicate(request.getUserId(), request.getContent());

        ForumPost post = forumPostRepository.findById(postId)
            .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        PostComment comment = new PostComment();
        comment.setContent(request.getContent());
        comment.setUserId(request.getUserId());
        comment.setPost(post);
        comment.setLikeCount(0);

        PostComment parentComment = null;
        if (request.getParentCommentId() != null) {
            parentComment = commentRepository.findById(request.getParentCommentId()).orElse(null);
            comment.setParentComment(parentComment);
        }

        ModerationResult mod = moderationService.analyze(stripHtml(request.getContent()), "comment");

        comment.setModerationScore(mod.getScore());
        comment.setModerationLabel(mod.getLabel());
        comment.setModerationReason(mod.getReason());
        comment.setModeratedAt(LocalDateTime.now());

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

        PostComment savedComment = commentRepository.save(comment);
        post.setCommentCount(post.getCommentCount() + 1);
        forumPostRepository.save(post);

        // Gửi notification ----------------------------------
        UserBriefResponse actor = getUserSafe(request.getUserId());
        String actorName = actor != null ? actor.getFullName() : "Ai đó";
        String actorAvatar = actor != null ? actor.getAvatar() : null;

        if(parentComment == null){
            // Comment gốc -> thông báo tác giả bài viết 
            forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("POST_COMMENTED-" + postId + "-" + savedComment.getCommentID())
                .eventType("POST_COMMENTED")
                .recipientUserId(post.getUserId())
                .actorUserId(request.getUserId())
                .actorName(actorName)
                .actorAvatar(actorAvatar)
                .postId(postId)
                .postTitle(post.getTitle())
                .commentId(savedComment.getCommentID())
                .build());
        } else {
            //Reply -> thông báo tác giả comment cha 
            forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("COMMENT_REPLIED-" + savedComment.getCommentID())
                .eventType("COMMENT_REPLIED")
                .recipientUserId(parentComment.getUserId())
                .actorUserId(request.getUserId())
                .actorName(actorName)
                .actorAvatar(actorAvatar)
                .postId(postId)
                .postTitle(post.getTitle())
                .commentId(savedComment.getCommentID())
                .parentCommentId(parentComment.getCommentID())
                .build());
        }
        return withCommentModeration(mapToDetailResponse(post, request.getUserId()), mod);
    }

    /** Gắn kết quả kiểm duyệt của comment vừa gửi vào response để FE thông báo cho user. */
    private PostDetailResponse withCommentModeration(PostDetailResponse resp, ModerationResult mod) {
        resp.setCommentModerationLabel(mod.getLabel());
        resp.setCommentModerationReason(mod.getReason());
        resp.setCommentModerationScore(mod.getScore());
        return resp;
    }

    @Override
    public void toggleCommentLike(Integer commentId, Integer userId) {
        PostComment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new RuntimeException("Comment not found: " + commentId));

        Optional<CommentLike> existing =
            commentLikeRepository.findByCommentCommentIDAndUserId(commentId, userId);

        if (existing.isPresent()) {
            commentLikeRepository.delete(existing.get());
            comment.setLikeCount(Math.max(0, (comment.getLikeCount() == null ? 0 : comment.getLikeCount()) - 1));
        } else {
            CommentLike like = new CommentLike();
            like.setUserId(userId);
            like.setComment(comment);
            commentLikeRepository.save(like);
            comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);
            
            // -- Gửi notification (chỉ khi THÊM like) --
            UserBriefResponse actor = getUserSafe(userId);
             forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("COMMENT_LIKED-" + commentId + "-" + userId + "-" + System.currentTimeMillis())
                .eventType("COMMENT_LIKED")
                .recipientUserId(comment.getUserId())
                .actorUserId(userId)
                .actorName(actor != null ? actor.getFullName() : "Ai đó")
                .actorAvatar(actor != null ? actor.getAvatar() : null)
                .postId(comment.getPost() != null ? comment.getPost().getPostID() : null)
                .postTitle(comment.getPost() != null ? comment.getPost().getTitle() : null)
                .commentId(commentId)
                .build());
        }
        commentRepository.save(comment);
    }

    // ── Categories ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAllActive().stream()
            .map(c -> CategoryResponse.builder()
                .categoryId(c.getCategoryID())
                .name(c.getName())
                .slug(c.getSlug())
                .description(c.getDescription())
                .iconUrl(c.getIconUrl())
                .displayOrder(c.getDisplayOrder())
                .postCount(c.getPosts() != null ? c.getPosts().size() : 0)
                .build())
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> getPopularCategories(int limit) {
        return categoryRepository.findPopularCategories(PageRequest.of(0, limit)).stream()
            .map(c -> CategoryResponse.builder()
                .categoryId(c.getCategoryID())
                .name(c.getName())
                .slug(c.getSlug())
                .iconUrl(c.getIconUrl())
                .postCount(c.getPosts() != null ? c.getPosts().size() : 0)
                .build())
            .collect(Collectors.toList());
    }

    // ── Tags ──────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PostListResponse.TagInfo> getPopularTags(int limit) {
        return tagRepository.findPopularTagsWithCount(PageRequest.of(0, limit)).stream()
            .map(row -> PostListResponse.TagInfo.builder()
                .tagId(((Number) row[0]).intValue())
                .tagName((String) row[1])
                .usageCount(((Number) row[2]).intValue())
                .build())
            .collect(Collectors.toList());
    }

    // ── User Stats ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> getUserStats(Integer userId) {
        long postCount = forumPostRepository.findByUserIdAndStatus(
            userId, ContentStatus.PUBLISHED, PageRequest.of(0, 1)).getTotalElements();
        long likeCount = postLikeRepository.countByUserId(userId);
        long commentCount = commentRepository.countByUserId(userId);

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("postCount", postCount);
        stats.put("likeCount", likeCount);
        stats.put("commentCount", commentCount);
        return stats;
    }

    // ── Bookmark ──────────────────────────────────────────────────────────────────

    @Override
    public boolean toggleBookmark(Integer postId, Integer userId) {
        if (userId == null) {
            return false;
        }

        Optional<PostBookmark> existing = postBookmarkRepository.findByPostIdAndUserId(postId, userId);

        if (existing.isPresent()) {
            postBookmarkRepository.delete(existing.get());
            return false;
        } else {
            ForumPost post = forumPostRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found"));

            PostBookmark bookmark = new PostBookmark();
            bookmark.setUserId(userId);
            bookmark.setPost(post);
            postBookmarkRepository.save(bookmark);

            post.setBookmarkCount((post.getBookmarkCount() != null ? post.getBookmarkCount() : 0) + 1);
            forumPostRepository.save(post);

            return true;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkBookmarkStatus(Integer postId, Integer userId) {
        if (userId == null) {
            return false;
        }
        return postBookmarkRepository.existsByPostIdAndUserId(postId, userId);
    }

    // ── Update Post ───────────────────────────────────────────────────────────

    @Override
    public PostDetailResponse updatePost(Integer postId, PostUpdateRequest request, Integer userId) {
        if (userId == null) {
            throw new RuntimeException("Unauthorized: User not authenticated");
        }

        ForumPost post = forumPostRepository.findByIdAndNotDeleted(postId)
            .orElseThrow(() -> new RuntimeException("Post not found"));

        if (!post.getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền sửa bài viết này");
        }

        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        post.setSummary(request.getSummary());
        post.setThumbnailUrl(request.getThumbnailUrl());
        post.setUpdatedAt(LocalDateTime.now());

        if (request.getCategoryId() != null) {
            PostCategory category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found"));
            post.setCategory(category);
        }

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            postTagRepository.deleteByPostPostID(postId);
            request.getTagIds().forEach(tagId -> {
                Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found"));
                PostTag postTag = new PostTag();
                postTag.setPost(post);
                postTag.setTag(tag);
                postTagRepository.save(postTag);
            });
        }

        ForumPost updated = forumPostRepository.save(post);
        return mapToDetailResponse(updated, userId);
    }

    @Override
    public void deletePost(Integer postId, Integer userId) {
        if (userId == null) {
            throw new RuntimeException("Unauthorized: User not authenticated");
        }

        ForumPost post = forumPostRepository.findById(postId)
            .orElseThrow(() -> new RuntimeException("Post not found"));

        if (!post.getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền xóa bài viết này");
        }

        post.setIsDeleted(true);
        post.setDeletedAt(LocalDateTime.now());
        forumPostRepository.save(post);
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z]+;", " ")   // &nbsp; &amp; v.v.
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Chặn user bị cấm hoạt động forum. Ban đã hết hạn → tự bỏ active. */
    @Override
    public void createReport(Integer reporterId, com.tourism.forum.dto.request.ReportRequest request) {
        if (reporterId == null) throw new RuntimeException("Bạn cần đăng nhập để báo cáo");
        com.tourism.forum.entity.ModerationAuditLog.TargetType targetType =
                com.tourism.forum.entity.ModerationAuditLog.TargetType.valueOf(request.getTargetType().toUpperCase());

        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(reporterId, targetType, request.getTargetId())) {
            throw new RuntimeException("Bạn đã báo cáo nội dung này rồi");
        }

        String preview = null;
        if (targetType == com.tourism.forum.entity.ModerationAuditLog.TargetType.POST) {
            preview = forumPostRepository.findById(request.getTargetId())
                    .map(p -> stripHtml(p.getTitle())).orElse(null);
        } else {
            preview = commentRepository.findById(request.getTargetId())
                    .map(c -> stripHtml(c.getContent())).orElse(null);
        }
        if (preview != null && preview.length() > 500) preview = preview.substring(0, 500);

        reportRepository.save(com.tourism.forum.entity.ContentReport.builder()
                .targetType(targetType)
                .targetId(request.getTargetId())
                .reporterId(reporterId)
                .reason(com.tourism.forum.entity.ReportReason.valueOf(request.getReason().toUpperCase()))
                .detail(request.getDetail())
                .status(com.tourism.forum.entity.ReportStatus.PENDING)
                .targetPreview(preview)
                .build());
    }

    private void checkForumBan(Integer userId) {
        if (userId == null) return;
        restrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
            .ifPresent(r -> {
                LocalDateTime until = r.getBannedUntil();
                if (until != null && until.isBefore(LocalDateTime.now())) {
                    // Ban hết hạn → tự gỡ
                    r.setActive(false);
                    restrictionRepository.save(r);
                    return;
                }
                String when = until == null ? "vĩnh viễn" : "đến " + until.toLocalDate();
                throw new RuntimeException(
                    "Tài khoản của bạn bị hạn chế hoạt động trên diễn đàn (" + when + ")."
                    + (r.getReason() != null ? " Lý do: " + r.getReason() : ""));
            });
    }
}
