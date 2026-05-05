package com.tourism.analytics.dto;

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
