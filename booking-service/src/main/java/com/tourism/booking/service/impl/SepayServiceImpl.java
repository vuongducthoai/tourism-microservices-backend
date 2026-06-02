package com.tourism.booking.service.impl;

import com.tourism.booking.config.SepayConfig;
import com.tourism.booking.dto.sepay.SepayTransactionResponse;
import com.tourism.booking.dto.sepay.TransactionVerificationDTO;
import com.tourism.booking.service.SepayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SepayServiceImpl implements SepayService {

    private final SepayConfig sepayConfig;
    private final RestTemplate restTemplate;

    @Override
    public List<SepayTransactionResponse.Transaction> getRecentTransactions() {
        try {
            String url = sepayConfig.getApiUrl() + "/transactions/list?account_number="
                    + sepayConfig.getAccountNumber() + "&limit=100";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(sepayConfig.getToken());
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<SepayTransactionResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, SepayTransactionResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<SepayTransactionResponse.Transaction> txns = response.getBody().getTransactions();
                return txns != null ? txns : new ArrayList<>();
            }
            return new ArrayList<>();

        } catch (Exception e) {
            log.error("Error fetching SePay transactions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public TransactionVerificationDTO verifyRefundTransaction(
            String bookingCode,
            BigDecimal amount,
            String accountNumber,
            String accountName,
            String bankCode
    ) {
        try {
            log.info("Verifying refund transaction for booking: {}, expected amount: {} VND", bookingCode, amount);

            List<SepayTransactionResponse.Transaction> transactions = getRecentTransactions();
            log.info("SePay returned {} transactions to scan", transactions.size());

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (SepayTransactionResponse.Transaction txn : transactions) {
                // Only check outgoing transactions
                if (txn.getAmountOut() == null || txn.getAmountOut().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                LocalDateTime txnDate;
                try {
                    txnDate = LocalDateTime.parse(txn.getTransactionDate(), formatter);
                } catch (Exception e) {
                    log.warn("Cannot parse transaction date: {}", txn.getTransactionDate());
                    continue;
                }

                // Only consider transactions within last 24 hours
                if (txnDate.isBefore(now.minusHours(24))) {
                    continue;
                }

                String content = txn.getTransactionContent();
                boolean contentMatch = content != null && content.toUpperCase().contains(bookingCode.toUpperCase());
                BigDecimal difference = txn.getAmountOut().subtract(amount).abs();
                boolean amountMatch = difference.compareTo(new BigDecimal("1000")) <= 0;

                log.info("  → Txn id={} date={} amountOut={} content=\"{}\" | contentMatch={} amountDiff={} amountMatch={}",
                        txn.getId(), txn.getTransactionDate(), txn.getAmountOut(),
                        content, contentMatch, difference.toPlainString(), amountMatch);

                // Allow ±1000 VND tolerance for rounding
                if (!amountMatch) {
                    continue;
                }

                // Check transfer content contains booking code
                if (contentMatch) {
                    log.info("✅ Found matching refund transaction for booking {}: ref={}", bookingCode, txn.getReferenceNumber());
                    return TransactionVerificationDTO.builder()
                            .bookingCode(bookingCode)
                            .expectedAmount(amount)
                            .expectedAccountNumber(accountNumber)
                            .expectedAccountName(accountName)
                            .expectedBank(bankCode)
                            .verified(true)
                            .transactionReference(txn.getReferenceNumber())
                            .transactionDate(txn.getTransactionDate())
                            .build();
                }
            }

            log.warn("❌ No matching refund transaction found for booking: {}", bookingCode);
            return TransactionVerificationDTO.builder()
                    .bookingCode(bookingCode)
                    .expectedAmount(amount)
                    .verified(false)
                    .build();

        } catch (Exception e) {
            log.error("Error verifying refund transaction: {}", e.getMessage());
            return TransactionVerificationDTO.builder()
                    .bookingCode(bookingCode)
                    .verified(false)
                    .build();
        }
    }

    @Override
    public String generateTransferContent(String bookingCode) {
        return "HOANTIEN " + bookingCode.toUpperCase();
    }

    @Override
    public TransactionVerificationDTO verifyWithdrawalTransaction(String referenceCode, java.math.BigDecimal amount) {
        try {
            log.info("Verifying coin withdrawal on SePay for referenceCode: {}, amount: {} VND", referenceCode, amount);
            List<SepayTransactionResponse.Transaction> transactions = getRecentTransactions();
            log.info("SePay returned {} transactions to scan for withdrawal {}", transactions.size(), referenceCode);

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (SepayTransactionResponse.Transaction txn : transactions) {
                // Only check outgoing transactions (money sent OUT from our account)
                if (txn.getAmountOut() == null || txn.getAmountOut().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    continue;
                }
                // Only transactions within last 24 hours
                LocalDateTime txnDate;
                try {
                    txnDate = LocalDateTime.parse(txn.getTransactionDate(), formatter);
                } catch (Exception e) {
                    continue;
                }
                if (txnDate.isBefore(now.minusHours(24))) {
                    continue;
                }
                String content = txn.getTransactionContent();
                boolean contentMatch = content != null && content.toUpperCase().contains(referenceCode.toUpperCase());
                java.math.BigDecimal difference = txn.getAmountOut().subtract(amount).abs();
                boolean amountMatch = difference.compareTo(new java.math.BigDecimal("1000")) <= 0;

                log.info("  → Txn id={} amountOut={} content=\"{}\" | contentMatch={} amountMatch={}",
                        txn.getId(), txn.getAmountOut(), content, contentMatch, amountMatch);

                if (contentMatch && amountMatch) {
                    String ref = txn.getReferenceNumber() != null ? txn.getReferenceNumber() : txn.getId();
                    log.info("✅ Found matching withdrawal transaction for {}: ref={}", referenceCode, ref);
                    return TransactionVerificationDTO.builder()
                            .bookingCode(referenceCode)
                            .expectedAmount(amount)
                            .verified(true)
                            .transactionReference(ref)
                            .transactionDate(txn.getTransactionDate())
                            .build();
                }
            }

            log.warn("❌ No matching withdrawal transaction found for referenceCode: {}", referenceCode);
            return TransactionVerificationDTO.builder()
                    .bookingCode(referenceCode)
                    .expectedAmount(amount)
                    .verified(false)
                    .build();
        } catch (Exception e) {
            log.error("Error verifying withdrawal transaction: {}", e.getMessage());
            return TransactionVerificationDTO.builder()
                    .bookingCode(referenceCode)
                    .verified(false)
                    .build();
        }
    }
}
