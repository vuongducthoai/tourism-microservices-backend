package com.tourism.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.ForumPost;

public interface PostRepository extends JpaRepository<ForumPost, Integer> {
    Page<ForumPost> findByStatusAndIsDeletedFalse(ContentStatus status, Pageable pageable);
    long countByStatus(ContentStatus status);
}
