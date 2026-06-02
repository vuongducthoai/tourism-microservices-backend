package com.tourism.booking.service;

import com.tourism.booking.dto.sepay.SepayTransactionResponse;
import com.tourism.booking.dto.sepay.TransactionVerificationDTO;

import java.math.BigDecimal;
import java.util.List;

public interface SepayService {

    List<SepayTransactionResponse.Transaction> getRecentTransactions();

    TransactionVerificationDTO verifyRefundTransaction(
            String bookingCode,
            BigDecimal amount,
            String accountNumber,
            String accountName,
            String bankCode
    );

    String generateTransferContent(String bookingCode);

    /**
     * Verify an outgoing withdrawal transfer on SePay by matching
     * referenceCode in transaction content and amount within ±1000 VND.
     */
    TransactionVerificationDTO verifyWithdrawalTransaction(String referenceCode, java.math.BigDecimal amount);
}
