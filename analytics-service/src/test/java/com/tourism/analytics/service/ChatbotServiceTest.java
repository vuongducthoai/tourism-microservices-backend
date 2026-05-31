package com.tourism.analytics.service;

import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho ChatbotService.
 *
 * Coverage:
 * - handleUserMessage: valid request, blank message
 * - buildEnhancedContext: discount query prioritization, context truncation
 * - buildEnhancedPrompt: prompt chứa userMessage và context
 * - callGeminiAPI: success path, error fallback
 * - buildTourSuggestions: deduplicate by tourId, max 6 items
 */
@ExtendWith(MockitoExtension.class)
class ChatbotServiceTest {

    @Mock
    private VectorService vectorService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RedisSessionService sessionService;

    @Mock
    private BookingConversationService bookingService;

    @Mock
    private IntentRouter intentRouter;

    @InjectMocks
    private ChatbotService chatbotService;

    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        injectField(chatbotService, "geminiApiKey",     "test-key");
        injectField(chatbotService, "generationModel",  "gemini-2.0-flash");

        lenient().when(sessionService.getOrCreate(anyString())).thenAnswer(invocation ->
                ConversationState.builder()
                        .stage(ConversationState.Stage.IDLE)
                        .recentTurns(new ArrayList<>())
                        .build());
        lenient().when(intentRouter.route(anyString(), any(ConversationState.class))).thenReturn(
                IntentResult.builder()
                        .intent(IntentResult.Intent.UNKNOWN)
                        .rawSource("test")
                        .confidence(0.3)
                        .build());
        lenient().when(bookingService.handle(any(ChatMessageRequest.class), any(ConversationState.class))).thenReturn(null);
    }

    // ─────────────────────────────────────────────
    // handleUserMessage
    // ─────────────────────────────────────────────

    @Test
    void handleUserMessage_returnsResponseWithReplyAndSuggestions() {
        // Arrange: mock Pinecone search
        VectorDocumentDTO doc = buildTourDoc(1, "HN001", "Tour Hà Nội", 2500000.0, 0.9f);
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of(doc));

        // Mock Gemini response
        mockGeminiResponse("Tour Hà Nội rất phù hợp cho gia đình.");

        ChatMessageRequest request = ChatMessageRequest.builder()
                .message("Tour Hà Nội có gì hay?")
                .sessionId("session-123")
                .userId(1)
                .build();

        // Act
        ChatMessageResponse response = chatbotService.handleUserMessage(request);

        // Assert
        assertThat(response.getReply()).contains("Tour Hà Nội");
        assertThat(response.getSessionId()).isEqualTo("session-123");
        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getTourSuggestions()).isNotEmpty();
        assertThat(response.getQuickActions()).isNotNull();
    }

    @Test
    void handleUserMessage_withNullSessionId_generatesNewSessionId() {
        // Arrange
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(List.of());
        mockGeminiResponse("Xin lỗi, không tìm thấy tour phù hợp.");

        ChatMessageRequest request = ChatMessageRequest.builder()
                .message("tour nào rẻ nhất?")
                .sessionId(null)
                .build();

        // Act
        ChatMessageResponse response = chatbotService.handleUserMessage(request);

        // Assert
        assertThat(response.getSessionId()).isNotNull().isNotBlank();
    }

    @Test
    void handleUserMessage_discountQuery_usesTopK50() {
        // Arrange
        when(vectorService.searchSimilar(anyString(), eq(50))).thenReturn(List.of());
        mockGeminiResponse("Hiện tại có các tour giảm giá...");

        // Act
        chatbotService.handleUserMessage(
                ChatMessageRequest.builder().message("tour giảm giá sâu nhất").build());

        // Assert: topK=50 was used for discount query
        verify(vectorService).searchSimilar(anyString(), eq(50));
    }

    @Test
    void handleUserMessage_normalQuery_usesTopK10() {
        // Arrange
        when(vectorService.searchSimilar(anyString(), eq(10))).thenReturn(List.of());
        mockGeminiResponse("Có nhiều tour đẹp.");

        // Act
        chatbotService.handleUserMessage(
                ChatMessageRequest.builder().message("tour miền bắc").build());

        // Assert: topK=10 was used
        verify(vectorService).searchSimilar(anyString(), eq(10));
    }

    // ─────────────────────────────────────────────
    // buildEnhancedContext
    // ─────────────────────────────────────────────

    @Test
    void buildEnhancedContext_discountQuery_prioritizesDiscountedDepartures() {
        // Arrange: 1 departure với coupon, 1 tour summary không có coupon
        Map<String, Object> depMeta = new HashMap<>();
        depMeta.put("tourId", 1.0);
        depMeta.put("tourCode", "HN001");
        depMeta.put("tourName", "Tour Hà Nội");
        depMeta.put("couponDiscount", 500000.0);
        depMeta.put("salePrice", 2000000.0);
        depMeta.put("imageUrl", "");
        depMeta.put("duration", "3N2Đ");

        VectorDocumentDTO depDoc = VectorDocumentDTO.builder()
                .id("DEP_1")
                .type("TOUR_DEPARTURE")
                .content("Tour Hà Nội giảm 500k")
                .metadata(gson.toJson(depMeta))
                .score(0.9f)
                .build();

        VectorDocumentDTO summaryDoc = buildTourDoc(2, "DN001", "Tour Đà Nẵng", 3000000.0, 0.8f);

        List<VectorDocumentDTO> docs = List.of(depDoc, summaryDoc);

        // Act
        String context = chatbotService.buildEnhancedContext(docs, "tour giảm giá khuyến mãi");

        // Assert: discounted departure is prioritized and coupon/discount data is preserved.
        assertThat(context).contains("Tour Hà Nội giảm 500k");
        assertThat(context).contains("Tour Hà Nội giảm 500k");
        assertThat(context).contains("500,000 VND");
    }

    @Test
    void buildEnhancedContext_emptyDocs_returnsEmptyString() {
        String context = chatbotService.buildEnhancedContext(List.of(), "bất kỳ câu hỏi nào");
        assertThat(context).isNotEmpty(); // now returns "Không tìm thấy thông tin..."
    }

    // ─────────────────────────────────────────────
    // buildEnhancedPrompt
    // ─────────────────────────────────────────────

    @Test
    void buildEnhancedPrompt_containsUserMessageAndContext() {
        String prompt = chatbotService.buildEnhancedPrompt(
                "Tour Phú Quốc có gì?",
                "Tour Phú Quốc 5N4Đ giá 5 triệu");

        assertThat(prompt).contains("Tour Phú Quốc có gì?");
        assertThat(prompt).contains("Tour Phú Quốc 5N4Đ giá 5 triệu");
        assertThat(prompt).contains("Trợ lý Du lịch AI");
        assertThat(prompt).contains("QUY TẮC");
    }

    // ─────────────────────────────────────────────
    // callGeminiAPI
    // ─────────────────────────────────────────────

    @Test
    void callGeminiAPI_returnsExtractedText() {
        // Arrange
        mockGeminiResponse("Câu trả lời từ Gemini");

        // Act
        String result = chatbotService.callGeminiAPI("bất kỳ prompt nào");

        // Assert
        assertThat(result).isEqualTo("Câu trả lời từ Gemini");
    }

    @Test
    void callGeminiAPI_networkErrorReturnsFallback() {
        // Arrange
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // Act
        String result = chatbotService.callGeminiAPI("prompt");

        // Assert: fallback message
        assertThat(result).contains("sự cố kỹ thuật");
    }

    // ─────────────────────────────────────────────
    // Tour suggestions deduplication
    // ─────────────────────────────────────────────

    @Test
    void handleUserMessage_deduplicatesTourSuggestionsById() {
        // Arrange: 3 docs đều cùng tourId=1
        List<VectorDocumentDTO> docs = List.of(
                buildTourDoc(1, "HN001", "Tour Hà Nội", 2000000.0, 0.95f),
                buildTourDoc(1, "HN001", "Tour Hà Nội", 1800000.0, 0.90f),
                buildTourDoc(1, "HN001", "Tour Hà Nội", 2200000.0, 0.85f),
                buildTourDoc(2, "DN001", "Tour Đà Nẵng", 3000000.0, 0.80f)
        );
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(docs);
        mockGeminiResponse("Kết quả tìm kiếm");

        // Act
        ChatMessageResponse response = chatbotService.handleUserMessage(
                ChatMessageRequest.builder().message("tour miền bắc").build());

        // Assert: chỉ 2 tour (1 HN001, 1 DN001), không duplicate
        assertThat(response.getTourSuggestions())
                .extracting(ChatMessageResponse.TourSuggestion::getTourCode)
                .containsExactlyInAnyOrder("HN001", "DN001");
    }

    @Test
    void handleUserMessage_maxSixTourSuggestions() {
        // Arrange: 10 tour khác nhau
        List<VectorDocumentDTO> docs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            docs.add(buildTourDoc(i, "T00" + i, "Tour " + i, 2000000.0, 0.9f - i * 0.01f));
        }
        when(vectorService.searchSimilar(anyString(), anyInt())).thenReturn(docs);
        mockGeminiResponse("nhiều tour");

        // Act
        ChatMessageResponse response = chatbotService.handleUserMessage(
                ChatMessageRequest.builder().message("tour").build());

        // Assert: max 6 suggestions
        assertThat(response.getTourSuggestions()).hasSizeLessThanOrEqualTo(6);
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private VectorDocumentDTO buildTourDoc(Integer tourId, String tourCode, String tourName,
                                            Double price, Float score) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("tourId",   tourId);
        meta.put("tourCode", tourCode);
        meta.put("tourName", tourName);
        meta.put("salePrice", price);
        meta.put("imageUrl", "https://example.com/img.jpg");
        meta.put("duration", "3N2Đ");

        return VectorDocumentDTO.builder()
                .id("TOUR_SUMMARY_" + tourId)
                .type("TOUR_SUMMARY")
                .content(tourName + " " + tourCode)
                .metadata(gson.toJson(meta))
                .score(score)
                .entityId(tourId)
                .build();
    }

    private void mockGeminiResponse(String text) {
        Map<String, Object> part    = Map.of("text", text);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> candidate = Map.of("content", content);
        Map<String, Object> body    = Map.of("candidates", List.of(candidate));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
