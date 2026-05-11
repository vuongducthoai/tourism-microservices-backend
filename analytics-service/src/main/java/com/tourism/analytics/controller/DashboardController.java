package com.tourism.analytics.controller;

import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;
import com.tourism.analytics.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * DashboardController — cung cấp 2 endpoints cho Admin Dashboard.
 *
 * <p>API Gateway sẽ rewrite:
 * <ul>
 *   <li>GET /api/admin/dashboard/statistics  →  GET /api/dashboard/statistics</li>
 *   <li>GET /api/admin/dashboard/analysis    →  GET /api/dashboard/analysis</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Dashboard", description = "Admin Dashboard Analytics — tổng hợp từ iam, booking, tour-catalog service")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET /api/dashboard/statistics (internal)
     * Được gọi qua gateway dưới dạng GET /api/admin/dashboard/statistics
     */
    @GetMapping("/statistics")
    @Operation(summary = "Lấy toàn bộ thống kê cho Admin Dashboard")
    @ApiResponse(responseCode = "200", description = "Dashboard statistics")
    public ResponseEntity<DashboardStatsDTO> getDashboardStatistics(
            @Parameter(description = "Ngày bắt đầu (yyyy-MM-dd), mặc định 30 ngày trước")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Ngày kết thúc (yyyy-MM-dd), mặc định hôm nay")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        log.info("Fetching dashboard statistics from {} to {}...", effectiveFrom, effectiveTo);
        return ResponseEntity.ok(dashboardService.getDashboardStatistics(effectiveFrom, effectiveTo));
    }

    @GetMapping("/analysis")
    @Operation(summary = "Lấy phân tích AI (Gemini) cho Dashboard")
    @ApiResponse(responseCode = "200", description = "AI Analysis")
    public ResponseEntity<DashboardStatsDTO.AIAnalysis> getDashboardAIAnalysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "OVERVIEW") String mode) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        log.info("Fetching AI analysis from {} to {} mode={}...", effectiveFrom, effectiveTo, mode);
        return ResponseEntity.ok(dashboardService.getDashboardAIAnalysis(effectiveFrom, effectiveTo, mode));
    }
}
