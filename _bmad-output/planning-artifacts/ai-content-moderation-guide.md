# Kế hoạch tích hợp AI Moderation vào Forum Service (Gemini)

## Tổng quan

Hệ thống AI moderation sẽ tự động phân tích nội dung bài viết và bình luận **trước khi publish**,
phân loại mức độ vi phạm, và quyết định auto-approve / hold / reject — giảm tải cho admin.

---

## Lựa chọn AI API

Dùng **Google Gemini API (`gemini-1.5-flash`)** vì:
- **Miễn phí hoàn toàn**: 15 req/phút, 1 triệu tokens/ngày — không cần thẻ tín dụng
- Nhanh (~1-2s), phân tích tiếng Việt tốt
- Trả JSON structured output dễ parse
- Không cần train model riêng

### Lấy API Key (miễn phí)

1. Truy cập `https://aistudio.google.com/apikey`
2. Đăng nhập tài khoản Google
3. Nhấn **Create API Key** → chọn project Google Cloud
4. Copy key dạng `AIzaSy...`
5. Set vào `docker-compose.yml` (xem Bước 5)

---

## Kiến trúc tổng thể

```
User tạo bài/comment
        │
        ▼
ForumServiceImpl.createPost() / addComment()
        │
        ▼
  ModerationService.analyze(content)
        │
   gọi Gemini API
        │
        ├─► SAFE (score < 0.3)    → status = PUBLISHED  → hiển thị ngay
        ├─► BORDERLINE (0.3–0.7)  → status = PENDING_REVIEW → chờ admin duyệt
        └─► TOXIC (score > 0.7)   → status = HIDDEN     → từ chối + thông báo
```

---

## Bước 1 — Thêm trạng thái PENDING_REVIEW vào ContentStatus enum

**File:** `forum-service/src/main/java/com/tourism/forum/entity/ContentStatus.java`

Hiện tại enum có: `DRAFT`, `PUBLISHED`, `HIDDEN`

```java
public enum ContentStatus {
    DRAFT,
    PUBLISHED,
    PENDING_REVIEW,   // ← thêm mới: đang chờ AI / admin duyệt
    HIDDEN
}
```

> **Tại sao cần PENDING_REVIEW?** Bài viết borderline không nên hiển thị ngay (PUBLISHED)
> cũng không nên từ chối hẳn (HIDDEN) — cần admin xem xét thủ công.

---

## Bước 2 — Thêm field moderation vào ForumPost và PostComment

**File:** `forum-service/src/main/java/com/tourism/forum/entity/ForumPost.java`

Thêm vào cuối class (trước `@OneToMany`):

```java
// ── AI Moderation ─────────────────────────────────────────────────────────
@Column(name = "moderation_score")
private Double moderationScore;           // 0.0 – 1.0 (0 = sạch, 1 = toxic)

@Column(name = "moderation_label", length = 50)
private String moderationLabel;           // "SAFE" | "BORDERLINE" | "TOXIC"

@Column(name = "moderation_reason", columnDefinition = "TEXT")
private String moderationReason;          // giải thích ngắn từ AI

@Column(name = "moderated_at")
private LocalDateTime moderatedAt;
```

Làm tương tự cho **PostComment.java** — cùng 4 field.

---

## Bước 3 — Tạo database migration

Thêm vào schema SQL hoặc tạo file migration mới:

```sql
ALTER TABLE forum_posts
    ADD COLUMN IF NOT EXISTS moderation_score   FLOAT,
    ADD COLUMN IF NOT EXISTS moderation_label   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS moderation_reason  TEXT,
    ADD COLUMN IF NOT EXISTS moderated_at       TIMESTAMP;

ALTER TABLE post_comments
    ADD COLUMN IF NOT EXISTS moderation_score   FLOAT,
    ADD COLUMN IF NOT EXISTS moderation_label   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS moderation_reason  TEXT,
    ADD COLUMN IF NOT EXISTS moderated_at       TIMESTAMP;

-- Index để admin query nhanh các bài chờ duyệt
CREATE INDEX IF NOT EXISTS idx_forum_posts_pending
    ON forum_posts(status) WHERE status = 'PENDING_REVIEW';

CREATE INDEX IF NOT EXISTS idx_post_comments_pending
    ON post_comments(status) WHERE status = 'PENDING_REVIEW';
```

> Nếu project dùng `spring.jpa.hibernate.ddl-auto: update` thì JPA tự thêm column — không cần chạy SQL tay.

---

## Bước 4 — Thêm dependency WebFlux vào pom.xml

**File:** `forum-service/pom.xml`

```xml
<!-- WebClient để gọi Gemini API (HTTP async) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> WebFlux ở đây chỉ dùng `WebClient` để gọi HTTP — không cần chuyển app sang reactive.
> Spring Boot quản lý version tự động, không cần ghi `<version>`.

---

## Bước 5 — Cấu hình Gemini API

**File:** `forum-service/src/main/resources/application.yml`

```yaml
gemini:
  api:
    key: ${GEMINI_API_KEY}                    # set qua env var, không hardcode
    model: gemini-2.0-flash                   # free tier, nhanh + chất lượng tốt hơn 1.5
    url: https://generativelanguage.googleapis.com/v1beta/models/${gemini.api.model}:generateContent

moderation:
  thresholds:
    safe: 0.3         # score < 0.3  → SAFE → auto publish
    toxic: 0.7        # score > 0.7  → TOXIC → auto hide
    # 0.3 – 0.7 → BORDERLINE → chờ admin duyệt
  enabled: true       # false = tắt moderation, mọi bài tự động PUBLISHED
```

**File:** `docker-compose.yml` — thêm vào phần `environment` của `tourism-forum-service`:

```yaml
  tourism-forum-service:
    environment:
      # ... các env var hiện có ...
      - GEMINI_API_KEY=AIzaSy...   # ← dán key lấy từ aistudio.google.com
```

---

## Bước 6 — Tạo ModerationResult DTO

**File mới:** `forum-service/src/main/java/com/tourism/forum/dto/ModerationResult.java`

```java
package com.tourism.forum.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModerationResult {
    private double score;    // 0.0 – 1.0
    private String label;    // "SAFE" | "BORDERLINE" | "TOXIC"
    private String reason;   // giải thích ngắn tiếng Việt từ AI
}
```

---

## Bước 7 — Tạo ModerationService interface

**File mới:** `forum-service/src/main/java/com/tourism/forum/service/ModerationService.java`

```java
package com.tourism.forum.service;

import com.tourism.forum.dto.ModerationResult;

public interface ModerationService {
    ModerationResult analyze(String content, String contentType);
    // contentType: "post" hoặc "comment"
}
```

---

## Bước 8 — Tạo ModerationServiceImpl (gọi Gemini)

**File mới:** `forum-service/src/main/java/com/tourism/forum/service/impl/ModerationServiceImpl.java`

```java
package com.tourism.forum.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.forum.dto.ModerationResult;
import com.tourism.forum.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements ModerationService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-1.5-flash}")
    private String model;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    @Value("${moderation.thresholds.safe:0.3}")
    private double safeThreshold;

    @Value("${moderation.thresholds.toxic:0.7}")
    private double toxicThreshold;

    @Override
    public ModerationResult analyze(String content, String contentType) {
        if (!enabled || content == null || content.isBlank()) {
            return ModerationResult.builder()
                    .score(0.0).label("SAFE").reason("Moderation disabled").build();
        }

        // Truncate nếu content quá dài — Gemini tính token theo độ dài
        String truncated = content.length() > 3000 ? content.substring(0, 3000) + "..." : content;
        String prompt = buildPrompt(truncated, contentType);

        try {
            // Gemini API endpoint: thêm key vào query param
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey;

            // Request body theo format Gemini
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(
                        Map.of("text", prompt)
                    ))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,        // thấp = ít ngẫu nhiên, nhất quán hơn
                    "maxOutputTokens", 300
                )
            );

            String responseBody = webClient.post()
                    .uri(url)
                    .header("content-type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));   // timeout 15s

            return parseGeminiResponse(responseBody);

        } catch (Exception e) {
            // Fail-open: nếu API lỗi → cho phép đăng, không block user
            log.warn("Gemini moderation API failed, defaulting to SAFE: {}", e.getMessage());
            return ModerationResult.builder()
                    .score(0.0).label("SAFE").reason("API unavailable").build();
        }
    }

    private String buildPrompt(String content, String contentType) {
        String type = contentType.equals("post") ? "bài viết" : "bình luận";
        return """
            Bạn là hệ thống kiểm duyệt nội dung cho diễn đàn du lịch Việt Nam.
            Phân tích %s sau và đánh giá mức độ vi phạm.

            Nội dung cần kiểm tra:
            ---
            %s
            ---

            Các loại vi phạm cần phát hiện:
            - Ngôn từ thô tục, lăng mạ, xúc phạm cá nhân
            - Nội dung kỳ thị (chủng tộc, giới tính, tôn giáo, vùng miền)
            - Spam, quảng cáo không liên quan đến du lịch
            - Thông tin sai lệch nghiêm trọng về điểm đến
            - Nội dung bạo lực hoặc đe dọa
            - Nội dung khiêu dâm

            Trả lời ĐÚNG định dạng JSON sau, KHÔNG thêm bất kỳ text nào khác ngoài JSON:
            {
              "score": 0.0,
              "label": "SAFE",
              "reason": "Nội dung bình thường về du lịch"
            }

            Quy tắc score (số thập phân từ 0.0 đến 1.0):
            - 0.0 – 0.29 → label = "SAFE"       (hoàn toàn bình thường)
            - 0.30 – 0.69 → label = "BORDERLINE" (có dấu hiệu đáng ngờ, cần xem xét)
            - 0.70 – 1.0  → label = "TOXIC"      (vi phạm rõ ràng)

            Reason: giải thích ngắn gọn bằng tiếng Việt, tối đa 100 ký tự.
            """.formatted(type, content);
    }

    private ModerationResult parseGeminiResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // Gemini response path: candidates[0].content.parts[0].text
        JsonNode candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            log.warn("Gemini returned empty candidates: {}", responseBody);
            return ModerationResult.builder().score(0.0).label("SAFE").reason("Empty response").build();
        }

        String text = candidates.get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText("");

        // Extract JSON từ response — Gemini đôi khi bọc trong ```json ... ```
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}') + 1;
        if (start < 0 || end <= start) {
            log.warn("Could not find JSON in Gemini response: {}", text);
            return ModerationResult.builder().score(0.0).label("SAFE").reason("Parse error").build();
        }

        String json = text.substring(start, end);
        JsonNode result = objectMapper.readTree(json);

        double score  = result.path("score").asDouble(0.0);
        String reason = result.path("reason").asText("");

        // Normalize label theo score (không tin label từ AI, tự tính lại)
        String label;
        if (score >= toxicThreshold)  label = "TOXIC";
        else if (score >= safeThreshold) label = "BORDERLINE";
        else                           label = "SAFE";

        log.info("Moderation result: score={}, label={}, reason={}", score, label, reason);
        return ModerationResult.builder().score(score).label(label).reason(reason).build();
    }
}
```

---

## Bước 9 — Tạo WebClientConfig

**File mới:** `forum-service/src/main/java/com/tourism/forum/config/WebClientConfig.java`

```java
package com.tourism.forum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)) // 2MB
                .build();
    }
}
```

---

## Bước 10 — Tích hợp vào ForumServiceImpl

**File:** `forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java`

### 10.1 Inject ModerationService

```java
// Thêm vào @RequiredArgsConstructor field list
private final ModerationService moderationService;
```

### 10.2 Sửa createPost() — chèn trước postRepository.save(post)

```java
// ── AI Moderation ──────────────────────────────────────────────────────────
// Chỉ moderate khi user muốn PUBLISH (không moderate bản nháp)
if (ContentStatus.PUBLISHED.equals(post.getStatus())) {
    String textToAnalyze = post.getTitle() + "\n" + stripHtml(post.getContent());
    ModerationResult mod = moderationService.analyze(textToAnalyze, "post");

    post.setModerationScore(mod.getScore());
    post.setModerationLabel(mod.getLabel());
    post.setModerationReason(mod.getReason());
    post.setModeratedAt(LocalDateTime.now());

    switch (mod.getLabel()) {
        case "TOXIC" -> {
            post.setStatus(ContentStatus.HIDDEN);
            log.info("Post blocked by AI: score={}, reason={}", mod.getScore(), mod.getReason());
        }
        case "BORDERLINE" -> {
            post.setStatus(ContentStatus.PENDING_REVIEW);
            log.info("Post sent to review queue: score={}", mod.getScore());
        }
        // SAFE → giữ nguyên PUBLISHED
    }
}
// ──────────────────────────────────────────────────────────────────────────
```

### 10.3 Sửa addComment() — chèn trước commentRepository.save(comment)

```java
// ── AI Moderation ──────────────────────────────────────────────────────────
ModerationResult mod = moderationService.analyze(comment.getContent(), "comment");

comment.setModerationScore(mod.getScore());
comment.setModerationLabel(mod.getLabel());
comment.setModerationReason(mod.getReason());
comment.setModeratedAt(LocalDateTime.now());

if ("TOXIC".equals(mod.getLabel())) {
    comment.setStatus(ContentStatus.HIDDEN);
    // Không tăng commentCount, không gửi notification
    PostComment saved = commentRepository.save(comment);
    return buildPostResponse(post, userId);   // return sớm, bỏ qua notification
}
if ("BORDERLINE".equals(mod.getLabel())) {
    comment.setStatus(ContentStatus.PENDING_REVIEW);
    // Không tăng commentCount khi đang chờ duyệt
}
// ──────────────────────────────────────────────────────────────────────────
```

### 10.4 Thêm helper stripHtml()

```java
private String stripHtml(String html) {
    if (html == null) return "";
    return html.replaceAll("<[^>]+>", " ")
               .replaceAll("&[a-zA-Z]+;", " ")   // &nbsp; &amp; v.v.
               .replaceAll("\\s+", " ")
               .trim();
}
```

---

## Bước 11 — Thêm Repository queries

**File:** `PostRepository.java` — thêm:

```java
Page<ForumPost> findByStatusAndIsDeletedFalse(ContentStatus status, Pageable pageable);
long countByStatus(ContentStatus status);
```

**File:** `CommentRepository.java` — thêm:

```java
Page<PostComment> findByStatusAndIsDeletedFalse(ContentStatus status, Pageable pageable);
long countByStatus(ContentStatus status);
```

---

## Bước 12 — Admin API để duyệt bài PENDING_REVIEW

**File mới:** `forum-service/src/main/java/com/tourism/forum/controller/AdminModerationController.java`

```java
package com.tourism.forum.controller;

import com.tourism.forum.entity.ContentStatus;
import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostComment;
import com.tourism.forum.repository.CommentRepository;
import com.tourism.forum.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {

    private final PostRepository    postRepository;
    private final CommentRepository commentRepository;

    /** Lấy danh sách bài đang chờ duyệt */
    @GetMapping("/posts/pending")
    public ResponseEntity<Page<ForumPost>> getPendingPosts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            postRepository.findByStatusAndIsDeletedFalse(
                ContentStatus.PENDING_REVIEW, PageRequest.of(page, size))
        );
    }

    /** Tổng số cần duyệt (hiển thị badge cho admin) */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats() {
        return ResponseEntity.ok(Map.of(
            "pendingPosts",    postRepository.countByStatus(ContentStatus.PENDING_REVIEW),
            "pendingComments", commentRepository.countByStatus(ContentStatus.PENDING_REVIEW)
        ));
    }

    @PutMapping("/posts/{postId}/approve")
    @Transactional
    public ResponseEntity<Void> approvePost(@PathVariable Integer postId) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setStatus(ContentStatus.PUBLISHED);
            post.setPublishedAt(LocalDateTime.now());
            postRepository.save(post);
        });
        return ResponseEntity.ok().build();
    }

    @PutMapping("/posts/{postId}/reject")
    @Transactional
    public ResponseEntity<Void> rejectPost(@PathVariable Integer postId) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setStatus(ContentStatus.HIDDEN);
            postRepository.save(post);
        });
        return ResponseEntity.ok().build();
    }

    /** Lấy danh sách comment đang chờ duyệt */
    @GetMapping("/comments/pending")
    public ResponseEntity<Page<PostComment>> getPendingComments(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            commentRepository.findByStatusAndIsDeletedFalse(
                ContentStatus.PENDING_REVIEW, PageRequest.of(page, size))
        );
    }

    @PutMapping("/comments/{commentId}/approve")
    @Transactional
    public ResponseEntity<Void> approveComment(@PathVariable Integer commentId) {
        commentRepository.findById(commentId).ifPresent(c -> {
            c.setStatus(ContentStatus.PUBLISHED);
            commentRepository.save(c);
        });
        return ResponseEntity.ok().build();
    }

    @PutMapping("/comments/{commentId}/reject")
    @Transactional
    public ResponseEntity<Void> rejectComment(@PathVariable Integer commentId) {
        commentRepository.findById(commentId).ifPresent(c -> {
            c.setStatus(ContentStatus.HIDDEN);
            commentRepository.save(c);
        });
        return ResponseEntity.ok().build();
    }
}
```

---

## Bước 13 — Thông báo cho user khi bài bị từ chối / chờ duyệt

Trong `ForumServiceImpl.createPost()`, sau switch-case moderation:

```java
if ("TOXIC".equals(mod.getLabel())) {
    forumEventPublisher.publishModerationEvent(
        post.getUserId(),
        "POST_REJECTED",
        "Bài viết vi phạm tiêu chuẩn cộng đồng",
        mod.getReason()
    );
}
if ("BORDERLINE".equals(mod.getLabel())) {
    forumEventPublisher.publishModerationEvent(
        post.getUserId(),
        "POST_PENDING",
        "Bài viết đang chờ kiểm duyệt",
        "Chúng tôi sẽ xem xét và phản hồi sớm nhất có thể"
    );
}
```

> `publishModerationEvent` là method mới cần thêm vào `ForumEventPublisher` —
> tương tự các method hiện có, publish lên exchange `tourism.events` với routing key `forum.notification.moderation`.

---

## Thứ tự thực hiện

```
[1]  Thêm PENDING_REVIEW vào ContentStatus enum
[2]  Thêm 4 field moderation vào ForumPost + PostComment entity
[3]  Chạy migration SQL (nếu ddl-auto=update thì JPA tự thêm)
[4]  Thêm spring-boot-starter-webflux vào pom.xml
[5]  Thêm gemini config vào application.yml
[6]  Thêm GEMINI_API_KEY vào docker-compose.yml
[7]  Tạo ModerationResult DTO
[8]  Tạo ModerationService interface
[9]  Tạo ModerationServiceImpl
[10] Tạo WebClientConfig bean
[11] Sửa ForumServiceImpl: inject + gọi trong createPost() + addComment()
[12] Thêm helper stripHtml() vào ForumServiceImpl
[13] Thêm queries vào PostRepository + CommentRepository
[14] Tạo AdminModerationController
[15] Build lại: mvn package -DskipTests
[16] docker-compose up -d --build forum-service
[17] Kiểm tra: POST bài bình thường → PUBLISHED ✓
[18] Kiểm tra: POST bài toxic → HIDDEN ✓
[19] Kiểm tra: GET /api/admin/moderation/posts/pending → thấy BORDERLINE ✓
```

---

## Xử lý các edge case

| Tình huống | Xử lý |
|---|---|
| Gemini API timeout (>15s) | Fail-open: để bài PUBLISHED, log warning |
| Gemini API lỗi 429 (rate limit free tier) | Fail-open: PUBLISHED |
| Response không chứa JSON hợp lệ | Fail-open: PUBLISHED, log warn |
| Content quá dài (>3000 chars) | Truncate trước khi gửi |
| Content HTML nhiều tag | `stripHtml()` trước khi gửi |
| `moderation.enabled=false` | Skip hoàn toàn, mọi bài tự PUBLISHED |
| Bài DRAFT | Không moderate — chỉ moderate khi status = PUBLISHED |
| Comment reply trong thread | Moderate như comment thường |

---

## Chi phí

**Gemini 1.5 Flash — FREE hoàn toàn:**
- 15 request/phút
- 1,000,000 tokens/ngày
- Không giới hạn thời gian, không cần thẻ tín dụng

**Đủ dùng cho:**
- ~900 bài viết/giờ (nếu mỗi bài ~1 request)
- ~14,400 bài viết/ngày trong giới hạn miễn phí

Nếu vượt giới hạn free tier → upgrade lên **Pay-as-you-go**: ~$0.000075/1K tokens input.

---

## Gemini API Response Format (để debug)

Khi gọi thành công, Gemini trả JSON dạng:

```json
{
  "candidates": [{
    "content": {
      "parts": [{
        "text": "{\"score\": 0.1, \"label\": \"SAFE\", \"reason\": \"Nội dung du lịch bình thường\"}"
      }],
      "role": "model"
    },
    "finishReason": "STOP"
  }],
  "usageMetadata": {
    "promptTokenCount": 312,
    "candidatesTokenCount": 28,
    "totalTokenCount": 340
  }
}
```

Khi bị block vì safety filter của Gemini (nội dung quá toxic):

```json
{
  "candidates": [{
    "finishReason": "SAFETY",
    "safetyRatings": [...]
  }]
}
```

→ Trường hợp này `candidates[0].content` sẽ null → code đã handle bằng fallback SAFE.
Nhưng thực tế nếu Gemini tự block thì nội dung chắc chắn toxic → nên trả `TOXIC` thay vì `SAFE`:

```java
// Trong parseGeminiResponse(), sau khi check candidates:
JsonNode firstCandidate = candidates.get(0);
if ("SAFETY".equals(firstCandidate.path("finishReason").asText())) {
    return ModerationResult.builder()
            .score(0.9).label("TOXIC").reason("Bị chặn bởi bộ lọc an toàn").build();
}
```
