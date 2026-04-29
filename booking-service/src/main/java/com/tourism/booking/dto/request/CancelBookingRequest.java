package com.tourism.booking.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CancelBookingRequest {
    private Integer bookingID;
    private String  cancelReason;
}
