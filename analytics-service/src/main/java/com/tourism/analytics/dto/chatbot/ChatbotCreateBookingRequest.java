package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatbotCreateBookingRequest {
    private Integer departureId;
    private Integer userId;          // null = guest
    private String contactFullName;
    private String contactPhone;
    private String contactEmail;
    private String contactAddress;
    private String customerNote;
    private List<PassengerRequest> passengers;
    private List<String> couponCode;
    private Integer pointsUsed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PassengerRequest {
        private String fullName;
        private String gender;       // MALE | FEMALE | OTHER
        private String dateOfBirth;  // YYYY-MM-DD
        private String type;         // ADULT | CHILD | TODDLER | INFANT
        private boolean singleRoom;
    }
}
