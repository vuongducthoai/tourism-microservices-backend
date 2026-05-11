package com.tourism.analytics.service;

import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;

import java.time.LocalDate;

public interface DashboardService {
    DashboardStatsDTO getDashboardStatistics(LocalDate from, LocalDate to);
    DashboardStatsDTO.AIAnalysis getDashboardAIAnalysis(LocalDate from, LocalDate to, String mode);
}
