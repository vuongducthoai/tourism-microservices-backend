package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.VectorDocumentDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * VectorService — quản lý toàn bộ giao tiếp với:
 *   1. Pinecone Inference API (llama-text-embed-v2) → tạo vector embedding 768 chiều
 *   2. Pinecone Vector DB → upsert / search / delete vector
 *
 * Được dùng bởi:
 *   - VectorSyncService: upsertVector() khi sync dữ liệu từ tour-catalog
 *   - ChatbotService:    searchSimilar() khi user gửi câu hỏi
 */
@Service
@Slf4j
public class VectorService {

    @Value("${chatbot.vector-db.pinecone.api-key}")
    private String pineconeApiKey;

    @Value("${chatbot.vector-db.pinecone.index-name}")
    private String indexName;

    @Value("${chatbot.vector-db.pinecone.host}")
    private String pineconeHost;

    @Value("${chatbot.embedding.model}")
    private String embeddingModel;

    @Autowired
    private RestTemplate restTemplate;

    private final Gson gson = new Gson();
    private String pineconeUrl;

    // Pinecone Inference API — dùng để embed văn bản (thay Gemini)
    private static final String PINECONE_INFERENCE_URL = "https://api.pinecone.io/embed";

    @PostConstruct
    public void init() {
        this.pineconeUrl = pineconeHost;
        log.info("✅ VectorService initialized. Pinecone host: {}, embedding model: {}", pineconeUrl, embeddingModel);
    }

    // ─────────────────────────────────────────────
    // 1. EMBEDDING
    // ─────────────────────────────────────────────

    /**
     * Tạo vector embedding 768 chiều từ text dùng Pinecone Inference API (llama-text-embed-v2).
     * input_type = "passage" — dùng khi embed nội dung để lưu (upsert).
     *
     * @param text   văn bản cần embed
     * @return List<Float> gồm 768 giá trị; empty nếu lỗi
     */
    public List<Float> createEmbedding(String text) {
        return callPineconeEmbed(text, "passage");
    }

    /**
     * Tạo vector embedding cho câu truy vấn người dùng.
     * input_type = "query" — dùng khi embed query để search.
     */
    private List<Float> createEmbeddingForQuery(String text) {
        return callPineconeEmbed(text, "query");
    }

    private List<Float> callPineconeEmbed(String text, String inputType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Api-Key", pineconeApiKey);
            headers.set("X-Pinecone-API-Version", "2025-04");

            Map<String, Object> body = new HashMap<>();
            body.put("model", embeddingModel);
            body.put("inputs", List.of(Map.of("text", text)));
            body.put("parameters", Map.of("input_type", inputType, "truncate", "END"));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(PINECONE_INFERENCE_URL, request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                if (data != null && !data.isEmpty()) {
                    List<Double> values = (List<Double>) data.get(0).get("values");
                    return values.stream().map(Double::floatValue).collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error creating embedding (inputType={}) for text: {}",
                    inputType, text.substring(0, Math.min(50, text.length())), e);
        }
        return new ArrayList<>();
    }

    // ─────────────────────────────────────────────
    // 2. UPSERT VECTOR
    // ─────────────────────────────────────────────

    /**
     * Lưu / cập nhật 1 document vào Pinecone.
     * Nếu id đã tồn tại → ghi đè (upsert).
     *
     * @param document VectorDocumentDTO đã có embedding sẵn
     */
    public void upsertVector(VectorDocumentDTO document) {
        try {
            HttpHeaders headers = buildPineconeHeaders();

            Map<String, Object> metadataMap = new HashMap<>();
            metadataMap.put("type",     document.getType());
            metadataMap.put("entityId", String.valueOf(document.getEntityId()));
            metadataMap.put("content",  document.getContent());
            metadataMap.put("metadata", document.getMetadata());

            Map<String, Object> vector = new HashMap<>();
            vector.put("id",       document.getId());
            vector.put("values",   document.getEmbedding());
            vector.put("metadata", metadataMap);

            Map<String, Object> body = Map.of("vectors", List.of(vector));

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(pineconeUrl + "/vectors/upsert", req, String.class);

            log.debug("✅ Upserted vector: {}", document.getId());
        } catch (Exception e) {
            log.error("❌ Error upserting vector {}: {}", document.getId(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 3. SEARCH SIMILAR
    // ─────────────────────────────────────────────

    /**
     * Tìm kiếm top-K vector gần nhất với queryText trong Pinecone.
     *
     * @param queryText câu hỏi / từ khoá của user
     * @param topK      số lượng kết quả muốn lấy
     * @return danh sách VectorDocumentDTO đã sắp xếp theo score giảm dần
     */
    public List<VectorDocumentDTO> searchSimilar(String queryText, int topK) {
        try {
            List<Float> queryEmbedding = createEmbeddingForQuery(queryText);
            if (queryEmbedding.isEmpty()) {
                log.warn("⚠️ Empty embedding for query, returning empty results");
                return new ArrayList<>();
            }

            HttpHeaders headers = buildPineconeHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("vector",          queryEmbedding);
            body.put("topK",            topK);
            body.put("includeMetadata", true);
            body.put("includeValues",   false);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(pineconeUrl + "/query", req, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> matches =
                        (List<Map<String, Object>>) response.getBody().get("matches");

                if (matches == null) return new ArrayList<>();

                return matches.stream()
                        .map(this::mapMatchToDocument)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("❌ Error searching vectors: {}", e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    // ─────────────────────────────────────────────
    // 4. DELETE
    // ─────────────────────────────────────────────

    /**
     * Xoá tất cả vector có entityId và type khớp.
     * Dùng trước khi upsert lại để tránh data stale.
     *
     * @param type     loại document (TOUR_SUMMARY, TOUR_DEPARTURE, LOCATION, REVIEW)
     * @param entityId ID entity tương ứng
     */
    public void deleteVectorsByEntityId(String type, Integer entityId) {
        try {
            HttpHeaders headers = buildPineconeHeaders();

            Map<String, Object> filter = Map.of(
                    "entityId", String.valueOf(entityId),
                    "type",     type
            );
            Map<String, Object> body = Map.of("filter", filter, "deleteAll", false);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(pineconeUrl + "/vectors/delete", req, String.class);

            log.debug("🗑️ Deleted vectors {}:{}", type, entityId);
        } catch (Exception e) {
            log.error("❌ Error deleting vectors {}:{} — {}", type, entityId, e.getMessage());
        }
    }

    /**
     * Xoá TOÀN BỘ vector trong Pinecone index.
     * Dùng cho admin reset hoặc full re-sync.
     */
    public void deleteAll() {
        try {
            HttpHeaders headers = buildPineconeHeaders();
            Map<String, Object> body = Map.of("deleteAll", true);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(pineconeUrl + "/vectors/delete", req, String.class);
            log.info("🗑️ Deleted ALL vectors from Pinecone index: {}", indexName);
        } catch (Exception e) {
            log.error("❌ Error deleting all vectors: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    private HttpHeaders buildPineconeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Api-Key", pineconeApiKey);
        return headers;
    }

    private VectorDocumentDTO mapMatchToDocument(Map<String, Object> match) {
        Map<String, Object> meta = (Map<String, Object>) match.get("metadata");
        return VectorDocumentDTO.builder()
                .id(     (String) match.get("id"))
                .content(getStr(meta, "content"))
                .type(   getStr(meta, "type"))
                .entityId(parseIntSafe(getStr(meta, "entityId")))
                .metadata(getStr(meta, "metadata"))
                .score(  ((Number) match.get("score")).floatValue())
                .build();
    }

    private String getStr(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return "";
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
