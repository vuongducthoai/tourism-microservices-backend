package com.tourism.booking.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class CreateBookingRequest {

    private Integer departureId;
    private Integer userId;         // null nếu guest

    private String contactFullName;
    private String contactPhone;
    private String contactEmail;
    private String contactAddress;
    private String customerNote;

    private List<PassengerRequest> passengers;
    private List<String> couponCode;
    private Integer pointsUsed;

    @Data
    public static class PassengerRequest {
        private String fullName;
        private String gender;
        private String dateOfBirth;     // "YYYY-MM-DD"
        private String type;            // ADULT, CHILD, TODDLER, INFANT
        private boolean singleRoom;
    }
}
