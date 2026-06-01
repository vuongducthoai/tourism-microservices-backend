package com.tourism.booking.service;

import com.tourism.booking.dto.request.ConsultationCreateRequest;
import com.tourism.booking.dto.request.ConsultationUpdateStatusRequest;
import com.tourism.booking.dto.response.ConsultationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

public interface ConsultationService {

    ConsultationResponse create(ConsultationCreateRequest req, Integer userId, String clientIp);

    ConsultationResponse getByCode(String requestCode);

    ConsultationResponse getById(Integer id);

    Page<ConsultationResponse> list(String status, Pageable pageable);

    ConsultationResponse updateStatus(Integer id, ConsultationUpdateStatusRequest req);

    Map<String, Long> stats();
}
