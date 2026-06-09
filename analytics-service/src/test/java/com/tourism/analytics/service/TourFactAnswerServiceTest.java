package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TourFactAnswerServiceTest {

    @Mock
    private VectorService vectorService;

    @InjectMocks
    private TourFactAnswerService service;

    private final Gson gson = new Gson();

    @Test
    void buffetHotelQuestion_returnsAmenityAnswer() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 1,
                "tourCode", "HN-SA-4N3D",
                "tourName", "Hà Nội - Sa Pa 4N3Đ",
                "duration", "4 Ngày 3 Đêm",
                "meals", "Ăn sáng buffet tại khách sạn, trưa và tối theo chương trình",
                "hotel", "Khách sạn 3-4 sao tại Sa Pa",
                "attractions", "Đỉnh Fansipan, bản Cát Cát"
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer(
                "tour Hà Nội Sa Pa có buffet khách sạn không",
                "s1",
                idle()
        );

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("buffet", "Khách sạn");
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
    }

    @Test
    void hcmStartQuestion_returnsTourSuggestions() {
        Map<String, Object> meta = meta(
                "tourId", 9,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "Hồ Chí Minh - Phú Quốc 5N4Đ",
                "duration", "5 Ngày 4 Đêm",
                "startLocationName", "Hồ Chí Minh",
                "minPrice", 6900000.0,
                "departureDates", List.of("2030-06-15", "2030-07-20")
        );
        when(vectorService.searchSimilar(anyString(), anyInt()))
                .thenReturn(List.of(doc("TOUR_START_LOCATION", meta)));

        ConversationState state = idle();
        ChatMessageResponse response = service.tryAnswer(
                "tour khởi hành hồ chí minh",
                "s1",
                state
        );

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
        assertThat(response.getTourSuggestions()).hasSize(1);
        assertThat(response.getReply()).contains("Hồ Chí Minh", "HCM-PQ-5N4D");
        assertThat(state.getLastSearchResults()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getTourCode()).isEqualTo("HCM-PQ-5N4D");
        assertThat(state.getLastMentionedTourId()).isEqualTo(9);
    }

    @Test
    void phoQuestion_returnsFoodTour() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 3,
                "tourCode", "HN-HL-3N2D",
                "tourName", "Hà Nội - Hạ Long 3N2Đ",
                "duration", "3 Ngày 2 Đêm",
                "meals", "Sáng: Phở/Bánh cuốn. Trưa: Hải sản. Tối: Buffet trên tàu",
                "hotel", "Tàu hạng sang",
                "attractions", "Vịnh Hạ Long"
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("tour nào ăn phở hay bún ko", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("Phở");
    }

    @Test
    void typoBufetQuestion_matchesBuffetTour() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "meals", "Buffet sang tai resort, hai san tuoi moi bua",
                "hotel", "Resort bien",
                "attractions", "Phu Quoc"
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("toi muon an bufet", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("Buffet");
    }

    @Test
    void genericDishQuestion_returnsTourSuggestions() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 10,
                "tourCode", "HCM-FOOD-3N2D",
                "tourName", "TP. Ho Chi Minh - Mien Tay Am Thuc 3N2D",
                "duration", "3 Ngay 2 Dem",
                "meals", "Lau ca bop, trai cay mien vuon",
                "hotel", "Khach san 3 sao",
                "attractions", "Cho noi Cai Rang",
                "minPrice", 3200000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("co lau ca bop khong", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
        assertThat(response.getReply()).contains("Lau ca bop");
    }

    @Test
    void genericDishQuestionWithTypo_usesFuzzyMatch() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 10,
                "tourCode", "HCM-FOOD-3N2D",
                "tourName", "TP. Ho Chi Minh - Mien Tay Am Thuc 3N2D",
                "duration", "3 Ngay 2 Dem",
                "meals", "Lau ca bop, trai cay mien vuon",
                "hotel", "Khach san 3 sao",
                "attractions", "Cho noi Cai Rang",
                "minPrice", 3200000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("co lau ca bot ko", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("Lau ca bop");
    }

    @Test
    void genericAttractionQuestion_returnsTourSuggestions() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "meals", "Buffet sang tai resort, hai san tuoi moi bua",
                "hotel", "Resort bien",
                "attractions", "Vinpearl Safari, Grand World, Bai Sao",
                "minPrice", 8500000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("co Vinpearl Safari khong", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
        assertThat(response.getReply()).contains("Vinpearl Safari");
    }

    @Test
    void genericAttractionWithPlaceQuestion_returnsMatchingTour() {
        VectorDocumentDTO doc = doc("TOUR_AMENITY", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "meals", "Buffet sang tai resort, hai san tuoi moi bua",
                "hotel", "Resort bien",
                "attractions", "Vinpearl Safari, Grand World, Bai Sao",
                "minPrice", 8500000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        ChatMessageResponse response = service.tryAnswer("Grand World Phu Quoc co tour nao", "s1", idle());

        assertThat(response).isNotNull();
        assertThat(response.getTourSuggestions()).hasSize(1);
        assertThat(response.getTourSuggestions().get(0).getTourCode()).isEqualTo("HCM-PQ-5N4D");
    }

    @Test
    void genericFactResult_remembersBookableDepartureContext() {
        VectorDocumentDTO amenity = doc("TOUR_AMENITY", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "meals", "Buffet sang tai resort, hai san tuoi moi bua",
                "hotel", "Resort bien",
                "attractions", "Vinpearl Safari, Grand World, Bai Sao",
                "minPrice", 8500000.0
        ));
        VectorDocumentDTO departure = doc("TOUR_DEPARTURE", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "startLocationName", "TP. Ho Chi Minh",
                "departureID", 88,
                "departureDate", "2027-04-20",
                "availableSlots", 10,
                "salePrice", 8500000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt()))
                .thenReturn(List.of(amenity), List.of(departure));

        ConversationState state = idle();
        ChatMessageResponse response = service.tryAnswer("co Vinpearl Safari khong", "s1", state);

        assertThat(response).isNotNull();
        assertThat(state.getStage()).isEqualTo(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        assertThat(state.getLastSearchResults()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getTourCode()).isEqualTo("HCM-PQ-5N4D");
        assertThat(state.getLastSearchResults().get(0).getDepartures()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getDepartures().get(0).getDepartureId()).isEqualTo(88);
    }

    @Test
    void semanticSeafoodQuestion_remembersBookableDepartureContext() {
        VectorDocumentDTO amenity = doc("TOUR_AMENITY", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "meals", "Buffet sang tai resort, hai san tuoi moi bua",
                "hotel", "Resort bien",
                "attractions", "Phu Quoc",
                "minPrice", 8500000.0
        ));
        VectorDocumentDTO departure = doc("TOUR_DEPARTURE", meta(
                "tourId", 4,
                "tourCode", "HCM-PQ-5N4D",
                "tourName", "TP. Ho Chi Minh - Phu Quoc 5N4D",
                "duration", "5 Ngay 4 Dem",
                "startLocationName", "TP. Ho Chi Minh",
                "departureID", 77,
                "departureDate", "2027-04-20",
                "availableSlots", 12,
                "salePrice", 8500000.0
        ));
        when(vectorService.searchSimilar(anyString(), anyInt()))
                .thenReturn(List.of(amenity), List.of(departure));

        ConversationState state = idle();
        ChatMessageResponse response = service.tryAnswer("tour nao ma hai san tuoi moi bua", "s1", state);

        assertThat(response).isNotNull();
        assertThat(response.getMessageType()).isEqualTo("TOUR_SUGGESTIONS");
        assertThat(state.getStage()).isEqualTo(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        assertThat(state.getLastSearchResults()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getDepartures()).hasSize(1);
        assertThat(state.getLastSearchResults().get(0).getDepartures().get(0).getDepartureId()).isEqualTo(77);
    }

    @Test
    void noFactSignal_returnsNullAndDoesNotSearch() {
        ChatMessageResponse response = service.tryAnswer("có tour đi sa pa không", "s1", idle());

        assertThat(response).isNull();
        verify(vectorService, never()).searchSimilar(anyString(), anyInt());
    }

    private ConversationState idle() {
        return ConversationState.builder()
                .stage(ConversationState.Stage.IDLE)
                .build();
    }

    private VectorDocumentDTO doc(String type, Map<String, Object> meta) {
        return VectorDocumentDTO.builder()
                .id(type + "_1")
                .type(type)
                .content("Chi tiết: test content")
                .metadata(gson.toJson(meta))
                .score(0.9f)
                .build();
    }

    private Map<String, Object> meta(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
