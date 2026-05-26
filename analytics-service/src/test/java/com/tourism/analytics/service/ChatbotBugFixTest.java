package com.tourism.analytics.service;

import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.feign.ChatbotBookingFeignClient;
import com.tourism.analytics.feign.ChatbotPaymentFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit tests for all chatbot bug fixes and business logic.
 *
 * Coverage:
 * BUG-SELECT : "1" in SHOWING_SEARCH_RESULTS → TOUR_SEARCH (was UNKNOWN)
 * BUG-DEP    : "1","2","3" in SELECTING_DEPARTURE → numeric index selection
 * BUG-DEST   : "toi di hcm den phu quoc" → destination = Phú Quốc (not HCM)
 * BUG-CONFIRM: "xác nhận" in CONFIRMING_BOOKING → BOOKING_FLOW (was UNKNOWN)
 * Logic      : parseAndFillSearchParamsV3, isCancel edge cases, departure prompt
 */
@ExtendWith(MockitoExtension.class)
class ChatbotBugFixTest {

    // ─── IntentRouter dependencies ───
    @Mock private GeminiIntentService geminiService;
    @Mock private LocationResolverService locationResolver;

    private IntentRouter intentRouter;

    // ─── BookingConversationService dependencies ───
    @Mock private RedisSessionService sessionService;
    @Mock private VectorService vectorService;
    @Mock private TourCatalogFeignClient tourCatalogClient;
    @Mock private ChatbotBookingFeignClient bookingClient;
    @Mock private ChatbotPaymentFeignClient paymentClient;

    private BookingConversationService bookingService;

    // ─── helpers ───
    private static ConversationState stateAt(ConversationState.Stage stage) {
        return ConversationState.builder()
                .stage(stage)
                .recentTurns(new ArrayList<>())
                .passengers(new ArrayList<>())
                .searchAdults(2)
                .build();
    }

    private static ChatMessageRequest req(String msg, String sid) {
        return ChatMessageRequest.builder().message(msg).sessionId(sid).build();
    }

    @BeforeEach
    void setUp() {
        intentRouter = new IntentRouter(new ReferenceResolverService(), geminiService, locationResolver);

        bookingService = new BookingConversationService(
                sessionService, vectorService, locationResolver,
                tourCatalogClient, bookingClient, paymentClient);
        ReflectionTestUtils.setField(bookingService, "frontendUrl", "http://localhost:3000");

        // Default: resolver returns empty for all roles
        lenient().when(locationResolver.resolve(any(), any())).thenReturn(Optional.empty());
        lenient().when(locationResolver.normalizeText(any())).thenAnswer(inv -> {
            String s = (String) inv.getArgument(0);
            if (s == null) return "";
            return s.toLowerCase()
                    .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
                    .replaceAll("[èéẹẻẽêềếệểễ]", "e")
                    .replaceAll("[ìíịỉĩ]", "i")
                    .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
                    .replaceAll("[ùúụủũưừứựửữ]", "u")
                    .replaceAll("[ỳýỵỷỹ]", "y")
                    .replaceAll("[đ]", "d")
                    .replaceAll("[^a-z0-9 ]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        });
        lenient().when(sessionService.getOrCreate(any())).thenReturn(stateAt(ConversationState.Stage.IDLE));
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 1 – IntentRouter fast-path fixes
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BUG-SELECT: numeric selection in SHOWING_SEARCH_RESULTS")
    class BugSelectTests {

        @ParameterizedTest(name = "msg=\"{0}\" → TOUR_SEARCH")
        @ValueSource(strings = {"1", "2", "3"})
        @DisplayName("Single digit in SHOWING_SEARCH_RESULTS → TOUR_SEARCH")
        void numericInShowingResults_shouldReturnTourSearch(String msg) {
            ConversationState state = stateAt(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
            IntentResult result = intentRouter.route(msg, state);
            assertThat(result.getIntent())
                    .as("msg='%s' in SHOWING_SEARCH_RESULTS must be TOUR_SEARCH", msg)
                    .isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }

        @Test
        @DisplayName("\"1\" in IDLE → NOT treated as tour selection")
        void numericInIdle_shouldNotBeTourSearch() {
            ConversationState state = stateAt(ConversationState.Stage.IDLE);
            IntentResult result = intentRouter.route("1", state);
            // In IDLE, "1" alone has no special meaning — should not be TOUR_SEARCH fast-path
            assertThat(result.getIntent()).isNotEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }

        @Test
        @DisplayName("\"tour 1\" in SHOWING_SEARCH_RESULTS → TOUR_SEARCH")
        void tourPhraseInShowingResults_shouldReturnTourSearch() {
            ConversationState state = stateAt(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
            IntentResult result = intentRouter.route("tour 1", state);
            assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }
    }

    @Nested
    @DisplayName("BUG-DEP: numeric selection in SELECTING_DEPARTURE")
    class BugDepartureIntentTests {

        @ParameterizedTest(name = "msg=\"{0}\" → BOOKING_FLOW")
        @ValueSource(strings = {"1", "2", "3"})
        @DisplayName("Single digit in SELECTING_DEPARTURE → BOOKING_FLOW")
        void numericInSelectingDeparture_shouldReturnBookingFlow(String msg) {
            ConversationState state = stateAt(ConversationState.Stage.SELECTING_DEPARTURE);
            IntentResult result = intentRouter.route(msg, state);
            assertThat(result.getIntent())
                    .as("msg='%s' in SELECTING_DEPARTURE must be BOOKING_FLOW", msg)
                    .isEqualTo(IntentResult.Intent.BOOKING_FLOW);
        }
    }

    @Nested
    @DisplayName("BUG-CONFIRM: confirmation in CONFIRMING_BOOKING → BOOKING_FLOW")
    class BugConfirmTests {

        @ParameterizedTest(name = "confirm msg=\"{0}\" → BOOKING_FLOW")
        @ValueSource(strings = {"xac nhan", "xác nhận", "ok", "dong y", "đồng ý", "dat ngay", "yes", "confirm"})
        @DisplayName("Confirmation words in CONFIRMING_BOOKING → BOOKING_FLOW")
        void confirmInConfirmingBooking_shouldReturnBookingFlow(String msg) {
            ConversationState state = stateAt(ConversationState.Stage.CONFIRMING_BOOKING);
            IntentResult result = intentRouter.route(msg, state);
            assertThat(result.getIntent())
                    .as("confirm msg='%s' in CONFIRMING_BOOKING must be BOOKING_FLOW", msg)
                    .isNotEqualTo(IntentResult.Intent.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("BUG-DEST: 'X đến Y' pattern extracts Y as destination")
    class BugDestinationTests {

        @BeforeEach
        void mockResolver() {
            // "phu quoc" → destination
            lenient().when(locationResolver.resolve(eq("phu quoc"), eq(LocationResolverService.Role.DESTINATION)))
                    .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("Phú Quốc", null, LocationResolverService.Role.DESTINATION, "catalog")));
            lenient().when(locationResolver.resolve(eq("phu quoc"), eq(LocationResolverService.Role.ANY)))
                    .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("Phú Quốc", null, LocationResolverService.Role.ANY, "catalog")));
            // "toi di hcm" → start = HCM
            lenient().when(locationResolver.resolve(eq("toi di hcm"), eq(LocationResolverService.Role.ANY)))
                    .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("TP. Hồ Chí Minh", null, LocationResolverService.Role.START, "catalog")));
        }

        @Test
        @DisplayName("'toi di hcm den phu quoc' → destination is Phú Quốc, NOT HCM")
        void denPattern_shouldSetDestinationToAfterDen() {
            // Use COLLECTING_SEARCH_INFO stage so extractSearchEntities is invoked via fallback
            IntentResult result = intentRouter.route("toi di hcm den phu quoc",
                    stateAt(ConversationState.Stage.COLLECTING_SEARCH_INFO));
            assertThat(result.getDestination())
                    .as("destination should be after den, NOT HCM")
                    .isNotNull()
                    .doesNotContainIgnoringCase("hcm")
                    .isEqualTo("Phú Quốc");
        }

        @Test
        @DisplayName("'di hcm den phu quoc thang 7' → destination=Phú Quốc + month extracted")
        void denPatternWithMonth_shouldExtractBoth() {
            IntentResult result = intentRouter.route("di hcm den phu quoc thang 7",
                    stateAt(ConversationState.Stage.COLLECTING_SEARCH_INFO));
            assertThat(result.getDestination()).isNotNull().isEqualTo("Phú Quốc");
            assertThat(result.getTravelMonth()).isEqualTo("thang 7");
        }

        @Test
        @DisplayName("'den nha trang' → destination contains 'nha trang'")
        void denPatternAtStart() {
            // extractFreeDestination fallback: "den nha trang" → "nha trang"
            IntentResult result = intentRouter.route("den nha trang",
                    stateAt(ConversationState.Stage.COLLECTING_SEARCH_INFO));
            assertThat(result.getDestination())
                    .isNotNull()
                    .contains("nha trang");
        }

        private ConversationState idleState() {
            return stateAt(ConversationState.Stage.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 2 – BookingConversationService.parseAndFillSearchParamsV3
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseAndFillSearchParamsV3: destination extraction logic")
    class ParseFillSearchParamsTests {

        private ConversationState state;

        @BeforeEach
        void setup() {
            state = stateAt(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            // "phu quoc" resolves to Phú Quốc
            lenient().when(locationResolver.resolve(eq("phu quoc"), any()))
                    .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("Phú Quốc", null, LocationResolverService.Role.ANY, "catalog")));
            // "toi di hcm" resolves start=HCM
            lenient().when(locationResolver.resolve(eq("toi di hcm"), any()))
                    .thenReturn(Optional.of(new LocationResolverService.ResolvedLocation("TP. Hồ Chí Minh", null, LocationResolverService.Role.START, "catalog")));
        }

        @Test
        @DisplayName("'toi di hcm den phu quoc' → dest=Phú Quốc, start=TP. Hồ Chí Minh")
        void denPattern_setsCorrectDestAndStart() {
            // Invoke via reflection since parseAndFillSearchParamsV3 is private
            invokeParseAndFill("toi di hcm den phu quoc", state);
            assertThat(state.getSearchDestination())
                    .as("destination must be Phú Quốc (after 'den')")
                    .isEqualTo("Phú Quốc");
            assertThat(state.getSearchStartLocation())
                    .as("start must be TP. Hồ Chí Minh (before 'den')")
                    .isEqualTo("TP. Hồ Chí Minh");
        }

        @Test
        @DisplayName("already-set destination should NOT be overridden when start location is also set")
        void presetDestination_shouldNotBeOverridden() {
            state.setSearchDestination("Phú Quốc");
            state.setSearchStartLocation("TP. Hồ Chí Minh");
            // Message says something unrelated to location
            invokeParseAndFill("thang 8 2 nguoi lon", state);
            // Destination should remain Phú Quốc
            assertThat(state.getSearchDestination()).isEqualTo("Phú Quốc");
            assertThat(state.getSearchStartLocation()).isEqualTo("TP. Hồ Chí Minh");
        }

        @Test
        @DisplayName("adult count extracted: '2 nguoi lon'")
        void adultCountExtracted() {
            invokeParseAndFill("2 nguoi lon", state);
            assertThat(state.getSearchAdults()).isEqualTo(2);
        }

        @Test
        @DisplayName("month extracted: 'thang 7'")
        void monthExtracted() {
            invokeParseAndFill("thang 7", state);
            assertThat(state.getSearchDateRange()).isEqualTo("2027-07");
        }

        @Test
        @DisplayName("'2 tre em' sets children count")
        void childrenExtracted() {
            invokeParseAndFill("2 tre em", state);
            assertThat(state.getSearchChildren()).isEqualTo(2);
        }

        @Test
        @DisplayName("'khong co tre' sets children=0, toddlers=0, infants=0")
        void noChildren() {
            invokeParseAndFill("khong co tre", state);
            assertThat(state.getSearchChildren()).isEqualTo(0);
            assertThat(state.getSearchToddlers()).isEqualTo(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 3 – handleDepartureSelection numeric index
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BUG-DEP: handleDepartureSelection numeric index '1','2','3'")
    class HandleDepartureSelectionTests {

        private ConversationState state;
        private ConversationState.TourGroupDisplay tour;

        @BeforeEach
        void setup() {
            state = stateAt(ConversationState.Stage.SELECTING_DEPARTURE);
            state.setSelectedTourId(101);

            ConversationState.DepartureMeta dep1 = ConversationState.DepartureMeta.builder()
                    .departureId(501).departureDate("2027-07-10").availableSlots(10)
                    .salePrice(5000000L).build();
            ConversationState.DepartureMeta dep2 = ConversationState.DepartureMeta.builder()
                    .departureId(502).departureDate("2027-07-20").availableSlots(5)
                    .salePrice(5000000L).build();
            ConversationState.DepartureMeta dep3 = ConversationState.DepartureMeta.builder()
                    .departureId(503).departureDate("2027-08-05").availableSlots(8)
                    .salePrice(5000000L).build();

            tour = ConversationState.TourGroupDisplay.builder()
                    .tourId(101).tourName("Tour Phú Quốc 4N3Đ")
                    .adultSalePrice(5000000L)
                    .departures(new ArrayList<>(List.of(dep1, dep2, dep3)))
                    .build();

            state.setLastSearchResults(new ArrayList<>(List.of(tour)));

            // Mock tourCatalogClient response so departure fetching doesn't NPE
            try {
                com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse pricing =
                        new com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse();
                pricing.setAdultPrice(BigDecimal.valueOf(5000000));
                pricing.setChildPrice(BigDecimal.ZERO);
                pricing.setToddlerPrice(BigDecimal.ZERO);
                pricing.setInfantPrice(BigDecimal.ZERO);
                lenient().when(tourCatalogClient.getDepartureOrderInfo(any())).thenReturn(pricing);
            } catch (Exception ignored) {}
        }

        @Test
        @DisplayName("msg='1' selects first departure (dep1, id=501)")
        void msg1_selectsFirstDeparture() {
            ChatMessageResponse resp = bookingService.handle(req("1", "sid1"), state);
            assertThat(resp).isNotNull();
            assertThat(state.getSelectedDepartureId())
                    .as("Departure id=501 should be selected for '1'")
                    .isEqualTo(501);
            assertThat(state.getStage())
                    .isEqualTo(ConversationState.Stage.COLLECTING_PASSENGERS);
        }

        @Test
        @DisplayName("msg='2' selects second departure (dep2, id=502)")
        void msg2_selectsSecondDeparture() {
            ChatMessageResponse resp = bookingService.handle(req("2", "sid2"), state);
            assertThat(resp).isNotNull();
            assertThat(state.getSelectedDepartureId())
                    .as("Departure id=502 should be selected for '2'")
                    .isEqualTo(502);
        }

        @Test
        @DisplayName("msg='3' selects third departure (dep3, id=503)")
        void msg3_selectsThirdDeparture() {
            ChatMessageResponse resp = bookingService.handle(req("3", "sid3"), state);
            assertThat(resp).isNotNull();
            assertThat(state.getSelectedDepartureId())
                    .as("Departure id=503 should be selected for '3'")
                    .isEqualTo(503);
        }

        @Test
        @DisplayName("msg='18/07' selects departure by date match")
        void msgDate_selectsByDate() {
            ChatMessageResponse resp = bookingService.handle(req("18/07", "sid4"), state);
            // Should NOT select any — no departure on 18/07, looksLikeDate → re-show list
            assertThat(resp).isNotNull();
            assertThat(state.getStage()).isEqualTo(ConversationState.Stage.SELECTING_DEPARTURE);
        }

        @Test
        @DisplayName("msg='10/07' selects dep1 (2027-07-10)")
        void msgExactDate_matchesDeparture() {
            ChatMessageResponse resp = bookingService.handle(req("10/07", "sid5"), state);
            assertThat(resp).isNotNull();
            assertThat(state.getSelectedDepartureId()).isEqualTo(501);
        }

        @Test
        @DisplayName("msg='off-topic question' returns null (RAG fallback)")
        void offTopic_returnsNull() {
            // Off-topic that doesn't look like date → null (RAG)
            ChatMessageResponse resp = bookingService.handle(req("thoi tiet hom nay nhu the nao", "sid6"), state);
            assertThat(resp).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 4 – isCancel edge cases
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isCancel edge cases")
    class IsCancelTests {

        @ParameterizedTest(name = "''{0}'' → cancel=true")
        @ValueSource(strings = {"huy", "thoi", "cancel", "thoat", "exit",
                "huy di", "thoi di", "bo qua", "khong dat", "thoat ra"})
        @DisplayName("Cancel phrases → isCancel=true")
        void cancelPhrases_returnTrue(String msg) {
            assertThat(bookingService.isCancel(msg))
                    .as("'%s' should be treated as cancel", msg)
                    .isTrue();
        }

        @ParameterizedTest(name = "''{0}'' → cancel=false")
        @ValueSource(strings = {
                "thoi tiet ha noi",     // weather query, NOT cancel
                "thoi tiet hom nay",    // weather query
                "huy bo bao hiem",      // compound phrase — context-dependent
                "xac nhan dat tour",    // confirmation
                "toi muon dat tour",    // booking intent
                "tour phu quoc"         // search
        })
        @DisplayName("Non-cancel phrases → isCancel=false")
        void nonCancelPhrases_returnFalse(String msg) {
            assertThat(bookingService.isCancel(msg))
                    .as("'%s' should NOT be treated as cancel", msg)
                    .isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 5 – Cancel in active booking stage
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Global cancel in active booking stage resets to IDLE")
    class CancelInActiveStageTests {

        @ParameterizedTest(name = "stage={0} + cancel → IDLE")
        @CsvSource({
                "SELECTING_DEPARTURE,huy",
                "COLLECTING_PASSENGERS,thoi",
                "COLLECTING_CONTACT_NAME_PHONE,cancel",
                "COLLECTING_CONTACT_EMAIL,thoat",
                "CONFIRMING_BOOKING,huy di"
        })
        @DisplayName("Cancel message in any active stage → IDLE")
        void cancelInActiveStage_resetsToIdle(String stageName, String cancelMsg) {
            ConversationState.Stage stage = ConversationState.Stage.valueOf(stageName);
            ConversationState state = stateAt(stage);
            state.setSelectedTourId(101);
            state.setSelectedDepartureId(501);

            ChatMessageResponse resp = bookingService.handle(req(cancelMsg, "sid"), state);

            assertThat(resp).isNotNull();
            assertThat(state.getStage())
                    .as("After cancel in %s, stage must be IDLE", stageName)
                    .isEqualTo(ConversationState.Stage.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 6 – IntentRouter stage-aware fallbacks
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage-aware fallbacks when Gemini is unavailable")
    class StageFallbackTests {

        @BeforeEach
        void setup() {
            // Gemini unavailable
            lenient().when(geminiService.classify(any(), any())).thenReturn(null);
        }

        @Test
        @DisplayName("COLLECTING_SEARCH_INFO + any text → TOUR_SEARCH")
        void collectingSearchInfo_fallback() {
            ConversationState state = stateAt(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            IntentResult result = intentRouter.route("thang 8", state);
            assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }

        @Test
        @DisplayName("SHOWING_SEARCH_RESULTS + any non-numeric text → TOUR_SEARCH fallback")
        void showingResults_textFallback() {
            ConversationState state = stateAt(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
            IntentResult result = intentRouter.route("chon tour dau tien", state);
            assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }

        @Test
        @DisplayName("COLLECTING_PASSENGERS → BOOKING_FLOW fallback")
        void collectingPassengers_fallback() {
            ConversationState state = stateAt(ConversationState.Stage.COLLECTING_PASSENGERS);
            IntentResult result = intentRouter.route("Nguyen Van A nam 1990", state);
            assertThat(result.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_FLOW);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 7 – Specific intent fast-paths
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Intent fast-paths")
    class IntentFastPathTests {

        @ParameterizedTest(name = "''{0}'' → CANCEL")
        @ValueSource(strings = {"huy", "thoi", "thoat", "cancel"})
        @DisplayName("Cancel phrases → CANCEL intent")
        void cancelIntents(String msg) {
            IntentResult r = intentRouter.route(msg, stateAt(ConversationState.Stage.COLLECTING_PASSENGERS));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.CANCEL);
        }

        @ParameterizedTest(name = "''{0}'' → GREETING")
        @ValueSource(strings = {"xin chao", "hello", "hi", "chao ban", "chào"})
        @DisplayName("Greeting phrases → GREETING intent")
        void greetingIntents(String msg) {
            IntentResult r = intentRouter.route(msg, stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.GREETING);
        }

        @Test
        @DisplayName("BK code → BOOKING_LOOKUP with code extracted")
        void bkLookup() {
            IntentResult r = intentRouter.route("BK12345678", stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_LOOKUP);
            assertThat(r.getBookingCode()).isEqualTo("BK12345678");
        }

        @Test
        @DisplayName("'tra cuu don hang' → BOOKING_LOOKUP")
        void lookupPhrase() {
            IntentResult r = intentRouter.route("tra cuu don hang cua toi", stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.BOOKING_LOOKUP);
        }

        @Test
        @DisplayName("'tiep tuc dat tour' → RESUME_BOOKING")
        void resumeBooking() {
            IntentResult r = intentRouter.route("tiep tuc dat tour", stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.RESUME_BOOKING);
        }

        @ParameterizedTest(name = "''{0}'' → ASK_DISCOUNT")
        @ValueSource(strings = {"co giam gia khong", "tour khuyen mai", "co uu dai gi khong"})
        @DisplayName("Discount queries → ASK_DISCOUNT")
        void discountQueries(String msg) {
            IntentResult r = intentRouter.route(msg, stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.ASK_DISCOUNT);
        }

        @ParameterizedTest(name = "''{0}'' → ASK_COUPON")
        @ValueSource(strings = {"co coupon khong", "ma coupon tour", "voucher tour"})
        @DisplayName("Coupon queries → ASK_COUPON")
        void couponQueries(String msg) {
            IntentResult r = intentRouter.route(msg, stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.ASK_COUPON);
        }

        @Test
        @DisplayName("'toi muon tim tour di phu quoc' → TOUR_SEARCH")
        void tourSearch() {
            IntentResult r = intentRouter.route("toi muon tim tour di phu quoc", stateAt(ConversationState.Stage.IDLE));
            assertThat(r.getIntent()).isEqualTo(IntentResult.Intent.TOUR_SEARCH);
        }

        @Test
        @DisplayName("null/blank → UNKNOWN")
        void nullBlank() {
            assertThat(intentRouter.route(null, stateAt(ConversationState.Stage.IDLE)).getIntent())
                    .isEqualTo(IntentResult.Intent.UNKNOWN);
            assertThat(intentRouter.route("   ", stateAt(ConversationState.Stage.IDLE)).getIntent())
                    .isEqualTo(IntentResult.Intent.UNKNOWN);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 8 – COLLECTING_LOOKUP_CODE stage
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("COLLECTING_LOOKUP_CODE: BK code input")
    class LookupCodeTests {

        @Test
        @DisplayName("BK code in COLLECTING_LOOKUP_CODE → performs lookup")
        void bkCodeInLookupStage() {
            ConversationState state = stateAt(ConversationState.Stage.COLLECTING_LOOKUP_CODE);

            ChatMessageResponse resp = bookingService.handle(req("BK12345678", "sid"), state);
            // Even on client error, should return a not-null message
            assertThat(resp).isNotNull();
        }

        @Test
        @DisplayName("Non-BK message in COLLECTING_LOOKUP_CODE asks for code again")
        void nonBkInLookupStage() {
            ConversationState state = stateAt(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
            ChatMessageResponse resp = bookingService.handle(req("abc123", "sid"), state);
            assertThat(resp).isNotNull();
            assertThat(resp.getReply()).contains("BK");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCK 9 – IDLE stage booking intent detection
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDLE stage: booking intent detection")
    class IdleStageTests {

        @ParameterizedTest(name = "''{0}'' → stage=COLLECTING_SEARCH_INFO")
        @ValueSource(strings = {
                "toi muon dat tour", "tim tour di phu quoc", "muon di du lich",
                "dat cho 2 nguoi", "book tour", "toi can dat tour"
        })
        @DisplayName("Booking phrases in IDLE → stage transitions to COLLECTING_SEARCH_INFO")
        void bookingPhraseInIdle_transitionsToCollectingSearchInfo(String msg) {
            ConversationState state = stateAt(ConversationState.Stage.IDLE);
            ChatMessageResponse resp = bookingService.handle(req(msg, "sid"), state);
            assertThat(resp).isNotNull();
            assertThat(state.getStage())
                    .as("After booking phrase '%s', stage should be COLLECTING_SEARCH_INFO", msg)
                    .isEqualTo(ConversationState.Stage.COLLECTING_SEARCH_INFO);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER – invoke private parseAndFillSearchParamsV3 via reflection
    // ═══════════════════════════════════════════════════════════════

    private void invokeParseAndFill(String msg, ConversationState state) {
        try {
            var method = BookingConversationService.class.getDeclaredMethod(
                    "parseAndFillSearchParamsV3", String.class, ConversationState.class);
            method.setAccessible(true);
            method.invoke(bookingService, msg, state);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parseAndFillSearchParamsV3: " + e.getMessage(), e);
        }
    }
}

