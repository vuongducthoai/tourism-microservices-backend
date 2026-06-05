package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumRewardConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ForumRewardConfigRepository extends JpaRepository<ForumRewardConfig, Long> {

    Optional<ForumRewardConfig> findByConfigKey(String configKey);
}
