package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Dữ liệu đính kèm trong ChatMessageResponse khi stage = CONFIRMING_BOOKING.
 * Frontend dùng để render BookingConfirmCard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingConfirmData {
    private String tourName;
    private String tourCode;
    private String tourImage;
    private String duration;
    private String departureDate;     // "18/06/2026"
    private String departureCity;

    private List<PassengerSummary> passengers;
    private String contactName;
    private String contactPhone;
    private String contactEmail;

    private int  adultCount;
    private int  childCount;
    private int  toddlerCount;
    private int  infantCount;
    private long adultPrice;
    private long childPrice;
    private long toddlerPrice;
    private long infantPrice;
    private long singleRoomSurcharge;
    private long estimatedTotal;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PassengerSummary {
        private String type;
        private String fullName;
        private String gender;
        private String dateOfBirth;
    }
}
