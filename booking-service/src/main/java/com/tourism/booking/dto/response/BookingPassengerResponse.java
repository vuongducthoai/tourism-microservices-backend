package com.tourism.booking.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
public class BookingPassengerResponse {
    private Integer    passengerID;
    private String     fullName;
    private String     gender;
    private LocalDate  dateOfBirth;
    private String     passengerType;
    private BigDecimal basePrice;
    private Boolean    requiresSingleRoom;
    private BigDecimal singleRoomSurcharge;
}
