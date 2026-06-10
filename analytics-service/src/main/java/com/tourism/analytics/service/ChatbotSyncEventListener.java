package com.tourism.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.config.ChatbotSyncRabbitConfig;
import com.tourism.analytics.dto.sync.ChatbotSyncEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotSyncEventListener {

    private final ObjectMapper objectMapper;
    private final ChatbotSyncDebounceService debounceService;

    @RabbitListener(queues = ChatbotSyncRabbitConfig.QUEUE_NAME)
    public void onMessage(Message message) {
        try {
            ChatbotSyncEventDTO event = objectMapper.readValue(message.getBody(), ChatbotSyncEventDTO.class);
            debounceService.enqueue(event);
        } catch (Exception e) {
            log.warn("Invalid chatbot sync event payload: {}", e.getMessage());
        }
    }
}
