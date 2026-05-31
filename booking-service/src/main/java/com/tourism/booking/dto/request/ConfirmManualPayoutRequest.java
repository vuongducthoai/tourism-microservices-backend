package com.tourism.booking.dto.request;

import lombok.Data;

@Data
public class ConfirmManualPayoutRequest {
    /** Mã giao dịch ngân hàng do admin nhập tay (tuỳ chọn). */
    private String transferRef;
    /** Ghi chú xác nhận (tuỳ chọn). */
    private String note;
}
