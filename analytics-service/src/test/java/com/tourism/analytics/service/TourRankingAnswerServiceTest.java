package com.tourism.analytics.service;

import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TourRankingAnswerServiceTest {

    @Mock
    private TourCatalogFeignClient tourCatalogFeignClient;

    @InjectMocks
    private TourRankingAnswerService service;

    @Test
    void highRatedQuestion_returnsSuggestionsAndBookingContext() {
        TourSyncDTO phuQuoc = tour(4, "HCM-PQ-5N4D", "TP. Hồ Chí Minh - Phú Quốc", 4.0, 3, 8500000.0, 88);
        TourSyncDTO haLong = tour(3, "HN-HL-3N2D", "Hà Nội - Hạ Long", 4.0, 1, 2900000.0, 77);
        TourSyncDTO noReview = tour(5, "NO-REVIEW", "Tour chưa review", 5.0, 0, 1000000.0, 99);
        when(tourCatalogFeignClient.getAllToursForChatbotSync()).thenReturn(List.of(haLong, noReview, phuQuoc));

        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.IDLE)
                .build();

        ChatMessageResponse response = service.tryAnswer("các tour được review cao", "s1", state);

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
        assertThat(response.getTourSuggestions()).extracting(ChatMessageResponse.TourSuggestion::getTourCode)
                .containsExactly("HCM-PQ-5N4D", "HN-HL-3N2D");
        assertThat(state.getStage()).isEqualTo(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        assertThat(state.getLastSearchResults()).hasSize(2);
        assertThat(state.getLastSearchResults().get(0).getTourCode()).isEqualTo("HCM-PQ-5N4D");
        assertThat(state.getLastSearchResults().get(0).getDepartures()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getDepartures().get(0).getDepartureId()).isEqualTo(88);
    }

    @Test
    void noRatedTours_returnsClearText() {
        when(tourCatalogFeignClient.getAllToursForChatbotSync())
                .thenReturn(List.of(tour(1, "A", "A", 0.0, 0, 100.0, 10)));

        ChatMessageResponse response = service.tryAnswer(
                "tour rating cao nhất",
                "s1",
                ConversationState.builder().stage(ConversationState.Stage.IDLE).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TEXT");
        assertThat(response.getReply()).contains("chưa thấy tour");
    }

    private TourSyncDTO tour(Integer id, String code, String name, Double rating, Integer reviews,
                             Double price, Integer departureId) {
        TourSyncDTO.DepartureSyncDTO dep = new TourSyncDTO.DepartureSyncDTO(
                departureId,
                "2027-04-20",
                10,
                price,
                price + 500000,
                null,
                null,
                null,
                null,
                List.of(),
                List.of()
        );
        return new TourSyncDTO(
                id,
                code,
                name,
                "5 Ngày 4 Đêm",
                "Máy bay",
                "TP. Hồ Chí Minh",
                1,
                "Phú Quốc",
                2,
                "Vinpearl Safari",
                "Buffet sáng",
                "Resort",
                "image.jpg",
                rating,
                reviews,
                List.of(dep),
                List.of()
        );
    }
}
