package com.tourism.analytics.service;

import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class TourRankingAnswerService {

    private final TourCatalogFeignClient tourCatalogFeignClient;

    public ChatMessageResponse tryAnswer(String userMessage, String sessionId, ConversationState state) {
        if (!canRun(state) || !isRankingQuestion(userMessage)) return null;

        List<TourSyncDTO> tours;
        try {
            tours = tourCatalogFeignClient.getAllToursForChatbotSync();
        } catch (Exception e) {
            log.warn("Could not fetch tour rankings for chatbot: {}", e.getMessage());
            return null;
        }

        List<TourSyncDTO> ranked = tours.stream()
                .filter(tour -> safeDouble(tour.getAvgRating()) >= 4.0)
                .filter(tour -> safeInt(tour.getReviewCount()) > 0)
                .sorted(Comparator
                        .comparing((TourSyncDTO tour) -> safeDouble(tour.getAvgRating())).reversed()
                        .thenComparing((TourSyncDTO tour) -> safeInt(tour.getReviewCount()), Comparator.reverseOrder())
                        .thenComparing(this::findMinPrice))
                .limit(5)
                .toList();

        if (ranked.isEmpty()) {
            return ChatMessageResponse.builder()
                    .reply("Hiện tại mình chưa thấy tour nào có đánh giá từ khách trong dữ liệu hệ thống.")
                    .quickActions(new ArrayList<>())
                    .tourSuggestions(new ArrayList<>())
                    .sessionId(sessionId)
                    .timestamp(LocalDateTime.now())
                    .messageType("TEXT")
                    .conversationStage(stageName(state))
                    .build();
        }

        rememberTourContext(state, ranked);
        StringBuilder reply = new StringBuilder("Các tour đang được khách đánh giá cao:");
        List<ChatMessageResponse.TourSuggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            TourSyncDTO tour = ranked.get(i);
            reply.append("\n").append(i + 1).append(". ")
                    .append(tour.getTourName())
                    .append(" (").append(tour.getTourCode()).append(")")
                    .append(" - ").append(formatRating(tour.getAvgRating()))
                    .append("/5 từ ").append(safeInt(tour.getReviewCount())).append(" lượt đánh giá")
                    .append(", giá từ ").append(formatPrice(findMinPrice(tour)));

            suggestions.add(ChatMessageResponse.TourSuggestion.builder()
                    .tourId(tour.getTourID())
                    .tourCode(tour.getTourCode())
                    .tourName(tour.getTourName())
                    .imageUrl(tour.getImageUrl())
                    .duration(tour.getDuration())
                    .minPrice(findMinPrice(tour))
                    .detailUrl(tour.getTourCode() != null ? "/tour/" + tour.getTourCode() : null)
                    .relevanceScore(safeDouble(tour.getAvgRating()) / 5.0)
                    .build());
        }
        reply.append("\n\nBạn có thể nhập \"xem chi tiết tour 1\" hoặc \"đặt tour 1\" để tiếp tục.");

        return ChatMessageResponse.builder()
                .reply(reply.toString())
                .tourSuggestions(suggestions)
                .quickActions(new ArrayList<>())
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now())
                .messageType("TOUR_SUGGESTIONS")
                .conversationStage(stageName(state))
                .build();
    }

    private void rememberTourContext(ConversationState state, List<TourSyncDTO> tours) {
        if (state == null || tours == null || tours.isEmpty()) return;

        List<ConversationState.TourGroupDisplay> groups = new ArrayList<>();
        List<ConversationState.DepartureMeta> allDepartures = new ArrayList<>();
        for (TourSyncDTO tour : tours) {
            List<ConversationState.DepartureMeta> departures = new ArrayList<>();
            if (tour.getDepartures() != null) {
                for (TourSyncDTO.DepartureSyncDTO dep : tour.getDepartures()) {
                    if (dep.getDepartureID() == null || dep.getDepartureDate() == null || dep.getDepartureDate().isBlank()) continue;
                    ConversationState.DepartureMeta meta = ConversationState.DepartureMeta.builder()
                            .departureId(dep.getDepartureID())
                            .departureDate(dep.getDepartureDate())
                            .availableSlots(dep.getAvailableSlots() != null ? dep.getAvailableSlots() : 0)
                            .salePrice(dep.getAdultSalePrice() != null ? dep.getAdultSalePrice().longValue() : 0L)
                            .build();
                    departures.add(meta);
                    allDepartures.add(meta);
                }
            }

            groups.add(ConversationState.TourGroupDisplay.builder()
                    .tourId(tour.getTourID())
                    .tourCode(tour.getTourCode())
                    .tourName(tour.getTourName())
                    .imageUrl(tour.getImageUrl())
                    .duration(tour.getDuration())
                    .startLocationName(tour.getStartLocationName())
                    .adultSalePrice(findMinPrice(tour).longValue())
                    .departures(departures)
                    .build());
        }

        state.setLastSearchResults(groups);
        state.setLastDepartures(allDepartures);
        state.setLastMentionedTourId(groups.get(0).getTourId());
        if (!allDepartures.isEmpty()) {
            state.setLastMentionedDepartureId(allDepartures.get(0).getDepartureId());
        }
        if (state.getStage() == ConversationState.Stage.IDLE
                || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                || state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS) {
            state.setStage(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        }
    }

    private boolean canRun(ConversationState state) {
        if (state == null || state.getStage() == null) return true;
        return state.getStage() == ConversationState.Stage.IDLE
                || state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS
                || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO;
    }

    private boolean isRankingQuestion(String message) {
        String text = normalize(message);
        return text.contains("danh gia cao")
                || text.contains("review cao")
                || text.contains("rating cao")
                || text.contains("cao nhat")
                || text.contains("tot nhat")
                || text.contains("khach khen")
                || text.contains("duoc khen")
                || text.contains("nhieu sao");
    }

    private Double findMinPrice(TourSyncDTO tour) {
        if (tour == null || tour.getDepartures() == null) return 0.0;
        return tour.getDepartures().stream()
                .filter(dep -> dep.getAdultSalePrice() != null)
                .mapToDouble(TourSyncDTO.DepartureSyncDTO::getAdultSalePrice)
                .min()
                .orElse(0.0);
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private double safeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private String formatRating(Double value) {
        return String.format(Locale.US, "%.1f", safeDouble(value));
    }

    private String formatPrice(Double price) {
        if (price == null || price <= 0) return "đang cập nhật";
        return String.format("%,.0f VNĐ", price);
    }

    private String stageName(ConversationState state) {
        return state != null && state.getStage() != null
                ? state.getStage().name()
                : ConversationState.Stage.IDLE.name();
    }
}
