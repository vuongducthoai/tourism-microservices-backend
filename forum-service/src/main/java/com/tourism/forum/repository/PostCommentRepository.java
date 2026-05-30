package com.tourism.forum.repository;

import com.tourism.forum.dto.response.AdminPostWithCommentsResponse;
import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostCommentRepository extends JpaRepository<PostComment, Integer>,
        JpaSpecificationExecutor<PostComment> {

    long countByStatus(ContentStatus status);

    /** Group view: danh sách bài có comment + đếm tổng/chờ duyệt. */
    @Query("""
        SELECT new com.tourism.forum.dto.response.AdminPostWithCommentsResponse(
            p.postID, p.title, p.userId,
            COUNT(c),
            SUM(CASE WHEN c.status = com.tourism.forum.entity.ContentStatus.PENDING_REVIEW THEN 1L ELSE 0L END),
            MAX(c.createdAt))
        FROM ForumPost p JOIN PostComment c ON c.post = p
        WHERE (c.isDeleted IS NULL OR c.isDeleted = false)
        GROUP BY p.postID, p.title, p.userId
        ORDER BY MAX(c.createdAt) DESC
        """)
    Page<AdminPostWithCommentsResponse> findPostsWithComments(Pageable pageable);

    /** Group view — chỉ bài có comment chờ duyệt. */
    @Query("""
        SELECT new com.tourism.forum.dto.response.AdminPostWithCommentsResponse(
            p.postID, p.title, p.userId,
            COUNT(c),
            SUM(CASE WHEN c.status = com.tourism.forum.entity.ContentStatus.PENDING_REVIEW THEN 1L ELSE 0L END),
            MAX(c.createdAt))
        FROM ForumPost p JOIN PostComment c ON c.post = p
        WHERE (c.isDeleted IS NULL OR c.isDeleted = false)
        GROUP BY p.postID, p.title, p.userId
        HAVING SUM(CASE WHEN c.status = com.tourism.forum.entity.ContentStatus.PENDING_REVIEW THEN 1L ELSE 0L END) > 0
        ORDER BY MAX(c.createdAt) DESC
        """)
    Page<AdminPostWithCommentsResponse> findPostsWithPendingComments(Pageable pageable);

    /** Tất cả comment của 1 bài (mọi trạng thái, chưa xóa) cho admin xem cây. */
    @Query("""
        SELECT c FROM PostComment c
        WHERE c.post.postID = :postId
          AND (c.isDeleted IS NULL OR c.isDeleted = false)
        ORDER BY c.createdAt ASC
        """)
    List<PostComment> findAllByPostIdForAdmin(@Param("postId") Integer postId);

    /** Comment đã xóa mềm — cho thùng rác. */
    @Query("SELECT c FROM PostComment c WHERE c.isDeleted = true ORDER BY c.deletedAt DESC")
    List<PostComment> findDeletedComments();

    @Query("""
        SELECT c FROM PostComment c
        WHERE c.post = :post
          AND c.parentComment IS NULL
          AND c.status = com.tourism.forum.entity.ContentStatus.PUBLISHED
          AND (c.isDeleted IS NULL OR c.isDeleted = false)
        ORDER BY c.createdAt ASC
        """)
    List<PostComment> findTopLevelByPost(@Param("post") ForumPost post);

    long countByUserId(Integer userId);
}
