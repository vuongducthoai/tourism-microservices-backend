package com.tourism.booking.convert;

import com.tourism.booking.dto.response.BookingPassengerResponse;
import com.tourism.booking.entity.BookingPassenger;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

/**
 * Converts between BookingPassenger entity and BookingPassengerResponse DTO.
 *
 * Fields mapped automatically by ModelMapper (same name + type):
 *   passengerID, fullName, gender, dateOfBirth, basePrice,
 *   requiresSingleRoom, singleRoomSurcharge
 *
 * Fields mapped manually (different type):
 *   passengerType  (PassengerType enum → String)
 */
@Component
public class BookingPassengerConverter {

    private final ModelMapper modelMapper;

    public BookingPassengerConverter(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    // ── Entity → DTO ─────────────────────────────────────────────────────────

    public BookingPassengerResponse toResponse(BookingPassenger passenger) {
        // ModelMapper handles all same-name/type fields automatically
        BookingPassengerResponse res = modelMapper.map(passenger, BookingPassengerResponse.class);

        // Manual: enum → String
        res.setPassengerType(passenger.getPassengerType() != null
                ? passenger.getPassengerType().name()
                : null);

        return res;
    }
}
