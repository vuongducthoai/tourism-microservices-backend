package com.tourism.payment.service.impl;

import com.tourism.payment.config.PayOSConfig;
import com.tourism.payment.config.VNPayConfig;
import com.tourism.payment.dto.PaymentStatusResponse;
import com.tourism.payment.dto.PaymentUrlResponse;
import com.tourism.payment.dto.PayosCreateRequest;
import com.tourism.payment.dto.VnpayCreateRequest;
import com.tourism.payment.entity.Payment;
import com.tourism.payment.entity.PaymentMethod;
import com.tourism.payment.entity.PaymentStatus;
import com.tourism.payment.feign.BookingFeignClient;
import com.tourism.payment.feign.dto.BookingPaymentDetailResponse;
import com.tourism.payment.repository.PaymentRepository;
import com.tourism.payment.service.PaymentGatewayService;
import com.tourism.payment.util.BankCodeUtil;
import com.tourism.payment.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.payos.PayOS;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;
import vn.payos.model.v2.paymentRequests.Transaction;
import vn.payos.model.webhooks.WebhookData;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayServiceImpl implements PaymentGatewayService {

    private final VNPayConfig vnPayConfig;
    private final PayOSConfig payOSConfig;
    private final PayOS payOS;
    private final PaymentRepository paymentRepository;
    private final BookingFeignClient bookingFeignClient;

    // ── VNPay ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentUrlResponse createVnpayPayment(VnpayCreateRequest request, HttpServletRequest httpRequest) {
        Integer bookingId = resolveBookingId(request.getBookingCode());

        try {
            String txnRef = request.getBookingCode() + "_" + System.currentTimeMillis();
            long amount = request.getAmount().longValue() * 100;
            String ipAddr = VNPayUtil.getIpAddress(httpRequest);

            Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"));
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss");
            String createDate = fmt.format(cld.getTime());
            cld.add(Calendar.MINUTE, 15);
            String expireDate = fmt.format(cld.getTime());

            String orderInfo = request.getOrderInfo() != null
                    ? request.getOrderInfo() : "Thanh toan " + request.getBookingCode();

            Map<String, String> params = new TreeMap<>();
            params.put("vnp_Version", "2.1.0");
            params.put("vnp_Command", "pay");
            params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
            params.put("vnp_Amount", String.valueOf(amount));
            params.put("vnp_CurrCode", "VND");
            params.put("vnp_TxnRef", txnRef);
            params.put("vnp_OrderInfo", orderInfo);
            params.put("vnp_OrderType", "other");
            params.put("vnp_Locale", request.getLocale() != null ? request.getLocale() : "vn");
            params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
            params.put("vnp_IpAddr", ipAddr);
            params.put("vnp_CreateDate", createDate);
            params.put("vnp_ExpireDate", expireDate);

            StringBuilder hashData = new StringBuilder();
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if (v != null && !v.isEmpty()) {
                    if (hashData.length() > 0) hashData.append('&');
                    hashData.append(k).append('=').append(v);
                    if (query.length() > 0) query.append('&');
                    query.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                         .append('=')
                         .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
                }
            }

            String secureHash = VNPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashData.toString());
            String paymentUrl = vnPayConfig.getApiUrl() + "?" + query + "&vnp_SecureHash=" + secureHash;

            Payment payment = new Payment();
            payment.setPaymentMethod(PaymentMethod.VNPAY);
            payment.setTransactionId(txnRef);
            payment.setAmount(request.getAmount());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setPaymentDate(LocalDateTime.now());
            payment.setBookingId(bookingId);
            paymentRepository.save(payment);

            return PaymentUrlResponse.builder()
                    .paymentUrl(paymentUrl)
                    .transactionId(txnRef)
                    .build();

        } catch (Exception e) {
            log.error("Error creating VNPay payment", e);
            throw new RuntimeException("Error creating VNPay payment: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public int handleVnpayCallback(Map<String, String> params) {
        String vnpSecureHash = params.get("vnp_SecureHash");
        Map<String, String> paramsToVerify = new HashMap<>(params);
        paramsToVerify.remove("vnp_SecureHashType");
        paramsToVerify.remove("vnp_SecureHash");

        String signValue = VNPayUtil.hashAllFields(paramsToVerify, vnPayConfig.getHashSecret());
        if (!signValue.equals(vnpSecureHash)) {
            log.error("Invalid VNPay signature");
            return -1;
        }

        String txnRef = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");

        Payment payment = paymentRepository.findByTransactionId(txnRef)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + txnRef));

        if ("00".equals(responseCode)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setBankCode(params.get("vnp_BankCode"));
            payment.setBankTransactionNo(params.get("vnp_TransactionNo"));
            payment.setPaymentDate(LocalDateTime.now());
            paymentRepository.save(payment);
            updateBookingStatusSilently(payment.getBookingId(), "PAID");
            return 1;
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setBankCode(params.get("vnp_BankCode"));
            paymentRepository.save(payment);
            return 0;
        }
    }

    // ── PayOS ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentUrlResponse createPayosPayment(PayosCreateRequest request) {
        Integer bookingId = resolveBookingId(request.getBookingCode());

        try {
            long amount = request.getAmount().longValue();
            String timeStr = String.valueOf(System.currentTimeMillis());
            long orderCode = Long.parseLong(timeStr.substring(timeStr.length() - 8));

            String cleanCode = request.getBookingCode().replaceAll("[^a-zA-Z0-9]", "");
            String description = request.getDescription() != null
                    ? request.getDescription() : "Thanh toan " + cleanCode;
            if (description.length() > 25) description = description.substring(0, 25);

            String returnUrl = request.getReturnUrl() != null
                    ? request.getReturnUrl() : payOSConfig.getReturnUrl();
            String cancelUrl = request.getCancelUrl() != null
                    ? request.getCancelUrl() : payOSConfig.getCancelUrl();

            List<PaymentLinkItem> items = List.of(
                PaymentLinkItem.builder()
                    .name("Tour " + cleanCode)
                    .quantity(1)
                    .price(amount)
                    .build()
            );

            CreatePaymentLinkRequest paymentData = CreatePaymentLinkRequest.builder()
                    .orderCode(orderCode)
                    .amount(amount)
                    .description(description)
                    .items(items)
                    .returnUrl(returnUrl.trim())
                    .cancelUrl(cancelUrl.trim())
                    .expiredAt((long) (System.currentTimeMillis() / 1000) + 900L)
                    .build();

            CreatePaymentLinkResponse checkoutResponse = payOS.paymentRequests().create(paymentData);

            Payment payment = paymentRepository.findByBookingId(bookingId).orElse(new Payment());
            payment.setBookingId(bookingId);
            payment.setPaymentMethod(PaymentMethod.PAYOS);
            payment.setTransactionId(String.valueOf(orderCode));
            payment.setAmount(request.getAmount());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setTimeLimit(LocalDateTime.now().plusMinutes(15));
            payment.setPaymentDate(LocalDateTime.now());
            paymentRepository.save(payment);

            return PaymentUrlResponse.builder()
                    .checkoutUrl(checkoutResponse.getCheckoutUrl())
                    .transactionId(String.valueOf(orderCode))
                    .qrCode(checkoutResponse.getQrCode())
                    .build();

        } catch (Exception e) {
            log.error("Error creating PayOS payment", e);
            throw new RuntimeException("Error creating PayOS payment: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public int handlePayosWebhook(String webhookData) {
        try {
            WebhookData data = payOS.webhooks().verify(webhookData);
            if (data == null) return -1;

            String orderCode = String.valueOf(data.getOrderCode());
            Payment payment = paymentRepository.findByTransactionId(orderCode)
                    .orElseThrow(() -> new RuntimeException("Payment not found for orderCode: " + orderCode));

            // "00" in data.code means success
            if ("00".equals(data.getCode())) {
                payment.setStatus(PaymentStatus.SUCCESS);
                payment.setPaymentDate(LocalDateTime.now());
                if (data.getCounterAccountName() != null) payment.setAccountName(data.getCounterAccountName());
                if (data.getCounterAccountNumber() != null) payment.setAccountNumber(data.getCounterAccountNumber());
                String wBankCode = data.getCounterAccountBankId();
                if (wBankCode != null) payment.setBankCode(wBankCode);
                String wBankName = data.getCounterAccountBankName();
                if (wBankName == null || wBankName.isBlank()) wBankName = BankCodeUtil.getBankName(wBankCode);
                if (wBankName != null) payment.setBank(wBankName);
                if (data.getReference() != null) payment.setBankTransactionNo(data.getReference());
                if (data.getTransactionDateTime() != null) {
                    try {
                        payment.setTransactionDatetime(
                            LocalDateTime.parse(data.getTransactionDateTime().replace("Z", "")));
                    } catch (Exception ex) {
                        payment.setTransactionDatetime(LocalDateTime.now());
                    }
                }
                paymentRepository.save(payment);
                updateBookingStatusSilently(payment.getBookingId(), "PENDING_CONFIRMATION");
                return 1;
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);
                return 0;
            }
        } catch (Exception e) {
            log.error("Error processing PayOS webhook", e);
            return -1;
        }
    }

    // ── Check status ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentStatusResponse checkPaymentStatus(Long orderCode) {
        Payment payment = paymentRepository.findByTransactionId(String.valueOf(orderCode)).orElse(null);

        if (payment != null && PaymentStatus.SUCCESS.equals(payment.getStatus())) {
            return PaymentStatusResponse.builder().status("SUCCESS").code("00").build();
        }
        if (payment != null && PaymentStatus.FAILED.equals(payment.getStatus())) {
            return PaymentStatusResponse.builder().status("FAILED").code("99").build();
        }

        // Query PayOS for live status
        try {
            PaymentLink link = payOS.paymentRequests().get(orderCode);
            PaymentLinkStatus status = link.getStatus();
            if (PaymentLinkStatus.PAID.equals(status)) {
                if (payment != null) {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setPaymentDate(LocalDateTime.now());
                    List<Transaction> transactions = link.getTransactions();
                    if (transactions != null && !transactions.isEmpty()) {
                        Transaction tx = transactions.get(0);
                        String bankCode = tx.getCounterAccountBankId();
                        if (bankCode != null) payment.setBankCode(bankCode);
                        String bankName = tx.getCounterAccountBankName();
                        if (bankName == null || bankName.isBlank()) {
                            bankName = BankCodeUtil.getBankName(bankCode);
                        }
                        if (bankName != null) payment.setBank(bankName);
                        if (tx.getCounterAccountName() != null) payment.setAccountName(tx.getCounterAccountName());
                        if (tx.getCounterAccountNumber() != null) payment.setAccountNumber(tx.getCounterAccountNumber());
                        if (tx.getReference() != null) payment.setBankTransactionNo(tx.getReference());
                        if (tx.getDescription() != null) payment.setPaymentDescription(tx.getDescription());
                        if (tx.getTransactionDateTime() != null) {
                            try {
                                payment.setTransactionDatetime(
                                    LocalDateTime.parse(tx.getTransactionDateTime().replace("Z", "")));
                            } catch (Exception ex) {
                                payment.setTransactionDatetime(LocalDateTime.now());
                            }
                        }
                    }
                    paymentRepository.save(payment);
                    updateBookingStatusSilently(payment.getBookingId(), "PENDING_CONFIRMATION");
                }
                return PaymentStatusResponse.builder().status("SUCCESS").code("00").build();
            } else if (PaymentLinkStatus.CANCELLED.equals(status) || PaymentLinkStatus.EXPIRED.equals(status)) {
                if (payment != null) {
                    payment.setStatus(PaymentStatus.FAILED);
                    paymentRepository.save(payment);
                }
                return PaymentStatusResponse.builder().status("CANCELLED").code("99").build();
            }
        } catch (Exception e) {
            log.warn("Could not check PayOS status for {}: {}", orderCode, e.getMessage());
        }

        return PaymentStatusResponse.builder().status("PENDING").code("01").build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Integer resolveBookingId(String bookingCode) {
        try {
            BookingPaymentDetailResponse booking = bookingFeignClient.getBookingByCode(bookingCode);
            return booking != null ? booking.getBookingId() : 0;
        } catch (Exception e) {
            log.warn("Could not resolve bookingId for code {}: {}", bookingCode, e.getMessage());
            return 0;
        }
    }

    private void updateBookingStatusSilently(Integer bookingId, String status) {
        try {
            if (bookingId != null && bookingId > 0) {
                bookingFeignClient.updateBookingStatus(bookingId, status);
            }
        } catch (Exception e) {
            log.warn("Could not update booking {} status to {}: {}", bookingId, status, e.getMessage());
        }
    }
}
