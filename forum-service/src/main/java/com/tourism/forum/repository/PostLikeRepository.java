package com.tourism.forum.repository;

import com.tourism.forum.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Integer> {

    Optional<PostLike> findByPostPostIDAndUserId(Integer postId, Integer userId);

    boolean existsByPostPostIDAndUserId(Integer postId, Integer userId);

    long countByUserId(Integer userId);

    // Đếm like từ NGƯỜI KHÁC (loại self-like) — dùng cho mốc thưởng coin
    long countByPostPostIDAndUserIdNot(Integer postId, Integer userId);
}
