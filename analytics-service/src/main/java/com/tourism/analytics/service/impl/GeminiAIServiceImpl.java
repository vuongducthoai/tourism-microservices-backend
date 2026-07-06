package com.tourism.analytics.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;
import com.tourism.analytics.service.GeminiAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * GeminiAIServiceImpl — port từ monolith Tourism_Backend.
 * Gọi Gemini 2.0 Flash API để tạo phân tích AI cho Dashboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiAIServiceImpl implements GeminiAIService {

    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    @Value("${gemini.generation.model:gemini-flash-latest}")
    private String geminiModel;

    private static final String GEMINI_API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    // RestTemplate có giới hạn thời gian: tránh treo vô hạn khi Gemini/mạng chậm,
    // nhờ đó lời gọi luôn kết thúc sớm thay vì để giao diện chờ tới lúc time out.
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 5 giây để mở kết nối tới Gemini
        factory.setReadTimeout(45000);     // tối đa 45 giây chờ Gemini trả lời
        return new RestTemplate(factory);
    }

    @Override
    public String generateDashboardSummary(String context) {
        String prompt = String.format(
                "Bạn là chuyên gia phân tích dữ liệu du lịch. Dựa trên dữ liệu sau:\n%s\n\n" +
                        "Hãy viết tóm tắt ngắn gọn (4-5 câu) về tình hình kinh doanh hiện tại. " +
                        "Giọng văn tích cực, chuyên nghiệp. Trả lời bằng tiếng Việt Nam.",
                context
        );
        return callGeminiAPI(prompt);
    }

    @Override
    public List<DashboardStatsDTO.Insight> generateInsights(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Hãy đưa ra 3-5 insight quan trọng dưới dạng JSON Array. " +
                        "Cấu trúc mỗi object: { \"title\": \"...\", \"description\": \"...\", \"type\": \"POSITIVE/NEUTRAL/NEGATIVE\", \"priority\": 1-5 }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Insight>>() {});
    }

    @Override
    public List<DashboardStatsDTO.Prediction> generatePredictions(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Hãy dự đoán 2-3 xu hướng sắp tới (1-3 tháng) dưới dạng JSON Array. " +
                        "Cấu trúc: { \"metric\": \"...\", \"prediction\": \"...\", \"confidence\": 0-100, \"timeframe\": \"...\" }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Prediction>>() {});
    }

    @Override
    public List<DashboardStatsDTO.Recommendation> generateRecommendations(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Đưa ra 3-5 khuyến nghị cải thiện dưới dạng JSON Array. " +
                        "Cấu trúc: { \"title\": \"...\", \"description\": \"...\", \"action\": \"...\", \"impact\": 1-5 }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Recommendation>>() {});
    }

    @Override
    public DashboardStatsDTO.AIAnalysis generateFullAnalysis(String context) {
        String prompt = String.format(
                "Bạn là chuyên gia phân tích kinh doanh du lịch cho quản trị viên không rành kỹ thuật.\n" +
                "Dữ liệu hệ thống:\n%s\n\n" +
                "QUY TẮC BẮT BUỘC:\n" +
                "1. Chỉ được dùng số liệu có trong AI_EVIDENCE_METRICS, không tự bịa số mới.\n" +
                "2. Mỗi nhận định, dự báo và khuyến nghị phải có usedMetricKeys trỏ tới metricKey đã dùng.\n" +
                "3. Nếu không có số liệu chứng minh, để usedMetricKeys=[] và viết thận trọng.\n" +
                "4. Không dùng chữ mơ hồ như 'kỳ này' hoặc 'kỳ trước'. Hãy dùng 'giai đoạn đang xem' và 'giai đoạn so sánh'.\n" +
                "5. Viết như báo cáo nghiệp vụ: nói vấn đề, con số chứng minh, ảnh hưởng tới doanh thu/khách hàng/vận hành.\n\n" +
                "Trả về đúng một JSON object, không Markdown, không giải thích ngoài JSON:\n" +
                "{\"summary\":\"Tóm tắt 4-5 câu dễ hiểu, có nêu số liệu chính\",\n" +
                "\"insights\":[{\"title\":\"...\",\"description\":\"...\",\"type\":\"POSITIVE|NEUTRAL|NEGATIVE\",\"priority\":1-5,\"usedMetricKeys\":[\"metric.key\"]}],\n" +
                "\"predictions\":[{\"metric\":\"...\",\"prediction\":\"...\",\"confidence\":0-100,\"timeframe\":\"...\",\"usedMetricKeys\":[\"metric.key\"]}],\n" +
                "\"recommendations\":[{\"title\":\"...\",\"description\":\"...\",\"action\":\"...\",\"impact\":1-5,\"usedMetricKeys\":[\"metric.key\"]}]}\n" +
                "Số lượng: insights 3-5, predictions 2-3, recommendations 3-5. Tất cả bằng tiếng Việt có dấu.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        try {
            String cleaned = cleanJsonString(jsonResponse);
            if (cleaned.startsWith("[")) cleaned = "{\"summary\":\"\",\"insights\":" + cleaned + ",\"predictions\":[],\"recommendations\":[]}";
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(cleaned);
            String summary = node.path("summary").asText("");
            List<DashboardStatsDTO.Insight> insights = objectMapper.convertValue(node.path("insights"), new TypeReference<List<DashboardStatsDTO.Insight>>() {});
            List<DashboardStatsDTO.Prediction> predictions = objectMapper.convertValue(node.path("predictions"), new TypeReference<List<DashboardStatsDTO.Prediction>>() {});
            List<DashboardStatsDTO.Recommendation> recs = objectMapper.convertValue(node.path("recommendations"), new TypeReference<List<DashboardStatsDTO.Recommendation>>() {});
            return DashboardStatsDTO.AIAnalysis.builder().summary(summary).insights(insights).predictions(predictions).recommendations(recs).build();
        } catch (Exception e) {
            log.error("Failed to parse full analysis JSON: {}", e.getMessage());
            return DashboardStatsDTO.AIAnalysis.builder().summary(jsonResponse).insights(List.of()).predictions(List.of()).recommendations(List.of()).build();
        }
    }

    private String callGeminiAPI(String prompt) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.warn("Gemini API Key is missing — returning empty response");
            return "[]";
        }
        // Thử model chính, nếu lỗi thì chuyển sang model dự phòng (nhẹ, nhanh hơn).
        // Mỗi model gọi 1 lần để tổng thời gian luôn nằm dưới mốc time out của giao diện.
        String[] models = { geminiModel, "gemini-flash-lite-latest" };
        for (String model : models) {
            for (int attempt = 1; attempt <= 1; attempt++) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    ));

                    HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
                    String url = GEMINI_API_BASE + model + ":generateContent?key=" + geminiApiKey;

                    ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        log.info("Gemini call succeeded with model={} attempt={}", model, attempt);
                        return extractTextFromResponse(response.getBody());
                    }
                } catch (org.springframework.web.client.HttpServerErrorException e) {
                    log.warn("Gemini model={} attempt={} server error: {} — retrying…", model, attempt, e.getStatusCode());
                    if (attempt < 2) {
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                } catch (Exception e) {
                    log.error("Error calling Gemini API model={}: {}", model, e.getMessage());
                    break; // non-server errors: skip retries, try next model
                }
            }
        }
        return "[]";
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> part : parts) {
                        Object t = part.get("text");
                        if (t != null) sb.append(t.toString());
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.error("Error parsing Gemini response structure", e);
        }
        return "[]";
    }

    private <T> List<T> parseResponse(String jsonResponse, TypeReference<List<T>> typeRef) {
        try {
            String cleaned = cleanJsonString(jsonResponse);
            return objectMapper.readValue(cleaned, typeRef);
        } catch (Exception e) {
            log.error("Failed to parse JSON from Gemini AI: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String cleanJsonString(String json) {
        if (json == null || json.isBlank()) return "[]";
        String cleaned = json.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
    }
}
