package com.tourism.analytics.dto;

import com.tourism.analytics.dto.chatbot.BookingConfirmData;
import com.tourism.analytics.dto.chatbot.ChatbotBookingDetailResponse;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageResponse {

    private String                  reply;
    private List<TourSuggestion>    tourSuggestions;
    private List<QuickAction>       quickActions;
    private String                  sessionId;
    private LocalDateTime           timestamp;

    // Fields mới — chatbot stateful booking flow
    /** TEXT | TOUR_SUGGESTIONS | BOOKING_CONFIRM | BOOKING_SUCCESS | ORDER_DETAIL */
    private String                  messageType;
    private String                  conversationStage;
    private BookingConfirmData      bookingConfirmData;
    private ChatbotBookingDetailResponse orderDetail;

    // Booking success fields (cho BookingSuccessCard frontend)
    private String                  bookingCode;
    private String                  paymentUrl;
    private String                  paymentWaitingLink;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TourSuggestion {
        private Integer tourId;
        private String  tourCode;
        private String  tourName;
        private String  imageUrl;
        private Double  minPrice;
        private String  duration;
        private String  detailUrl;
        private Double  relevanceScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuickAction {
        private String label;
        private String action;
        private String url;
    }
}
