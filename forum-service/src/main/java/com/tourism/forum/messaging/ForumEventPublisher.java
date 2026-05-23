package com.tourism.forum.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.tourism.forum.dto.event.ForumNotificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForumEventPublisher {
    private static final String EXCHANGE = "tourism.events";
    private final RabbitTemplate rabbitTemplate;

    // Gửi forum notification vào RabbitMQ
    public void publishForumEvent(ForumNotificationEvent event) {
       // Không gửi nếu người nhận = người gửi (tự like/ comment chính mình)
       if(event.getRecipientUserId() == null) return;
       if(event.getRecipientUserId().equals(event.getActorUserId())) return;

       String routingKey = "forum.notification." + event.getEventType().toLowerCase(); // VD: forum.notification.post_liked
    
       try {
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);
            log.info("Published forum event: {} to exchange: {} with routingKey: {}", event, EXCHANGE, routingKey); 
       } catch (Exception e) {
          // Non-critical: notification thất bại không được làm hỏng like/comment
            log.warn("Failed to publish forum event (non-critical): {}", e.getMessage());
       }
    }
}
