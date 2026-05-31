package com.tourism.booking.service.transfer;

import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferResult {

    public enum Type {
        SUCCESS,
        RETRYABLE_FAILURE,
        MANUAL
    }

    private Type type;
    private String transferRef;
    private String note;
    private CoinWithdrawalErrorSource errorSource;

    public static TransferResult success(String transferRef) {
        return TransferResult.builder()
                .type(Type.SUCCESS)
                .transferRef(transferRef)
                .build();
    }

    public static TransferResult retryableFailure(CoinWithdrawalErrorSource errorSource, String note) {
        return TransferResult.builder()
                .type(Type.RETRYABLE_FAILURE)
                .errorSource(errorSource)
                .note(note)
                .build();
    }

    public static TransferResult manual(String note) {
        return TransferResult.builder()
                .type(Type.MANUAL)
                .note(note)
                .build();
    }
}