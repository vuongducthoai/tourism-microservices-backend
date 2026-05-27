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

        TRANSACTION_FLOW,
        BOOKING_LOOKUP_PAYMENT,
        TOUR_RETRIEVAL,
        GENERAL_RAG,

        UNKNOWN
    }

    public enum RetrievalTask {
        SEARCH,
        DETAIL,
        SLOT,
        PRICE,
        CHILD_PRICE,
        DEPARTURE_DATE,
        ITINERARY,
        POLICY,
        DISCOUNT,
        COUPON,
        ADVICE
    }

    private Intent  intent;
    private RetrievalTask retrievalTask;
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
