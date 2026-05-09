package com.tourism.payment.controller;

import com.tourism.payment.dto.PaymentInfoResponse;
import com.tourism.payment.entity.Payment;
import com.tourism.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Tag(name = "Payment", description = "Quản lý thanh toán — truy vấn nội bộ giữa các service")
public class PaymentController {

    private final PaymentRepository paymentRepository;

    @Operation(summary = "[Internal] Lấy thông tin thanh toán theo bookingId",
               description = "Endpoint nội bộ — booking-service gọi qua Feign. Trả về 404 nếu booking chưa thanh toán")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thông tin payment"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy payment")
    })
    @GetMapping("/by-booking/{bookingId}")
    public ResponseEntity<PaymentInfoResponse> getPaymentByBooking(@PathVariable Integer bookingId) {
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        PaymentInfoResponse res = new PaymentInfoResponse();
        res.setPaymentID(payment.getPaymentID());
        res.setAmount(payment.getAmount());
        res.setTimeLimit(payment.getTimeLimit());
        res.setPaymentMethod(payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null);
        res.setStatus(payment.getStatus() != null ? payment.getStatus().name() : null);
        res.setBank(payment.getBank());
        res.setAccountNumber(payment.getAccountNumber());
        res.setAccountName(payment.getAccountName());
        return ResponseEntity.ok(res);
    }
}
