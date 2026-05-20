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
import java.util.Optional;

public interface ForumPostRepository extends JpaRepository<ForumPost, Integer>, JpaSpecificationExecutor<ForumPost> {

    @Query("SELECT p FROM ForumPost p WHERE p.userId = :userId AND p.status = :status AND p.isDeleted = false")
    Page<ForumPost> findByUserIdAndStatus(Integer userId, ContentStatus status, Pageable pageable);

    @Query("SELECT p FROM ForumPost p WHERE p.postID = :postId AND p.isDeleted = false")
    Optional<ForumPost> findByIdAndNotDeleted(@Param("postId") Integer postId);

    @Query("SELECT p FROM ForumPost p WHERE p.userId = :userId AND p.isDeleted = false ORDER BY p.createdAt DESC")
    Page<ForumPost> findByUserIdAndNotDeleted(@Param("userId") Integer userId, Pageable pageable);

    @Query("""
        SELECT p FROM ForumPost p
        WHERE p.status = 'PUBLISHED'
          AND p.createdAt >= :since
          AND p.isDeleted = false
        ORDER BY (p.viewCount * 1 + p.likeCount * 3 + p.commentCount * 2) DESC
        """)
    Page<ForumPost> findTrendingPosts(@Param("since") LocalDateTime since, Pageable pageable);
}
