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
    }

    @Test
    @DisplayName("BK lookup pattern -> BOOKING_LOOKUP")
    void testBkLookup() {
        IntentResult result = intentRouter.route("BK12345678", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_LOOKUP);
        assertThat(result.getBookingCode()).isEqualTo("BK12345678");
    }

    @Test
    @DisplayName("Tour search phrase -> TOUR_SEARCH")
    void testTourSearch() {
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.DESTINATION)))
                .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("da nang", null, LocationResolverService.Role.DESTINATION, "test")));
        IntentResult result = intentRouter.route("toi muon tim tour di da nang", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        assertThat(result.getDestination()).isEqualTo("da nang");
    }

    @Test
    @DisplayName("Booking intent phrase -> BOOKING_FLOW")
    void testBookingIntent() {
        IntentResult result = intentRouter.route("toi muon dat tour", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_FLOW);
    }

    @Test
    @DisplayName("Vietnamese booking intent with d-stroke -> BOOKING_FLOW")
    void testVietnameseBookingIntent() {
        IntentResult result = intentRouter.route("tôi đặt tour", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_FLOW);
    }

    @Test
    @DisplayName("Free-form destination without resolver -> TOUR_SEARCH")
    void testFreeFormDestinationWithoutResolver() {
        IntentResult result = intentRouter.route("co tour di phu yen ko", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        assertThat(result.getDestination()).isEqualTo("phu yen");
    }

    @Test
    @DisplayName("Vietnamese free-form destination -> TOUR_SEARCH")
    void testVietnameseFreeFormDestination() {
        IntentResult result = intentRouter.route("có tour đi đà lạt ko", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        assertThat(result.getDestination()).isEqualTo("da lat");
    }

    @Test
    @DisplayName("Booking lookup help phrase -> BOOKING_LOOKUP")
    void testBookingLookupHelp() {
        IntentResult result = intentRouter.route("toi muon xem 1 booking thi sao", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_LOOKUP);
    }

    @Test
    @DisplayName("Payment help phrase -> PAYMENT_HELP")
    void testPaymentHelp() {
        IntentResult result = intentRouter.route("toi can giup do ve thanh toan", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.PAYMENT_HELP);
    }

    @Test
    @DisplayName("System booking support phrase -> SYSTEM_HELP")
    void testSystemHelp() {
        IntentResult result = intentRouter.route("co ho tro dat tour tren chat khong", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.SYSTEM_HELP);
    }

    @Test
    @DisplayName("Start location search -> START_LOCATION_SEARCH")
    void testStartLocationSearch() {
        lenient().when(locationResolverService.resolve(any(), eq(LocationResolverService.Role.START)))
                .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("hcm", null, LocationResolverService.Role.START, "test")));
        IntentResult result = intentRouter.route("co tour khoi hanh hcm khong", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.START_LOCATION_SEARCH);
        assertThat(result.getStartLocation()).isEqualTo("hcm");
    }

    @Test
    @DisplayName("Pronoun reference with tour in context -> resolve via ReferenceResolver")
    void testPronounReferenceWithContext() {
        idleState.setLastMentionedTourId(101);
        idleState.setLastMentionedDepartureId(501);
        idleState.setLastSearchResults(new ArrayList<>());

        IntentResult result = intentRouter.route("tour do con may cho?", idleState);
        assertThat(result.getResolvedTourId()).isEqualTo(101);
        assertThat(result.getResolvedDepId()).isEqualTo(501);
        assertThat(result.getRawSource()).isEqualTo("reference-resolver");
    }

    @Test
    @DisplayName("Short contextual question with departure context -> ASK_SLOT")
    void testContextualSlotQuestion() {
        idleState.setLastMentionedTourId(200);
        idleState.setLastMentionedDepartureId(600);

        IntentResult result = intentRouter.route("con may slot?", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.ASK_SLOT);
        assertThat(result.getRawSource()).isEqualTo("reference-resolver");
    }

    @Test
    @DisplayName("Month extraction from search query")
    void testMonthExtraction() {
        IntentResult result = intentRouter.route("tim tour di hue thang 7", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        assertThat(result.getTravelMonth()).isEqualTo("thang 7");
    }

    @Test
    @DisplayName("Adult count extraction")
    void testAdultCountExtraction() {
        IntentResult result = intentRouter.route("tim tour cho 3 nguoi lon", idleState);
        assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        assertThat(result.getAdultCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("null/blank message -> UNKNOWN")
    void testNullMessage() {
        assertThat(intentRouter.route(null, idleState).getIntent()).isEqualTo(IntentResult.Intent.UNKNOWN);
        assertThat(intentRouter.route("   ", idleState).getIntent()).isEqualTo(IntentResult.Intent.UNKNOWN);
    }
}
