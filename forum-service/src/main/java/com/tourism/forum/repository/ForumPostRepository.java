package com.tourism.forum.repository;

import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.ForumPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ForumPostRepository extends JpaRepository<ForumPost, Integer>, JpaSpecificationExecutor<ForumPost> {

    @Query("SELECT p FROM ForumPost p WHERE p.userId = :userId AND p.status = :status AND p.isDeleted = false")
    Page<ForumPost> findByUserIdAndStatus(Integer userId, ContentStatus status, Pageable pageable);

    @Query("SELECT p FROM ForumPost p WHERE p.isDeleted = true ORDER BY p.deletedAt DESC")
    List<ForumPost> findDeletedPosts();

    @Query("SELECT p FROM ForumPost p WHERE p.postID = :postId AND p.isDeleted = false")
    Optional<ForumPost> findByIdAndNotDeleted(@Param("postId") Integer postId);

    @Query("SELECT p FROM ForumPost p WHERE p.userId = :userId AND p.isDeleted = false ORDER BY p.createdAt DESC")
    Page<ForumPost> findByUserIdAndNotDeleted(@Param("userId") Integer userId, Pageable pageable);

    @Query("""
        SELECT p FROM ForumPost p
        WHERE p.status = 'PUBLISHED'
          AND p.createdAt >= :since
          AND p.isDeleted = false
        ORDER BY p.isPinned DESC,
                 (p.viewCount * 1 + p.likeCount * 3 + p.commentCount * 2) DESC
        """)
    Page<ForumPost> findTrendingPosts(@Param("since") LocalDateTime since, Pageable pageable);

    /** Sprint A: Nổi bật — sort theo likeCount + commentCount trong window, kèm pin đầu. */
    @Query("""
        SELECT p FROM ForumPost p
        WHERE p.status = 'PUBLISHED'
          AND p.createdAt >= :since
          AND p.isDeleted = false
        ORDER BY p.isPinned DESC,
                 (p.likeCount * 2 + p.commentCount * 1) DESC,
                 p.createdAt DESC
        """)
    Page<ForumPost> findPopularPosts(@Param("since") LocalDateTime since, Pageable pageable);

    // ── Admin stats / analytics ────────────────────────────────────────────────
    long countByIsDeletedFalse();

    long countByStatusAndIsDeletedFalse(ContentStatus status);

    long countByCategoryCategoryIDAndIsDeletedFalse(Integer categoryId);

    @Query("SELECT p.postType, COUNT(p) FROM ForumPost p WHERE p.isDeleted = false GROUP BY p.postType")
    java.util.List<Object[]> countGroupedByPostType();

    @Query("SELECT p.status, COUNT(p) FROM ForumPost p WHERE p.isDeleted = false GROUP BY p.status")
    java.util.List<Object[]> countGroupedByStatus();

    @Query("""
        SELECT COALESCE(p.moderationLabel, 'NONE'), COUNT(p)
        FROM ForumPost p
        WHERE p.isDeleted = false
        GROUP BY p.moderationLabel
        """)
    java.util.List<Object[]> countGroupedByModerationLabel();

    @Query(value = """
        SELECT to_char(created_at, 'YYYY-MM-DD') AS d, COUNT(*)
        FROM forum_posts
        WHERE is_deleted = false AND created_at >= :since
        GROUP BY d
        ORDER BY d
        """, nativeQuery = true)
    java.util.List<Object[]> countDailySince(@Param("since") LocalDateTime since);

    /** Top user vi phạm: số bài bị admin/AI HIDDEN hoặc đã DELETE trong khoảng. */
    @Query("""
        SELECT p.userId, COUNT(p)
        FROM ForumPost p
        WHERE p.createdAt >= :since
          AND (p.status = com.tourism.forum.entity.ContentStatus.HIDDEN OR p.isDeleted = true)
        GROUP BY p.userId
        ORDER BY COUNT(p) DESC
        """)
    java.util.List<Object[]> topViolators(@Param("since") LocalDateTime since, org.springframework.data.domain.Pageable pageable);

    /** AI accuracy: với mỗi label AI, đếm theo trạng thái cuối. Dùng để tính precision. */
    @Query("""
        SELECT p.moderationLabel, p.status, COUNT(p)
        FROM ForumPost p
        WHERE p.moderationLabel IS NOT NULL
          AND p.createdAt >= :since
        GROUP BY p.moderationLabel, p.status
        """)
    java.util.List<Object[]> aiLabelVsFinalStatus(@Param("since") LocalDateTime since);
}
