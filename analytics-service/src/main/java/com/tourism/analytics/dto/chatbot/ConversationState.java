package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * ConversationState — lưu toàn bộ trạng thái hội thoại trong Redis.
 * Key: "chatbot:session:{sessionId}"  TTL: 30 phút
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationState implements Serializable {

    public enum Stage {
        IDLE,
        COLLECTING_SEARCH_INFO,
        SHOWING_SEARCH_RESULTS,
        SELECTING_DEPARTURE,
        COLLECTING_PASSENGERS,
        COLLECTING_CONTACT_NAME_PHONE,
        COLLECTING_CONTACT_EMAIL,
        COLLECTING_NOTE_COUPON,
        CONFIRMING_BOOKING,
        BOOKING_SUCCESS,
        COLLECTING_LOOKUP_CODE
    }

    @Builder.Default
    private Stage stage = Stage.IDLE;
    private Stage previousStage;

    // ─── Search params ───
    private String searchDestination;
    private String searchStartLocation; // điểm khởi hành (ví dụ: "hcm", "hà nội")
    private String searchDateRange;
    @Builder.Default private int searchAdults   = 1;
    @Builder.Default private int searchChildren = 0;
    @Builder.Default private int searchToddlers = 0;
    @Builder.Default private int searchInfants  = 0;
    @Builder.Default private boolean searchStartLocationProvided = false;
    @Builder.Default private boolean searchDateRangeProvided = false;
    @Builder.Default private boolean searchAdultsProvided = false;
    @Builder.Default private boolean searchChildrenProvided = false;

    // ─── Tour đã chọn (từ Pinecone metadata) ───
    private Integer selectedTourId;
    private String  selectedTourCode;
    private String  selectedTourName;
    private String  selectedTourImage;
    private String  selectedDuration;
    private String  departureCity;

    // ─── Departure đã chọn (departureID có sẵn trong Pinecone!) ───
    private Integer selectedDepartureId;
    private String  departureDateDisplay;  // "18/06/2026" — hiển thị
    private String  departureDateRaw;      // "2026-06-18" — dùng API

    // ─── Giá (lấy từ /api/departures/order-info) ───
    private Long adultPrice;
    private Long childPrice;
    private Long toddlerPrice;
    private Long infantPrice;
    private Long singleRoomSurcharge;
    private Integer availableSlots;

    // ─── Passengers ───
    @Builder.Default
    private List<PassengerData> passengers = new ArrayList<>();
    @Builder.Default
    private int currentPassengerIndex = 0;

    // ─── Contact ───
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String contactAddress;
    private String customerNote;
    @Builder.Default
    private List<String> couponCodes = new ArrayList<>();
    private Integer pointsUsed;

    // ─── Booking result ───
    private String bookingCode;
    private Long   bookingId;
    private Long   totalPrice;
    private String paymentUrl;
    private String paymentWaitingLink;  // /payment-waiting?orderCode=...&bookingCode=...
    private String paymentDeadline;

    // ─── Order lookup ───
    private String lookupCode;

    // ─── Tour search results cache ───
    @Builder.Default
    private List<TourGroupDisplay> lastSearchResults = new ArrayList<>();

    // ─── All departure docs from last Pinecone search ───
    @Builder.Default
    private List<DepartureMeta> lastDepartures = new ArrayList<>();

    // ─── Conversation memory (Phase 2) ───
    @Builder.Default
    private List<ChatTurn> recentTurns = new ArrayList<>();   // giữ 6 turns gần nhất
    private Integer lastMentionedTourId;        // tourId được nhắc gần nhất
    private Integer lastMentionedDepartureId;   // departureId được nhắc gần nhất

    // ────────────────────────────────────────
    // Inner classes
    // ────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PassengerData implements Serializable {
        private String  type;         // ADULT | CHILD | TODDLER | INFANT
        private int     index;        // 1-based within type
        private String  fullName;
        private String  gender;       // MALE | FEMALE | OTHER
        private String  dateOfBirth;  // YYYY-MM-DD collected from customer
        private boolean singleRoom;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TourGroupDisplay implements Serializable {
        private Integer tourId;
        private String  tourCode;
        private String  tourName;
        private String  imageUrl;
        private String  duration;
        private String  startLocationName;
        private Long    adultSalePrice;
        private List<DepartureMeta> departures;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartureMeta implements Serializable {
        private Integer departureId;
        private String  departureDate;   // "2026-06-18"
        private Integer availableSlots;
        private Long    salePrice;
    }

    /** Lưu một lượt hội thoại (user hoặc assistant) để truyền context cho Gemini */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ChatTurn implements Serializable {
        private String role;       // "user" | "assistant"
        private String content;
        private long   timestamp;  // System.currentTimeMillis()
    }
}
