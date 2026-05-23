# Forum Notifications — Migrate sang RabbitMQ

## Mục lục
1. [RabbitMQ là gì? Giải thích từ đầu](#1-rabbitmq-là-gì-giải-thích-từ-đầu)
2. [Tại sao dùng RabbitMQ cho forum notifications?](#2-tại-sao-dùng-rabbitmq-cho-forum-notifications)
3. [Kiến trúc tổng thể](#3-kiến-trúc-tổng-thể)
4. [Phần 1 — forum-service (Producer: gửi event)](#4-phần-1--forum-service-producer-gửi-event)
5. [Phần 2 — notification-service (Consumer: nhận và xử lý)](#5-phần-2--notification-service-consumer-nhận-và-xử-lý)
6. [Thứ tự thực hiện](#6-thứ-tự-thực-hiện)
7. [Test nhanh](#7-test-nhanh)

---

## 1. RabbitMQ là gì? Giải thích từ đầu

### Ý tưởng đơn giản

Hãy tưởng tượng hệ thống bưu điện:
- **Producer** (người gửi) = forum-service — khi user like/comment, viết thư bỏ vào hộp thư
- **Exchange** (bưu điện trung tâm) = nhận thư, đọc địa chỉ, phân loại gửi đi
- **Queue** (hòm thư đích) = nơi thư nằm chờ
- **Consumer** (người nhận) = notification-service — lấy thư ra xử lý

```
forum-service  ──publish──►  [Exchange: tourism.events]
                                        │
                         routing key: forum.notification.#
                                        │
                                        ▼
                          [Queue: forum.notification.queue]
                                        │
                                        ▼
                          notification-service ──► DB + WebSocket
```

### Các khái niệm quan trọng

#### Exchange
Nơi nhận message từ Producer. Exchange **không lưu** message — nó chỉ định tuyến (route).

Trong project này dùng **TopicExchange** với tên `"tourism.events"` (đã có sẵn).

#### Routing Key
Chuỗi phân cấp bằng dấu chấm, ví dụ: `"forum.notification.post_liked"`

Exchange dùng routing key để quyết định message đi vào Queue nào.

#### Queue
Hàng đợi thực sự lưu message. Consumer lắng nghe Queue này.

Ta sẽ tạo queue mới: `"forum.notification.queue"`

#### Binding
Quy tắc nối Exchange với Queue. Dùng **pattern** (wildcard):
- `"forum.notification.*"` → khớp với `"forum.notification.post_liked"`, `"forum.notification.comment_replied"`, v.v.
- `*` = đúng 1 từ, `#` = 0 hoặc nhiều từ

#### Dead Letter Queue (DLQ)
Nếu Consumer xử lý lỗi 3 lần liên tiếp → message tự động chuyển sang `"forum.notification.dlq"`.  
Không mất message, admin có thể xem lại sau.

#### Idempotency Key
Mỗi message có 1 key duy nhất (UUID). Nếu RabbitMQ retry gửi lại (do network lỗi),
Consumer kiểm tra key này trong DB — nếu đã xử lý rồi thì bỏ qua, tránh gửi 2 thông báo cho 1 event.

### So sánh Feign vs RabbitMQ

| | Feign (HTTP) | RabbitMQ |
|---|---|---|
| **Kiểu** | Đồng bộ — gọi xong chờ response | Bất đồng bộ — gửi xong quên |
| **Nếu notification-service down** | Gọi thất bại, forum-service nhận lỗi | Message nằm trong Queue, xử lý khi service lên lại |
| **Retry tự động** | Không (phải tự code) | Có — cấu hình trong application.yml |
| **Scale** | 1-1 | Nhiều Consumer cùng lắng nghe 1 Queue |
| **Độ phức tạp** | Đơn giản | Cao hơn một chút |

---

## 2. Tại sao dùng RabbitMQ cho forum notifications?

Forum là nơi nhiều user tương tác liên tục. Khi dùng Feign:
- forum-service phải **chờ** notification-service trả lời trước khi trả kết quả cho user
- Nếu notification-service chậm → like/comment của user bị chậm theo

Với RabbitMQ:
- forum-service gửi message vào Queue → **trả về kết quả cho user ngay lập tức**
- notification-service xử lý trong nền, không ảnh hưởng trải nghiệm user

---

## 3. Kiến trúc tổng thể

```
User nhấn Like
     │
     ▼
forum-service.toggleLike()
     │  publish message
     │  exchange: "tourism.events"
     │  routing key: "forum.notification.post_liked"
     ▼
[RabbitMQ Exchange: tourism.events]  ← đã có sẵn trong project
     │
     │  binding: "forum.notification.*"
     ▼
[Queue: forum.notification.queue]  ← tạo mới
     │
     │  @RabbitListener
     ▼
notification-service.ForumEventListener
     ├── saveNotification()  → notifications table (DB)
     └── webSocketService.notifyUserForum()  → /topic/user/{userId}/notifications
                                                          │
                                                          ▼
                                                   Frontend nhận real-time
```

---

## 4. Phần 1 — forum-service (Producer: gửi event)

### 4.1 Tạo DTO ForumNotificationEvent

**Tạo file mới:**
`forum-service/src/main/java/com/tourism/forum/dto/event/ForumNotificationEvent.java`

```java
package com.tourism.forum.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumNotificationEvent {

    // Key duy nhất để chống duplicate — dùng UUID
    // VD: "POST_LIKED-postId5-userId2-1716300000000"
    private String idempotencyKey;

    // Loại event — Consumer dùng để switch-case
    // Giá trị: POST_LIKED | POST_COMMENTED | COMMENT_REPLIED | COMMENT_LIKED | NEW_POST_FROM_FOLLOWING
    private String eventType;

    // Người NHẬN thông báo (tác giả bài/comment/follower)
    private Integer recipientUserId;

    // Người THỰC HIỆN hành động (người like, comment, reply)
    private Integer actorUserId;
    private String  actorName;
    private String  actorAvatar;

    // Context — để frontend điều hướng khi click vào thông báo
    private Integer postId;
    private String  postTitle;
    private Integer commentId;        // comment mới hoặc comment bị like
    private Integer parentCommentId;  // chỉ dùng khi COMMENT_REPLIED
}
```

---

### 4.2 Tạo ForumEventPublisher

**Tạo file mới:**
`forum-service/src/main/java/com/tourism/forum/messaging/ForumEventPublisher.java`

```java
package com.tourism.forum.messaging;

import com.tourism.forum.dto.event.ForumNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForumEventPublisher {

    // Exchange đã có sẵn trong project (tourism.events)
    private static final String EXCHANGE = "tourism.events";

    private final RabbitTemplate rabbitTemplate;

    /**
     * Gửi forum notification event vào RabbitMQ.
     * Routing key: "forum.notification.{eventType_lowercase}"
     * VD: "forum.notification.post_liked"
     *
     * Fire-and-forget: wrap try-catch để lỗi RabbitMQ
     * không ảnh hưởng đến like/comment của user.
     */
    public void publishForumEvent(ForumNotificationEvent event) {
        // Không gửi nếu người nhận = người gửi (tự like/comment chính mình)
        if (event.getRecipientUserId() == null) return;
        if (event.getRecipientUserId().equals(event.getActorUserId())) return;

        String routingKey = "forum.notification." + event.getEventType().toLowerCase();

        try {
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);
            log.info("Published forum event: type={}, recipient={}, routing={}",
                    event.getEventType(), event.getRecipientUserId(), routingKey);
        } catch (Exception e) {
            // Non-critical: notification thất bại không được làm hỏng like/comment
            log.warn("Failed to publish forum event (non-critical): {}", e.getMessage());
        }
    }
}
```

---

### 4.3 Thêm RabbitMQ config vào forum-service

`forum-service` đã có config RabbitMQ trong `application.yml` (host, port, username, password).
Tuy nhiên cần khai báo Exchange và converter để dùng JSON.

**Tạo file mới:**
`forum-service/src/main/java/com/tourism/forum/config/RabbitMQConfig.java`

```java
package com.tourism.forum.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Khai báo lại Exchange đã có — RabbitMQ idempotent (tạo lại không bị lỗi)
    @Bean
    public TopicExchange tourismEventsExchange() {
        return new TopicExchange("tourism.events", true, false);
    }

    // Dùng JSON thay vì Java serialization
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
```

---

### 4.4 Sửa ForumServiceImpl — inject publisher và thêm 4 trigger calls

**File:** `forum-service/src/main/java/com/tourism/forum/service/impl/ForumServiceImpl.java`

**Bước A — Thêm field:**
```java
// Thêm vào danh sách field (dưới private final IamFeignClient iamFeignClient;)
private final ForumEventPublisher forumEventPublisher;
```

`@RequiredArgsConstructor` sẽ tự inject qua constructor — không cần `@Autowired`.

---

**Bước B — Sửa method `toggleLike()` (dòng 365):**

```java
@Override
public void toggleLike(Integer postId, Integer userId) {
    ForumPost post = forumPostRepository.findById(postId)
        .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

    Optional<PostLike> existing = postLikeRepository.findByPostPostIDAndUserId(postId, userId);
    if (existing.isPresent()) {
        // Unlike — xóa like, giảm count
        postLikeRepository.delete(existing.get());
        post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
    } else {
        // Like mới — tạo like, tăng count
        PostLike like = new PostLike();
        like.setPost(post);
        like.setUserId(userId);
        postLikeRepository.save(like);
        post.setLikeCount(post.getLikeCount() + 1);

        // ── Gửi notification (chỉ khi THÊM like, không gửi khi bỏ like) ──
        UserBriefResponse actor = getUserSafe(userId);
        forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("POST_LIKED-" + postId + "-" + userId + "-" + System.currentTimeMillis())
                .eventType("POST_LIKED")
                .recipientUserId(post.getUserId())
                .actorUserId(userId)
                .actorName(actor != null ? actor.getFullName() : "Ai đó")
                .actorAvatar(actor != null ? actor.getAvatar() : null)
                .postId(postId)
                .postTitle(post.getTitle())
                .build());
    }
    forumPostRepository.save(post);
}
```

---

**Bước C — Sửa method `addComment()` (dòng 391):**

```java
@Override
public PostDetailResponse addComment(Integer postId, CommentRequest request) {
    ForumPost post = forumPostRepository.findById(postId)
        .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

    PostComment comment = new PostComment();
    comment.setContent(request.getContent());
    comment.setUserId(request.getUserId());
    comment.setPost(post);
    comment.setLikeCount(0);

    PostComment parentComment = null;
    if (request.getParentCommentId() != null) {
        parentComment = commentRepository.findById(request.getParentCommentId()).orElse(null);
        comment.setParentComment(parentComment);
    }

    PostComment savedComment = commentRepository.save(comment);
    post.setCommentCount(post.getCommentCount() + 1);
    forumPostRepository.save(post);

    // ── Gửi notification ──────────────────────────────────────────────────
    UserBriefResponse actor = getUserSafe(request.getUserId());
    String actorName   = actor != null ? actor.getFullName() : "Ai đó";
    String actorAvatar = actor != null ? actor.getAvatar() : null;

    if (parentComment == null) {
        // Comment gốc → thông báo tác giả bài viết
        forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("POST_COMMENTED-" + postId + "-" + savedComment.getCommentID())
                .eventType("POST_COMMENTED")
                .recipientUserId(post.getUserId())
                .actorUserId(request.getUserId())
                .actorName(actorName)
                .actorAvatar(actorAvatar)
                .postId(postId)
                .postTitle(post.getTitle())
                .commentId(savedComment.getCommentID())
                .build());
    } else {
        // Reply → thông báo tác giả comment cha
        forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("COMMENT_REPLIED-" + savedComment.getCommentID())
                .eventType("COMMENT_REPLIED")
                .recipientUserId(parentComment.getUserId())
                .actorUserId(request.getUserId())
                .actorName(actorName)
                .actorAvatar(actorAvatar)
                .postId(postId)
                .postTitle(post.getTitle())
                .commentId(savedComment.getCommentID())
                .parentCommentId(parentComment.getCommentID())
                .build());
    }
    // ─────────────────────────────────────────────────────────────────────

    return mapToDetailResponse(post, request.getUserId());
}
```

---

**Bước D — Sửa method `toggleCommentLike()` (dòng 415):**

```java
@Override
public void toggleCommentLike(Integer commentId, Integer userId) {
    PostComment comment = commentRepository.findById(commentId)
        .orElseThrow(() -> new RuntimeException("Comment not found: " + commentId));

    Optional<CommentLike> existing =
        commentLikeRepository.findByCommentCommentIDAndUserId(commentId, userId);

    if (existing.isPresent()) {
        // Unlike comment
        commentLikeRepository.delete(existing.get());
        comment.setLikeCount(Math.max(0, (comment.getLikeCount() == null ? 0 : comment.getLikeCount()) - 1));
    } else {
        // Like comment mới
        CommentLike like = new CommentLike();
        like.setUserId(userId);
        like.setComment(comment);
        commentLikeRepository.save(like);
        comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);

        // ── Gửi notification (chỉ khi THÊM like) ──
        UserBriefResponse actor = getUserSafe(userId);
        forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                .idempotencyKey("COMMENT_LIKED-" + commentId + "-" + userId + "-" + System.currentTimeMillis())
                .eventType("COMMENT_LIKED")
                .recipientUserId(comment.getUserId())
                .actorUserId(userId)
                .actorName(actor != null ? actor.getFullName() : "Ai đó")
                .actorAvatar(actor != null ? actor.getAvatar() : null)
                .postId(comment.getPost() != null ? comment.getPost().getPostID() : null)
                .postTitle(comment.getPost() != null ? comment.getPost().getTitle() : null)
                .commentId(commentId)
                .build());
    }
    commentRepository.save(comment);
}
```

---

**Bước E — Sửa method `createPost()` (dòng 279) — thông báo cho follower:**

> ⚠️ `forum-service` chưa có `FollowerRepository`. Cần tạo thêm (xem bên dưới).

```java
// Thêm vào CUỐI createPost(), ngay trước dòng return
// (sau khi post đã được save và tags đã được gán)

// Thông báo cho tất cả follower về bài viết mới
// (chỉ thông báo khi PUBLISHED, không thông báo DRAFT)
if (!Boolean.TRUE.equals(request.getIsDraft())) {
    List<Integer> followerIds = followerRepository
            .findFollowerIdsByFollowingUserId(request.getUserId());

    if (!followerIds.isEmpty()) {
        UserBriefResponse author  = getUserSafe(request.getUserId());
        String authorName   = author != null ? author.getFullName() : "Ai đó";
        String authorAvatar = author != null ? author.getAvatar()   : null;

        ForumPost finalPost = post; // effectively final cho lambda
        for (Integer followerId : followerIds) {
            forumEventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                    .idempotencyKey("NEW_POST-" + finalPost.getPostID() + "-follower-" + followerId)
                    .eventType("NEW_POST_FROM_FOLLOWING")
                    .recipientUserId(followerId)
                    .actorUserId(request.getUserId())
                    .actorName(authorName)
                    .actorAvatar(authorAvatar)
                    .postId(finalPost.getPostID())
                    .postTitle(finalPost.getTitle())
                    .build());
        }
    }
}
```

---

### 4.5 Tạo FollowerRepository (chưa có trong project)

**Tạo file mới:**
`forum-service/src/main/java/com/tourism/forum/repository/FollowerRepository.java`

```java
package com.tourism.forum.repository;

import com.tourism.forum.entity.Follower;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FollowerRepository extends JpaRepository<Follower, Integer> {

    // Lấy tất cả userId của những người đang follow user X
    // followingUserId = người được follow (tác giả bài viết)
    // followerUserId  = người follow (sẽ nhận thông báo)
    @Query("SELECT f.followerUserId FROM Follower f WHERE f.followingUserId = :userId")
    List<Integer> findFollowerIdsByFollowingUserId(@Param("userId") Integer userId);
}
```

Sau đó thêm field vào `ForumServiceImpl`:
```java
private final FollowerRepository followerRepository;
```

---

## 5. Phần 2 — notification-service (Consumer: nhận và xử lý)

### 5.1 Thêm types vào NotificationType enum

**File:** `notification-service/src/main/java/com/tourism/notification/entity/NotificationType.java`

```java
// Thêm 3 type còn thiếu vào block Forum (POST_LIKED, POST_COMMENTED đã có sẵn):
COMMENT_REPLIED,            // Ai đó reply comment của mình
COMMENT_LIKED,              // Ai đó like comment của mình
NEW_POST_FROM_FOLLOWING,    // Người mình follow vừa đăng bài
```

---

### 5.2 Tạo DTO ForumNotificationEvent (mirror)

**Tạo file mới:**
`notification-service/src/main/java/com/tourism/notification/dto/ForumNotificationEvent.java`

```java
package com.tourism.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Mirror của forum-service DTO — 2 service không share code, phải tạo riêng
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumNotificationEvent {
    private String  idempotencyKey;
    private String  eventType;
    private Integer recipientUserId;
    private Integer actorUserId;
    private String  actorName;
    private String  actorAvatar;
    private Integer postId;
    private String  postTitle;
    private Integer commentId;
    private Integer parentCommentId;
}
```

---

### 5.3 Thêm Queue và Binding vào RabbitMQConfig

**File:** `notification-service/src/main/java/com/tourism/notification/config/RabbitMQConfig.java`

Thêm các constants và beans sau vào class (giữ nguyên code cũ):

```java
// Thêm constants
public static final String QUEUE_FORUM_NOTIFICATION = "forum.notification.queue";
public static final String DLQ_FORUM_NOTIFICATION   = "forum.notification.dlq";

// Thêm beans
@Bean
public Queue forumNotificationDlq() {
    return QueueBuilder.durable(DLQ_FORUM_NOTIFICATION).build();
}

@Bean
public Queue forumNotificationQueue() {
    return QueueBuilder.durable(QUEUE_FORUM_NOTIFICATION)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", DLQ_FORUM_NOTIFICATION)
            .build();
}

@Bean
public Binding forumNotificationBinding(Queue forumNotificationQueue,
                                         TopicExchange tourismEventsExchange) {
    // "forum.notification.*" khớp với mọi routing key bắt đầu bằng "forum.notification."
    return BindingBuilder.bind(forumNotificationQueue)
            .to(tourismEventsExchange)
            .with("forum.notification.*");
}
```

---

### 5.4 Tạo ForumEventListener

**Tạo file mới:**
`notification-service/src/main/java/com/tourism/notification/listener/ForumEventListener.java`

```java
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

    private final NotificationRepository  notificationRepository;
    private final ProcessedEventRepository processedEventRepo;
    private final WebSocketService         webSocketService;
    private final ObjectMapper             objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FORUM_NOTIFICATION)
    @Transactional
    public void onForumEvent(ForumNotificationEvent event) {
        String key = event.getIdempotencyKey();

        // ── 1. Idempotency check ──────────────────────────────────────────────
        if (key != null && processedEventRepo.existsByIdempotencyKey(key)) {
            log.info("Skipping duplicate forum event: key={}", key);
            return;
        }

        log.info("Received forum event: type={}, recipient={}, key={}",
                event.getEventType(), event.getRecipientUserId(), key);

        try {
            // ── 2. Build title + message theo loại event ──────────────────────
            String title;
            String message;
            NotificationType type;

            switch (event.getEventType()) {
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

            // ── 3. Lưu DB ─────────────────────────────────────────────────────
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("postId",          event.getPostId());
            metadata.put("postTitle",        event.getPostTitle());
            metadata.put("actorUserId",      event.getActorUserId());
            metadata.put("actorName",        event.getActorName());
            metadata.put("actorAvatar",      event.getActorAvatar());
            metadata.put("commentId",        event.getCommentId());
            metadata.put("parentCommentId",  event.getParentCommentId());

            Notification saved = notificationRepository.save(
                    Notification.builder()
                            .userId(event.getRecipientUserId())
                            .type(type)
                            .title(title)
                            .message(message)
                            .metadata(objectMapper.valueToTree(metadata))
                            .build()
            );

            // ── 4. Push WebSocket real-time ───────────────────────────────────
            Map<String, Object> wsPayload = new HashMap<>(metadata);
            wsPayload.put("notificationId", saved.getNotificationID());
            wsPayload.put("type",           type.name());
            wsPayload.put("title",          title);
            wsPayload.put("message",        message);
            wsPayload.put("isRead",         false);
            wsPayload.put("createdAt",      saved.getCreatedAt() != null
                    ? saved.getCreatedAt().toString() : null);

            webSocketService.notifyUserForum(event.getRecipientUserId(), wsPayload);

            // ── 5. Đánh dấu đã xử lý ─────────────────────────────────────────
            if (key != null) {
                try {
                    processedEventRepo.save(ProcessedEvent.builder()
                            .idempotencyKey(key)
                            .eventType(event.getEventType())
                            .build());
                } catch (DataIntegrityViolationException e) {
                    // Race condition — đã xử lý bởi instance khác, bỏ qua
                    log.info("Concurrent duplicate forum event ignored: key={}", key);
                }
            }

        } catch (Exception e) {
            log.error("Error processing forum event key={}: {}", key, e.getMessage(), e);
            // Rethrow để Spring Retry và DLQ xử lý
            throw new RuntimeException("Failed to process forum event: " + key, e);
        }
    }
}
```

---

### 5.5 Thêm notifyUserForum vào WebSocketService

**File:** `notification-service/src/main/java/com/tourism/notification/service/impl/WebSocketService.java`

Thêm method sau vào cuối class (trước dấu `}`):

```java
/**
 * Push forum notification đến user cụ thể.
 * Frontend subscribe: /topic/user/{userId}/notifications
 */
public void notifyUserForum(Integer userId, Object payload) {
    if (userId == null) return;
    try {
        messagingTemplate.convertAndSend("/topic/user/" + userId + "/notifications", payload);
        log.info("WebSocket forum notification pushed to userId={}", userId);
    } catch (Exception e) {
        log.error("WebSocket push failed for userId={}: {}", userId, e.getMessage());
    }
}
```

---

### 5.6 Bật Spring Retry cho forum queue trong application.yml

**File:** `notification-service/src/main/resources/application.yml`

Kiểm tra xem đã có `spring.rabbitmq.listener.simple.retry` chưa. Nếu chưa, thêm:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000   # chờ 1s trước khi retry lần 1
          max-attempts: 3          # tổng 3 lần thử
          multiplier: 2.0          # lần 2 chờ 2s, lần 3 chờ 4s
          max-interval: 10000      # tối đa chờ 10s
```

---

## 6. Thứ tự thực hiện

### Bước 1 — notification-service
1. Thêm enum `COMMENT_REPLIED`, `COMMENT_LIKED`, `NEW_POST_FROM_FOLLOWING` vào `NotificationType.java`
2. Tạo `dto/ForumNotificationEvent.java`
3. Thêm Queue/Binding vào `RabbitMQConfig.java`
4. Tạo `listener/ForumEventListener.java`
5. Thêm `notifyUserForum()` vào `WebSocketService.java`
6. Thêm retry config vào `application.yml`

### Bước 2 — forum-service
1. Tạo `config/RabbitMQConfig.java`
2. Tạo `dto/event/ForumNotificationEvent.java`
3. Tạo `messaging/ForumEventPublisher.java`
4. Tạo `repository/FollowerRepository.java`
5. Sửa `ForumServiceImpl.java`: inject publisher + followerRepository, sửa 4 method

### Bước 3 — Build và chạy
```bash
# Build forum-service
cd d:\Tourism_Microservices\forum-service
mvn package -DskipTests

# Build notification-service
cd d:\Tourism_Microservices\notification-service
mvn package -DskipTests

# Rebuild container
docker-compose up -d --build forum-service notification-service
```

---

## 7. Test nhanh

### Kiểm tra Queue đã được tạo trong RabbitMQ
Mở browser: `http://localhost:15672` (username: `tourism`, password: `tourism123`)  
→ Tab **Queues** → tìm `forum.notification.queue`

### Kiểm tra luồng hoàn chỉnh
1. Đăng nhập bằng 2 tài khoản khác nhau (user A và user B)
2. User B like bài viết của user A
3. Kiểm tra DB notification-service:
```sql
SELECT * FROM notifications WHERE user_id = {userId_A} ORDER BY created_at DESC LIMIT 5;
```
4. Kiểm tra WebSocket: user A phải nhận message real-time tại `/topic/user/{userId_A}/notifications`

### Kiểm tra idempotency
Like cùng 1 bài 2 lần nhanh → DB chỉ có 1 notification (key trùng → bị skip lần 2)

### Kiểm tra DLQ
Nếu notification-service down trong lúc like → message nằm trong Queue  
→ Khi service lên lại → message được xử lý tự động → notification xuất hiện
