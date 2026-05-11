package com.tourism.booking.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentBookingItem {
    private String bookingCode;
    private String description;
    private String createdAt;   // ISO datetime string
    private String type;        // BOOKING | REFUND
    private String severity;    // WARNING | URGENT
}
