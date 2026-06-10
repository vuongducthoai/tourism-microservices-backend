package com.tourism.booking.service;

import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.event.ChatbotSyncEventDTO;
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

    private final RabbitTemplate rabbitTemplate;

    public void publish(String entityType, Integer entityId, String operation) {
        try {
            ChatbotSyncEventDTO event = ChatbotSyncEventDTO.builder()
                    .eventId(UUID.randomUUID().toString())
                    .sourceService("booking-service")
                    .entityType(entityType)
                    .entityId(entityId)
                    .operation(operation)
                    .occurredAt(LocalDateTime.now().toString())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, "chatbot.sync." + entityType.toLowerCase(), event);
        } catch (Exception e) {
            log.warn("Could not publish chatbot sync event {}:{} - {}", entityType, entityId, e.getMessage());
        }
    }
}
