package com.tourism.notification.repository;

import com.tourism.notification.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByIdempotencyKey(String idempotencyKey);
}
