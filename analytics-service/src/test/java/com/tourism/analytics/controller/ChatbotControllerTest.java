package com.tourism.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.service.ChatbotService;
import com.tourism.analytics.service.ChatbotVectorSyncRunService;
import com.tourism.analytics.service.VectorService;
import com.tourism.analytics.service.VectorSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration-style controller tests cho ChatbotController.
 * Dùng @WebMvcTest — test HTTP layer, mock Service layer.
 *
 * Coverage:
 * - POST /api/chatbot/chat — valid, blank message
 * - POST /api/chatbot/admin/sync — success, exception
 * - DELETE /api/chatbot/admin/clear — success
 * - GET  /api/chatbot/health — always UP
 */
@WebMvcTest(value = ChatbotController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@MockBean(JpaMetamodelMappingContext.class)
class ChatbotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatbotService chatbotService;

    @MockBean
    private VectorSyncService vectorSyncService;

    @MockBean
    private VectorService vectorService;

    @MockBean
    private ChatbotVectorSyncRunService syncRunService;

    // ─────────────────────────────────────────────
    // POST /api/chatbot/chat
    // ─────────────────────────────────────────────

    @Test
    void chat_validRequest_returns200WithReply() throws Exception {
        // Arrange
        ChatMessageResponse mockResponse = ChatMessageResponse.builder()
                .reply("Tour Hà Nội rất phù hợp cho gia đình.")
                .sessionId("sess-abc")
                .timestamp(LocalDateTime.now())
                .tourSuggestions(List.of())
                .quickActions(List.of())
                .build();

        when(chatbotService.handleUserMessage(any(ChatMessageRequest.class)))
                .thenReturn(mockResponse);

        ChatMessageRequest request = ChatMessageRequest.builder()
                .message("Tour Hà Nội có gì hay?")
                .sessionId("sess-abc")
                .userId(1)
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/chatbot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Tour Hà Nội rất phù hợp cho gia đình."))
                .andExpect(jsonPath("$.sessionId").value("sess-abc"));
    }

    @Test
    void chat_blankMessage_returns400() throws Exception {
        // Arrange
        ChatMessageRequest request = ChatMessageRequest.builder()
                .message("")
                .build();

        // Act & Assert: blank message → 400 Bad Request
        mockMvc.perform(post("/api/chatbot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reply").value("Vui lòng nhập câu hỏi của bạn."));

        // Service should NOT be called for blank message
        verify(chatbotService, never()).handleUserMessage(any());
    }

    @Test
    void chat_nullMessage_returns400() throws Exception {
        // Arrange
        ChatMessageRequest request = new ChatMessageRequest(null, null, null);

        // Act & Assert
        mockMvc.perform(post("/api/chatbot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(chatbotService, never()).handleUserMessage(any());
    }

    // ─────────────────────────────────────────────
    // POST /api/chatbot/admin/sync
    // ─────────────────────────────────────────────

    @Test
    void sync_success_returns200WithSuccessStatus() throws Exception {
        // Arrange: doNothing = default for void method
        doNothing().when(vectorSyncService).syncAll();

        // Act & Assert
        mockMvc.perform(post("/api/chatbot/admin/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").exists());

        verify(vectorSyncService).syncAll();
    }

    @Test
    void sync_serviceThrows_returns500() throws Exception {
        // Arrange
        doThrow(new RuntimeException("Feign connection error"))
                .when(vectorSyncService).syncAll();

        // Act & Assert
        mockMvc.perform(post("/api/chatbot/admin/sync"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    // ─────────────────────────────────────────────
    // DELETE /api/chatbot/admin/clear
    // ─────────────────────────────────────────────

    @Test
    void clear_success_returns200() throws Exception {
        // Arrange
        doNothing().when(vectorService).deleteAll();

        // Act & Assert
        mockMvc.perform(delete("/api/chatbot/admin/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(vectorService).deleteAll();
    }

    // ─────────────────────────────────────────────
    // GET /api/chatbot/health
    // ─────────────────────────────────────────────

    @Test
    void health_alwaysReturns200WithStatusUp() throws Exception {
        mockMvc.perform(get("/api/chatbot/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("chatbot"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
