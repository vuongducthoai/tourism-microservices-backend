package com.tourism.payment.feign.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BookingPaymentDetailResponse {
    private Integer bookingId;
    private String bookingCode;
    private String status;
}
