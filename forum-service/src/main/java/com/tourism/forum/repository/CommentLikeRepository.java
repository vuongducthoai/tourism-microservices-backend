package com.tourism.forum.repository;

import com.tourism.forum.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Integer> {

    Optional<CommentLike> findByCommentCommentIDAndUserId(Integer commentId, Integer userId);

    boolean existsByCommentCommentIDAndUserId(Integer commentId, Integer userId);

    List<CommentLike> findByUserId(Integer userId);

    // Đếm like từ NGƯỜI KHÁC (loại self-like) — dùng cho mốc thưởng coin
    long countByCommentCommentIDAndUserIdNot(Integer commentId, Integer userId);
}
