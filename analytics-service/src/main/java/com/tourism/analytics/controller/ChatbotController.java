package com.tourism.analytics.controller;

import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.service.ChatbotService;
import com.tourism.analytics.service.VectorService;
import com.tourism.analytics.service.VectorSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * ChatbotController — cung cấp 4 endpoints:
 *
 *   POST   /api/chatbot/chat            — gửi tin nhắn, nhận câu trả lời (public)
 *   POST   /api/chatbot/admin/sync      — trigger đồng bộ dữ liệu lên Pinecone (admin)
 *   DELETE /api/chatbot/admin/clear     — xoá toàn bộ vector (admin)
 *   GET    /api/chatbot/health          — kiểm tra trạng thái service (public)
 *
 * Route được cấu hình trong api-gateway:
 *   /api/chatbot/** → analytics-service (port 8087)
 */
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Chatbot", description = "AI Chatbot endpoints — RAG với Gemini + Pinecone")
public class ChatbotController {

    private final ChatbotService    chatbotService;
    private final VectorSyncService vectorSyncService;
    private final VectorService     vectorService;

    // ─────────────────────────────────────────────
    // 1. CHAT (public)
    // ─────────────────────────────────────────────

    /**
     * Xử lý tin nhắn từ user.
     *
     * @param request ChatMessageRequest { message, sessionId, userId }
     * @return ChatMessageResponse { reply, tourSuggestions, quickActions, sessionId, timestamp }
     */
    @PostMapping("/chat")
    @Operation(summary = "Gửi tin nhắn tới chatbot AI",
               description = "RAG pipeline: embed → Pinecone → Gemini → response")
    public ResponseEntity<ChatMessageResponse> chat(
            @Valid @RequestBody ChatMessageRequest request) {

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ChatMessageResponse.builder()
                            .reply("Vui lòng nhập câu hỏi của bạn.")
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        ChatMessageResponse response = chatbotService.handleUserMessage(request);
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────
    // 2. SYNC (admin)
    // ─────────────────────────────────────────────

    /**
     * Trigger đồng bộ toàn bộ dữ liệu tour, location, review lên Pinecone.
     * Endpoint dành cho admin — gọi bất cứ khi nào cần cập nhật dữ liệu.
     */
    @PostMapping("/admin/sync")
    @Operation(summary = "Đồng bộ dữ liệu lên Pinecone Vector DB",
               description = "Lấy dữ liệu mới nhất từ tour-catalog và sync lên Pinecone")
    public ResponseEntity<Map<String, Object>> triggerSync() {
        log.info("🔄 Admin triggered manual sync");
        long start = System.currentTimeMillis();
        try {
            vectorSyncService.syncAll();
            long elapsed = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "status",  "success",
                    "message", "Đồng bộ dữ liệu thành công",
                    "elapsed", elapsed + "ms",
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("❌ Sync failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status",  "error",
                    "message", "Lỗi khi đồng bộ: " + e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────
    // 3. CLEAR (admin)
    // ─────────────────────────────────────────────

    /**
     * Xoá TOÀN BỘ vector trong Pinecone index.
     * Dùng trước full re-sync hoặc khi index bị corrupted.
     */
    @DeleteMapping("/admin/clear")
    @Operation(summary = "Xoá toàn bộ vector trong Pinecone",
               description = "Cảnh báo: không thể hoàn tác. Phải sync lại sau khi clear.")
    public ResponseEntity<Map<String, Object>> clearVectors() {
        log.warn("⚠️ Admin triggered clear ALL vectors");
        try {
            vectorService.deleteAll();
            return ResponseEntity.ok(Map.of(
                    "status",  "success",
                    "message", "Đã xoá toàn bộ vector. Cần chạy sync lại.",
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("❌ Clear failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status",  "error",
                    "message", "Lỗi khi xoá: " + e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────
    // 4. HEALTH (public)
    // ─────────────────────────────────────────────

    /**
     * Health check — xác nhận chatbot service đang hoạt động.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check của chatbot service")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",    "UP",
                "service",   "chatbot",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
