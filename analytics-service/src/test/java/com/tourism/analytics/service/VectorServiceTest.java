package com.tourism.analytics.service;

import com.tourism.analytics.dto.VectorDocumentDTO;
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
 * Unit tests cho VectorService.
 *
 * Strategy:
 * - Mock RestTemplate (không gọi Gemini/Pinecone thật)
 * - Test từng public method: createEmbedding, upsertVector, searchSimilar, deleteAll
 * - Test graceful degradation khi API trả lỗi
 */
@ExtendWith(MockitoExtension.class)
class VectorServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private VectorService vectorService;

    @BeforeEach
    void setUp() throws Exception {
        // Inject @Value fields via reflection
        injectField(vectorService, "pineconeApiKey", "test-pinecone-key");
        injectField(vectorService, "pineconeHost",   "https://test.pinecone.io");
        injectField(vectorService, "indexName",      "tourism-chatbot");
        injectField(vectorService, "embeddingModel", "llama-text-embed-v2");

        // Simulate @PostConstruct
        vectorService.init();
    }

    // ─────────────────────────────────────────────
    // createEmbedding
    // ─────────────────────────────────────────────

    @Test
    void createEmbedding_successReturns768FloatList() {
        // Arrange
        List<Double> fakeValues = new ArrayList<>();
        for (int i = 0; i < 768; i++) fakeValues.add(0.1 * i);

        // Pinecone Inference API format: {"data": [{"values": [...]}]}
        Map<String, Object> responseBody = Map.of("data", List.of(Map.of("values", fakeValues)));

        ResponseEntity<Map> responseEntity = ResponseEntity.ok(responseBody);
        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(responseEntity);

        // Act
        List<Float> result = vectorService.createEmbedding("Tour Hà Nội 3 ngày 2 đêm");

        // Assert
        assertThat(result).hasSize(768);
        assertThat(result.get(0)).isEqualTo(0.0f);
        verify(restTemplate, times(1)).postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    void createEmbedding_apiErrorReturnsEmptyList() {
        // Arrange
        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("Network error"));

        // Act
        List<Float> result = vectorService.createEmbedding("any text");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void createEmbedding_nullBodyReturnsEmptyList() {
        // Arrange
        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(null));

        // Act
        List<Float> result = vectorService.createEmbedding("text");

        // Assert
        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    // upsertVector
    // ─────────────────────────────────────────────

    @Test
    void upsertVector_callsPineconeUpsertEndpoint() {
        // Arrange
        List<Float> embedding = List.of(0.1f, 0.2f, 0.3f);
        VectorDocumentDTO doc = VectorDocumentDTO.builder()
                .id("TOUR_SUMMARY_1")
                .content("Tour Hà Nội")
                .type("TOUR_SUMMARY")
                .entityId(1)
                .embedding(embedding)
                .metadata("{\"tourCode\":\"HN001\"}")
                .build();

        when(restTemplate.postForEntity(contains("/vectors/upsert"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        // Act & Assert (no exception thrown)
        assertThatNoException().isThrownBy(() -> vectorService.upsertVector(doc));
        verify(restTemplate).postForEntity(contains("/vectors/upsert"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void upsertVector_apiErrorDoesNotThrow() {
        // Arrange
        when(restTemplate.postForEntity(contains("/vectors/upsert"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Pinecone timeout"));

        VectorDocumentDTO doc = VectorDocumentDTO.builder()
                .id("TEST_1")
                .content("test")
                .type("TOUR_SUMMARY")
                .entityId(1)
                .embedding(List.of(0.1f))
                .metadata("{}")
                .build();

        // Act & Assert — should log error, not throw
        assertThatNoException().isThrownBy(() -> vectorService.upsertVector(doc));
    }

    // ─────────────────────────────────────────────
    // searchSimilar
    // ─────────────────────────────────────────────

    @Test
    void searchSimilar_returnsDocumentListWithScore() {
        // Arrange: mock embedding (Pinecone Inference API format)
        List<Double> fakeEmbedding = new ArrayList<>();
        for (int i = 0; i < 768; i++) fakeEmbedding.add(0.01);

        Map<String, Object> embedResponse = Map.of("data", List.of(Map.of("values", fakeEmbedding)));
        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(embedResponse));

        // Arrange: mock Pinecone query
        Map<String, Object> meta = new HashMap<>();
        meta.put("type",     "TOUR_SUMMARY");
        meta.put("entityId", "42");
        meta.put("content",  "Tour Đà Nẵng 4N3Đ");
        meta.put("metadata", "{\"tourCode\":\"DN001\"}");

        Map<String, Object> match = new HashMap<>();
        match.put("id",       "TOUR_SUMMARY_42");
        match.put("score",    0.95);
        match.put("metadata", meta);

        Map<String, Object> queryResponse = Map.of("matches", List.of(match));
        when(restTemplate.postForEntity(contains("/query"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(queryResponse));

        // Act
        List<VectorDocumentDTO> results = vectorService.searchSimilar("tour đà nẵng", 5);

        // Assert
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("TOUR_SUMMARY_42");
        assertThat(results.get(0).getScore()).isEqualTo(0.95f);
        assertThat(results.get(0).getContent()).isEqualTo("Tour Đà Nẵng 4N3Đ");
    }

    @Test
    void searchSimilar_emptyEmbeddingReturnsEmpty() {
        // Arrange: embedding returns null body
        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(null));

        // Act
        List<VectorDocumentDTO> result = vectorService.searchSimilar("tour nào đó", 10);

        // Assert
        assertThat(result).isEmpty();
        // Pinecone should NOT be called if embedding is empty
        verify(restTemplate, never()).postForEntity(contains("/query"), any(), any());
    }

    @Test
    void searchSimilar_noMatchesReturnsEmpty() {
        // Arrange: embed success but no Pinecone matches
        List<Double> fakeEmbed = new ArrayList<>();
        for (int i = 0; i < 768; i++) fakeEmbed.add(0.0);

        when(restTemplate.postForEntity(contains("api.pinecone.io/embed"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data", List.of(Map.of("values", fakeEmbed)))));
        when(restTemplate.postForEntity(contains("/query"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("matches", Collections.emptyList())));

        // Act
        List<VectorDocumentDTO> result = vectorService.searchSimilar("câu hỏi không có kết quả", 10);

        // Assert
        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    // deleteAll
    // ─────────────────────────────────────────────

    @Test
    void deleteAll_callsPineconeDeleteWithDeleteAllTrue() {
        // Arrange
        when(restTemplate.postForEntity(contains("/vectors/delete"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        // Act
        assertThatNoException().isThrownBy(() -> vectorService.deleteAll());

        // Verify deleteAll:true was sent
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(contains("/vectors/delete"), captor.capture(), eq(String.class));

        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("deleteAll", true);
    }

    // ─────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
