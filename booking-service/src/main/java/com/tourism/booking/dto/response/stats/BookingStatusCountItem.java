package com.tourism.booking.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingStatusCountItem {
    private String status;
    private Long count;
    private BigDecimal revenue;
}
