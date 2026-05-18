package com.tourism.payment.service;

import com.tourism.payment.dto.PaymentUrlResponse;
import com.tourism.payment.dto.PaymentStatusResponse;
import com.tourism.payment.dto.VnpayCreateRequest;
import com.tourism.payment.dto.PayosCreateRequest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public interface PaymentGatewayService {

    PaymentUrlResponse createVnpayPayment(VnpayCreateRequest request, HttpServletRequest httpRequest);

    int handleVnpayCallback(Map<String, String> params);

    PaymentUrlResponse createPayosPayment(PayosCreateRequest request);

    int handlePayosWebhook(String webhookData);

    PaymentStatusResponse checkPaymentStatus(Long orderCode);
}
