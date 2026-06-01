package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostBookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostBookmarkRepository extends JpaRepository<PostBookmark, Integer> {

    @Query("SELECT COUNT(b) FROM PostBookmark b WHERE b.post.postID = :postId AND b.userId = :userId")
    long countByPostIdAndUserId(Integer postId, Integer userId);

    @Query("SELECT b FROM PostBookmark b WHERE b.post.postID = :postId AND b.userId = :userId")
    Optional<PostBookmark> findByPostIdAndUserId(Integer postId, Integer userId);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM PostBookmark b WHERE b.post.postID = :postId AND b.userId = :userId")
    boolean existsByPostIdAndUserId(Integer postId, Integer userId);

    /** Sprint A: lấy danh sách post đã bookmark, sort theo thời gian bookmark mới nhất. */
    @Query("""
        SELECT b.post FROM PostBookmark b
        WHERE b.userId = :userId
          AND (b.post.isDeleted IS NULL OR b.post.isDeleted = false)
          AND b.post.status = com.tourism.forum.entity.ContentStatus.PUBLISHED
        ORDER BY b.createdAt DESC
        """)
    Page<ForumPost> findBookmarkedPostsByUserId(@Param("userId") Integer userId, Pageable pageable);
}
