package com.tourism.analytics.service;

import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.feign.ChatbotBookingFeignClient;
import com.tourism.analytics.feign.ChatbotPaymentFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ChatbotBugFixTest {

    @Mock private GeminiIntentService geminiService;
    @Mock private LocationResolverService locationResolver;
    @Mock private RedisSessionService sessionService;
    @Mock private VectorService vectorService;
    @Mock private TourCatalogFeignClient tourCatalogClient;
    @Mock private ChatbotBookingFeignClient bookingClient;
    @Mock private ChatbotPaymentFeignClient paymentClient;

    private IntentRouter intentRouter;
    private BookingConversationService bookingService;

    @BeforeEach
    void setUp() {
        intentRouter = new IntentRouter(new ReferenceResolverService(), geminiService, locationResolver);
        bookingService = new BookingConversationService(
                sessionService, vectorService, locationResolver,
                tourCatalogClient, bookingClient, paymentClient);
        ReflectionTestUtils.setField(bookingService, "frontendUrl", "http://localhost:3000");

        lenient().when(locationResolver.resolve(any(), any())).thenReturn(Optional.empty());
        lenient().when(locationResolver.normalizeText(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        });
    }

    @Test
    @DisplayName("Route groups replace small business intents")
    void routeGroupsReplaceSmallBusinessIntents() {
        assertThat(intentRouter.route("toi muon dat tour", idle()).getIntent())
                .isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);

        IntentResult slot = intentRouter.route("con may slot", idle());
        assertThat(slot.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(slot.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SLOT);

        IntentResult discount = intentRouter.route("tour nao dang giam gia", idle());
        assertThat(discount.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(discount.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.DISCOUNT);

        assertThat(intentRouter.route("toi muon xem don hang da dat", idle()).getIntent())
                .isEqualTo(IntentResult.Intent.BOOKING_LOOKUP_PAYMENT);
    }

    @Test
    @DisplayName("Search result index stays transaction flow, not a new search")
    void searchIndexStaysTransactionFlow() {
        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.SHOWING_SEARCH_RESULTS)
                .recentTurns(new ArrayList<>())
                .build();

        assertThat(intentRouter.route("1", state).getIntent())
                .isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
        assertThat(intentRouter.route("2", state).getIntent())
                .isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
    }

    @Test
    @DisplayName("Route pattern 'A den B' keeps B as destination")
    void routePatternKeepsDestinationAfterDen() {
        IntentResult result = intentRouter.route("tour ha noi den hai phong thang 4 2 nguoi lon", idle());

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SEARCH);
        assertThat(result.getDestination()).contains("hai phong");
        assertThat(result.getTravelMonth()).isEqualTo("thang 4");
        assertThat(result.getAdultCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Booking passenger count rejects infant count greater than adults")
    void passengerCountRejectsInfantsGreaterThanAdults() {
        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.COLLECTING_PASSENGERS)
                .searchAdults(1)
                .availableSlots(10)
                .passengers(new ArrayList<>())
                .build();

        ChatMessageResponse response = bookingService.handle(
                ChatMessageRequest.builder()
                        .sessionId("test-session")
                        .message("1 nguoi lon 2 em be")
                        .build(),
                state);

        assertThat(response).isNotNull();
        assertThat(response.getConversationStage()).isEqualTo("COLLECTING_PASSENGERS");
        assertThat(state.getPassengers()).isEmpty();
    }

    @Test
    @DisplayName("Optional note/coupon step moves to confirmation")
    void optionalNoteCouponMovesToConfirmation() {
        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.COLLECTING_NOTE_COUPON)
                .selectedTourName("Tour Test")
                .selectedTourCode("TEST")
                .selectedDuration("2 Ngay 1 Dem")
                .departureDateDisplay("20/03/2027")
                .departureCity("Ha Noi")
                .searchAdults(1)
                .adultPrice(1_000_000L)
                .passengers(List.of(ConversationState.PassengerData.builder()
                        .type("ADULT")
                        .fullName("Nguyen Van A")
                        .gender("MALE")
                        .dateOfBirth("1990-01-01")
                        .build()))
                .contactName("Nguyen Van A")
                .contactPhone("0901234567")
                .contactEmail("a@gmail.com")
                .build();

        ChatMessageResponse response = bookingService.handle(
                ChatMessageRequest.builder()
                        .sessionId("test-session")
                        .message("bo qua")
                        .build(),
                state);

        assertThat(response.getMessageType()).isEqualTo("BOOKING_CONFIRM");
        assertThat(response.getConversationStage()).isEqualTo("CONFIRMING_BOOKING");
        assertThat(state.getStage()).isEqualTo(ConversationState.Stage.CONFIRMING_BOOKING);
    }

    private ConversationState idle() {
        return ConversationState.builder()
                .stage(ConversationState.Stage.IDLE)
                .recentTurns(new ArrayList<>())
                .build();
    }
}
