package com.tourism.forum.repository;

import com.tourism.forum.entity.PostBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
