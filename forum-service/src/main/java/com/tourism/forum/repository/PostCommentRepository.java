package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostCommentRepository extends JpaRepository<PostComment, Integer> {

    @Query("""
        SELECT c FROM PostComment c
        WHERE c.post = :post
          AND c.parentComment IS NULL
          AND (c.isDeleted IS NULL OR c.isDeleted = false)
        ORDER BY c.createdAt ASC
        """)
    List<PostComment> findTopLevelByPost(@Param("post") ForumPost post);

    long countByUserId(Integer userId);
}
