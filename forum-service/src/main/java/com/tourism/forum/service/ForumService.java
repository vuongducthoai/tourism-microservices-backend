package com.tourism.forum.service;

import com.tourism.forum.dto.request.CommentRequest;
import com.tourism.forum.dto.request.CreatePostRequest;
import com.tourism.forum.dto.request.PostFilterRequest;
import com.tourism.forum.dto.request.PostUpdateRequest;
import com.tourism.forum.dto.response.CategoryResponse;
import com.tourism.forum.dto.response.PostDetailResponse;
import com.tourism.forum.dto.response.PostListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface ForumService {

    Page<PostListResponse> getPosts(PostFilterRequest filter, Pageable pageable);

    Page<PostListResponse> getPosts(PostFilterRequest filter, Pageable pageable, Integer currentUserId);

    Page<PostListResponse> getTrendingPosts(Pageable pageable);

    Page<PostListResponse> getTrendingPosts(Pageable pageable, Integer currentUserId);

    Page<PostListResponse> getPostsByUser(Integer userId, Pageable pageable);

    Page<PostListResponse> getPostsByUser(Integer userId, Pageable pageable, Integer currentUserId);

    // Trang quản lý: trả tất cả bài kể cả HIDDEN / PENDING_REVIEW
    Page<PostListResponse> getMyPostsForManagement(Integer userId, Pageable pageable);

    PostDetailResponse createPost(CreatePostRequest request);

    PostDetailResponse getPostDetail(Integer postId, Integer currentUserId);

    void recordView(Integer postId, String viewerKey);

    PostDetailResponse updatePost(Integer postId, PostUpdateRequest request, Integer userId);

    void deletePost(Integer postId, Integer userId);

    void changePostStatusByOwner(Integer postId, String status, Integer userId);

    void toggleLike(Integer postId, Integer userId);

    boolean checkLikeStatus(Integer postId, Integer userId);

    PostDetailResponse addComment(Integer postId, CommentRequest request);

    void toggleCommentLike(Integer commentId, Integer userId);

    List<CategoryResponse> getAllCategories();

    List<CategoryResponse> getPopularCategories(int limit);

    List<PostListResponse.TagInfo> getPopularTags(int limit);

    Map<String, Long> getUserStats(Integer userId);

    boolean toggleBookmark(Integer postId, Integer userId);

    boolean checkBookmarkStatus(Integer postId, Integer userId);

    void createReport(Integer reporterId, com.tourism.forum.dto.request.ReportRequest request);

    // Trạng thái hạn chế forum của user (để FE hiện banner cảnh báo)
    com.tourism.forum.dto.response.RestrictionStatusResponse getRestrictionStatus(Integer userId);

    // Sprint A: bookmark list
    org.springframework.data.domain.Page<PostListResponse> getBookmarkedPosts(
            Integer userId, org.springframework.data.domain.Pageable pageable);

    // Sprint B: share counter
    void recordShare(Integer postId, String channel);

    // Sprint C: follow + feed
    boolean toggleFollow(Integer followerId, Integer authorId);

    boolean isFollowing(Integer followerId, Integer authorId);

    org.springframework.data.domain.Page<PostListResponse> getFollowingFeed(
            Integer userId, org.springframework.data.domain.Pageable pageable);
}
