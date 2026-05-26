package com.tourism.analytics.dto.chatbot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IntentResult - normalized routing result from fast rules or Gemini.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {

    public enum Intent {
        GREETING,
        CANCEL,
        RESUME_BOOKING,

        TOUR_SEARCH,
        CHANGE_SEARCH,
        START_LOCATION_SEARCH,

        BOOKING_FLOW,
        BOOKING_LOOKUP,

        ASK_DETAIL,
        ASK_SLOT,
        ASK_PRICE,
        ASK_CHILD_PRICE,
        ASK_DEPARTURE_DATE,
        ASK_ITINERARY,
        ASK_POLICY,
        ASK_DISCOUNT,
        ASK_COUPON,

        PAYMENT_HELP,
        GENERAL_TRAVEL_ADVICE,
        SYSTEM_HELP,
        UNKNOWN
    }

    private Intent  intent;
    private String  destination;
    private String  startLocation;
    private String  travelMonth;
    private Integer adultCount;
    private Integer childCount;
    private String  bookingCode;
    private Integer resolvedTourId;
    private Integer resolvedDepId;
    private Integer resolvedTourIdx;
    private String  queryText;
    private String  rawSource;
    private double  confidence;
}
