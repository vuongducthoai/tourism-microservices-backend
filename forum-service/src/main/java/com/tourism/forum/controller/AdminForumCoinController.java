package com.tourism.forum.controller;

import com.tourism.forum.config.RequireAdmin;
import com.tourism.forum.service.AdminForumCoinService;
import com.tourism.forum.service.ForumRewardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin quản lý coin forum (PLAN_ADMIN_FORUM_COIN).
 * Đặt dưới /api/admin/forum/** để AdminAuthInterceptor áp dụng:
 * ADMIN + MODERATOR xem được; thao tác sửa đổi (@RequireAdmin) chỉ ADMIN.
 */
@RestController
@RequestMapping("/api/admin/forum/coin")
@RequiredArgsConstructor
public class AdminForumCoinController {

    private final AdminForumCoinService adminCoinService;
    private final ForumRewardService rewardService;
    private final com.tourism.forum.service.ForumRewardAlertService alertService;
    private final com.tourism.forum.service.ForumRewardConfigService configService;

    // ── §1 Dashboard ──
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(Map.of("success", true, "data", adminCoinService.getStats(days)));
    }

    // ── §2 Log viewer ──
    @GetMapping("/logs")
    public ResponseEntity<?> getLogs(
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", adminCoinService.getLogs(userId, action, status, from, to, page, size)));
    }

    // ── §3 Khóa thưởng ──
    @GetMapping("/restrictions")
    public ResponseEntity<?> getRestrictions() {
        return ResponseEntity.ok(Map.of("success", true, "data", adminCoinService.getRestrictions()));
    }

    @RequireAdmin
    @PostMapping("/restrict")
    public ResponseEntity<?> restrict(@RequestBody RestrictRequest request) {
        adminCoinService.restrictUser(request.getUserId(), request.getReason(), request.getDurationDays());
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã khóa thưởng coin cho user"));
    }

    @RequireAdmin
    @PostMapping("/unrestrict/{userId}")
    public ResponseEntity<?> unrestrict(@PathVariable Integer userId) {
        adminCoinService.unrestrictUser(userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã gỡ khóa thưởng coin"));
    }

    // ── §4 Thu hồi coin ──
    @RequireAdmin
    @PostMapping("/revoke/{logId}")
    public ResponseEntity<?> revoke(@PathVariable Long logId, @RequestBody(required = false) RevokeRequest request) {
        Map<String, Object> result = adminCoinService.revoke(logId,
                request != null ? request.getReason() : null);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã thu hồi coin", "data", result));
    }

    @RequireAdmin
    @PostMapping("/revoke-bulk")
    public ResponseEntity<?> revokeBulk(@RequestBody BulkRevokeRequest request) {
        Map<String, Object> result = adminCoinService.revokeBulk(
                request.getUserId(), request.getFrom(), request.getTo(), request.getReason());
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã thu hồi hàng loạt", "data", result));
    }

    // ── §7 Vận hành ──
    @GetMapping("/stuck")
    public ResponseEntity<?> getStuckPending() {
        return ResponseEntity.ok(Map.of("success", true, "data", adminCoinService.getStuckPending()));
    }

    @RequireAdmin
    @PostMapping("/stuck/republish")
    public ResponseEntity<?> republishStuck() {
        rewardService.retryPendingRewards();
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã gửi lại các reward PENDING"));
    }

    @GetMapping("/reconcile")
    public ResponseEntity<?> reconcile() {
        return ResponseEntity.ok(Map.of("success", true, "data", adminCoinService.reconcile()));
    }

    // ── §5 Cảnh báo bất thường (Đợt 3) ──
    @GetMapping("/alerts")
    public ResponseEntity<?> getAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(Map.of("success", true, "data", alertService.getAlerts(status, page, size)));
    }

    @RequireAdmin
    @PostMapping("/alerts/{alertId}/resolve")
    public ResponseEntity<?> resolveAlert(@PathVariable Long alertId) {
        alertService.resolveAlert(alertId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã đánh dấu xử lý"));
    }

    @RequireAdmin
    @PostMapping("/alerts/scan")
    public ResponseEntity<?> scanAlerts() {
        int created = alertService.runRules();
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Quét xong: " + created + " cảnh báo mới", "data", Map.of("created", created)));
    }

    // ── §6 Config runtime + kill switch (Đợt 4) ──
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of("success", true, "data", configService.snapshot()));
    }

    @RequireAdmin
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, String> changes) {
        configService.update(changes);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật cấu hình (hiệu lực ngay)"));
    }

    @RequireAdmin
    @PostMapping("/kill-switch")
    public ResponseEntity<?> killSwitch(@RequestBody KillSwitchRequest request) {
        configService.killSwitch(Boolean.TRUE.equals(request.getEnabled()));
        return ResponseEntity.ok(Map.of("success", true,
                "message", Boolean.TRUE.equals(request.getEnabled())
                        ? "Đã BẬT lại thưởng coin forum"
                        : "Đã TẮT KHẨN CẤP toàn bộ thưởng coin forum"));
    }

    // ── Request DTOs ──

    @Data
    public static class RestrictRequest {
        private Integer userId;
        private String reason;
        private Integer durationDays; // null/0 = vĩnh viễn
    }

    @Data
    public static class RevokeRequest {
        private String reason;
    }

    @Data
    public static class KillSwitchRequest {
        private Boolean enabled;
    }

    @Data
    public static class BulkRevokeRequest {
        private Integer userId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;
        private String reason;
    }
}
