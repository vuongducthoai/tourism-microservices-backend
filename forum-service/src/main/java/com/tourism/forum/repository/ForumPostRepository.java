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

public interface ForumPostRepository extends JpaRepository<ForumPost, Integer>, JpaSpecificationExecutor<ForumPost> {

    Page<ForumPost> findByUserIdAndStatus(Integer userId, ContentStatus status, Pageable pageable);

    @Query("""
        SELECT p FROM ForumPost p
        WHERE p.status = 'PUBLISHED'
          AND p.createdAt >= :since
          AND (p.isDeleted IS NULL OR p.isDeleted = false)
        ORDER BY (p.viewCount * 1 + p.likeCount * 3 + p.commentCount * 2) DESC
        """)
    Page<ForumPost> findTrendingPosts(@Param("since") LocalDateTime since, Pageable pageable);
}
