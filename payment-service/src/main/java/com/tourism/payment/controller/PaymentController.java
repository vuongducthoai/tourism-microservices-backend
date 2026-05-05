package com.tourism.payment.controller;

import com.tourism.payment.dto.PaymentInfoResponse;
import com.tourism.payment.entity.Payment;
import com.tourism.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal endpoint — used by booking-service via Feign.
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentRepository paymentRepository;

    /**
     * GET /api/payment/by-booking/{bookingId}
     * Returns payment info for a given booking ID.
     * Returns 404 if not found (booking may be unpaid).
     */
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
