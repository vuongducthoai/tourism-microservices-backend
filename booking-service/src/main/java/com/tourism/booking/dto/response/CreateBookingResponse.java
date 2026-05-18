package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingResponse {
    private String bookingCode;
    private Integer bookingId;
    private BigDecimal totalPrice;
    private String status;
}
