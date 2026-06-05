package com.tourism.forum.service.impl;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.forum.dto.moderation.ModerationResult;
import com.tourism.forum.service.ModerationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements ModerationService {
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    @Value("${moderation.thresholds.safe:0.3}")
    private double safeThreshold;

    @Value("${moderation.thresholds.toxic:0.7}")
    private double toxicThreshold;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    @Override
    public ModerationResult analyze(String content, String contentType) {
        if (!enabled || content == null || content.isBlank()) {
            return ModerationResult.builder()
                    .score(0.0)
                    .label("SAFE")
                    .reason("Moderation disabled")
                    .build();
        }

        String truncated = content.length() > 3000 ? content.substring(0, 3000) + "..." : content;
        String prompt = buildPrompt(truncated, contentType);

        try {
            // Groq API request body theo format OpenAI chat completions
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system",
                        "content", "Bạn là hệ thống kiểm duyệt nội dung. Chỉ trả về JSON, không text thừa."),
                    Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.1,
                "max_tokens", 300,
                "response_format", Map.of("type", "json_object")
            );

            String responseBody = webClient.post()
                    .uri(GROQ_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), resp ->
                        resp.bodyToMono(String.class).map(body -> {
                            log.warn("Groq API error status={} body={}", resp.statusCode(), body);
                            return new RuntimeException("Groq HTTP error: " + resp.statusCode() + " " + body);
                        })
                    )
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            log.info("Groq raw response: {}",
                responseBody != null ? responseBody.substring(0, Math.min(300, responseBody.length())) : "null");
            return parseGroqResponse(responseBody);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("429") || msg.contains("Too Many Requests")) {
                log.warn("Groq rate limit (429) — post sent to PENDING_REVIEW");
                return ModerationResult.builder()
                        .score(0.5).label("BORDERLINE").reason("Chờ kiểm duyệt (rate limit)").build();
            }
            log.warn("Groq moderation API failed, defaulting to SAFE: {} — {}",
                e.getClass().getSimpleName(), msg);
            return ModerationResult.builder()
                    .score(0.0).label("SAFE").reason("API unavailable").build();
        }
    }

    private String buildPrompt(String content, String contentType) {
        String type = contentType.equals("post") ? "bài viết" : "bình luận";
        return """
            Bạn là hệ thống kiểm duyệt nội dung cho diễn đàn DU LỊCH Việt Nam.
            Phân tích %s sau theo HAI tiêu chí: (A) mức độ vi phạm, (B) có liên quan du lịch không.

            Nội dung cần kiểm tra:
            ---
            %s
            ---

            (A) Các loại vi phạm cần phát hiện:
            - Ngôn từ thô tục, lăng mạ, xúc phạm cá nhân
            - Nội dung kỳ thị (chủng tộc, giới tính, tôn giáo, vùng miền)
            - Spam, quảng cáo
            - Nội dung bạo lực, đe dọa, khiêu dâm

            (B) Chủ đề du lịch: bài viết PHẢI liên quan đến du lịch
            (điểm đến, tour, lịch trình, ẩm thực khi đi du lịch, lưu trú, phương tiện,
            kinh nghiệm/mẹo đi du lịch, review địa điểm...). Các nội dung KHÔNG liên quan
            du lịch (vd: bàn về công nghệ, chính trị, mua bán đồ, tâm sự cá nhân, văn bản
            vô nghĩa như "aaa", "test"...) → relevant = false.

            Trả lời ĐÚNG định dạng JSON sau:
            {
              "score": 0.0,
              "relevant": true,
              "reason": "Nội dung bình thường về du lịch"
            }

            Quy tắc:
            - score (0.0–1.0): mức độ vi phạm (A). 0.0–0.29 sạch, 0.30–0.69 đáng ngờ, 0.70–1.0 vi phạm rõ.
            - relevant (true/false): bài CÓ liên quan du lịch (B) hay không.
            - reason: giải thích ngắn gọn tiếng Việt, tối đa 100 ký tự. Nếu off-topic, ghi rõ "Không liên quan du lịch".
            """.formatted(type, content);
    }

    private ModerationResult parseGroqResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // Groq response path: choices[0].message.content
        JsonNode choices = root.path("choices");
        if (choices.isEmpty()) {
            log.warn("Groq returned empty choices: {}", responseBody);
            return ModerationResult.builder().score(0.0).label("SAFE").reason("Empty response").build();
        }

        String text = choices.get(0)
                .path("message")
                .path("content")
                .asText("");

        // Extract JSON từ content
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}') + 1;
        if (start < 0 || end <= start) {
            log.warn("Could not find JSON in Groq response: {}", text);
            return ModerationResult.builder().score(0.0).label("SAFE").reason("Parse error").build();
        }

        String json = text.substring(start, end);
        JsonNode result = objectMapper.readTree(json);

        double score  = result.path("score").asDouble(0.0);
        String reason = result.path("reason").asText("");
        // relevant mặc định true để không chặn nhầm khi AI quên trả field này.
        boolean relevant = result.path("relevant").asBoolean(true);

        String label;
        if (score >= toxicThreshold)  label = "TOXIC";
        else if (!relevant)           label = "OFF_TOPIC";        // off-topic ưu tiên hơn BORDERLINE
        else if (score >= safeThreshold) label = "BORDERLINE";
        else label = "SAFE";

        // Bài off-topic cần được nhận diện ngay cả khi không "độc hại"
        if ("OFF_TOPIC".equals(label) && (reason == null || reason.isBlank())) {
            reason = "Nội dung không liên quan đến du lịch";
        }

        log.info("Moderation result: score={}, relevant={}, label={}, reason={}", score, relevant, label, reason);
        return ModerationResult.builder().score(score).label(label).reason(reason).build();
    }
}
