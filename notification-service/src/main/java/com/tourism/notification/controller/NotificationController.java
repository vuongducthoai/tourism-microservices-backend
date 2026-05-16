package com.tourism.notification.controller;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import com.tourism.notification.dto.VerificationEmailRequest;
import com.tourism.notification.service.NotificationService;
import com.tourism.notification.service.MailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "[Internal] Endpoints được các service khác gọi qua Feign để gửi email và push WebSocket")
public class NotificationController {

    private final NotificationService notificationService;
    private final MailService mailService;

    @Operation(summary = "[Internal] Yêu cầu hoàn tiền", description = "booking-service gọi khi khách hàng gửi yêu cầu hoàn tiền. Gửi email thông báo cho admin")
    @ApiResponse(responseCode = "200", description = "Xử lý thành công")
    @PostMapping("/refund-requested")
    public ResponseEntity<Void> refundRequested(@RequestBody BookingEventDTO event) {
        notificationService.handleRefundRequested(event);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Trạng thái booking được cập nhật", description = "booking-service gọi khi admin thay đổi trạng thái (huỷ/hoàn tiền). Gửi email cho khách hàng")
    @ApiResponse(responseCode = "200", description = "Xử lý thành công")
    @PostMapping("/status-updated")
    public ResponseEntity<Void> statusUpdated(@RequestBody BookingEventDTO event) {
        notificationService.handleStatusUpdated(event);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Admin xác nhận booking", description = "booking-service gọi khi admin xác nhận PENDING_CONFIRMATION → PAID. Gửi email cho khách hàng")
    @ApiResponse(responseCode = "200", description = "Xử lý thành công")
    @PostMapping("/booking-confirmed")
    public ResponseEntity<Void> bookingConfirmed(@RequestBody BookingEventDTO event) {
        notificationService.handleBookingConfirmed(event);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Khoá / mở khoá tài khoản user",
               description = "iam-service gọi khi admin thay đổi trạng thái user. Gửi email + push WebSocket đến /topic/admin/users")
    @ApiResponse(responseCode = "200", description = "Xử lý thành công")
    @PostMapping("/user-status-updated")
    public ResponseEntity<Void> userStatusUpdated(@RequestBody UserStatusEventDTO event) {
        notificationService.handleUserStatusUpdated(event);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Gửi email xác thực tài khoản",
               description = "iam-service gọi khi user đăng ký hoặc yêu cầu gửi lại email xác thực")
    @ApiResponse(responseCode = "200", description = "Email được gửi thành công")
    @PostMapping("/send-verification-email")
    public ResponseEntity<Void> sendVerificationEmail(@RequestBody VerificationEmailRequest request) {
        mailService.sendVerificationEmail(request);
        return ResponseEntity.ok().build();
    }
}
