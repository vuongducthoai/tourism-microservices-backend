# KẾ HOẠCH NÂNG CẤP CHATBOT FUTURE TRAVEL — v2
### Phân tích chi tiết RabbitMQ, Redis, Lưu lịch sử chat, Pinecone Event-Driven Sync

> **Ngày:** 2026-05-23  
> **Tham khảo:** Screenshots thực tế Tripi (travel.com.vn) + phân tích source code hệ thống

---

## MỤC LỤC
1. [Đọc hiểu hệ thống hiện tại](#1-đọc-hiểu-hệ-thống-hiện-tại)
2. [So sánh chi tiết với Tripi (travel.com.vn)](#2-so-sánh-chi-tiết-với-tripi-travelcomvn)
3. [Phân tích RabbitMQ cho Pinecone Sync](#3-phân-tích-rabbitmq-cho-pinecone-sync)
4. [Phân tích Redis cho Chatbot](#4-phân-tích-redis-cho-chatbot)
5. [Lưu lịch sử chat — kể cả chưa đăng nhập](#5-lưu-lịch-sử-chat--kể-cả-chưa-đăng-nhập)
6. [Lộ trình triển khai 4 Phase](#6-lộ-trình-triển-khai-4-phase)
7. [Danh sách file cần thay đổi](#7-danh-sách-file-cần-thay-đổi)
8. **[Booking-in-Chat Flow chi tiết (Tripi-style)](#8-booking-in-chat-flow-chi-tiết-tripi-style)** ← MỚI
9. **[Order Lookup Flow chi tiết](#9-order-lookup-flow-chi-tiết)** ← MỚI

---

## 1. ĐỌC HIỂU HỆ THỐNG HIỆN TẠI

### 1.1 Kiến trúc hiện tại — RAG không trạng thái

```
[ChatbotWidget.jsx]
  sessionId = `session_${Date.now()}`  ← tạo mới mỗi lần F5
  userId = null                         ← hardcoded
  
  POST http://localhost:8080/api/chatbot/chat
  { message, sessionId, userId: null }
       │
       ▼
[API Gateway :8080]
       │
       ▼
[analytics-service :8087]  ChatbotService.handleUserMessage()
  1. regex detect DISCOUNT / COUPON → topK 50 vs 10
  2. VectorService.searchSimilar(message, topK)
     └─ Pinecone llama-text-embed-v2 1024d cosine search
  3. buildEnhancedContext(docs) — re-rank coupon/discount
  4. buildEnhancedPrompt(message, context) — system prompt tiếng Việt
  5. callGeminiAPI(prompt) — Gemini 2.0 Flash temp=0.7
  6. buildTourSuggestions(docs) → max 6 tours (commented out ở FE)
  7. buildQuickActions(request) → 4 actions (commented out ở FE)
  └─ Trả về ChatMessageResponse

[VectorSyncService] @Scheduled(cron = "0 0 2 * * *") — 2:00 AM
  syncAllTours()      → Feign → tour-catalog-service → upsert TOUR_SUMMARY + TOUR_DEPARTURE
  syncAllLocations()  → Feign → tour-catalog-service → upsert LOCATION
  syncAllReviews()    → Feign → tour-catalog-service → upsert REVIEW
  syncAllCoupons()    → Feign → booking-service      → upsert COUPON
```

### 1.2 Điểm mạnh hiện tại ✅
- RAG pipeline hoạt động tốt: cosine search + Gemini trả lời tiếng Việt tự nhiên
- Re-rank logic thông minh cho coupon/discount queries
- Tour card + quickActions đã code backend → chỉ cần bỏ comment FE
- VectorSyncService đầy đủ 5 loại document
- Mascot video ChromaKey animation đẹp
- Unit test + controller test đầy đủ

### 1.3 Điểm yếu cần giải quyết ❌
- **Không nhớ ngữ cảnh**: `sessionId` tạo mới mỗi lần F5, không lưu lịch sử
- **Tour cards bị comment** trong ChatbotWidget.jsx (dòng 326-360)
- **userId = null** hardcoded, không kết nối auth
- **Sync 1x/ngày**: Tour mới tạo, departure thêm slot, review mới → phải đợi đến 2AM
- **API keys hardcoded** trong application.yml (bảo mật)
- **URL hardcoded** `http://localhost:8080` trong fetch (không dùng proxy)
- **Không có Welcome chips** gợi ý câu hỏi ban đầu
- **Không có feedback** Like/Dislike

---

## 2. SO SÁNH CHI TIẾT VỚI TRIPI (TRAVEL.COM.VN)

### 2.1 Tour Card của Tripi (từ screenshots thực tế)

```
┌─────────────────────────────────────────────┐
│  [Ảnh full-width: Phan Thiết Mũi Né...]     │
│                                             │
│  NDSGN555  •  TP. Hồ Chí Minh              │
│  Phan Thiết – Mũi Né – TTC World Tà Cú...  │
│  ──────────────────────────────────────     │
│  ⏱ 3N2Đ                                     │
│  📅 [24/05]  [07/06]  [14/06]  [21/06]     │
│  ──────────────────────────────────────     │
│  Giá từ: 3.790.000 ₫               [Xem]   │
└─────────────────────────────────────────────┘
```
> **Gap:** FE đã có code card nhưng bị comment. Backend cần bổ sung `departureDates[]`.

### 2.2 Slot-filling của Tripi

```
User: "Tôi muốn đi biển"

Tripi: "Quý khách dự định đi đâu, khởi hành từ đâu,
        dự kiến đi tháng mấy và đi bao nhiêu người
        để em có thể tìm tour phù hợp — kể cả các
        lựa chọn giá tốt nhất — cho Quý khách ạ?"

→ User: "Từ HCM, tháng 7, 2 người, Đà Nẵng"
→ Tripi tìm tour Đà Nẵng, tháng 7, 2 người khởi hành HCM
```
> **Gap:** Future Travel trả lời ngay mà không hỏi thêm → kết quả chung chung.

### 2.3 Booking flow của Tripi

```
[1] Gợi ý tour cards → user bấm "Xem ngay" hoặc chat "đặt tour này"
[2] Tripi: "Quý khách thích tour nào để em tư vấn thêm và đặt chỗ?"
[3] User: "Đặt tour Phan Thiết ngày 24/05"
[4] Tripi hỏi họ tên + SĐT liên hệ
[5] Hiển thị BookingConfirmCard — Tên tour + Ngày + Số người + Tổng tiền
[6] User xác nhận → Tripi tạo booking → trả về mã booking + link thanh toán
```
> **Gap:** Phải tích hợp booking-service Feign + state machine trong chatbot.

### 2.4 Order Lookup của Tripi

```
User: "Tra cứu đơn hàng VTV123456"
Tripi: "Để tra cứu đơn hàng, vui lòng cung cấp:
        - Mã booking (Ví dụ: VTV123456)
        - Họ tên đầy đủ khách hàng chính"
→ Hiển thị: Tên tour | Ngày khởi hành | Trạng thái | Tổng tiền
```

---

## 3. PHÂN TÍCH RABBITMQ CHO PINECONE SYNC

### 3.1 Vấn đề thực tế của cron 2AM

```
Kịch bản thực tế:
10:00 AM — Admin thêm tour mới "Tour Phú Quốc 4N3Đ" vào tour-catalog
10:30 AM — Khách hỏi chatbot: "Có tour Phú Quốc mới không?"
           → Bot: "Không tìm thấy tour Phú Quốc mới nào" ← SAI
01:59 AM (ngày sau) — VectorSyncService chạy
02:00 AM — Tour mới được sync vào Pinecone
02:01 AM — Bot mới biết tour Phú Quốc mới tồn tại
```

Độ trễ tối đa: **16-20 giờ** — không chấp nhận được cho production.

### 3.2 Phương án: Debounced RabbitMQ Sync (Recommended ✅)

**Nguyên lý:** Thay vì sync ngay khi có 1 event (gây tốn API Pinecone + embedding), dồn lại các sự kiện trong cửa sổ thời gian 5 phút rồi sync 1 lần.

```
┌──────────────────────────────────────────────────────────────────┐
│  EVENT-DRIVEN SYNC ARCHITECTURE                                  │
└──────────────────────────────────────────────────────────────────┘

[tour-catalog-service]
  TourService.createTour()    → publish TourChangedEvent(tourId, CREATED)
  TourService.updateTour()    → publish TourChangedEvent(tourId, UPDATED)
  ReviewService.addReview()   → publish ReviewAddedEvent(reviewId, tourId)
  DepartureService.addDep()   → publish DepartureChangedEvent(deptId, CREATED)
  DepartureService.updateSlots() → publish DepartureChangedEvent(deptId, SLOT_CHANGED)

[booking-service]
  BookingService.createBooking()    → publish DepartureChangedEvent(deptId, SLOT_CHANGED)
  BookingService.cancelBooking()    → publish DepartureChangedEvent(deptId, SLOT_CHANGED)
  CouponService.createCoupon()      → publish CouponChangedEvent(couponId, CREATED)

  ↓↓ RabbitMQ exchange: tourism.events routing key: chatbot.sync.* ↓↓

[analytics-service] @RabbitListener("chatbot.sync.queue")
  ChatbotSyncEventListener.onEvent(event)
    → pendingTourIds.add(event.tourId)    // ConcurrentHashMap<String, Long>
    → pendingDeptIds.add(event.deptId)    // ghi nhận time nhận event
    → pendingReviewIds.add(...)

  @Scheduled(fixedDelay = 300_000)  // mỗi 5 phút kiểm tra
  ChatbotDebounceScheduler.flushPending()
    → if (pendingTourIds.isEmpty && pendingDeptIds.isEmpty) return;
    → Batch sync chỉ những entity CÓ thay đổi
    → pendingTourIds.clear()
```

### 3.3 Lý do dùng 5 phút (300s)

| Tần suất | Ưu điểm | Nhược điểm |
|---|---|---|
| **Ngay lập tức (0s)** | Data real-time | Spam Pinecone API; 1 tour update → 1 lần embed + upsert |
| **1 phút** | Gần real-time | Vẫn có thể spam nếu admin bulk update |
| **5 phút (recommended)** | Bao gồm bulk operations, tiết kiệm API | Độ trễ 5 phút — chấp nhận được |
| **30 phút** | Rất ít gọi Pinecone | Độ trễ quá lớn cho flash sale |
| **Cron 2AM** | Đơn giản | Độ trễ 16-20h — không chấp nhận được |

**Kết hợp cron + event-driven:**
- Event-driven (5 phút): sync khi có thay đổi thực tế
- Cron 2AM: full sync để đảm bảo consistency (phòng trường hợp event bị missed)

### 3.4 Thiết kế Queue & Routing

**Sử dụng exchange có sẵn:** `tourism.events` (TopicExchange — đã tồn tại)

```yaml
# Thêm vào booking-service và tour-catalog-service:
Routing keys:
  chatbot.sync.tour     ← khi tour tạo/cập nhật
  chatbot.sync.departure ← khi departure thêm/thay đổi slot
  chatbot.sync.review    ← khi review mới
  chatbot.sync.coupon    ← khi coupon tạo/hết hạn

Queue: chatbot.sync.queue (analytics-service lắng nghe)
Binding: chatbot.sync.*
```

**Tại sao KHÔNG dùng queue riêng?** Exchange `tourism.events` đã có sẵn, chỉ cần thêm binding mới. Tránh tạo thêm infrastructure.

### 3.5 Implementation Steps

#### A. tour-catalog-service — publish event khi data thay đổi

```java
// TourService.java — thêm publish sau khi lưu tour
@Service
@RequiredArgsConstructor
public class TourService {
    private final RabbitTemplate rabbitTemplate;

    public TourResponse createTour(TourRequest req) {
        Tour tour = tourRepository.save(mapper.toEntity(req));
        // Publish event để analytics-service sync Pinecone
        rabbitTemplate.convertAndSend(
            "tourism.events",
            "chatbot.sync.tour",
            new ChatbotSyncEvent("TOUR", tour.getId(), "CREATED")
        );
        return mapper.toResponse(tour);
    }

    public TourResponse updateTour(Integer id, TourRequest req) {
        // ... update logic
        rabbitTemplate.convertAndSend("tourism.events", "chatbot.sync.tour",
            new ChatbotSyncEvent("TOUR", id, "UPDATED"));
        return response;
    }
}

// ReviewService.java
public ReviewResponse addReview(ReviewRequest req) {
    // ... save review
    rabbitTemplate.convertAndSend("tourism.events", "chatbot.sync.review",
        new ChatbotSyncEvent("REVIEW", review.getId(), "CREATED", review.getTourId()));
    return response;
}
```

#### B. booking-service — publish khi slot thay đổi

```java
// BookingServiceImpl.java — THÊM SAU khi tạo/hủy booking
// (booking tạo → giảm slot, hủy → tăng slot → cần re-sync)
private void publishDepartureSlotChanged(Integer departureId) {
    try {
        rabbitTemplate.convertAndSend("tourism.events", "chatbot.sync.departure",
            new ChatbotSyncEvent("DEPARTURE", departureId, "SLOT_CHANGED"));
    } catch (Exception e) {
        log.warn("Failed to publish chatbot sync event, will be picked up at 2AM: {}", e.getMessage());
    }
}

public BookingResponse createBooking(BookingRequest req) {
    // ... existing booking logic
    publishDepartureSlotChanged(req.getDepartureId()); // thêm dòng này
    return response;
}
```

#### C. analytics-service — nhận event + debounce

```java
// ChatbotSyncEvent.java (DTO dùng chung qua shared-library)
@Data @AllArgsConstructor @NoArgsConstructor
public class ChatbotSyncEvent {
    private String entityType;   // TOUR, DEPARTURE, REVIEW, COUPON
    private Integer entityId;
    private String operation;    // CREATED, UPDATED, DELETED, SLOT_CHANGED
    private Integer parentId;    // tourId nếu là REVIEW
    private Instant occurredAt;
}

// ChatbotSyncEventListener.java
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatbotSyncEventListener {

    // Pending sets — dùng ConcurrentSkipListSet để thread-safe
    private final Set<Integer> pendingTourIds     = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pendingDeptIds     = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pendingReviewIds   = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pendingCouponIds   = ConcurrentHashMap.newKeySet();
    private volatile boolean   hasPending         = false;

    private final VectorSyncService vectorSyncService;

    @RabbitListener(queues = "chatbot.sync.queue")
    public void onSyncEvent(ChatbotSyncEvent event) {
        log.debug("📨 Chatbot sync event: {} {} {}", event.getEntityType(), event.getEntityId(), event.getOperation());
        switch (event.getEntityType()) {
            case "TOUR"      -> pendingTourIds.add(event.getEntityId());
            case "DEPARTURE" -> pendingDeptIds.add(event.getEntityId());
            case "REVIEW"    -> pendingReviewIds.add(event.getEntityId());
            case "COUPON"    -> pendingCouponIds.add(event.getEntityId());
        }
        hasPending = true;
    }

    /**
     * Flush mỗi 5 phút — chỉ chạy khi thực sự có pending events.
     * Debounce: nhiều event trong 5 phút → chỉ sync 1 lần.
     */
    @Scheduled(fixedDelay = 300_000)  // 5 phút
    public void flushPending() {
        if (!hasPending) return;

        log.info("🔄 Flushing pending chatbot sync: {} tours, {} departures, {} reviews, {} coupons",
                pendingTourIds.size(), pendingDeptIds.size(), pendingReviewIds.size(), pendingCouponIds.size());

        // Drain & sync tours (TOUR_SUMMARY + TOUR_DEPARTURE)
        if (!pendingTourIds.isEmpty()) {
            Set<Integer> batch = new HashSet<>(pendingTourIds);
            pendingTourIds.removeAll(batch);
            vectorSyncService.syncToursByIds(new ArrayList<>(batch));
        }

        // Drain & sync departures (chỉ TOUR_DEPARTURE của departure đó)
        if (!pendingDeptIds.isEmpty()) {
            Set<Integer> batch = new HashSet<>(pendingDeptIds);
            pendingDeptIds.removeAll(batch);
            vectorSyncService.syncDeparturesByIds(new ArrayList<>(batch));
        }

        // Drain & sync reviews
        if (!pendingReviewIds.isEmpty()) {
            Set<Integer> batch = new HashSet<>(pendingReviewIds);
            pendingReviewIds.removeAll(batch);
            vectorSyncService.syncReviewsByIds(new ArrayList<>(batch));
        }

        // Drain & sync coupons
        if (!pendingCouponIds.isEmpty()) {
            Set<Integer> batch = new HashSet<>(pendingCouponIds);
            pendingCouponIds.removeAll(batch);
            vectorSyncService.syncAllCoupons(); // coupon ít, sync full
        }

        hasPending = (!pendingTourIds.isEmpty() || !pendingDeptIds.isEmpty()
                   || !pendingReviewIds.isEmpty() || !pendingCouponIds.isEmpty());

        log.info("✅ Chatbot sync flush complete");
    }
}
```

#### D. analytics-service — thêm Feign endpoints mới (sync theo ID)

```java
// TourCatalogFeignClient.java — thêm endpoint lấy 1 tour theo ID
@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {
    // Endpoint đã có:
    @GetMapping("/api/tours/chatbot-sync/all")
    List<TourSyncDTO> getAllToursForChatbotSync();

    // Endpoint mới cần thêm vào tour-catalog-service:
    @GetMapping("/api/tours/chatbot-sync/by-ids")
    List<TourSyncDTO> getToursByIds(@RequestParam("ids") List<Integer> ids);

    @GetMapping("/api/tours/chatbot-sync/departure/{id}")
    TourSyncDTO.DepartureSyncDTO getDepartureById(@PathVariable Integer id);

    @GetMapping("/api/reviews/chatbot-sync/by-ids")
    List<ReviewSyncDTO> getReviewsByIds(@RequestParam("ids") List<Integer> ids);
}
```

#### E. Cấu hình Queue trong RabbitMQConfig (analytics-service)

```java
// analytics-service/config/RabbitMQConfig.java — TẠO MỚI
@Configuration
public class AnalyticsRabbitMQConfig {

    public static final String EXCHANGE        = "tourism.events";
    public static final String CHATBOT_QUEUE   = "chatbot.sync.queue";
    public static final String CHATBOT_DLQ     = "chatbot.sync.dlq";

    @Bean
    public TopicExchange tourismEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue chatbotSyncQueue() {
        return QueueBuilder.durable(CHATBOT_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", CHATBOT_DLQ)
                .build();
    }

    @Bean
    public Queue chatbotSyncDlq() {
        return QueueBuilder.durable(CHATBOT_DLQ).build();
    }

    @Bean
    public Binding chatbotSyncBinding(Queue chatbotSyncQueue, TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(chatbotSyncQueue)
                .to(tourismEventsExchange)
                .with("chatbot.sync.*");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return factory;
    }
}
```

### 3.6 Tổng kết quyết định RabbitMQ

```
RabbitMQ cho Chatbot:

✅ DÙNG để event-driven Pinecone sync (tour/departure/review/coupon thay đổi)
   → Routing: chatbot.sync.* → chatbot.sync.queue
   → Debounce 5 phút: nhiều event → batch sync 1 lần
   → Kết hợp cron 2AM: full sync backup

❌ KHÔNG dùng cho:
   → Hội thoại chatbot (sync request/response)
   → Booking trong chat (Feign đồng bộ OK)
   → Lưu conversation state (dùng Redis)
```

---

## 4. PHÂN TÍCH REDIS CHO CHATBOT

### 4.1 Multi-turn Conversation State

**Vấn đề:**
```
User: "Tour Đà Nẵng có không?"
Bot:  "Có 3 tour Đà Nẵng: ..."
User: "Cái nào rẻ nhất?"
Bot:  "Tôi không biết bạn hỏi về tour nào..." ← SAI vì không nhớ context
```

**Giải pháp với Redis:**
```
key: "chatbot:session:{sessionId}"
TTL: 2 tiếng (tự động xóa nếu không hoạt động)
value (JSON):
{
  "history": [
    { "role": "user", "content": "Tour Đà Nẵng có không?" },
    { "role": "bot", "content": "Có 3 tour: ..." }
  ],
  "slots": {
    "destination": "Đà Nẵng",
    "departureCity": null,
    "travelMonth": null,
    "numberOfPeople": null
  },
  "stage": "SEARCHING",
  "lastTourIds": [12, 15, 18]
}
```

### 4.2 ConversationStateService — thiết kế

```java
@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "chatbot:session:";
    private static final long TTL_HOURS = 2;

    public ConversationState getOrCreate(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return ConversationState.builder()
                    .history(new ArrayList<>())
                    .slots(new HashMap<>())
                    .stage("GREETING")
                    .lastTourIds(new ArrayList<>())
                    .build();
        }
        return deserialize(json);
    }

    public void save(String sessionId, ConversationState state) {
        String key = KEY_PREFIX + sessionId;
        state.setLastActivity(Instant.now().toEpochMilli());
        redisTemplate.opsForValue().set(key, serialize(state), TTL_HOURS, TimeUnit.HOURS);
    }

    /** Sliding window TTL — mỗi lần chat gia hạn thêm 2 giờ */
    public void refreshTTL(String sessionId) {
        redisTemplate.expire(KEY_PREFIX + sessionId, TTL_HOURS, TimeUnit.HOURS);
    }
}
```

### 4.3 System Prompt với History

Khi build prompt, chèn conversation history vào:
```
=== LỊCH SỬ HỘI THOẠI (giữ ngữ cảnh) ===
User: Tour Đà Nẵng có không?
Bot: Có 3 tour Đà Nẵng tháng 6: [Tour A...], [Tour B...], [Tour C...]

=== DỮ LIỆU HỆ THỐNG (retrieved từ Pinecone) ===
{context từ vector search}

=== THÔNG TIN ĐÃ BIẾT VỀ KHÁCH HÀNG ===
Điểm đến: Đà Nẵng
Tháng đi: (chưa biết)
Số người: (chưa biết)

=== CÂU HỎI HIỆN TẠI ===
Cái nào rẻ nhất?

QUY TẮC BỔ SUNG:
- Trả lời nhất quán với lịch sử hội thoại ở trên
- Nếu thấy thiếu thông tin (điểm đi, tháng, số người), hỏi tự nhiên 1 câu
- KHÔNG hỏi lại thông tin đã có trong slots
- Sử dụng slots đã biết để lọc kết quả phù hợp hơn
```

---

## 5. LƯU LỊCH SỬ CHAT — KỂ CẢ CHƯA ĐĂNG NHẬP

### 5.1 Vấn đề: sessionId reset mỗi lần F5

**Code hiện tại** (ChatbotWidget.jsx dòng 120):
```jsx
const [sessionId] = useState(`session_${Date.now()}`);
// ← TẠO MỚI MỖI LẦN RENDER → mất hết lịch sử khi F5 hoặc đóng tab
```

**Mục tiêu:** Khách chưa đăng nhập vẫn có thể:
1. Quay lại trang → vẫn thấy lịch sử chat của phiên hôm nay
2. Đóng tab → mở lại → vẫn thấy lịch sử trong vòng 24 giờ

### 5.2 Giải pháp: sessionId lưu localStorage + Redis TTL 24h

#### Frontend — ChatbotWidget.jsx

```jsx
// Thay thế dòng 120:
// const [sessionId] = useState(`session_${Date.now()}`);

const [sessionId] = useState(() => {
  const STORAGE_KEY = 'ft_chat_session_id';
  const TTL_KEY     = 'ft_chat_session_exp';

  const existing = localStorage.getItem(STORAGE_KEY);
  const expiry   = localStorage.getItem(TTL_KEY);

  // Nếu session còn hạn (< 24 giờ) → dùng lại
  if (existing && expiry && Date.now() < parseInt(expiry, 10)) {
    return existing;
  }

  // Tạo session mới
  const newId = `ft_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const expMs = Date.now() + 24 * 60 * 60 * 1000; // 24 giờ
  localStorage.setItem(STORAGE_KEY, newId);
  localStorage.setItem(TTL_KEY, String(expMs));
  return newId;
});
```

#### Frontend — Khôi phục lịch sử khi mở chat

```jsx
// Thêm state để lưu lịch sử
const [messages, setMessages] = useState([
  { id: 1, sender: 'bot', text: 'Xin chào! 👋 Mình là trợ lý du lịch ảo...', timestamp: new Date() }
]);

// Load lịch sử khi component mount
useEffect(() => {
  const loadHistory = async () => {
    if (!sessionId) return;
    try {
      const resp = await fetch(`/api/chatbot/history/${sessionId}`);
      if (resp.ok) {
        const data = await resp.json();
        if (data.messages && data.messages.length > 0) {
          // Thêm tin nhắn khôi phục phiên
          setMessages([
            { id: 0, sender: 'bot', text: 'Chào mừng bạn quay lại! 👋 Em nhớ cuộc trò chuyện trước của mình nhé.', timestamp: new Date() },
            ...data.messages.map((m, i) => ({
              id: i + 1,
              sender: m.role === 'user' ? 'user' : 'bot',
              text: m.content,
              timestamp: new Date(m.timestamp),
            }))
          ]);
        }
      }
    } catch (e) {
      // Lỗi không ảnh hưởng chức năng chính
      console.debug('Cannot load chat history:', e.message);
    }
  };

  loadHistory();
}, [sessionId]);
```

#### Backend — Endpoint lấy lịch sử

```java
// ChatbotController.java — thêm endpoint
@GetMapping("/history/{sessionId}")
public ResponseEntity<ChatHistoryResponse> getHistory(@PathVariable String sessionId) {
    ConversationState state = conversationStateService.get(sessionId);
    if (state == null || state.getHistory().isEmpty()) {
        return ResponseEntity.ok(ChatHistoryResponse.builder().messages(List.of()).build());
    }

    // Chỉ trả về tối đa 20 tin nhắn gần nhất
    List<Map<String,String>> history = state.getHistory();
    if (history.size() > 20) {
        history = history.subList(history.size() - 20, history.size());
    }

    return ResponseEntity.ok(ChatHistoryResponse.builder()
            .sessionId(sessionId)
            .messages(history)
            .build());
}
```

#### Lưu lịch sử vào Redis — ChatbotService

```java
public ChatMessageResponse handleUserMessage(ChatMessageRequest request) {
    String sessionId = request.getSessionId();

    // Load state từ Redis
    ConversationState state = conversationStateService.getOrCreate(sessionId);

    // ... existing RAG logic ...

    String reply = callGeminiAPI(prompt);

    // Lưu lịch sử vào state
    Map<String,Object> userTurn = Map.of(
        "role", "user",
        "content", request.getMessage(),
        "timestamp", Instant.now().toEpochMilli()
    );
    Map<String,Object> botTurn = Map.of(
        "role", "bot",
        "content", reply,
        "timestamp", Instant.now().toEpochMilli()
    );
    state.getHistory().add(userTurn);
    state.getHistory().add(botTurn);

    // Giữ tối đa 30 lượt (60 entry) gần nhất — tránh prompt quá dài
    if (state.getHistory().size() > 60) {
        state.setHistory(new ArrayList<>(state.getHistory().subList(
            state.getHistory().size() - 60, state.getHistory().size()
        )));
    }

    // Lưu ngược vào Redis với TTL 24 giờ (để khách quay lại còn thấy)
    conversationStateService.save(sessionId, state, 24, TimeUnit.HOURS);

    return buildResponse(reply, suggestions, quickActions, sessionId);
}
```

### 5.3 Khi User Đăng Nhập — Merge Session

```jsx
// ChatbotWidget.jsx — thêm auth integration
const { user } = useSelector(state => state.auth); // Redux

// Khi user đăng nhập, gửi kèm userId để backend merge sessions
body: JSON.stringify({
  message: userMessage.text,
  sessionId: sessionId,
  userId: user?.id ?? null,  // ← thay null bằng userId thực tế
})
```

```java
// ChatbotService.java — khi userId không null, load profile
public ChatMessageResponse handleUserMessage(ChatMessageRequest request) {
    String sessionId = request.getSessionId();
    ConversationState state = conversationStateService.getOrCreate(sessionId);

    // Nếu user đăng nhập, enrich state với thông tin user
    if (request.getUserId() != null && state.getUserInfo() == null) {
        try {
            UserProfileDTO profile = iamFeignClient.getUserProfile(request.getUserId());
            state.setUserInfo(Map.of(
                "name",  profile.getFullName(),
                "phone", profile.getPhone(),
                "email", profile.getEmail()
            ));
            // Bot không cần hỏi thêm tên/SĐT khi user đã đăng nhập
        } catch (Exception e) {
            log.warn("Cannot load user profile: {}", e.getMessage());
        }
    }
    // ...
}
```

### 5.4 Bảng quyết định lưu lịch sử

| Trạng thái | sessionId | Lưu ở đâu | TTL | Ghi chú |
|---|---|---|---|---|
| Chưa đăng nhập, lần đầu | `ft_xxx_yyy` (tạo mới) | localStorage + Redis | 24h | Tạo session mới |
| Chưa đăng nhập, quay lại | `ft_xxx_yyy` (từ localStorage) | Redis (load lại) | Reset về 24h | Khôi phục lịch sử |
| Đăng nhập | `ft_xxx_yyy` + `userId` | Redis (enriched) | 24h | Pre-fill tên/SĐT khi booking |
| Session hết hạn (>24h) | Tạo `ft_new_zzz` | localStorage + Redis | 24h | Bắt đầu lại |

---

## 6. LỘ TRÌNH TRIỂN KHAI 4 PHASE

### ═══ PHASE 1: Quick Wins (1 tuần) ═══

**Mục tiêu: Kích hoạt những tính năng đã code, bảo mật cơ bản**

#### 1.1 Backend — analytics-service

| Task | File | Thay đổi |
|---|---|---|
| Bổ sung `departureDates[]` vào TourSuggestion | `ChatbotService.java` | `buildTourSuggestions()` thêm list ngày |
| Add `tourCode` vào TourSuggestion DTO | `ChatMessageResponse.java` | Thêm field `tourCode` |
| imageUrl fallback | `ChatbotService.java` | Kiểm tra null → fallback URL |
| Chuyển API keys ra env | `application.yml`, `docker-compose.yml` | Xóa hardcode |
| Thêm endpoint feedback | `ChatbotController.java` | `POST /api/chatbot/feedback` |
| Tạo bảng chatbot_feedback | SQL migration | Schema |

#### 1.2 Frontend — ChatbotWidget.jsx

| Task | Thay đổi |
|---|---|
| **Bỏ comment tour cards** (dòng 326-360) | Uncomment + chỉnh CSS |
| **Bỏ comment quick actions** | Uncomment |
| **Thêm departure dates buttons** | Render `tour.departureDates` dưới card |
| **Welcome chips** | 4 nút gợi ý khi `messages.length === 1` |
| **Like/Dislike feedback** | Nút 👍/👎 dưới mỗi bot message |
| **sessionId persist localStorage** | Thay dòng 120 (như trên) |
| **Fix URL hardcode** | Dùng `/api/chatbot/chat` thay `http://localhost:8080/...` |

#### 1.3 Sửa bug slot khi hủy booking

```java
// BookingServiceImpl.java — thêm increaseSlots() khi hủy
public void cancelBooking(Integer bookingId) {
    Booking booking = findById(bookingId);
    // ... existing cancel logic
    tourCatalogFeignClient.increaseSlots(booking.getDepartureId(), booking.getNumberOfPeople()); // THÊM
    publishDepartureSlotChanged(booking.getDepartureId()); // THÊM — trigger re-sync Pinecone
}
```

---

### ═══ PHASE 2: Redis + Multi-turn (2 tuần) ═══

**Mục tiêu: Chatbot nhớ ngữ cảnh, khôi phục lịch sử khi quay lại**

#### 2.1 Dependencies

```xml
<!-- analytics-service/pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

#### 2.2 Files cần tạo/sửa

| File | Hành động | Mô tả |
|---|---|---|
| `ConversationState.java` | TẠO MỚI | POJO: history, slots, stage, lastTourIds, userInfo |
| `ConversationStateService.java` | TẠO MỚI | Redis get/save/refresh TTL |
| `ChatbotService.java` | SỬA | Load state → build prompt với history → save state |
| `ChatbotController.java` | SỬA | `GET /api/chatbot/history/{sessionId}` |
| `ChatbotWidget.jsx` | SỬA | sessionId từ localStorage + load history khi mount |
| `application.yml` | SỬA | Thêm `spring.data.redis.host: ${REDIS_HOST:localhost}` |
| `docker-compose.yml` | SỬA | Thêm `REDIS_HOST=redis` cho analytics-service |

#### 2.3 docker-compose.yml — analytics-service section

```yaml
analytics-service:
  environment:
    - REDIS_HOST=redis
    - GEMINI_API_KEY=${GEMINI_API_KEY}
    - PINECONE_API_KEY=${PINECONE_API_KEY}
    - PINECONE_HOST=${PINECONE_HOST}
  depends_on:
    redis:
      condition: service_healthy
```

---

### ═══ PHASE 3: RabbitMQ Event-Driven Sync (2 tuần) ═══

**Mục tiêu: Pinecone cập nhật trong 5 phút khi data thay đổi**

#### 3.1 Files cần tạo/sửa

**shared-library** (DTO dùng chung):
| File | Hành động |
|---|---|
| `ChatbotSyncEvent.java` | TẠO MỚI — event object |

**tour-catalog-service**:
| File | Hành động |
|---|---|
| `TourService.java` | SỬA — thêm `publishChatbotSyncEvent()` sau create/update tour |
| `ReviewService.java` | SỬA — publish sau add review |
| `DepartureService.java` | SỬA — publish sau thêm/update departure |
| `ChatbotSyncController.java` | TẠO MỚI — `GET /api/tours/chatbot-sync/by-ids`, `GET /api/reviews/chatbot-sync/by-ids`, `GET /api/tours/chatbot-sync/departure/{id}` |

**booking-service**:
| File | Hành động |
|---|---|
| `BookingServiceImpl.java` | SỬA — publish sau create/cancel booking |
| `CouponService.java` | SỬA — publish sau create coupon |

**analytics-service**:
| File | Hành động |
|---|---|
| `config/AnalyticsRabbitMQConfig.java` | TẠO MỚI — queue + binding |
| `listener/ChatbotSyncEventListener.java` | TẠO MỚI — `@RabbitListener` + pending sets |
| `scheduler/ChatbotDebounceScheduler.java` | TẠO MỚI — `@Scheduled(fixedDelay=300_000)` flush |
| `service/VectorSyncService.java` | SỬA — thêm `syncToursByIds()`, `syncDeparturesByIds()`, `syncReviewsByIds()` |
| `feign/TourCatalogFeignClient.java` | SỬA — thêm endpoints by-ids |

---

### ═══ PHASE 4: Booking Flow + Order Lookup (3 tuần) ═══

**Mục tiêu: Đặt tour trong chat + tra cứu đơn hàng**

#### 4.1 Booking Flow State Machine

```
Stage: GREETING
  User: "tìm tour Đà Nẵng tháng 7"
  → Bot: [tour cards] + "Quý khách thích tour nào?"
  → Stage: SEARCHING

Stage: SEARCHING
  User: "đặt tour thứ 2" hoặc bấm "Đặt tour này"
  → Bot: "Quý khách đi bao nhiêu người?"
  → Stage: COLLECTING_BOOKING_INFO

Stage: COLLECTING_BOOKING_INFO
  Bot hỏi từng thiếu: số người → họ tên → SĐT → email
  Nếu user đã đăng nhập: pre-fill từ profile → bỏ qua câu hỏi
  Khi đủ thông tin:
  → Bot build BookingConfirmCard
  → Stage: CONFIRMING

Stage: CONFIRMING
  User: "Xác nhận" hoặc bấm nút
  → analytics-service Feign → booking-service POST /api/bookings
  → Bot: "Đặt thành công! Mã: FT26XXXXX\n💳 [Link thanh toán]"
  → Stage: BOOKING_CONFIRMED

Stage: BOOKING_CONFIRMED
  Cho phép đặt tiếp hoặc tra cứu đơn
```

#### 4.2 Files cần tạo/sửa

**analytics-service**:
| File | Hành động |
|---|---|
| `feign/ChatbotBookingFeignClient.java` | TẠO MỚI — POST /api/bookings |
| `feign/ChatbotIamFeignClient.java` | TẠO MỚI — GET /api/users/{id}/profile |
| `ChatbotService.java` | SỬA — booking state machine |
| `ChatbotController.java` | SỬA — POST /api/chatbot/booking, GET /api/chatbot/order-lookup |

**Frontend**:
| File | Hành động |
|---|---|
| `BookingConfirmCard.jsx` | TẠO MỚI — component xác nhận đặt tour |
| `ChatbotWidget.jsx` | SỬA — render BookingConfirmCard, userId từ auth |

---

## 7. DANH SÁCH FILE CẦN THAY ĐỔI

### 7.1 PHASE 1 — Files thay đổi

| Service | File | Hành động | Mô tả |
|---|---|---|---|
| `analytics-service` | `src/.../service/ChatbotService.java` | SỬA | Thêm `departureDates[]`, `tourCode` vào TourSuggestion builder |
| `analytics-service` | `src/.../dto/ChatMessageResponse.java` | SỬA | Thêm field `tourCode`, `departureDates` vào TourSuggestion |
| `analytics-service` | `src/.../controller/ChatbotController.java` | SỬA | Thêm POST `/feedback`, GET `/history/{sessionId}` |
| `analytics-service` | `src/main/resources/application.yml` | SỬA | Xóa hardcode API keys → dùng `${ENV_VAR}` |
| `booking-service` | `src/.../service/impl/BookingServiceImpl.java` | SỬA | Thêm `increaseSlots()` khi cancel |
| `docker-compose.yml` | Root | SỬA | Thêm env vars GEMINI_API_KEY, PINECONE_API_KEY cho analytics-service |
| Frontend | `src/.../ChatbotWidget/ChatbotWidget.jsx` | SỬA | Uncomment tour cards, welcome chips, fix sessionId, fix URL |
| Frontend | `src/.../ChatbotWidget/ChatbotWidget.module.scss` | SỬA | CSS tour card responsive, departure date chips, welcome chips |

### 7.2 PHASE 2 — Files thay đổi

| Service | File | Hành động |
|---|---|---|
| `analytics-service` | `pom.xml` | SỬA: thêm spring-boot-starter-data-redis |
| `analytics-service` | `src/.../entity/ConversationState.java` | TẠO MỚI |
| `analytics-service` | `src/.../service/ConversationStateService.java` | TẠO MỚI |
| `analytics-service` | `src/.../service/ChatbotService.java` | SỬA: load/save state, build prompt với history |
| `analytics-service` | `src/.../controller/ChatbotController.java` | SỬA: GET /history/{sessionId} |
| `analytics-service` | `src/main/resources/application.yml` | SỬA: thêm redis config |
| `docker-compose.yml` | Root | SỬA: thêm REDIS_HOST, depends_on redis cho analytics-service |
| Frontend | `ChatbotWidget.jsx` | SỬA: sessionId từ localStorage, load history on mount |

### 7.3 PHASE 3 — Files thay đổi

| Service | File | Hành động |
|---|---|---|
| `shared-library` | `src/.../event/ChatbotSyncEvent.java` | TẠO MỚI |
| `tour-catalog-service` | `src/.../service/TourService.java` | SỬA: publish event sau create/update |
| `tour-catalog-service` | `src/.../service/ReviewService.java` | SỬA: publish event sau add review |
| `tour-catalog-service` | `src/.../service/DepartureService.java` | SỬA: publish event |
| `tour-catalog-service` | `src/.../controller/ChatbotSyncController.java` | TẠO MỚI: endpoints by-ids |
| `booking-service` | `src/.../service/impl/BookingServiceImpl.java` | SỬA: publish event sau create/cancel |
| `analytics-service` | `src/.../config/AnalyticsRabbitMQConfig.java` | TẠO MỚI |
| `analytics-service` | `src/.../listener/ChatbotSyncEventListener.java` | TẠO MỚI |
| `analytics-service` | `src/.../scheduler/ChatbotDebounceScheduler.java` | TẠO MỚI |
| `analytics-service` | `src/.../service/VectorSyncService.java` | SỬA: thêm syncByIds methods |
| `analytics-service` | `src/.../feign/TourCatalogFeignClient.java` | SỬA: thêm by-ids endpoints |

### 7.4 PHASE 4 — Files thay đổi

| Service | File | Hành động |
|---|---|---|
| `analytics-service` | `src/.../feign/ChatbotBookingFeignClient.java` | TẠO MỚI |
| `analytics-service` | `src/.../feign/ChatbotIamFeignClient.java` | TẠO MỚI |
| `analytics-service` | `src/.../service/ChatbotService.java` | SỬA: booking state machine |
| `analytics-service` | `src/.../controller/ChatbotController.java` | SỬA: booking + order-lookup endpoints |
| Frontend | `src/.../ChatbotWidget/BookingConfirmCard.jsx` | TẠO MỚI |
| Frontend | `ChatbotWidget.jsx` | SỬA: BookingConfirmCard, userId từ auth |

---

## 8. TÓM TẮT KIẾN TRÚC MỤC TIÊU (SAU PHASE 4)

```
┌─────────────────────────────────────────────────────────────────────┐
│             FUTURE TRAVEL CHATBOT — TARGET ARCHITECTURE             │
└─────────────────────────────────────────────────────────────────────┘

[tour-catalog-service]
  Tạo/sửa tour/review/departure
    → publish ChatbotSyncEvent("chatbot.sync.tour/review/departure")
        │
        │ RabbitMQ exchange: tourism.events
        ▼
[chatbot.sync.queue] ← analytics-service lắng nghe
  ChatbotSyncEventListener.onEvent()
    → pendingTourIds.add(id)  // debounce
    → pendingDeptIds.add(id)
        │
        │ @Scheduled(fixedDelay=300_000) — mỗi 5 phút
        ▼
  ChatbotDebounceScheduler.flushPending()
    → Feign → tour-catalog: getToursByIds(pendingIds)
    → VectorSyncService.syncToursByIds() → Pinecone upsert
        │
        │ (backup) @Scheduled cron 2AM → full sync
        ▼
[Pinecone Vector DB]
  Index: tourism-chatbot (1024d, cosine)
  5 namespaces: TOUR_SUMMARY, TOUR_DEPARTURE, LOCATION, REVIEW, COUPON
  Data độ trễ tối đa: 5 phút (vs 16 giờ hiện tại)

─────────────────────────────────────────────────────────────────────

[ChatbotWidget.jsx]
  sessionId từ localStorage (persist 24h)
  userId từ Redux auth (null nếu chưa login)

  Mở chat → GET /api/chatbot/history/{sessionId}
    → Hiển thị lịch sử gần đây (nếu có)

  POST /api/chatbot/chat { message, sessionId, userId }
        │
        ▼
[analytics-service :8087]
  ChatbotService.handleUserMessage()
    ├── Load ConversationState từ Redis (key: chatbot:session:{id})
    ├── Nếu userId ≠ null → Feign → iam-service → load profile
    ├── VectorService.searchSimilar() → Pinecone
    ├── buildPromptWithHistory(message, context, state.history)
    ├── callGeminiAPI(prompt) → trả lời có ngữ cảnh
    ├── buildTourSuggestions() với departureDates[]
    ├── Booking state machine (nếu stage ≠ SEARCHING)
    ├── Update state.history + state.slots + state.stage
    └── Save ConversationState → Redis TTL 24h

[Redis]
  chatbot:session:{sessionId}
    TTL: 24 giờ (sliding window)
    history: [{role, content, timestamp}]
    slots: {destination, month, people...}
    stage: GREETING|COLLECTING|SEARCHING|CONFIRMING|CONFIRMED
    userInfo: {name, phone, email} (nếu đăng nhập)

[booking-service]
  Tạo/hủy booking
    → publish ChatbotSyncEvent("chatbot.sync.departure", SLOT_CHANGED)
    → increaseSlots() khi hủy (fix bug hiện tại)
```

---

## 9. BẢNG ƯU TIÊN TỔNG HỢP

| # | Task | Phase | Effort | Impact | Cần gì |
|---|---|---|---|---|---|
| 1 | Uncomment tour cards FE | 1 | 2h | 🔥 Rất cao | Chỉ cần xóa `{/* */}` + CSS |
| 2 | sessionId persist localStorage | 1 | 1h | 🔥 Cao | Thay 1 dòng JS |
| 3 | Fix URL hardcode localhost | 1 | 30m | 🔒 Security | Thay 1 dòng |
| 4 | API keys ra env vars | 1 | 2h | 🔒 Security | docker-compose.yml |
| 5 | Fix bug slot khi hủy | 1 | 2h | Cao | BookingServiceImpl |
| 6 | Thêm departureDates[] tour card | 1 | 4h | Cao | BE + FE |
| 7 | Welcome chips | 1 | 2h | Vừa | FE only |
| 8 | Like/Dislike feedback | 1 | 4h | Vừa | FE + BE endpoint |
| 9 | Redis + ConversationState | 2 | 3 ngày | 🔥 Rất cao | pom.xml + 2 classes mới |
| 10 | Load history khi mở chat | 2 | 1 ngày | Cao | FE + BE endpoint |
| 11 | Slot-filling qua prompt | 2 | 1 ngày | Rất cao | Chỉ cần cập nhật prompt |
| 12 | RabbitMQ event-driven sync | 3 | 5 ngày | Cao | 10+ files thay đổi |
| 13 | Debounce 5 phút flush | 3 | 1 ngày | Cao | ChatbotDebounceScheduler |
| 14 | Booking flow state machine | 4 | 7 ngày | 🔥 Rất cao | 5+ classes mới |
| 15 | BookingConfirmCard UI | 4 | 2 ngày | Rất cao | FE component |
| 16 | Order lookup | 4 | 2 ngày | Vừa | Feign + controller |
| 17 | Auth integration (userId) | 4 | 1 ngày | Cao | Redux → request |

---

*Kế hoạch dựa trên source code thực tế + screenshots Tripi chatbot (travel.com.vn) ngày 2026-05-17.*
*Tác giả: AI Analysis — Future Travel Dev Team*

---

## 8. BOOKING-IN-CHAT FLOW CHI TIẾT (TRIPI-STYLE)

### 8.0 Phân tích code thực tế booking-service + payment-service

> ⚠️ **ĐÃ ĐỌC CODE THỰC TẾ** — Các section dưới đây dùng đúng tên class/field/endpoint có sẵn trong codebase.

#### 8.0.1 Booking Entity (đã có)
```java
// Booking.java — các field quan trọng với chatbot
bookingCode       String     // "BK" + UUID 8 ký tự, tự sinh @PrePersist
userId            Integer    // NULL nếu guest ← ĐÃ HỖ TRỢ GUEST BOOKING
departureId       Integer    // FK sang tour-catalog-service
contactFullName   String     // tên người đặt (notBlank)
contactPhone      String     // SĐT (notBlank)
contactEmail      String     // email (notBlank, @Email)
contactAddress    String     // địa chỉ (notBlank) ← chatbot cần hỏi thêm!
totalPassengers   Integer
subtotalPrice     BigDecimal
couponDiscount    BigDecimal
paidByCoin        BigDecimal
totalPrice        BigDecimal
bookingStatus     BookingStatus (enum)
passengers        List<BookingPassenger>
```

**BookingStatus enum:**
```
PENDING_PAYMENT      ← vừa tạo, chưa thanh toán
OVERDUE_PAYMENT      ← hết deadline chưa trả
PENDING_CONFIRMATION ← đã trả, chờ admin xác nhận
PAID                 ← admin đã xác nhận
CANCELLED            ← đã hủy
PENDING_REVIEW       ← đã đi xong, chờ review
REVIEWED             ← đã review
PENDING_REFUND       ← yêu cầu hoàn tiền
```

#### 8.0.2 BookingPassenger Entity (đã có)
```java
// BookingPassenger.java
fullName       String
gender         String           // "MALE" | "FEMALE"
dateOfBirth    LocalDate        // bắt buộc!
passengerType  PassengerType    // ADULT, CHILD, TODDLER, INFANT
basePrice      BigDecimal
requiresSingleRoom Boolean
singleRoomSurcharge BigDecimal
```
> ⚠️ **QUAN TRỌNG cho chatbot:** `dateOfBirth` là bắt buộc cho từng hành khách → chatbot cần thu thập ngày sinh. Đây là điểm phức tạp nhất.

#### 8.0.3 CreateBookingRequest (đã có)
```java
// CreateBookingRequest.java — request gửi đến POST /api/bookings/create
Integer              departureId
Integer              userId          // NULL nếu guest ✅
String               contactFullName
String               contactPhone
String               contactEmail
String               contactAddress  // bắt buộc
String               customerNote
List<PassengerRequest> passengers    // PHẢI có ít nhất 1 passenger
List<String>         couponCode      // optional
Integer              pointsUsed      // optional, 0 nếu không dùng
```
```java
// PassengerRequest (nested class)
String  fullName
String  gender        // "MALE" | "FEMALE"
String  dateOfBirth   // "YYYY-MM-DD"
String  type          // "ADULT" | "CHILD" | "TODDLER" | "INFANT"
boolean singleRoom
```

#### 8.0.4 CreateBookingResponse (đã có)
```java
// POST /api/bookings/create → trả về:
String     bookingCode    // "BKxxxxxxxx"
Integer    bookingId
BigDecimal totalPrice
String     status         // "PENDING_PAYMENT"
```
> ✅ Sau khi tạo booking thành công, chatbot cần tạo tiếp **payment URL** từ payment-service.

#### 8.0.5 GET /api/bookings/order?tourCode=X&departureId=Y (đã có)
```java
// BookingOrderResponse — dùng để lấy giá trước khi đặt
tourId, tourCode, tourName, image
availableSlots
adultPrice, childPrice, toddlerPrice, infantPrice, singleRoomSurcharge
outboundFlight, inboundFlight    // FlightInfo (transportCode, departTime, arrivalTime, vehicleType...)
departureCoupon, globalCoupons   // coupon available
```

#### 8.0.6 GET /api/bookings/payment/{bookingCode} (đã có)
```java
// BookingPaymentDetailResponse
bookingId, bookingCode, createdDate, status
originalPrice, paidAmount, remainingAmount, paymentDeadline
appliedCouponCodes
tourName, tourCode, tourImage, duration
outboundTransport, inboundTransport
passengers: [{fullName, dateOfBirth, gender, type, singleRoom}]
```

#### 8.0.7 BookingResponse (đã có — dùng cho order lookup)
```java
// BookingResponse — trả về đầy đủ nhất
bookingID, bookingCode, bookingDate, bookingStatus
contactEmail, contactFullName, contactPhone, contactAddress
totalPassengers, subtotalPrice, couponDiscount, paidByCoin, totalPrice
departureID, departureDate, tourID, tourCode, tourName, image, duration
// Payment info (từ payment-service):
paymentID, amount, timeLimit, bank, accountNumber, accountName
passengers: List<BookingPassengerResponse>
refundBank, refundAccountNumber, refundAccountName, refundStatus
coinRefundStatus
```

#### 8.0.8 Payment endpoints (đã có)
```
POST /api/payment/vnpay/create   { bookingCode, amount, orderInfo, locale }
                                  → { paymentUrl }

POST /api/payment/payos/create   { bookingCode, amount, description, returnUrl, cancelUrl }
                                  → { checkoutUrl, transactionId, qrCode }

GET  /api/payment/check-status/{orderCode}  → PaymentStatusResponse

GET  /api/payment/by-booking/{bookingId}    → PaymentInfoResponse
     { paymentID, amount, timeLimit, paymentMethod, status, bank, accountNumber, accountName }
```

#### 8.0.9 Kết luận: Chatbot chỉ cần thêm 2 endpoint mới vào booking-service
```
1. GET  /api/bookings/chatbot/lookup?code=BKxxxxxxxx
   → Trả BookingResponse đầy đủ (không cần xác thực name — chỉ cần bookingCode)
   → Hoặc dùng luôn GET /api/bookings/payment/{bookingCode} đã có!

2. Không cần POST endpoint mới:
   → Dùng luôn POST /api/bookings/create đã có (userId = null → guest ✅)
   → Sau đó gọi POST /api/payment/payos/create hoặc /vnpay/create
```

---

### 8.1 Tổng quan flow — quan sát từ screenshots thực tế

Từ screenshots `130705.png → 130953.png`, Tripi thực hiện booking trong chat theo đúng trình tự:

```
[Bước 1]  User: hỏi về tour / tìm tour
[Bước 2]  Bot: hiển thị tour cards → hỏi "Quý khách thích tour nào?"
[Bước 3]  User: chọn tour (text hoặc bấm nút)
[Bước 4]  Bot: hỏi số người lớn / trẻ em
[Bước 5]  Bot: hỏi họ tên + số điện thoại (1 tin nhắn)
[Bước 6]  Bot: hiện BookingConfirmCard đầy đủ → nút [Xác nhận] [Đặt lại]
[Bước 7]  User: bấm Xác nhận
[Bước 8]  Bot: "Đặt thành công! Mã đặt chỗ: FT26XXXXX\n💳 Link thanh toán: ..."
```

### 8.2 State Machine — ConversationStage enum

```java
// ConversationState.java — stage field
public enum ConversationStage {
    GREETING,                // Lần đầu mở chat
    SEARCHING,               // Đã hiện tour cards, đang chờ user chọn
    COLLECTING_TOUR_CHOICE,  // Đang xác nhận user muốn tour nào
    COLLECTING_PASSENGERS,   // Đang hỏi số người
    COLLECTING_CONTACT,      // Đang hỏi họ tên + SĐT
    AWAITING_CONFIRMATION,   // Đã hiện BookingConfirmCard, chờ xác nhận
    BOOKING_CONFIRMED,       // Đặt thành công
    ORDER_LOOKUP,            // Đang tra cứu đơn hàng
}
```

### 8.3 Luồng đầy đủ kèm tin nhắn bot bằng tiếng Việt

#### STAGE: GREETING / SEARCHING

```
User nhắn: "tìm tour Đà Lạt tháng 7, 2 người"

→ Bot: (kết quả RAG từ Pinecone)
  "Em tìm được 3 tour Đà Lạt phù hợp cho 2 người tháng 7 ạ! 🌿

  [TourCard: Tour Đà Lạt 3N2Đ - DLADT001 - từ 1.990.000đ]
  [TourCard: Tour Đà Lạt Thác Voi 4N3Đ - DLADT002 - từ 2.490.000đ]
  [TourCard: Tour Đà Lạt Langbiang 5N4Đ - DLADT003 - từ 3.190.000đ]

  Quý khách thích tour nào để em tư vấn thêm và tiến hành đặt chỗ ạ?"

  → state.stage = SEARCHING
  → state.slots.destination = "Đà Lạt"
  → state.slots.travelMonth = "7"
  → state.slots.numberOfPeople = 2
  → state.lastTourIds = [tourId1, tourId2, tourId3]
```

#### STAGE: SEARCHING → COLLECTING_PASSENGERS

Trigger khi user nhắn bất kỳ:
- "đặt tour đầu tiên" / "đặt tour 1" / "cái thứ 2" / "tour DLADT002"
- Bấm nút **[Đặt tour này]** trên TourCard (gửi message ẩn: `__BOOK_TOUR_ID__:123`)

```
→ Bot:
  "Tuyệt vời! Em sẽ đặt chỗ cho tour **Đà Lạt Thác Voi 4N3Đ** nhé! 🎉

  Quý khách vui lòng cho em biết:
  - 👤 Số người lớn (từ 12 tuổi trở lên):
  - 👶 Số trẻ em (dưới 12 tuổi, nếu có):"

  → state.stage = COLLECTING_PASSENGERS
  → state.slots.selectedTourId   = 456
  → state.slots.selectedTourName = "Đà Lạt Thác Voi 4N3Đ"
  → state.slots.selectedDepartureId = null  ← chưa chọn ngày cụ thể
```

> **Lưu ý:** Nếu user đã nói "2 người" trong câu đầu → bot biết số người rồi, bỏ qua bước hỏi số người, nhảy thẳng sang COLLECTING_CONTACT.

#### STAGE: COLLECTING_PASSENGERS → COLLECTING_CONTACT

User trả lời: "2 người lớn, 1 trẻ em"

```
→ Bot:
  "Cảm ơn Quý khách! Để em giữ chỗ, vui lòng cung cấp:
  - 📛 Họ tên đầy đủ người đặt:
  - 📞 Số điện thoại liên hệ:"

  → state.stage = COLLECTING_CONTACT
  → state.slots.adults   = 2
  → state.slots.children = 1
```

> **Nếu user đã đăng nhập** (userId ≠ null) và profile có sẵn name + phone:
> Bot bỏ qua bước này, hiện luôn BookingConfirmCard với thông tin đã biết.
> Bot nhắn: *"Em đã có thông tin của Quý khách (Nguyễn Văn A / 0901234567). Xin xác nhận đặt tour nhé!"*

#### STAGE: COLLECTING_CONTACT → AWAITING_CONFIRMATION

User trả lời: "Nguyễn Văn A, 0901234567"

```
→ Bot: hiển thị BookingConfirmCard (xem thiết kế 8.4 bên dưới)

  Kèm tin nhắn:
  "Em đã tổng hợp thông tin đặt tour của Quý khách. Vui lòng kiểm tra và xác nhận ạ!"

  → state.stage = AWAITING_CONFIRMATION
  → state.slots.contactName  = "Nguyễn Văn A"
  → state.slots.contactPhone = "0901234567"
```

#### STAGE: AWAITING_CONFIRMATION → BOOKING_CONFIRMED

User bấm **[Xác nhận đặt tour]** hoặc nhắn "xác nhận" / "đồng ý" / "ok đặt"

```java
// ChatbotService.java — xử lý xác nhận
private ChatMessageResponse processBookingConfirmation(ConversationState state) {
    BookingSlots s = state.getSlots();

    // Gọi Feign đến booking-service
    CreateBookingRequest req = CreateBookingRequest.builder()
        .departureId(s.getSelectedDepartureId())
        .numberOfAdults(s.getAdults())
        .numberOfChildren(s.getChildren())
        .contactName(s.getContactName())
        .contactPhone(s.getContactPhone())
        .contactEmail(s.getContactEmail())
        .note("Đặt qua chatbot")
        .build();

    BookingResponse booking = chatbotBookingFeignClient.createBooking(
        state.getUserId(),   // null nếu chưa đăng nhập → guest booking
        req
    );

    String successMsg = String.format(
        "🎉 Đặt tour thành công! Em xác nhận thông tin của Quý khách:\n\n" +
        "📋 **Mã đặt chỗ:** %s\n" +
        "🏞️ **Tour:** %s\n" +
        "📅 **Ngày khởi hành:** %s\n" +
        "👥 **Số khách:** %d người lớn%s\n" +
        "💰 **Tổng tiền:** %s\n\n" +
        "💳 **Thanh toán ngay:** %s\n\n" +
        "Cảm ơn Quý khách đã tin tưởng Future Travel! " +
        "Em sẽ liên hệ xác nhận trong vòng 24h ạ 🙏",
        booking.getBookingCode(),
        booking.getTourName(),
        booking.getDepartureDate(),
        s.getAdults(),
        s.getChildren() > 0 ? " + " + s.getChildren() + " trẻ em" : "",
        formatCurrency(booking.getTotalAmount()),
        booking.getPaymentUrl()
    );

    state.setStage(ConversationStage.BOOKING_CONFIRMED);
    state.setLastBookingCode(booking.getBookingCode());

    return buildTextResponse(successMsg, List.of(
        QuickAction.of("Đặt tour khác", "tìm tour"),
        QuickAction.of("Tra cứu đơn hàng", "tra cứu đơn " + booking.getBookingCode()),
        QuickAction.of("Xem tour khác", "xem thêm tour")
    ));
}
```

Tin nhắn bot sau khi xác nhận:
```
🎉 Đặt tour thành công! Em xác nhận thông tin của Quý khách:

📋 Mã đặt chỗ: FT26A00123
🏞️ Tour: Đà Lạt Thác Voi 4N3Đ
📅 Ngày khởi hành: 14/07/2026
👥 Số khách: 2 người lớn + 1 trẻ em
💰 Tổng tiền: 7.470.000 ₫

💳 Thanh toán ngay: https://pay.futuretravel.vn/FT26A00123

Cảm ơn Quý khách đã tin tưởng Future Travel!
Em sẽ liên hệ xác nhận trong vòng 24h ạ 🙏
```

#### STAGE: AWAITING_CONFIRMATION → SEARCHING (user đổi ý)

User bấm **[Đặt lại]** hoặc nhắn "hủy" / "đổi tour" / "thôi không đặt nữa"

```
→ Bot:
  "Không sao ạ! Quý khách muốn chọn lại tour hay tìm tour khác?
  Em có thể tìm thêm tour phù hợp cho Quý khách ạ."

  → state.stage = SEARCHING (reset, giữ slots destination/month/people)
  → Xóa: selectedTourId, selectedDepartureId, contactName, contactPhone
```

### 8.4 BookingConfirmCard — Thiết kế UI (Tripi-style)

Dựa trên screenshot `130904.png`, BookingConfirmCard hiển thị:

```
┌──────────────────────────────────────────────────────────┐
│  🗓️  XÁC NHẬN ĐẶT TOUR                                  │
├──────────────────────────────────────────────────────────┤
│  [Ảnh thumbnail tour - 80px height, full-width]          │
├──────────────────────────────────────────────────────────┤
│  📍 Đà Lạt Thác Voi 4N3Đ                                │
│  🏷️ Mã tour: DLADT002                                    │
│  📅 Khởi hành: 14/07/2026                               │
│  🚌 Từ: TP. Hồ Chí Minh                                 │
│  ⏱️ Thời gian: 4 ngày 3 đêm                             │
├──────────────────────────────────────────────────────────┤
│  👥 THÔNG TIN HÀNH KHÁCH                                 │
│     Người lớn: 2 × 2.490.000đ = 4.980.000đ             │
│     Trẻ em:   1 × 1.245.000đ = 1.245.000đ              │
│  ──────────────────────────────────────────────          │
│  💰 TỔNG TIỀN:                     6.225.000 ₫           │
├──────────────────────────────────────────────────────────┤
│  📞 THÔNG TIN LIÊN HỆ                                    │
│     Họ tên:  Nguyễn Văn A                               │
│     SĐT:    0901 234 567                                 │
├──────────────────────────────────────────────────────────┤
│  [✅ Xác nhận đặt tour]   [❌ Đặt lại]                  │
└──────────────────────────────────────────────────────────┘
```

#### Frontend — BookingConfirmCard.jsx

```jsx
// src/components/ChatbotWidget/BookingConfirmCard.jsx
import React from 'react';
import styles from './BookingConfirmCard.module.scss';

const BookingConfirmCard = ({ booking, onConfirm, onCancel }) => {
  const {
    tourName, tourCode, imageUrl,
    departureDate, departureCity, duration,
    adults, children, adultPrice, childPrice,
    contactName, contactPhone
  } = booking;

  const totalAdults   = adults * adultPrice;
  const totalChildren = children * childPrice;
  const totalAmount   = totalAdults + totalChildren;

  const fmt = (n) => n.toLocaleString('vi-VN') + ' ₫';

  return (
    <div className={styles.card}>
      <div className={styles.header}>🗓️ XÁC NHẬN ĐẶT TOUR</div>

      {imageUrl && (
        <img src={imageUrl} alt={tourName} className={styles.tourImage} />
      )}

      <div className={styles.section}>
        <div className={styles.tourName}>📍 {tourName}</div>
        <div className={styles.detail}>🏷️ Mã tour: {tourCode}</div>
        <div className={styles.detail}>📅 Khởi hành: {departureDate}</div>
        <div className={styles.detail}>🚌 Từ: {departureCity}</div>
        <div className={styles.detail}>⏱️ {duration}</div>
      </div>

      <div className={styles.section}>
        <div className={styles.sectionTitle}>👥 THÔNG TIN HÀNH KHÁCH</div>
        <div className={styles.row}>
          <span>Người lớn: {adults} × {fmt(adultPrice)}</span>
          <span>{fmt(totalAdults)}</span>
        </div>
        {children > 0 && (
          <div className={styles.row}>
            <span>Trẻ em: {children} × {fmt(childPrice)}</span>
            <span>{fmt(totalChildren)}</span>
          </div>
        )}
        <div className={styles.totalRow}>
          <span>💰 TỔNG TIỀN:</span>
          <span className={styles.totalAmount}>{fmt(totalAmount)}</span>
        </div>
      </div>

      <div className={styles.section}>
        <div className={styles.sectionTitle}>📞 THÔNG TIN LIÊN HỆ</div>
        <div className={styles.detail}>Họ tên: {contactName}</div>
        <div className={styles.detail}>SĐT: {contactPhone}</div>
      </div>

      <div className={styles.actions}>
        <button className={styles.confirmBtn} onClick={onConfirm}>
          ✅ Xác nhận đặt tour
        </button>
        <button className={styles.cancelBtn} onClick={onCancel}>
          ❌ Đặt lại
        </button>
      </div>
    </div>
  );
};

export default BookingConfirmCard;
```

#### Frontend — SCSS

```scss
// BookingConfirmCard.module.scss
.card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e8e8e8;
  overflow: hidden;
  margin: 8px 0;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
  max-width: 320px;
}
.header {
  background: linear-gradient(135deg, #1a73e8, #0d47a1);
  color: #fff;
  padding: 10px 14px;
  font-weight: 600;
  font-size: 13px;
}
.tourImage {
  width: 100%;
  height: 80px;
  object-fit: cover;
}
.section {
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f0;
}
.sectionTitle {
  font-weight: 600;
  font-size: 12px;
  color: #555;
  margin-bottom: 6px;
  text-transform: uppercase;
}
.tourName {
  font-weight: 600;
  font-size: 14px;
  color: #222;
  margin-bottom: 4px;
}
.detail {
  font-size: 12px;
  color: #555;
  margin: 2px 0;
}
.row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #555;
  margin: 2px 0;
}
.totalRow {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid #eee;
  font-weight: 600;
  font-size: 13px;
}
.totalAmount {
  color: #e53935;
}
.actions {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
}
.confirmBtn {
  flex: 1;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  &:hover { background: #1558b0; }
}
.cancelBtn {
  flex: 1;
  background: #f5f5f5;
  color: #555;
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 8px;
  font-size: 12px;
  cursor: pointer;
  &:hover { background: #eee; }
}
```

### 8.5 ChatbotWidget.jsx — tích hợp BookingConfirmCard

```jsx
// ChatbotWidget.jsx — thêm render BookingConfirmCard trong message list

import BookingConfirmCard from './BookingConfirmCard';

// Message object cần thêm type
// { id, sender, text, type, bookingData, tourSuggestions, quickActions, timestamp }

const renderMessage = (msg) => {
  if (msg.type === 'BOOKING_CONFIRM') {
    return (
      <BookingConfirmCard
        key={msg.id}
        booking={msg.bookingData}
        onConfirm={() => sendSystemMessage('__CONFIRM_BOOKING__')}
        onCancel={() => sendSystemMessage('__CANCEL_BOOKING__')}
      />
    );
  }
  // ... render text / tour cards bình thường
};

// Gửi message ẩn khi user bấm nút (không hiển thị trong chat)
const sendSystemMessage = (payload) => {
  // Gửi lên backend nhưng KHÔNG render trong chat UI
  fetch('/api/chatbot/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: payload,
      sessionId,
      userId: user?.id ?? null,
      isSystemMessage: true   // backend biết đây là action, không dùng Gemini
    })
  }).then(r => r.json()).then(handleBotResponse);
};

// Bấm "Đặt tour này" trên TourCard
const handleBookTour = (tourId, departureId) => {
  sendSystemMessage(`__BOOK_TOUR_ID__:${tourId}:${departureId}`);
};
```

### 8.6 Backend — ChatbotBookingFeignClient (dùng endpoint thực tế)

> Dùng lại endpoint đã có — **KHÔNG cần tạo endpoint mới** trong booking-service!

```java
// analytics-service/feign/ChatbotBookingFeignClient.java
// TÁCH RIÊNG với BookingFeignClient hiện tại (vốn dùng cho dashboard + coupon sync)
@FeignClient(name = "booking-service")
public interface ChatbotBookingFeignClient {

    /**
     * Lấy giá + slot + coupon trước khi đặt.
     * Endpoint đã có: GET /api/bookings/order?tourCode=X&departureId=Y
     */
    @GetMapping("/api/bookings/order")
    BookingOrderResponse getOrderInfo(
        @RequestParam("tourCode")    String  tourCode,
        @RequestParam("departureId") Integer departureId
    );

    /**
     * Tạo booking (guest OK — userId = null trong request body).
     * Endpoint đã có: POST /api/bookings/create
     * Trả về: bookingCode, bookingId, totalPrice, status="PENDING_PAYMENT"
     */
    @PostMapping("/api/bookings/create")
    CreateBookingResponse createBooking(@RequestBody CreateBookingRequest request);

    /**
     * Lấy thông tin booking để hiển thị sau khi tạo + cho order lookup.
     * Endpoint đã có: GET /api/bookings/payment/{bookingCode}
     * Trả về: BookingPaymentDetailResponse (đủ: tên tour, ngày, giá, passengers)
     */
    @GetMapping("/api/bookings/payment/{bookingCode}")
    BookingPaymentDetailResponse getBookingPaymentDetail(@PathVariable String bookingCode);

    /**
     * Order lookup đầy đủ (cần thêm endpoint mới vào booking-service — xem Section 9.3).
     * GET /api/bookings/chatbot/lookup?code=BKxxxxxxxx
     */
    @GetMapping("/api/bookings/chatbot/lookup")
    BookingResponse lookupByCode(@RequestParam("code") String bookingCode);
}
```

> **Import các class từ booking-service shared-library hoặc copy DTO sang analytics-service:**
> - `BookingOrderResponse` — copy hoặc tạo mirror DTO tương đương
> - `CreateBookingRequest` + `CreateBookingResponse` — copy
> - `BookingPaymentDetailResponse` — copy
> - `BookingResponse` — copy (dùng cho order lookup)

#### Payment Feign Client (thêm mới vào analytics-service)

```java
// analytics-service/feign/ChatbotPaymentFeignClient.java
@FeignClient(name = "payment-service")
public interface ChatbotPaymentFeignClient {

    /**
     * Tạo link thanh toán PayOS sau khi booking thành công.
     * POST /api/payment/payos/create
     * returnUrl + cancelUrl trỏ về frontend chatbot
     */
    @PostMapping("/api/payment/payos/create")
    PaymentUrlResponse createPayosPayment(@RequestBody PayosCreateRequest request);

    /**
     * Tạo link VNPay (alternative).
     * POST /api/payment/vnpay/create
     */
    @PostMapping("/api/payment/vnpay/create")
    PaymentUrlResponse createVnpayPayment(@RequestBody VnpayCreateRequest request);
}
```

**DTOs cần copy/mirror từ payment-service:**
```java
// PayosCreateRequest — copy từ payment-service
@Data public class PayosCreateRequest {
    private String     bookingCode;
    private BigDecimal amount;
    private String     description;
    private String     returnUrl;   // "http://localhost:3000/payment-success"
    private String     cancelUrl;   // "http://localhost:3000/payment-failed"
}

// PaymentUrlResponse — copy từ payment-service
@Data public class PaymentUrlResponse {
    private String paymentUrl;    // VNPay link
    private String checkoutUrl;   // PayOS link
    private String transactionId;
    private String qrCode;
}
```

### 8.7 Backend — ChatbotService booking flow (dùng API thực tế)

```java
// ChatbotService.java — xử lý xác nhận đặt tour

private ChatMessageResponse processBookingConfirmation(ConversationState state, String sessionId) {
    Map<String,String> s = state.getSlots();

    // === BƯỚC 1: Tạo booking ===
    // Build danh sách passengers — chatbot đơn giản hóa: chỉ 1 passenger chính (người liên hệ)
    // Các passenger con nếu có: dùng họ tên phụ, ngày sinh = 01/01 (chatbot không hỏi từng người)
    List<CreateBookingRequest.PassengerRequest> passengers = buildPassengersForChatbot(s);

    CreateBookingRequest bookingReq = new CreateBookingRequest();
    bookingReq.setDepartureId(Integer.parseInt(s.get("selectedDepartureId")));
    bookingReq.setUserId(state.getUserId());          // null nếu guest ✅
    bookingReq.setContactFullName(s.get("contactName"));
    bookingReq.setContactPhone(s.get("contactPhone"));
    bookingReq.setContactEmail(s.getOrDefault("contactEmail", ""));
    bookingReq.setContactAddress(s.getOrDefault("contactAddress", "Đặt qua chatbot"));
    bookingReq.setCustomerNote("Đặt qua chatbot Future Travel");
    bookingReq.setPassengers(passengers);
    bookingReq.setCouponCode(List.of());
    bookingReq.setPointsUsed(0);

    CreateBookingResponse bookingResp;
    try {
        bookingResp = chatbotBookingFeignClient.createBooking(bookingReq);
    } catch (FeignException e) {
        log.error("Booking failed: {}", e.getMessage());
        return buildTextResponse(
            "❌ Hệ thống đặt chỗ đang bận. Vui lòng thử lại sau ít phút " +
            "hoặc gọi hotline **1800 xxxx** để được hỗ trợ ạ.",
            List.of(), sessionId
        );
    }

    // === BƯỚC 2: Tạo link thanh toán PayOS ===
    String checkoutUrl = null;
    try {
        PayosCreateRequest payReq = new PayosCreateRequest();
        payReq.setBookingCode(bookingResp.getBookingCode());
        payReq.setAmount(bookingResp.getTotalPrice());
        payReq.setDescription("Thanh toan tour " + s.get("selectedTourName"));
        payReq.setReturnUrl("http://localhost:3000/payment-success");
        payReq.setCancelUrl("http://localhost:3000/payment-failed");

        PaymentUrlResponse payResp = chatbotPaymentFeignClient.createPayosPayment(payReq);
        checkoutUrl = payResp.getCheckoutUrl();
    } catch (Exception e) {
        log.warn("Payment URL creation failed, booking still created: {}", e.getMessage());
        // Booking đã tạo thành công — payment URL lỗi không rollback booking
    }

    // === BƯỚC 3: Trả thông báo thành công ===
    String formatPrice = String.format("%,.0f ₫", bookingResp.getTotalPrice());
    String paymentLine = checkoutUrl != null
        ? "\n💳 **[Thanh toán ngay](" + checkoutUrl + ")**"
        : "\n💳 Vui lòng thanh toán tại trang web hoặc gọi hotline.";

    String successMsg = String.format(
        "🎉 **Đặt tour thành công!**\n\n" +
        "📋 Mã đặt chỗ: **%s**\n" +
        "🏞️ Tour: %s\n" +
        "📅 Ngày khởi hành: %s\n" +
        "👥 Số khách: %s\n" +
        "💰 Tổng tiền: **%s**\n" +
        "%s\n\n" +
        "Em sẽ liên hệ xác nhận trong vòng 24h ạ 🙏\n" +
        "_Quý khách lưu mã đặt chỗ để tra cứu sau nhé!_",
        bookingResp.getBookingCode(),
        s.get("selectedTourName"),
        s.getOrDefault("departureDate", "(chưa chọn ngày)"),
        formatPassengers(s),
        formatPrice,
        paymentLine
    );

    state.setStage(ConversationStage.BOOKING_CONFIRMED);
    state.getSlots().put("lastBookingCode", bookingResp.getBookingCode());
    conversationStateService.save(sessionId, state);

    return buildTextResponse(successMsg, List.of(
        QuickAction.of("Tra cứu đơn hàng", "tra cứu đơn " + bookingResp.getBookingCode()),
        QuickAction.of("Đặt tour khác",    "tìm tour khác"),
        QuickAction.of("Trang chủ",        "về trang chủ")
    ), sessionId);
}

/** Build danh sách passenger đơn giản cho chatbot (không thu thập ngày sinh từng người) */
private List<CreateBookingRequest.PassengerRequest> buildPassengersForChatbot(Map<String,String> s) {
    List<CreateBookingRequest.PassengerRequest> result = new ArrayList<>();
    int adults   = Integer.parseInt(s.getOrDefault("adults",   "1"));
    int children = Integer.parseInt(s.getOrDefault("children", "0"));

    // Người lớn đầu tiên = người liên hệ
    for (int i = 0; i < adults; i++) {
        var p = new CreateBookingRequest.PassengerRequest();
        p.setFullName(i == 0 ? s.get("contactName") : s.get("contactName") + " (KH " + (i+1) + ")");
        p.setGender("MALE");          // chatbot không hỏi gender
        p.setDateOfBirth("1990-01-01"); // placeholder — chatbot không hỏi ngày sinh chi tiết
        p.setType("ADULT");
        p.setSingleRoom(false);
        result.add(p);
    }
    for (int i = 0; i < children; i++) {
        var p = new CreateBookingRequest.PassengerRequest();
        p.setFullName("Trẻ em " + (i+1));
        p.setGender("MALE");
        p.setDateOfBirth("2015-01-01"); // placeholder
        p.setType("CHILD");
        p.setSingleRoom(false);
        result.add(p);
    }
    return result;
}
```

> **Lưu ý:** `dateOfBirth` là bắt buộc trong `BookingPassenger`. Chatbot dùng placeholder `1990-01-01` cho người lớn và `2015-01-01` cho trẻ em. Admin có thể cập nhật sau qua trang web. Đây là trade-off chấp nhận được để đơn giản hóa flow chatbot.

    state.getSlots().put("adults", String.valueOf(adults));
    state.getSlots().put("children", String.valueOf(children));
    return proceedToContactCollection(state, sessionId);
}

private ChatMessageResponse proceedToContactCollection(ConversationState state, String sessionId) {
    // Nếu user đã đăng nhập và có thông tin → skip
    Map<String,String> userInfo = state.getUserInfo();
    if (userInfo != null && userInfo.containsKey("name") && userInfo.containsKey("phone")) {
        state.getSlots().put("contactName",  userInfo.get("name"));
        state.getSlots().put("contactPhone", userInfo.get("phone"));
        return buildBookingConfirmCard(state, sessionId);
    }

    state.setStage(ConversationStage.COLLECTING_CONTACT);
    conversationStateService.save(sessionId, state);

    return buildTextResponse(
        "Cảm ơn Quý khách! Để em giữ chỗ, vui lòng cung cấp:\n" +
        "• 📛 Họ tên đầy đủ người đặt:\n" +
        "• 📞 Số điện thoại liên hệ:",
        List.of(), sessionId
    );
}

private ChatMessageResponse handleContactInput(String msg, ConversationState state, String sessionId) {
    // Parse "Nguyễn Văn A, 0901234567" hoặc 2 dòng
    String[] parsed = parseContact(msg);
    if (parsed == null) {
        return buildTextResponse(
            "Em chưa đọc được thông tin. Quý khách vui lòng nhập theo dạng:\n" +
            "**Họ tên, số điện thoại**\n" +
            "Ví dụ: Nguyễn Văn A, 0901234567",
            List.of(), sessionId
        );
    }
    state.getSlots().put("contactName",  parsed[0]);
    state.getSlots().put("contactPhone", parsed[1]);
    return buildBookingConfirmCard(state, sessionId);
}

private ChatMessageResponse buildBookingConfirmCard(ConversationState state, String sessionId) {
    Map<String,String> s = state.getSlots();

    // Build booking preview data
    BookingConfirmData data = BookingConfirmData.builder()
        .tourName(s.get("selectedTourName"))
        .tourCode(s.get("selectedTourCode"))
        .departureDate(s.getOrDefault("departureDate", "(chưa chọn ngày)"))
        .adults(Integer.parseInt(s.getOrDefault("adults", "1")))
        .children(Integer.parseInt(s.getOrDefault("children", "0")))
        .adultPrice(Long.parseLong(s.getOrDefault("adultPrice", "0")))
        .childPrice(Long.parseLong(s.getOrDefault("childPrice", "0")))
        .contactName(s.get("contactName"))
        .contactPhone(s.get("contactPhone"))
        .build();

    state.setStage(ConversationStage.AWAITING_CONFIRMATION);
    conversationStateService.save(sessionId, state);

    return ChatMessageResponse.builder()
        .sessionId(sessionId)
        .message("Em đã tổng hợp thông tin đặt tour của Quý khách. Vui lòng kiểm tra và xác nhận ạ!")
        .type("BOOKING_CONFIRM")
        .bookingConfirmData(data)
        .build();
}
```

### 8.8 Edge cases cần xử lý

| Tình huống | Xử lý |
|---|---|
| User hỏi "hủy đặt" khi ở stage AWAITING_CONFIRMATION | Reset về SEARCHING, giữ slots search |
| Departure đã hết slot khi xác nhận | Bot báo: "Rất tiếc, chuyến ngày X đã hết chỗ. Em tìm chuyến khác?" |
| User chọn tour không có trong lastTourIds | Bot hỏi: "Quý khách muốn đặt tour nào? Quý khách nhắn tên hoặc mã tour để em kiểm tra" |
| Parse số điện thoại sai format | Bot nhắn: "SĐT chưa đúng định dạng Việt Nam (10 số). Vui lòng nhập lại." |
| User nhắn "đặt tour" ngay từ đầu (không qua search) | Bot: "Quý khách muốn đi đâu ạ? Em sẽ tìm tour phù hợp rồi hỗ trợ đặt chỗ." → stage GREETING |
| Feign booking-service lỗi | Bot: "Hệ thống đặt chỗ đang bận. Quý khách vui lòng thử lại sau ít phút hoặc gọi hotline 1800XXXX" |
| User nhắn "đặt tour" khi đang ở AWAITING_CONFIRMATION | Nhắc nhở: "Quý khách chưa xác nhận booking hiện tại. Xác nhận ngay hay huỷ để đặt tour mới?" |

---

## 9. ORDER LOOKUP FLOW CHI TIẾT

### 9.1 Quan sát từ screenshots thực tế (130953.png)

```
User: "tra cứu đơn hàng của tôi"

Tripi:
  "Để tra cứu thông tin đặt chỗ, Quý khách vui lòng cung cấp:
   • Mã đặt chỗ (Ví dụ: VTV123456 hoặc FT26A00123)
   • Họ tên đầy đủ khách hàng chính"
```

Tripi hỏi cả 2 thông tin trong **1 tin nhắn** — không hỏi từng cái một. Đây là điểm khác biệt so với booking flow (hỏi từng bước).

### 9.2 Luồng Order Lookup đầy đủ

#### TRIGGER: Detect order lookup intent

Các mẫu câu kích hoạt:
```java
private boolean isOrderLookupIntent(String msg) {
    return msg.matches(
        ".*(tra cứu|kiểm tra|xem đơn|đơn hàng|đặt chỗ|booking|mã đặt|" +
        "tình trạng tour|thanh toán chưa|đã đặt chưa|hủy tour|hủy booking).*"
    );
}
```

#### Bước 1: Bot hỏi 2 thông tin (1 tin nhắn)

```
User: "tra cứu đơn hàng VTV123456" (hoặc "kiểm tra booking của tôi")

→ Bot:
  "Để tra cứu thông tin đặt chỗ, Quý khách vui lòng cung cấp:
   • 📋 Mã đặt chỗ (Ví dụ: FT26A00123)
   • 📛 Họ tên đầy đủ khách hàng chính

  (Nếu quên mã, Quý khách có thể xem trong email xác nhận đặt tour)"

  → state.stage = ORDER_LOOKUP

  // Nếu user đã đưa mã trong câu → pre-fill
  // "tra cứu đơn FT26A00123" → state.slots.lookupCode = "FT26A00123"
  // Bot chỉ hỏi thêm họ tên
```

#### Bước 2: User cung cấp thông tin

User nhắn: "FT26A00123, Nguyễn Văn A"  
hoặc 2 dòng:
```
FT26A00123
Nguyễn Văn A
```

#### Bước 3: Backend lookup + hiển thị kết quả

```java
private ChatMessageResponse handleOrderLookupInput(String msg, ConversationState state, String sessionId) {
    // Parse code + name từ message
    String code = extractBookingCode(msg);   // regex: [A-Z]{2}\d{2}[A-Z]\d{5} hoặc VTV\d+
    String name = extractFullName(msg);

    // Nếu thiếu code nhưng đã có trong slots → dùng lại
    if (code == null && state.getSlots().containsKey("lookupCode")) {
        code = state.getSlots().get("lookupCode");
    }
    // Nếu thiếu name nhưng đã đăng nhập
    if (name == null && state.getUserInfo() != null) {
        name = state.getUserInfo().get("name");
    }

    if (code == null) {
        return buildTextResponse(
            "Em chưa nhận được mã đặt chỗ. Quý khách vui lòng nhập theo dạng:\n" +
            "**FT26A00123, Nguyễn Văn A**",
            List.of(), sessionId
        );
    }
    if (name == null) {
        state.getSlots().put("lookupCode", code);
        conversationStateService.save(sessionId, state);
        return buildTextResponse(
            "Cảm ơn! Em đã có mã **" + code + "**. " +
            "Quý khách vui lòng cho em họ tên đầy đủ để xác thực ạ:",
            List.of(), sessionId
        );
    }

    // Gọi Feign → booking-service
    try {
        BookingLookupResponse booking = chatbotBookingFeignClient.lookupBooking(code, name);
        return buildOrderResultMessage(booking, state, sessionId);
    } catch (FeignException.NotFound e) {
        return buildTextResponse(
            "❌ Em không tìm thấy đơn hàng **" + code + "** với tên **" + name + "**.\n\n" +
            "Quý khách vui lòng kiểm tra lại:\n" +
            "• Mã đặt chỗ có đúng không? (phân biệt chữ hoa/thường)\n" +
            "• Họ tên có khớp với lúc đặt không?\n\n" +
            "Nếu cần hỗ trợ thêm, Quý khách gọi hotline **1800 xxxx** (miễn phí) ạ.",
            List.of(
                QuickAction.of("Thử lại", "tra cứu đơn khác"),
                QuickAction.of("Tìm tour mới", "tìm tour")
            ), sessionId
        );
    }
}
```

#### Kết quả tìm thấy — OrderResultCard

```
┌──────────────────────────────────────────────────────────┐
│  📋  THÔNG TIN ĐẶT TOUR                                  │
├──────────────────────────────────────────────────────────┤
│  📋 Mã đặt chỗ:  FT26A00123                             │
│  🏞️ Tour:        Đà Lạt Thác Voi 4N3Đ                   │
│  📅 Khởi hành:   14/07/2026  •  4N3Đ                   │
│  🚌 Khởi hành từ: TP. Hồ Chí Minh                       │
│  👥 Số khách:    2 người lớn + 1 trẻ em                 │
│  💰 Tổng tiền:   6.225.000 ₫                            │
├──────────────────────────────────────────────────────────┤
│  🟡 Trạng thái: CHỜ THANH TOÁN                          │
│  💳 [Thanh toán ngay]                                    │
├──────────────────────────────────────────────────────────┤
│  [🔍 Tra cứu đơn khác]  [📞 Liên hệ hỗ trợ]           │
└──────────────────────────────────────────────────────────┘
```

```java
private ChatMessageResponse buildOrderResultMessage(BookingLookupResponse b,
                                                     ConversationState state, String sessionId) {
    String statusEmoji = switch (b.getStatus()) {
        case "PENDING_PAYMENT"  -> "🟡 Chờ thanh toán";
        case "CONFIRMED"        -> "🟢 Đã xác nhận";
        case "CANCELLED"        -> "🔴 Đã hủy";
        case "COMPLETED"        -> "✅ Hoàn thành";
        case "PENDING_CONFIRM"  -> "🔵 Chờ xác nhận";
        default                 -> "⚪ " + b.getStatus();
    };

    String resultMsg = String.format(
        "📋 **THÔNG TIN ĐẶT TOUR**\n\n" +
        "📋 Mã đặt chỗ: **%s**\n" +
        "🏞️ Tour: %s\n" +
        "📅 Khởi hành: %s (%s)\n" +
        "🚌 Từ: %s\n" +
        "👥 Số khách: %s\n" +
        "💰 Tổng tiền: **%s**\n\n" +
        "Trạng thái: **%s**\n" +
        "%s",
        b.getBookingCode(),
        b.getTourName(),
        b.getDepartureDate(), b.getDuration(),
        b.getDepartureCity(),
        formatPassengers(b.getAdults(), b.getChildren()),
        formatCurrency(b.getTotalAmount()),
        statusEmoji,
        b.getStatus().equals("PENDING_PAYMENT") && b.getPaymentUrl() != null
            ? "💳 **[Thanh toán ngay](" + b.getPaymentUrl() + ")**"
            : ""
    );

    state.setStage(ConversationStage.GREETING); // reset sau lookup
    conversationStateService.save(sessionId, state);

    return buildTextResponse(resultMsg, List.of(
        QuickAction.of("Tra cứu đơn khác", "tra cứu đơn hàng"),
        QuickAction.of("Tìm tour mới",     "tìm tour"),
        QuickAction.of("Liên hệ hỗ trợ",  "tôi cần hỗ trợ")
    ), sessionId);
}
```

### 9.3 booking-service — endpoint mới cho chatbot lookup

```java
// booking-service/controller/BookingController.java — thêm endpoint
@GetMapping("/bookings/lookup")
@PermitAll  // Không cần JWT — xác thực qua booking code + full name
public ResponseEntity<BookingLookupResponse> lookupForChatbot(
        @RequestParam String code,
        @RequestParam String name) {

    Booking booking = bookingRepository.findByBookingCode(code)
        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy booking: " + code));

    // Verify full name (case-insensitive, normalize diacritics)
    if (!normalizeVietnamese(booking.getContactName())
             .equalsIgnoreCase(normalizeVietnamese(name))) {
        throw new ResourceNotFoundException("Thông tin không khớp");
    }

    return ResponseEntity.ok(bookingMapper.toLookupResponse(booking));
}

// BookingLookupResponse.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BookingLookupResponse {
    private String  bookingCode;
    private String  tourName;
    private String  departureDate;   // dd/MM/yyyy
    private String  departureCity;
    private String  duration;        // "4N3Đ"
    private Integer adults;
    private Integer children;
    private Long    totalAmount;
    private String  status;          // PENDING_PAYMENT, CONFIRMED, CANCELLED, COMPLETED
    private String  paymentUrl;      // null nếu đã thanh toán
    private String  createdAt;
}
```

### 9.4 Pre-fill nếu user đã đăng nhập

Nếu user đã đăng nhập, thay vì hỏi booking code + họ tên, bot có thể:

```java
// Nếu userId ≠ null và user hỏi "đơn hàng của tôi" (không nêu code cụ thể)
if (isGenericOrderLookup(msg) && state.getUserId() != null) {
    List<BookingLookupResponse> myBookings =
        chatbotBookingFeignClient.getMyRecentBookings(state.getUserId(), 3);

    if (myBookings.isEmpty()) {
        return buildTextResponse(
            "Quý khách chưa có đơn đặt tour nào. Quý khách muốn tìm tour không ạ?",
            List.of(QuickAction.of("Tìm tour ngay", "tìm tour")), sessionId
        );
    }

    // Hiển thị danh sách 3 đơn gần nhất
    StringBuilder sb = new StringBuilder("📋 **Đơn đặt tour gần đây của Quý khách:**\n\n");
    for (BookingLookupResponse b : myBookings) {
        sb.append(String.format("• **%s** — %s — %s\n",
            b.getBookingCode(), b.getTourName(), getStatusEmoji(b.getStatus())));
    }
    sb.append("\nQuý khách muốn xem chi tiết đơn nào?");

    return buildTextResponse(sb.toString(), List.of(), sessionId);
}
```

### 9.5 Edge cases Order Lookup

| Tình huống | Xử lý |
|---|---|
| Mã đúng nhưng tên sai | "Thông tin không khớp. Họ tên phải đúng như lúc đặt tour." |
| Mã không tồn tại | "Không tìm thấy mã FT26XXXXX. Kiểm tra lại email xác nhận?" |
| Booking đã hủy | Hiển thị đầy đủ nhưng status "🔴 Đã hủy", không có link thanh toán |
| Booking đã hoàn thành | Status "✅ Hoàn thành" + gợi ý "Đặt tour lần tiếp" |
| User nhắn mã sai format | Bot hỏi lại: "Mã đặt chỗ thường có dạng FT26A00123. Quý khách kiểm tra lại email xác nhận nhé." |
| Nhiều booking cùng tên | Trả về tất cả (hiếm, nhưng cần paginate hoặc hỏi thêm ngày đặt) |
| Lookup khi đang trong booking flow | Bot nhắc: "Quý khách đang đặt tour [X]. Xác nhận trước hay hủy để tra cứu đơn?" |

---

## 10. TÓM TẮT CÁC CLASS MỚI CẦN TẠO (PHASE 4)

### 10.1 analytics-service — danh sách file

| File | Loại | Mô tả |
|---|---|---|
| `entity/ConversationStage.java` | Enum | GREETING, SEARCHING, COLLECTING_PASSENGERS, COLLECTING_CONTACT, AWAITING_CONFIRMATION, BOOKING_CONFIRMED, ORDER_LOOKUP |
| `entity/ConversationState.java` | POJO | history, slots(Map), stage, lastTourIds, userId, userInfo |
| `entity/BookingSlots.java` | POJO | selectedTourId, selectedDepartureId, adults, children, contactName, contactPhone, contactEmail, lookupCode |
| `entity/BookingConfirmData.java` | DTO | tourName, tourCode, imageUrl, departureDate, departureCity, duration, adults, children, adultPrice, childPrice, contactName, contactPhone |
| `service/ConversationStateService.java` | Service | getOrCreate, save, refresh TTL — Redis StringRedisTemplate |
| `service/ChatbotBookingService.java` | Service | startBookingFlow, handlePassengersInput, handleContactInput, buildConfirmCard, processConfirmation, cancelFlow, handleOrderLookup |
| `feign/ChatbotBookingFeignClient.java` | Feign | createBooking, lookupBooking, getDeparturePricing, getMyRecentBookings |
| `feign/ChatbotIamFeignClient.java` | Feign | getUserProfile(userId) |
| `dto/BookingChatResponse.java` | DTO | bookingCode, tourName, departureDate, totalAmount, paymentUrl, status |
| `dto/BookingLookupResponse.java` | DTO | Như trên + duration, adults, children, departureCity |
| `dto/CreateBookingChatRequest.java` | DTO | departureId, numberOfAdults, numberOfChildren, contactName, contactPhone, note |
| `dto/DeparturePricingResponse.java` | DTO | departureId, departureDate, departureCity, adultPrice, childPrice, availableSlots |

### 10.2 booking-service — file mới/sửa

| File | Hành động | Thay đổi |
|---|---|---|
| `controller/BookingController.java` | SỬA | Thêm `GET /bookings/lookup`, `GET /bookings/chatbot/my-bookings` |
| `controller/BookingController.java` | SỬA | Thêm `POST /bookings/chatbot` — guest booking từ chatbot |
| `dto/BookingLookupResponse.java` | TẠO MỚI | DTO cho chatbot lookup |
| `dto/CreateBookingChatRequest.java` | TẠO MỚI | Request từ chatbot |
| `service/impl/BookingServiceImpl.java` | SỬA | `createBookingFromChat()` — cho phép không có JWT |
| `util/VietnameseNormalizer.java` | TẠO MỚI (optional) | Normalize tiếng Việt để so tên (bỏ dấu) |

### 10.3 Frontend — file mới/sửa

| File | Hành động | Thay đổi |
|---|---|---|
| `ChatbotWidget/BookingConfirmCard.jsx` | TẠO MỚI | Component card xác nhận đặt tour |
| `ChatbotWidget/BookingConfirmCard.module.scss` | TẠO MỚI | Styles |
| `ChatbotWidget/OrderResultCard.jsx` | TẠO MỚI (optional) | Component hiển thị kết quả tra cứu (có thể dùng text message thay thế) |
| `ChatbotWidget/ChatbotWidget.jsx` | SỬA | Import BookingConfirmCard, render theo `msg.type`, `sendSystemMessage()`, userId từ Redux |

---

*Phiên bản 2.1 — Cập nhật 2026-05-23: Bổ sung Section 8 (Booking Flow) + Section 9 (Order Lookup) + Section 10 (Class List) dựa trên phân tích đầy đủ 28 screenshots Tripi chatbot.*
*Tác giả: AI Analysis — Future Travel Dev Team*
