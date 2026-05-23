package com.tourism.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.notification.config.RabbitMQConfig;
import com.tourism.notification.dto.ForumNotificationEvent;
import com.tourism.notification.entity.Notification;
import com.tourism.notification.entity.NotificationType;
import com.tourism.notification.entity.ProcessedEvent;
import com.tourism.notification.repository.NotificationRepository;
import com.tourism.notification.repository.ProcessedEventRepository;
import com.tourism.notification.service.impl.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Lắng nghe forum events từ Queue "forum.notification.queue".
 *
 * Flow:
 * 1. Kiểm tra idempotencyKey — nếu đã xử lý rồi thì bỏ qua (tránh duplicate)
 * 2. Lưu Notification vào DB
 * 3. Push WebSocket real-time đến /topic/user/{recipientId}/notifications
 * 4. Đánh dấu đã xử lý vào processed_events
 *
 * Retry: Spring tự retry 3 lần (cấu hình trong application.yml).
 * Nếu vẫn lỗi → chuyển sang forum.notification.dlq.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ForumEventListener {
    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepo;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FORUM_NOTIFICATION)
    @Transactional
    public void onForumEvent(ForumNotificationEvent event) {
       String key = event.getIdempotencyKey();

       // --1. Idempotency check --
       if(key != null && processedEventRepo.existsByIdempotencyKey(key)){
            log.info("Skipping duplicate forum event: key={}", key);
            return;
       }

       log.info("Received forum event: type={}, recipientId={}, key={}", event.getEventType(), event.getRecipientUserId(), key);
       
       try {
           //  ── 2. Build title + message theo loại event ──────────────────────
           String title;
           String message;
           NotificationType type;

           switch(event.getEventType()){
              case "POST_LIKED":
                    type    = NotificationType.POST_LIKED;
                    title   = "Bài viết của bạn được thích";
                    message = String.format("%s đã thích bài viết \"%s\" của bạn",
                            event.getActorName(), event.getPostTitle());
                    break;

                case "POST_COMMENTED":
                    type    = NotificationType.POST_COMMENTED;
                    title   = "Bình luận mới";
                    message = String.format("%s đã bình luận vào bài viết \"%s\" của bạn",
                            event.getActorName(), event.getPostTitle());
                    break;

                case "COMMENT_REPLIED":
                    type    = NotificationType.COMMENT_REPLIED;
                    title   = "Phản hồi mới";
                    message = String.format("%s đã phản hồi bình luận của bạn trong bài \"%s\"",
                            event.getActorName(), event.getPostTitle());
                    break;

                case "COMMENT_LIKED":
                    type    = NotificationType.COMMENT_LIKED;
                    title   = "Bình luận của bạn được thích";
                    message = String.format("%s đã thích bình luận của bạn trong bài \"%s\"",
                            event.getActorName(), event.getPostTitle());
                    break;

                case "NEW_POST_FROM_FOLLOWING":
                    type    = NotificationType.NEW_POST_FROM_FOLLOWING;
                    title   = "Bài viết mới";
                    message = String.format("%s vừa đăng bài viết mới: \"%s\"",
                            event.getActorName(), event.getPostTitle());
                    break;

                default:
                    log.warn("Unknown forum event type: {}", event.getEventType());
                    return;
               }

               // --3. Lưu DB --------
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("postId", event.getPostId());
                metadata.put("postTitle", event.getPostTitle());
                metadata.put("actorUserId", event.getActorUserId());
                metadata.put("actorName", event.getActorName());
                metadata.put("actorAvatar", event.getActorAvatar());
                metadata.put("commentId", event.getCommentId());    
                metadata.put("parentCommentId", event.getParentCommentId());

               Notification saved = notificationRepository.save(
                    Notification.builder()
                        .userId(event.getRecipientUserId())
                        .type(type)
                        .title(title)
                        .message(message)
                        .metadata(objectMapper.valueToTree(metadata))
                        .build()
                );

                // --4. Push WebSocket real-time --
                Map<String, Object> wsPayload = new HashMap<>();
                wsPayload.put("notificationID", saved.getNotificationID());
                wsPayload.put("type", type.name());
                wsPayload.put("title", title);
                wsPayload.put("message", message);
                wsPayload.put("isRead", false);
                wsPayload.put("createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
                wsPayload.put("metadata", metadata);

                webSocketService.notifyUserForum(event.getRecipientUserId(), wsPayload);

                // -- 5. Đánh dấu đã xử lý --
                if(key != null){
                    try {
                        processedEventRepo.save(ProcessedEvent.builder()
                          .idempotencyKey(key)
                          .eventType(event.getEventType())
                          .build());
                    } catch (DataIntegrityViolationException ex) {
                       // Race condition — đã xử lý bởi instance khác, bỏ qua
                    log.info("Concurrent duplicate forum event ignored: key={}", key);
                    }
                }
        } catch(Exception ex){
            log.error("Error processing forum event: key={}, error={}", key, ex.getMessage(), ex);
            // Bất kỳ exception nào cũng sẽ khiến message bị ném ra ngoài → Spring tự retry
            throw new RuntimeException("Failed to process forum event:  " + key, ex);
        }
    }

}
