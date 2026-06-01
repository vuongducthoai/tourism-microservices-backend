package com.tourism.booking.controller;

import com.tourism.booking.dto.request.ConsultationCreateRequest;
import com.tourism.booking.dto.response.ConsultationResponse;
import com.tourism.booking.service.ConsultationRateLimitService.ConsultationRateLimitException;
import com.tourism.booking.service.ConsultationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService service;

    /**
     * Public endpoint — user/guest gửi yêu cầu tư vấn từ trang chi tiết tour.
     * Header X-User-Id được gateway forward nếu user đã login (optional cho guest).
     */
    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody ConsultationCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId,
            HttpServletRequest httpRequest
    ) {
        String clientIp = resolveIp(httpRequest);
        ConsultationResponse data = service.create(request, userId, clientIp);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Đã gửi yêu cầu. Chúng tôi sẽ liên hệ lại trong ít phút.",
                "data", data));
    }

    /** Cho phép user check trạng thái đơn của mình qua mã CR-XXX. */
    @GetMapping("/{requestCode}/status")
    public ResponseEntity<?> checkStatus(@PathVariable String requestCode) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", service.getByCode(requestCode)));
    }

    // ── Error handlers ─────────────────────────────────────────────────────────

    @ExceptionHandler(ConsultationRateLimitException.class)
    public ResponseEntity<?> handleRateLimit(ConsultationRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("success", false, "message", ex.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "message", ex.getMessage()));
    }

    private static String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
