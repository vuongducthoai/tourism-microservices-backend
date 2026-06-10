package com.tourism.analytics.repository;

import com.tourism.analytics.entity.ChatbotVectorSyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatbotVectorSyncRunRepository extends JpaRepository<ChatbotVectorSyncRun, Long> {
    long countByStartedAtBetween(LocalDateTime from, LocalDateTime to);
    long countByStatus(String status);
    long countByStatusAndStartedAtBetween(String status, LocalDateTime from, LocalDateTime to);
    Optional<ChatbotVectorSyncRun> findTopByOrderByStartedAtDesc();
    List<ChatbotVectorSyncRun> findAllByOrderByStartedAtDesc();
    List<ChatbotVectorSyncRun> findByStartedAtBetweenOrderByStartedAtDesc(
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    );
}
