package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDepartureListItem {
    private Integer departureID;
    private Integer tourID;
    private String tourName;
    private String tourCode;
    private String tourDuration;
    private String departureDate;
    private Integer availableSlots;
    private Integer totalBookings;
    private BigDecimal lowestPrice;
    private Boolean hasOutboundTransport;
    private Boolean hasInboundTransport;
    private Boolean status;
    private LocalDateTime createdAt;
}
