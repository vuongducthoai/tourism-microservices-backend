package com.tourism.analytics.service;

import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class IntentRouterTest {

    @Mock
    private GeminiIntentService geminiIntentService;

    @Mock
    private LocationResolverService locationResolverService;

    private IntentRouter intentRouter;
    private ConversationState idleState;

    @BeforeEach
    void setUp() {
        intentRouter = new IntentRouter(new ReferenceResolverService(), geminiIntentService, locationResolverService);
        idleState = ConversationState.builder()
                .stage(ConversationState.Stage.IDLE)
                .recentTurns(new ArrayList<>())
                .build();

        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.DESTINATION)))
                .thenReturn(Optional.empty());
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.START)))
                .thenReturn(Optional.empty());
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.ANY)))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("BK code is routed to booking lookup/payment group")
    void bkCodeRoutesToBookingLookupPayment() {
        IntentResult result = intentRouter.route("BK12345678", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_LOOKUP_PAYMENT);
        assertThat(result.getBookingCode()).isEqualTo("BK12345678");
    }

    @Test
    @DisplayName("Booking request is routed to transaction flow")
    void bookingRequestRoutesToTransactionFlow() {
        IntentResult result = intentRouter.route("toi muon dat tour nay", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
    }

    @Test
    @DisplayName("Tour search is routed to tour retrieval with SEARCH task")
    void tourSearchRoutesToTourRetrieval() {
        whenDestination("da nang");

        IntentResult result = intentRouter.route("toi muon tim tour di da nang", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SEARCH);
        assertThat(result.getDestination()).isEqualTo("da nang");
    }

    @Test
    @DisplayName("Free-form destination is extracted without hard-coded aliases")
    void freeFormDestinationIsExtracted() {
        IntentResult result = intentRouter.route("co tour di phu yen ko", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SEARCH);
        assertThat(result.getDestination()).isEqualTo("phu yen");
    }

    @Test
    @DisplayName("Start location search remains tour retrieval search")
    void startLocationSearchRoutesToTourRetrieval() {
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.START)))
                .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation(
                        "TP. Ho Chi Minh", null, LocationResolverService.Role.START, "test")));

        IntentResult result = intentRouter.route("co tour khoi hanh hcm khong", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SEARCH);
        assertThat(result.getStartLocation()).isEqualTo("TP. Ho Chi Minh");
    }

    @Test
    @DisplayName("Context slot question is retrieval SLOT task")
    void contextualSlotQuestionRoutesToSlotTask() {
        idleState.setLastMentionedTourId(200);
        idleState.setLastMentionedDepartureId(600);

        IntentResult result = intentRouter.route("con may slot?", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.SLOT);
        assertThat(result.getRawSource()).isEqualTo("reference-resolver");
    }

    @Test
    @DisplayName("Detail question is retrieval DETAIL task")
    void detailQuestionRoutesToDetailTask() {
        IntentResult result = intentRouter.route("xem chi tiet tour nay", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_RETRIEVAL);
        assertThat(result.getRetrievalTask()).isEqualTo(IntentResult.RetrievalTask.DETAIL);
    }

    @Test
    @DisplayName("General advice stays in general RAG")
    void generalAdviceRoutesToGeneralRag() {
        IntentResult result = intentRouter.route("gia dinh co tre nho nen chuan bi gi khi di dai ngay", idleState);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.GENERAL_RAG);
    }

    @Test
    @DisplayName("Numeric selection in search results is transaction input")
    void numericSelectionRoutesToTransactionFlow() {
        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.SHOWING_SEARCH_RESULTS)
                .recentTurns(new ArrayList<>())
                .build();

        IntentResult result = intentRouter.route("1", state);

        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
    }

    @Test
    @DisplayName("Date/index input in departure stage is transaction input")
    void departureStageDateRoutesToTransactionFlow() {
        ConversationState state = ConversationState.builder()
                .stage(ConversationState.Stage.SELECTING_DEPARTURE)
                .recentTurns(new ArrayList<>())
                .build();

        assertThat(intentRouter.route("1", state).getIntent())
                .isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
        assertThat(intentRouter.route("20/03", state).getIntent())
                .isEqualTo(IntentResult.Intent.TRANSACTION_FLOW);
    }

    @Test
    @DisplayName("Blank message is unknown")
    void blankMessageIsUnknown() {
        assertThat(intentRouter.route(null, idleState).getIntent()).isEqualTo(IntentResult.Intent.UNKNOWN);
        assertThat(intentRouter.route("   ", idleState).getIntent()).isEqualTo(IntentResult.Intent.UNKNOWN);
    }

    private void whenDestination(String name) {
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.DESTINATION)))
                .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation(
                        name, null, LocationResolverService.Role.DESTINATION, "test")));
    }
}
