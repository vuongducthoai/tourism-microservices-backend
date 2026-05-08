package com.tourism.booking.dto.sepay;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SepayTransactionResponse {

    private int status;
    private Object error;
    private Messages messages;
    private List<Transaction> transactions;

    @Data
    public static class Messages {
        private boolean success;
    }

    @Data
    public static class Transaction {

        private String id;

        @JsonProperty("transaction_date")
        private String transactionDate;

        @JsonProperty("account_number")
        private String accountNumber;

        @JsonProperty("amount_in")
        private BigDecimal amountIn;

        @JsonProperty("amount_out")
        private BigDecimal amountOut;

        private BigDecimal accumulated;

        @JsonProperty("transaction_content")
        private String transactionContent;

        @JsonProperty("reference_number")
        private String referenceNumber;

        @JsonProperty("sub_account")
        private String subAccount;

        @JsonProperty("bank_brand_name")
        private String bankBrandName;

        @JsonProperty("bank_account_id")
        private String bankAccountId;

        private String code;
    }
}
