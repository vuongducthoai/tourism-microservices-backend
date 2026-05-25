package com.tourism.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.PostComment;

public interface CommentRepository extends JpaRepository<PostComment, Integer> {
    Page<PostComment> findByStatusAndIsDeletedFalse(ContentStatus status, Pageable pageable);
    long countByStatus(ContentStatus status);
}
