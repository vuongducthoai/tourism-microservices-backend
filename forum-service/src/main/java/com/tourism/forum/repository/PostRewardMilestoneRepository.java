package com.tourism.forum.repository;

import com.tourism.forum.entity.PostRewardMilestone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRewardMilestoneRepository extends JpaRepository<PostRewardMilestone, Long> {

    boolean existsByTargetTypeAndTargetIdAndMilestone(
            PostRewardMilestone.TargetType targetType, Integer targetId, Integer milestone);
}
