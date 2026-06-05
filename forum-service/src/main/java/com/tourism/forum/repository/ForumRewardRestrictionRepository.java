package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumRewardRestriction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ForumRewardRestrictionRepository extends JpaRepository<ForumRewardRestriction, Long> {

    Optional<ForumRewardRestriction> findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(Integer userId);

    List<ForumRewardRestriction> findByActiveTrueOrderByCreatedAtDesc();
}
