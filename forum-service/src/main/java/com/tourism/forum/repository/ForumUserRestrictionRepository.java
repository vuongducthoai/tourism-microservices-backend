package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumUserRestriction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ForumUserRestrictionRepository extends JpaRepository<ForumUserRestriction, Long> {

    /** Restriction đang active của 1 user (mới nhất). */
    Optional<ForumUserRestriction> findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(Integer userId);

    List<ForumUserRestriction> findByActiveTrueOrderByCreatedAtDesc();
}
