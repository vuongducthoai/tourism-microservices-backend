package com.tourism.forum.service.impl;

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
import com.tourism.forum.repository.*;
import com.tourism.forum.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;

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
    public PostDetailResponse createPost(CreatePostRequest request) {
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
        ForumPost post = forumPostRepository.findById(postId)
            .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        PostComment comment = new PostComment();
        comment.setContent(request.getContent());
        comment.setUserId(request.getUserId());
        comment.setPost(post);
        comment.setLikeCount(0);

        if (request.getParentCommentId() != null) {
            PostComment parent = commentRepository.findById(request.getParentCommentId())
                .orElse(null);
            comment.setParentComment(parent);
        }

        commentRepository.save(comment);
        post.setCommentCount(post.getCommentCount() + 1);
        forumPostRepository.save(post);

        return mapToDetailResponse(post, request.getUserId());
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
}
