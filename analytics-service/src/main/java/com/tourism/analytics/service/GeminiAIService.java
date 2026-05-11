package com.tourism.analytics.service;

import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;

import java.util.List;

public interface GeminiAIService {
    String generateDashboardSummary(String context);
    List<DashboardStatsDTO.Insight> generateInsights(String context);
    List<DashboardStatsDTO.Prediction> generatePredictions(String context);
    List<DashboardStatsDTO.Recommendation> generateRecommendations(String context);
}
