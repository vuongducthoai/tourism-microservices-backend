package com.tourism.tourcatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.tourcatalog.dto.internal.GroqSummaryResult;
import com.tourism.tourcatalog.entity.Review;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gọi Groq Llama 3.3 70B để tóm tắt 50 review thành 3 mục (pros / cons / tips).
 * Dùng response_format json_object để Groq trả về JSON parse-được.
 */
@Service
@Slf4j
public class GroqReviewSummaryClient {

    private final WebClient groqWebClient;
    private final ObjectMapper objectMapper;

    public GroqReviewSummaryClient(@Qualifier("groqWebClient") WebClient groqWebClient,
                                   ObjectMapper objectMapper) {
        this.groqWebClient = groqWebClient;
        this.objectMapper = objectMapper;
    }

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.api.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${review-summary.timeout-sec:30}")
    private long timeoutSec;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    public GroqSummaryResult summarize(String tourName, List<Review> reviews) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("GROQ_API_KEY chưa được cấu hình");
        }
        String prompt = buildPrompt(tourName, reviews);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system",
                                "content", "Bạn là trợ lý phân tích review du lịch. Chỉ trả về JSON đúng format yêu cầu, không thêm chữ thừa."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.3,
                "max_tokens", 800,
                "response_format", Map.of("type", "json_object")
        );

        String body = groqWebClient.post()
                .uri(GROQ_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), resp ->
                        resp.bodyToMono(String.class).map(b -> {
                            log.warn("Groq API error status={} body={}", resp.statusCode(), b);
                            return new RuntimeException("Groq HTTP error: " + resp.statusCode());
                        })
                )
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSec))
                .block();

        return parseResponse(body);
    }

    private GroqSummaryResult parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new RuntimeException("Groq trả về content rỗng");
            }
            JsonNode json = objectMapper.readTree(content);
            return new GroqSummaryResult(
                    json.path("pros").asText("").trim(),
                    json.path("cons").asText("").trim(),
                    json.path("tips").asText("").trim(),
                    model
            );
        } catch (Exception e) {
            log.error("Không parse được Groq response: {}", e.getMessage());
            throw new RuntimeException("Không parse được kết quả AI: " + e.getMessage());
        }
    }

    private String buildPrompt(String tourName, List<Review> reviews) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tóm tắt ").append(reviews.size())
                .append(" review của khách đã đi tour \"").append(tourName).append("\" theo 3 mục:\n")
                .append("1. \"pros\" — Ưu điểm chính (3-5 ý ngắn, viết tiếng Việt, kèm số review đề cập nếu rõ).\n")
                .append("2. \"cons\" — Nhược điểm thường gặp (3-5 ý ngắn, tiếng Việt).\n")
                .append("3. \"tips\" — Lời khuyên thực tế cho khách sắp đi (2-4 lời khuyên).\n\n")
                .append("Trả về DUY NHẤT JSON: {\"pros\":\"...\",\"cons\":\"...\",\"tips\":\"...\"}\n")
                .append("Mỗi mục dùng định dạng bullet \"- \" và \\n giữa các bullet.\n")
                .append("Không bịa nội dung không có trong review.\n\n")
                .append("=== REVIEWS ===\n");

        int idx = 1;
        for (Review r : reviews) {
            sb.append("[").append(idx++).append("] ⭐")
                    .append(r.getRating() != null ? r.getRating() : 0)
                    .append("/5: ")
                    .append(truncate(r.getComment(), 300))
                    .append('\n');
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }
}
