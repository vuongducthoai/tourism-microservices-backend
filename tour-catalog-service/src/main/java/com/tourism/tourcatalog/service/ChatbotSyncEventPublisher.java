package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.config.ChatbotSyncRabbitConfig;
import com.tourism.tourcatalog.event.ChatbotSyncEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotSyncEventPublisher {

    private final RabbitTemplate chatbotSyncRabbitTemplate;

    public void publish(String entityType, Integer entityId, Integer parentTourId, String operation) {
        try {
            ChatbotSyncEventDTO event = ChatbotSyncEventDTO.builder()
                    .eventId(UUID.randomUUID().toString())
                    .sourceService("tour-catalog-service")
                    .entityType(entityType)
                    .entityId(entityId)
                    .parentTourId(parentTourId)
                    .operation(operation)
                    .occurredAt(LocalDateTime.now().toString())
                    .build();
            chatbotSyncRabbitTemplate.convertAndSend(
                    ChatbotSyncRabbitConfig.EXCHANGE,
                    "chatbot.sync." + entityType.toLowerCase(),
                    event
            );
        } catch (Exception e) {
            log.warn("Could not publish chatbot sync event {}:{} - {}", entityType, entityId, e.getMessage());
        }
    }
}
