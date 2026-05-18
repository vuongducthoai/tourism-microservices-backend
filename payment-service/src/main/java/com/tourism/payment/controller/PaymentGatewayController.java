package com.tourism.payment.controller;

import com.tourism.payment.dto.PaymentStatusResponse;
import com.tourism.payment.dto.PaymentUrlResponse;
import com.tourism.payment.dto.PayosCreateRequest;
import com.tourism.payment.dto.VnpayCreateRequest;
import com.tourism.payment.service.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Tag(name = "Payment Gateway", description = "VNPay va PayOS thanh toan")
public class PaymentGatewayController {

    private final PaymentGatewayService paymentGatewayService;

    @Operation(summary = "Tao VNPay payment URL")
    @PostMapping("/vnpay/create")
    public ResponseEntity<PaymentUrlResponse> createVnpayPayment(
            @RequestBody VnpayCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(paymentGatewayService.createVnpayPayment(request, httpRequest));
    }

    @Operation(summary = "VNPay callback sau khi thanh toan")
    @GetMapping("/vnpay-callback")
    public ResponseEntity<Void> vnpayCallback(@RequestParam Map<String, String> params) {
        int result = paymentGatewayService.handleVnpayCallback(params);
        String redirectUrl = result == 1
                ? "http://localhost:3000/payment-success"
                : "http://localhost:3000/payment-failed?errorCode=" + params.getOrDefault("vnp_ResponseCode", "99");
        return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
    }

    @Operation(summary = "Tao PayOS checkout URL")
    @PostMapping("/payos/create")
    public ResponseEntity<PaymentUrlResponse> createPayosPayment(
            @RequestBody PayosCreateRequest request) {
        return ResponseEntity.ok(paymentGatewayService.createPayosPayment(request));
    }

    @Operation(summary = "PayOS webhook nhan thanh toan")
    @PostMapping("/payos/webhook")
    public ResponseEntity<Map<String, String>> payosWebhook(@RequestBody String webhookData) {
        int result = paymentGatewayService.handlePayosWebhook(webhookData);
        if (result >= 0) {
            return ResponseEntity.ok(Map.of("code", "00", "desc", "success"));
        }
        return ResponseEntity.ok(Map.of("code", "01", "desc", "failed"));
    }

    @Operation(summary = "Kiem tra trang thai thanh toan (polling)")
    @GetMapping("/check-status/{orderCode}")
    public ResponseEntity<PaymentStatusResponse> checkStatus(@PathVariable Long orderCode) {
        return ResponseEntity.ok(paymentGatewayService.checkPaymentStatus(orderCode));
    }
}
