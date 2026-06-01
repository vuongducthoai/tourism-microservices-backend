package com.tourism.booking.repository;

import com.tourism.booking.entity.ConsultationRequest;
import com.tourism.booking.entity.ConsultationRequest.ConsultationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ConsultationRequestRepository
        extends JpaRepository<ConsultationRequest, Integer>,
                JpaSpecificationExecutor<ConsultationRequest> {

    Page<ConsultationRequest> findByStatusOrderByCreatedAtDesc(ConsultationStatus status, Pageable pageable);

    Page<ConsultationRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(ConsultationStatus status);

    Optional<ConsultationRequest> findByRequestCode(String code);
}
