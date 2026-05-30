package com.tourism.forum.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAnalyticsResponse {
    private Map<String, Long> postsByType;
    private Map<String, Long> postsByStatus;
    private List<DailyCount> postsLast30Days;
    private List<NameCount> topCategories;
    private List<NameCount> topTags;
    private Map<String, Long> moderationDistribution;

    // Sprint 5: dashboard nâng cao
    private List<ViolatorCount> topViolators;
    private AiAccuracy aiAccuracy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyCount {
        private String date;
        private Long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NameCount {
        private String name;
        private Long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolatorCount {
        private Integer userId;
        private String userName;
        private String userEmail;
        private Long violationCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiAccuracy {
        private Long totalFlagged;       // số bài AI flag (TOXIC + BORDERLINE)
        private Long confirmedByAdmin;   // AI flag → admin giữ HIDDEN/DELETED → AI đúng
        private Long overruledByAdmin;   // AI flag → admin approve PUBLISHED → AI sai
        private Double precisionPercent; // confirmedByAdmin / totalFlagged * 100
    }
}
