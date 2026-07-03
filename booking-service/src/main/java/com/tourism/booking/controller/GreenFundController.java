package com.tourism.booking.controller;

import com.tourism.booking.service.GreenFundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API Quỹ Trồng Cây Xanh (PLAN_GREEN_FUND_TRONG_CAY §6.A.8).
 * /summary public (dashboard số liệu), /donate yêu cầu user đăng nhập (FE gửi userId).
 */
@RestController
@RequestMapping("/api/green-fund")
@RequiredArgsConstructor
public class GreenFundController {

    private final GreenFundService greenFundService;

    /** Số liệu công khai: tổng quỹ, số cây, người đóng góp, đợt trồng. */
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        return ResponseEntity.ok(Map.of("success", true, "data", greenFundService.getSummary()));
    }

    /** Dashboard công khai đầy đủ: tổng quan + mục tiêu + leaderboard + lịch sử + đợt trồng. */
    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard() {
        return ResponseEntity.ok(Map.of("success", true, "data", greenFundService.getDashboard()));
    }

    /** Bảng vinh danh. period = all | month. */
    @GetMapping("/leaderboard")
    public ResponseEntity<?> getLeaderboard(
            @RequestParam(defaultValue = "all") String period,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(Map.of("success", true, "data", greenFundService.getLeaderboard(period, limit)));
    }

    /** User góp coin trồng cây — trừ coin đồng bộ qua IAM. */
    @PostMapping("/donate")
    public ResponseEntity<?> donate(@RequestBody DonateRequest request) {
        Map<String, Object> result = greenFundService.donate(
                request.getUserId(), request.getCoinAmount(), Boolean.TRUE.equals(request.getAnonymous()));
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Cảm ơn bạn đã góp trồng cây 🌳", "data", result));
    }

    /** Đóng góp cá nhân: tổng cây đã góp + lịch sử. */
    @GetMapping("/me")
    public ResponseEntity<?> getMyContribution(@RequestParam(required = false) Integer userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", greenFundService.getMyContribution(userId)));
    }

    @Data
    public static class DonateRequest {
        private Integer userId;
        private Integer coinAmount;
        private Boolean anonymous;
    }
}
