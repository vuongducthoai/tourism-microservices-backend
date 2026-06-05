package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumFollowRewardHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForumFollowRewardHistoryRepository extends JpaRepository<ForumFollowRewardHistory, Long> {

    boolean existsByFollowerIdAndFollowingId(Integer followerId, Integer followingId);
}
