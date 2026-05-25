# CHATBOT UPGRADE PLAN v3 — Future Travel Microservices
**Ngày:** 2026-05-25  
**Phiên bản:** 3.0 — Dựa trên phân tích 28 screenshots Tripi (travel.com.vn) + code thực tế booking-service + payment-service  
**Trạng thái:** PLANNING ONLY — chưa implement  

---

## MỤC LỤC

1. [Phân tích hệ thống hiện tại](#1-phân-tích-hệ-thống-hiện-tại)
2. [So sánh với Tripi — travel.com.vn](#2-so-sánh-với-tripi--travelcomvn)
3. [Kiến trúc mục tiêu](#3-kiến-trúc-mục-tiêu)
4. [RabbitMQ Debounce Sync (Pinecone)](#4-rabbitmq-debounce-sync-pinecone)
5. [Redis Session + Chat History](#5-redis-session--chat-history)
6. [Booking-in-Chat Flow](#6-booking-in-chat-flow)
7. [Order Lookup Flow](#7-order-lookup-flow)
8. [Frontend Changes (ChatbotWidget)](#8-frontend-changes-chatbotwidget)
9. [Roadmap Triển Khai (4 Phase)](#9-roadmap-triển-khai-4-phase)
10. [Danh Sách File Cần Tạo/Sửa](#10-danh-sách-file-cần-tạosửa)

---

## 1. PHÂN TÍCH HỆ THỐNG HIỆN TẠI

### 1.1 Kiến trúc chatbot hiện tại (analytics-service)

```
User Message
    ↓
ChatbotController.chat(ChatMessageRequest)
    ↓
ChatbotService.processMessage()
    ↓
[1] intent classify (keyword matching)
    ↓
[2a] isSearchIntent → searchTours(query) → Pinecone similarity search → Gemini summarize
[2b] isBookingIntent → "Vui lòng đặt tour qua website..." (DUMMY)
[2c] general → conversationService.chat(history, message) → Gemini generate
    ↓
ChatMessageResponse { message, tourCards? }
```

**Vấn đề nghiêm trọng hiện tại:**

| Vấn đề | Mô tả | Ảnh hưởng |
|--------|-------|-----------|
| Stateless | `conversationHistory` là List in-memory, mất khi F5 | User phải giải thích lại từ đầu |
| sessionId reset | `session_${Date.now()}` → mỗi lần F5 = session mới | Không có chat history |
| Booking dummy | Intent booking chỉ trả text "đặt qua website" | Không có chức năng |
| Pinecone stale | VectorSyncService sync 1 lần/ngày lúc 2AM | Tour mới thêm sẽ không searchable trong 24h |
| userId hardcoded null | `userId = null` trong ChatbotWidget | Không tích hợp với đăng nhập |
| Tour cards tắt | Lines 326-360 trong ChatbotWidget.jsx đang comment out | Không hiển thị tour card |

### 1.2 Luồng VectorSyncService hiện tại

```
@Scheduled(cron = "0 0 2 * * *")   ← chỉ 2AM hàng ngày
syncAllTours() {
    tourCatalogFeignClient.getAllTours()    // lấy tất cả tours
    → upsert Pinecone (tourId, tourCode, description, embeddings)
}
```

Vấn đề: Khi admin thêm tour mới hoặc thay đổi giá → Pinecone không biết cho đến 2AM hôm sau.

---

## 2. SO SÁNH VỚI TRIPI — travel.com.vn

### 2.1 Tính năng chatbot Tripi (phân tích 28 screenshots thực tế)

#### A. Tour Card UI

```
┌─────────────────────────────────────────────┐
│  [Ảnh tour full-width]                       │
│  📌 NDSGN555                                │  ← tour code badge
│  **Đà Nẵng - Hội An - Bà Nà Hills 4N3Đ**  │
│  TP.HCM  →  Đà Nẵng  •  4N3Đ              │
│  🗓️ [05/07] [12/07] [19/07✓] [26/07]      │  ← clickable date chips, highlight nearest
│  💰 Giá từ: 4.990.000₫/người               │
│  [🛒 Xem ngay]                              │  ← action button
└─────────────────────────────────────────────┘
```

#### B. Booking Flow đầy đủ (từ screenshots 125xxx → 131xxx)

```
[1] User: "m có thể giúp t đặt tour được ko"
    Bot:  "Quý khách muốn đặt tour nào? Cho em biết:
           • Điểm đến mong muốn
           • Thời gian dự kiến
           • Số người đi"

[2] User: "hà nội, 15/06, 1 mk 1 t"
    Bot:  [Hiện 3 tour cards] "Quý khách thích tour nào để em tư vấn thêm ạ?"

[3] User: "tour 1"
    Bot:  "Chị muốn khởi hành ngày nào?
           • Ngày 18/06
           • Ngày 19/06"

[4] User: "Ngày 18"
    Bot:  "Để tiện đặt tour, anh/chị có thể cho em xin
           tên đầy đủ và số điện thoại liên hệ ạ?"

[5] User: "trần anh thu, 0343525252"
    Bot:  "Cảm ơn! Em có thể xin email để gửi xác nhận đặt tour không ạ?"

[6] User: "trananthu@gmail.com"
    Bot:  [Booking Confirm Card]:
           Tour: Hà Nội - Sapa 3N2Đ
           Ngày khởi hành: 18/06/2026
           Số người: 1 người lớn, 1 trẻ em
           Tổng tiền: 13.590.000₫
           [✅ Xác nhận đặt tour]  [❌ Hủy]

[7] User: "✅ Xác nhận"
    Bot:  "🎉 Đặt tour thành công!
           📋 Mã booking: 260517X4S77C
           🏞️ Tour: Hà Nội - Sapa 3N2Đ
           📅 Ngày khởi hành: 18/06/2026
           👥 Số người: 1 MK + 1 TN
           💰 Tổng tiền: 13.590.000₫
           📧 Email xác nhận: trananthu@gmail.com
           💳 [Thanh toán ngay](https://travel.com.vn/payment-booking/260517X4S77C)
           ⚠️ Lưu lại mã booking 260517X4S77C để tra cứu!"
```

#### C. Order Lookup (screenshot 130953.png)

```
[1] User: "tra cứu đơn hàng của tôi"
    Bot:  "Để tra cứu thông tin đặt chỗ, Quý khách vui lòng cung cấp:
           • Mã đặt chỗ (Ví dụ: VTV123456 hoặc FT26A00123)
           • Họ tên đầy đủ khách hàng chính"

[2] User: "260517ZGADF9" (nhập thiếu tên)
    Bot:  (vẫn hỏi thêm tên)

[3] User: "Trần Anh Thu"
    Bot:  "Em tra cứu mã 260517ZGADF9 với tên Trần Anh Thu nhưng
           hệ thống không tìm thấy.
           Quý khách kiểm tra lại:
           ✓ Mã booking có chính xác không?
           ✓ Họ tên có đúng với tên khi đặt tour?
           Nếu cần hỗ trợ: Hotline 1800646888 (miễn phí)"
```

### 2.2 Bảng so sánh chi tiết

| Tính năng | Tripi (travel.com.vn) | Future Travel (hiện tại) | Mục tiêu |
|-----------|----------------------|--------------------------|----------|
| Session persistence | Cookie/localStorage | ❌ Mất khi F5 | Redis 24h + localStorage |
| Chat history | ✅ Có | ❌ Không có | Redis + GET /history/{sid} |
| Tour cards | ✅ Ảnh + date chips | ❌ Comment out | ✅ Bật lại + date chips |
| Booking trong chat | ✅ Đầy đủ (không cần login) | ❌ Dummy text | ✅ Tích hợp booking-service |
| Collect contact | Name + Phone + Email | ❌ Không có | Name + Phone + Email |
| Confirm card | ✅ Có | ❌ Không có | BookingConfirmCard component |
| Booking code returned | ✅ Ngay sau confirm | ❌ Không có | ✅ Từ booking-service |
| Payment link | ✅ Direct URL sau booking | ❌ Không có | PayOS checkoutUrl |
| Order lookup | ✅ Code + Name | ❌ Không có | Code (+ Name verify) |
| Pinecone sync | N/A | 2AM hàng ngày | RabbitMQ 5-min debounce |
| Hotline | ✅ 1800646888 | ❌ Không | Thêm vào error messages |
| Guest booking | ✅ Không cần login | N/A | userId = null ✅ đã hỗ trợ |

### 2.3 Key insights từ Tripi chatbot

1. **One-message collection**: Tripi hỏi destination + date + người trong 1 message, không hỏi từng cái
2. **No login required**: Booking hoàn toàn qua chat, collect name+phone+email là đủ
3. **Immediate feedback**: Booking code trả về ngay sau confirm, không cần check status
4. **Direct payment URL**: Sau booking, link trực tiếp đến payment page với bookingCode
5. **Friendly Vietnamese**: Dùng "ạ", "Quý khách", "chị/anh" — không máy móc
6. **Error với hotline**: Khi không tìm thấy đơn → luôn cung cấp hotline fallback

---

## 3. KIẾN TRÚC MỤC TIÊU

### 3.1 Diagram

```
User Browser
    │  sessionId (localStorage, persist qua F5)
    │
    ↓
API Gateway :8080
    │
    ↓
analytics-service :8087
    │
    ├── ChatbotController
    │       ↓
    │   ChatbotService (stateful — Redis ConversationState)
    │       │
    │       ├── intent: SEARCH → Pinecone + Gemini → TourCards
    │       ├── intent: BOOKING → ChatbotBookingService (booking flow)
    │       ├── intent: ORDER_LOOKUP → lookup qua Feign → OrderResultCard
    │       └── intent: GENERAL → conversationHistory (Redis) + Gemini
    │
    ├── ConversationStateService  → Redis (TTL 24h)
    ├── ChatHistoryService        → Redis List (TTL 24h, max 50 msgs)
    │
    ├── Feign clients:
    │       ├── ChatbotBookingFeignClient  → booking-service
    │       ├── ChatbotPaymentFeignClient  → payment-service
    │       └── TourCatalogFeignClient     → tour-catalog-service (existing)
    │
    └── RabbitMQ Consumer:
            ← tourism.events exchange
            routing: chatbot.sync.*
            → Debounce 5min → VectorSyncService.syncTour(tourId)

booking-service :8083
    ├── POST /api/bookings/create        (existing — guest OK, userId nullable)
    ├── GET  /api/bookings/payment/{code} (existing — lookup by code)
    └── GET  /api/bookings/chatbot/my-bookings?userId=X  (NEW — cho logged-in user)

payment-service :8086
    ├── POST /api/payment/payos/create  (existing → checkoutUrl)
    └── POST /api/payment/vnpay/create  (existing → paymentUrl)
```

### 3.2 ConversationState (Redis data model)

```java
// Key: "chatbot:state:{sessionId}"
// TTL: 24h (sliding — refresh khi có message mới)
public class ConversationState {
    private String sessionId;
    private ConversationStage stage;          // state machine stage
    private List<Map<String, String>> history; // [{role, content}, ...]
    private Map<String, String> slots;         // booking/lookup data
    private List<Integer> lastTourIds;         // tour IDs từ search gần nhất
    private Integer userId;                    // null nếu guest
    private Map<String, String> userInfo;      // {name, email, phone} từ profile
    private Instant lastActivity;
}

// Slots keys:
// Booking: destination, travelDate, adults, children,
//          selectedTourId, selectedTourCode, selectedTourName, selectedTourImage,
//          selectedDepartureId, departureDate, departureCity, duration,
//          adultPrice, childPrice,
//          contactName, contactPhone, contactEmail
// Lookup:  lookupCode, lookupName
```

### 3.3 ConversationStage enum

```java
public enum ConversationStage {
    GREETING,              // Initial / reset state
    SEARCHING,             // User searching tours, showing results
    SELECTING_DEPARTURE,   // User picked tour, now choosing departure date
    COLLECTING_PASSENGERS, // Collecting adults/children count (nếu chưa có)
    COLLECTING_CONTACT,    // Collecting name + phone + email
    AWAITING_CONFIRMATION, // Showing BookingConfirmCard, waiting for confirm/cancel
    BOOKING_CONFIRMED,     // Booking created, showing booking code + payment link
    ORDER_LOOKUP           // In order lookup flow
}
```

---

## 4. RABBITMQ DEBOUNCE SYNC (PINECONE)

### 4.1 Vấn đề cần giải quyết

Khi admin thêm/sửa tour trong tour-catalog-service:
- Hiện tại: Pinecone không biết cho đến 2AM hôm sau
- Mục tiêu: Sync trong vòng 5 phút sau khi có thay đổi

### 4.2 Luồng event

```
tour-catalog-service:
    TourService.createTour() → publish event
    TourService.updateTour() → publish event
    TourService.deleteTour() → publish event
        ↓
    RabbitMQ Exchange: tourism.events
    Routing key: chatbot.sync.tour.{tourId}

analytics-service:
    ChatbotSyncEventListener.onTourChanged(tourId)
        → pendingTourIds.add(tourId)  // ConcurrentHashMap
        ← debounce timer (5 min, @Scheduled)
        → VectorSyncService.syncTours(pendingTourIds)
        → Pinecone upsert/delete
```

### 4.3 Cấu hình RabbitMQ

```yaml
# analytics-service/src/main/resources/application.yaml
chatbot:
  sync:
    queue: chatbot.sync.queue
    exchange: tourism.events
    routing-key: chatbot.sync.#
    debounce-ms: 300000   # 5 phút
```

### 4.4 ChatbotRabbitMQConfig.java

```java
@Configuration
public class ChatbotRabbitMQConfig {

    @Value("${chatbot.sync.queue}") private String queue;
    @Value("${chatbot.sync.exchange}") private String exchange;
    @Value("${chatbot.sync.routing-key}") private String routingKey;

    @Bean Queue chatbotSyncQueue() {
        return QueueBuilder.durable(queue).build();
    }

    @Bean Binding chatbotSyncBinding() {
        return BindingBuilder
            .bind(chatbotSyncQueue())
            .to(new TopicExchange(exchange))
            .with(routingKey);
    }
}
```

### 4.5 ChatbotSyncEventListener.java

```java
@Component
@RequiredArgsConstructor
public class ChatbotSyncEventListener {

    private final VectorSyncService vectorSyncService;

    // Thread-safe set of tour IDs waiting to be synced
    private final Set<Integer> pendingTourIds =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> pendingDeleteIds =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    @RabbitListener(queues = "${chatbot.sync.queue}")
    public void onTourChanged(TourSyncEvent event) {
        if ("DELETED".equals(event.getAction())) {
            pendingDeleteIds.add(event.getTourId());
        } else {
            pendingTourIds.add(event.getTourId());
        }
        log.info("[ChatbotSync] Queued tourId={} action={}", event.getTourId(), event.getAction());
    }

    // Debounce: flush mỗi 5 phút
    @Scheduled(fixedDelayString = "${chatbot.sync.debounce-ms:300000}")
    public void flushPendingSync() {
        if (pendingTourIds.isEmpty() && pendingDeleteIds.isEmpty()) return;

        Set<Integer> toSync = new HashSet<>(pendingTourIds);
        Set<Integer> toDelete = new HashSet<>(pendingDeleteIds);
        pendingTourIds.removeAll(toSync);
        pendingDeleteIds.removeAll(toDelete);

        log.info("[ChatbotSync] Flushing: sync={}, delete={}", toSync.size(), toDelete.size());

        if (!toSync.isEmpty())   vectorSyncService.syncToursByIds(toSync);
        if (!toDelete.isEmpty()) vectorSyncService.deleteToursByIds(toDelete);
    }
}
```

### 4.6 TourSyncEvent.java (shared DTO)

```java
// shared-library hoặc trực tiếp trong mỗi service
@Data @NoArgsConstructor @AllArgsConstructor
public class TourSyncEvent {
    private Integer tourId;
    private String action;    // "CREATED", "UPDATED", "DELETED"
    private Instant timestamp;
}
```

### 4.7 Publisher trong tour-catalog-service

```java
// Thêm vào TourService (tour-catalog-service)
@RequiredArgsConstructor
public class TourServiceImpl implements TourService {
    private final RabbitTemplate rabbitTemplate;
    private static final String EXCHANGE = "tourism.events";

    private void publishSyncEvent(Integer tourId, String action) {
        TourSyncEvent event = new TourSyncEvent(tourId, action, Instant.now());
        rabbitTemplate.convertAndSend(
            EXCHANGE,
            "chatbot.sync.tour." + tourId,
            event
        );
    }

    // Gọi publishSyncEvent() sau mỗi create/update/delete
}
```

### 4.8 Backup: full sync 2AM vẫn giữ

```java
@Scheduled(cron = "0 0 2 * * *")
public void fullNightlySync() {
    log.info("[VectorSync] Starting nightly full sync...");
    syncAllTours();
}
```

---

## 5. REDIS SESSION + CHAT HISTORY

### 5.1 sessionId persistence

**Vấn đề:** Hiện tại `sessionId = session_${Date.now()}` trong ChatbotWidget.jsx → mỗi F5 = session mới.

**Giải pháp:**
```javascript
// ChatbotWidget.jsx — init session
const initSession = () => {
    let sid = localStorage.getItem('chatbot_session_id');
    if (!sid) {
        sid = `session_${Date.now()}_${Math.random().toString(36).substr(2,9)}`;
        localStorage.setItem('chatbot_session_id', sid);
    }
    return sid;
};

const [sessionId] = useState(initSession);
```

### 5.2 ConversationStateService.java

```java
@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "chatbot:state:";
    private static final Duration TTL_GUEST  = Duration.ofHours(2);   // guest
    private static final Duration TTL_LOGGED = Duration.ofHours(24);  // đã đăng nhập

    public ConversationState getOrCreate(String sessionId) {
        String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null) {
            ConversationState state = new ConversationState();
            state.setSessionId(sessionId);
            state.setStage(ConversationStage.GREETING);
            state.setSlots(new HashMap<>());
            state.setHistory(new ArrayList<>());
            state.setLastActivity(Instant.now());
            return state;
        }
        return deserialize(json);
    }

    public void save(String sessionId, ConversationState state) {
        state.setLastActivity(Instant.now());
        Duration ttl = state.getUserId() != null ? TTL_LOGGED : TTL_GUEST;
        redis.opsForValue().set(
            KEY_PREFIX + sessionId,
            serialize(state),
            ttl
        );
    }

    public void delete(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
    }
}
```

### 5.3 ChatHistoryService.java

```java
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final StringRedisTemplate redis;

    private static final String HIST_PREFIX = "chatbot:history:";
    private static final Duration HIST_TTL   = Duration.ofHours(24);
    private static final int     MAX_HISTORY = 50;

    public void appendMessage(String sessionId, String role, String content) {
        String key = HIST_PREFIX + sessionId;
        String entry = role + "|" + Instant.now().toEpochMilli() + "|" + content;
        redis.opsForList().rightPush(key, entry);
        redis.opsForList().trim(key, -MAX_HISTORY, -1);
        redis.expire(key, HIST_TTL);
    }

    public List<ChatHistoryItem> getHistory(String sessionId) {
        List<String> raw = redis.opsForList().range(HIST_PREFIX + sessionId, 0, -1);
        if (raw == null) return List.of();
        return raw.stream().map(this::parse).collect(Collectors.toList());
    }
}
```

### 5.4 GET /api/chatbot/history/{sessionId}

```java
// ChatbotController.java — endpoint mới
@GetMapping("/chatbot/history/{sessionId}")
public ResponseEntity<List<ChatHistoryItem>> getHistory(@PathVariable String sessionId) {
    return ResponseEntity.ok(chatHistoryService.getHistory(sessionId));
}
```

---

## 6. BOOKING-IN-CHAT FLOW

### 6.1 State Machine

```
GREETING
    ↓ (user nhắn tour search / "đặt tour X")
SEARCHING
    ↓ (bot trả tour cards, user chọn 1 tour)
SELECTING_DEPARTURE
    ↓ (user chọn ngày khởi hành)
COLLECTING_CONTACT
    ↓ (user nhắn "Tên, SĐT, email")
AWAITING_CONFIRMATION
    ↓ (user nhấn "Xác nhận" button)
BOOKING_CONFIRMED
    ↓ (reset về GREETING sau 5 phút hoặc user nhắn tiếp)
GREETING
```

> **Lưu ý:** `COLLECTING_PASSENGERS` được đơn giản hóa — số người được hỏi ở bước SEARCHING (khi user nói "đặt tour", bot hỏi "Quý khách đi bao nhiêu người?"). Không cần state riêng.

### 6.2 Intent detection

```java
// ChatbotService.java
private boolean isBookingIntent(String msg) {
    return msg.matches(
        ".*(đặt tour|book tour|đặt chỗ|giữ chỗ|mua tour|đăng ký tour|" +
        "tôi muốn đặt|em muốn đặt|cho tôi đặt|cho em đặt).*"
    );
}
```

### 6.3 Booking flow — backend logic (ChatbotBookingService.java)

#### handleSelectTour — user chọn tour

```java
private ChatMessageResponse handleSelectTour(int tourIndex, ConversationState state, String sessionId) {
    List<Integer> tourIds = state.getLastTourIds();

    if (tourIds == null || tourIndex < 1 || tourIndex > tourIds.size()) {
        return buildTextResponse(
            "Quý khách vui lòng chọn số tour (1, 2, 3...) từ kết quả tìm kiếm ạ.",
            List.of(), sessionId
        );
    }

    Integer selectedTourId = tourIds.get(tourIndex - 1);
    TourDetailResponse tour = tourCatalogFeignClient.getTourById(selectedTourId);
    List<DepartureInfo> departures = tourCatalogFeignClient.getUpcomingDepartures(selectedTourId);

    // Save to slots
    state.getSlots().put("selectedTourId", String.valueOf(selectedTourId));
    state.getSlots().put("selectedTourCode", tour.getTourCode());
    state.getSlots().put("selectedTourName", tour.getName());
    state.getSlots().put("selectedTourImage", tour.getImageUrl());
    state.setStage(ConversationStage.SELECTING_DEPARTURE);
    conversationStateService.save(sessionId, state);

    // Build departure date options
    String datesText = buildDepartureDateText(departures);

    return buildTextResponse(
        "Tour **" + tour.getName() + "** có các ngày khởi hành sau:\n\n" + datesText +
        "\n\nQuý khách muốn đi ngày nào ạ?",
        buildDepartureDateQuickActions(departures), sessionId
    );
}
```

#### handleSelectDeparture — user chọn ngày khởi hành

```java
private ChatMessageResponse handleSelectDeparture(String msg, ConversationState state, String sessionId) {
    // Detect departure ID from message (slot-filling từ quick action hoặc text)
    DepartureInfo dep = findMatchingDeparture(msg, state);

    if (dep == null) {
        return buildTextResponse(
            "Em chưa xác định được ngày khởi hành. Quý khách chọn một trong các ngày trên nhé ạ.",
            List.of(), sessionId
        );
    }

    state.getSlots().put("selectedDepartureId", String.valueOf(dep.getId()));
    state.getSlots().put("departureDate",        dep.getDepartureDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    state.getSlots().put("departureCity",        dep.getDepartureCity());
    state.getSlots().put("duration",             dep.getDuration());
    state.getSlots().put("adultPrice",           String.valueOf(dep.getAdultPrice()));
    state.getSlots().put("childPrice",           String.valueOf(dep.getChildPrice()));

    // Đã có adults/children từ bước search?
    boolean hasPassengers = state.getSlots().containsKey("adults");

    if (!hasPassengers) {
        state.setStage(ConversationStage.COLLECTING_CONTACT);
        conversationStateService.save(sessionId, state);
        return buildTextResponse(
            "Để em giữ chỗ, anh/chị cho em xin:\n" +
            "• 📛 **Họ tên đầy đủ** người đặt\n" +
            "• 📞 **Số điện thoại** liên hệ\n\n" +
            "_(Nhập theo dạng: Nguyễn Văn A, 0901234567)_",
            List.of(), sessionId
        );
    }

    return buildContactRequestMessage(state, sessionId);
}
```

#### handleContactInput — thu thập name + phone + (email)

```java
private ChatMessageResponse handleContactInput(String msg, ConversationState state, String sessionId) {
    Map<String, String> slots = state.getSlots();

    // Parse: "Nguyễn Văn A, 0901234567, email@gmail.com"
    // hoặc 2 bước: trước đó đã có name+phone → bây giờ nhập email
    ContactInfo contact = parseContact(msg);

    if (contact == null) {
        return buildTextResponse(
            "Em chưa đọc được thông tin. Quý khách vui lòng nhập theo dạng:\n" +
            "**Họ tên, số điện thoại**\n" +
            "Ví dụ: **Nguyễn Văn A, 0901234567**",
            List.of(), sessionId
        );
    }

    if (contact.getName() != null)  slots.put("contactName",  contact.getName());
    if (contact.getPhone() != null) slots.put("contactPhone", contact.getPhone());
    if (contact.getEmail() != null) slots.put("contactEmail", contact.getEmail());

    // Nếu chưa có email → hỏi email
    if (!slots.containsKey("contactEmail")) {
        conversationStateService.save(sessionId, state);
        return buildTextResponse(
            "Cảm ơn! Cho em xin địa chỉ email để gửi xác nhận đặt tour nhé ạ?",
            List.of(), sessionId
        );
    }

    // Đủ thông tin → show confirm card
    return buildBookingConfirmCard(state, sessionId);
}
```

#### buildBookingConfirmCard — show confirm card

```java
private ChatMessageResponse buildBookingConfirmCard(ConversationState state, String sessionId) {
    Map<String, String> s = state.getSlots();

    int adults   = Integer.parseInt(s.getOrDefault("adults",   "1"));
    int children = Integer.parseInt(s.getOrDefault("children", "0"));
    long adultPrice  = Long.parseLong(s.getOrDefault("adultPrice",  "0"));
    long childPrice  = Long.parseLong(s.getOrDefault("childPrice",  "0"));
    long totalAmount = adultPrice * adults + childPrice * children;

    BookingConfirmData data = BookingConfirmData.builder()
        .tourName(s.get("selectedTourName"))
        .tourCode(s.get("selectedTourCode"))
        .tourImage(s.get("selectedTourImage"))
        .departureDate(s.get("departureDate"))
        .departureCity(s.get("departureCity"))
        .duration(s.get("duration"))
        .adults(adults)
        .children(children)
        .adultPrice(adultPrice)
        .childPrice(childPrice)
        .totalAmount(totalAmount)
        .contactName(s.get("contactName"))
        .contactPhone(s.get("contactPhone"))
        .contactEmail(s.get("contactEmail"))
        .build();

    state.setStage(ConversationStage.AWAITING_CONFIRMATION);
    conversationStateService.save(sessionId, state);

    return ChatMessageResponse.builder()
        .sessionId(sessionId)
        .message("Em đã tổng hợp thông tin đặt tour. Quý khách kiểm tra và xác nhận nhé ạ!")
        .type("BOOKING_CONFIRM")
        .bookingConfirmData(data)
        .build();
}
```

#### processBookingConfirmation — gọi booking + payment API

```java
public ChatMessageResponse processBookingConfirmation(ConversationState state, String sessionId) {
    Map<String, String> s = state.getSlots();

    int adults   = Integer.parseInt(s.getOrDefault("adults",   "1"));
    int children = Integer.parseInt(s.getOrDefault("children", "0"));

    // === BƯỚC 1: Build passengers (dateOfBirth là placeholder) ===
    List<PassengerRequest> passengers = new ArrayList<>();
    for (int i = 0; i < adults; i++) {
        passengers.add(PassengerRequest.builder()
            .fullName(s.get("contactName") + (adults > 1 ? " " + (i+1) : ""))
            .gender("OTHER")
            .dateOfBirth(LocalDate.of(1990, 1, 1))  // placeholder — REQUIRED in DB
            .type("ADULT")
            .singleRoom(false)
            .build());
    }
    for (int i = 0; i < children; i++) {
        passengers.add(PassengerRequest.builder()
            .fullName("Trẻ em " + (i+1))
            .gender("OTHER")
            .dateOfBirth(LocalDate.of(2015, 1, 1))  // placeholder
            .type("CHILD")
            .singleRoom(false)
            .build());
    }

    // === BƯỚC 2: Tính total amount ===
    long adultPrice  = Long.parseLong(s.getOrDefault("adultPrice",  "0"));
    long childPrice  = Long.parseLong(s.getOrDefault("childPrice",  "0"));
    long totalAmount = adultPrice * adults + childPrice * children;

    // === BƯỚC 3: Tạo booking ===
    CreateBookingRequest bookingReq = CreateBookingRequest.builder()
        .departureId(Integer.parseInt(s.get("selectedDepartureId")))
        .userId(state.getUserId())                         // null nếu guest
        .contactFullName(s.get("contactName"))
        .contactPhone(s.get("contactPhone"))
        .contactEmail(s.get("contactEmail"))
        .contactAddress("Đặt qua chatbot")                // REQUIRED (NotBlank)
        .numberOfAdults(adults)
        .numberOfChildren(children)
        .totalPrice(totalAmount)
        .note("Đặt qua chatbot tự động")
        .passengers(passengers)
        .build();

    try {
        BookingResponse bookingRes = chatbotBookingFeignClient.createBooking(bookingReq);
        String bookingCode = bookingRes.getBookingCode();

        // === BƯỚC 4: Tạo payment link (PayOS) ===
        String paymentUrl = null;
        try {
            PaymentCreateRequest payReq = PaymentCreateRequest.builder()
                .bookingCode(bookingCode)
                .amount(totalAmount)
                .description("Thanh toán " + s.get("selectedTourCode"))
                .returnUrl(frontendBaseUrl + "/booking/success?code=" + bookingCode)
                .cancelUrl(frontendBaseUrl  + "/booking/cancel?code=" + bookingCode)
                .build();
            PaymentResponse payRes = chatbotPaymentFeignClient.createPayOSPayment(payReq);
            paymentUrl = payRes.getCheckoutUrl();
        } catch (Exception payEx) {
            log.warn("[ChatbotBooking] Payment link failed for {}: {}", bookingCode, payEx.getMessage());
            // Không fail booking chỉ vì payment link lỗi
        }

        // === BƯỚC 5: Update state ===
        s.put("bookingCode", bookingCode);
        s.put("paymentUrl",  paymentUrl != null ? paymentUrl : "");
        state.setStage(ConversationStage.BOOKING_CONFIRMED);
        conversationStateService.save(sessionId, state);

        // === BƯỚC 6: Build success message ===
        String successMsg = buildBookingSuccessMessage(s, bookingCode, totalAmount, paymentUrl);
        return buildTextResponse(successMsg,
            List.of(
                QuickAction.of("Tra cứu đơn hàng", "tra cứu đơn " + bookingCode),
                QuickAction.of("Tìm tour khác",    "tìm tour"),
                QuickAction.of("Liên hệ hỗ trợ",  "tôi cần hỗ trợ")
            ), sessionId);

    } catch (FeignException e) {
        log.error("[ChatbotBooking] Booking failed: {}", e.getMessage());
        state.setStage(ConversationStage.AWAITING_CONFIRMATION); // Giữ nguyên để retry
        conversationStateService.save(sessionId, state);
        return buildTextResponse(
            "❌ Hệ thống đặt chỗ đang bận. Quý khách vui lòng thử lại sau ít phút ạ.\n" +
            "Nếu cần hỗ trợ ngay, gọi hotline: **1800 xxxx** (miễn phí)",
            List.of(
                QuickAction.of("Thử lại", "xác nhận đặt tour"),
                QuickAction.of("Hủy",     "hủy đặt tour")
            ), sessionId);
    }
}

private String buildBookingSuccessMessage(Map<String, String> s, String bookingCode,
                                          long totalAmount, String paymentUrl) {
    String base = String.format(
        "🎉 **Đặt tour thành công!**\n\n" +
        "📋 Mã booking: **%s**\n" +
        "🏞️ Tour: %s\n" +
        "📅 Khởi hành: %s từ %s\n" +
        "⏳ Thời gian: %s\n" +
        "👥 Số khách: %s\n" +
        "💰 Tổng tiền: **%s**\n" +
        "📧 Email xác nhận: %s\n\n",
        bookingCode,
        s.get("selectedTourName"),
        s.get("departureDate"),
        s.get("departureCity"),
        s.getOrDefault("duration", ""),
        formatPassengers(
            Integer.parseInt(s.getOrDefault("adults", "1")),
            Integer.parseInt(s.getOrDefault("children", "0"))
        ),
        formatCurrency(totalAmount),
        s.getOrDefault("contactEmail", "")
    );

    if (paymentUrl != null && !paymentUrl.isBlank()) {
        base += "💳 **[Thanh toán ngay](" + paymentUrl + ")**\n\n";
    }

    base += "⚠️ Lưu lại mã booking **" + bookingCode + "** để tra cứu sau nhé!";
    return base;
}
```

### 6.4 API endpoints thực tế (booking-service)

**Quan trọng:** Các field bắt buộc khi gọi `POST /api/bookings/create`:

```java
// booking-service: CreateBookingRequest
public class CreateBookingRequest {
    private Integer    departureId;           // REQUIRED — departure ID
    private Integer    userId;                // NULLABLE — null = guest booking ✅
    private String     contactFullName;       // REQUIRED
    private String     contactPhone;          // REQUIRED
    private String     contactEmail;          // OPTIONAL (nhưng nên có)
    @NotBlank
    private String     contactAddress;        // REQUIRED → chatbot dùng "Đặt qua chatbot"
    private Integer    numberOfAdults;        // REQUIRED
    private Integer    numberOfChildren;
    private Long       totalPrice;
    private String     note;
    private List<PassengerRequest> passengers; // REQUIRED

    // PassengerRequest:
    //   fullName: String REQUIRED
    //   gender: String ("MALE", "FEMALE", "OTHER")
    //   dateOfBirth: LocalDate NOT NULL → dùng placeholder 1990-01-01 / 2015-01-01
    //   type: String ("ADULT", "CHILD")
    //   singleRoom: Boolean
}

// BookingResponse chứa:
//   bookingId: Integer
//   bookingCode: String  ← "BK" + UUID.substring(0,8) — VD: BK9f3a2c1b
//   status: BookingStatus
//   totalPrice: Long
//   ...
```

**GET booking by code (order lookup):**
```
GET /api/bookings/payment/{bookingCode}
→ trả BookingDetailResponse (đầy đủ thông tin)
→ KHÔNG cần JWT nếu endpoint không có @PreAuthorize
→ Kiểm tra lại security config trong booking-service
```

**Payment API:**
```
POST /api/payment/payos/create
Body: { bookingCode, amount, description, returnUrl, cancelUrl }
Response: { checkoutUrl }   ← link thanh toán PayOS

POST /api/payment/vnpay/create  
Body: { bookingCode, amount, ...}
Response: { paymentUrl }       ← link VNPay
```

### 6.5 ChatbotBookingFeignClient.java

```java
@FeignClient(name = "booking-service", url = "${service.booking.url:http://booking-service:8083}")
public interface ChatbotBookingFeignClient {

    @PostMapping("/api/bookings/create")
    BookingResponse createBooking(@RequestBody CreateBookingChatRequest request);

    @GetMapping("/api/bookings/payment/{bookingCode}")
    BookingDetailResponse getBookingByCode(@PathVariable String bookingCode);

    @GetMapping("/api/bookings/chatbot/my-bookings")
    List<BookingDetailResponse> getMyRecentBookings(
        @RequestParam Integer userId,
        @RequestParam(defaultValue = "3") int limit
    );
}
```

### 6.6 ChatbotPaymentFeignClient.java

```java
@FeignClient(name = "payment-service", url = "${service.payment.url:http://payment-service:8086}")
public interface ChatbotPaymentFeignClient {

    @PostMapping("/api/payment/payos/create")
    PayOSResponse createPayOSPayment(@RequestBody PaymentCreateRequest request);

    @PostMapping("/api/payment/vnpay/create")
    VNPayResponse createVNPayPayment(@RequestBody PaymentCreateRequest request);
}
```

### 6.7 Edge cases booking flow

| Tình huống | Xử lý |
|-----------|-------|
| User hủy khi đang ở AWAITING_CONFIRMATION | Reset stage → GREETING, clear booking slots |
| Departure đã đủ slot (409 từ booking-service) | "Rất tiếc, chuyến ngày X đã hết chỗ. Quý khách muốn đi ngày khác không?" |
| SĐT sai format (không phải 10 số VN) | "SĐT chưa đúng định dạng (10 số, bắt đầu 0). Vui lòng nhập lại." |
| Email sai format | "Email chưa đúng định dạng. Vui lòng nhập lại." |
| User nhắn "đặt tour" khi đang AWAITING_CONFIRMATION | "Quý khách đang có booking chờ xác nhận. Xác nhận ngay hay hủy để đặt tour mới?" |
| Feign timeout (booking-service) | Retry 1 lần, sau đó báo lỗi + hotline |
| PayOS lỗi nhưng booking thành công | Vẫn hiện booking code, note "Thanh toán tại quầy hoặc gọi hotline" |
| User đăng nhập → pre-fill từ profile | Lấy name/phone/email từ IAM service → bỏ qua bước collect contact |

---

## 7. ORDER LOOKUP FLOW

### 7.1 Intent detection

```java
private boolean isOrderLookupIntent(String msg) {
    return msg.matches(
        ".*(tra cứu|kiểm tra|xem đơn|đơn hàng|mã booking|mã đặt chỗ|" +
        "tình trạng tour|đã đặt chưa|thanh toán chưa|hủy tour|hủy booking|" +
        "tôi có đơn|đơn của tôi|xem booking).*"
    );
}
```

### 7.2 Luồng 3 bước

```
[Bước 1] TRIGGER — Bot hỏi code + tên
User: "tra cứu đơn hàng của tôi"
Bot:  "Để tra cứu thông tin đặt chỗ, Quý khách vui lòng cung cấp:
       • 📋 Mã đặt chỗ (Ví dụ: BK9f3a2c1b)
       • 📛 Họ tên đầy đủ người đặt tour

       _(Nếu quên mã, Quý khách xem trong email xác nhận nhé ạ)_"

→ state.stage = ORDER_LOOKUP

// Pre-fill: nếu user đã nhập code trong câu trigger
// "tra cứu đơn BK9f3a2c1b" → extract code → chỉ hỏi tên

[Bước 2] User cung cấp thông tin
User: "BK9f3a2c1b, Nguyễn Văn A"
(hoặc 2 dòng)

[Bước 3] Bot lookup + trả kết quả
→ Gọi GET /api/bookings/payment/{bookingCode}
→ Verify name (case-insensitive, bỏ dấu)
→ Hiện OrderResultCard hoặc error message
```

### 7.3 Backend lookup handler

```java
private ChatMessageResponse handleOrderLookupInput(String msg, ConversationState state, String sessionId) {
    Map<String, String> slots = state.getSlots();

    String code = extractBookingCode(msg);  // regex: BK[a-f0-9]{8} hoặc VTV\d+
    String name = extractFullName(msg);

    // Use pre-filled values từ slots nếu thiếu
    if (code == null) code = slots.get("lookupCode");
    if (name == null && state.getUserId() != null) {
        name = slots.get("userFullName");  // pre-fill từ profile
    }

    // Còn thiếu code?
    if (code == null) {
        return buildTextResponse(
            "Em chưa nhận được mã đặt chỗ. Quý khách nhập theo dạng:\n**BK9f3a2c1b, Nguyễn Văn A**",
            List.of(), sessionId
        );
    }

    // Còn thiếu tên?
    if (name == null) {
        slots.put("lookupCode", code);
        conversationStateService.save(sessionId, state);
        return buildTextResponse(
            "Cảm ơn! Em đã có mã **" + code + "**. " +
            "Quý khách vui lòng cho em họ tên đầy đủ để xác thực ạ:",
            List.of(), sessionId
        );
    }

    // Gọi booking-service
    try {
        BookingDetailResponse booking = chatbotBookingFeignClient.getBookingByCode(code);

        // Verify tên (optional nhưng nên có để bảo mật)
        if (!normalizeVN(booking.getContactFullName()).equalsIgnoreCase(normalizeVN(name))) {
            return buildTextResponse(
                "❌ Thông tin không khớp. Họ tên phải đúng với lúc đặt tour ạ.\n" +
                "Nếu cần hỗ trợ: Hotline **1800 xxxx**",
                List.of(QuickAction.of("Thử lại", "tra cứu đơn hàng")), sessionId
            );
        }

        state.setStage(ConversationStage.GREETING);
        conversationStateService.save(sessionId, state);
        return buildOrderResultMessage(booking, sessionId);

    } catch (FeignException.NotFound e) {
        return buildTextResponse(
            "❌ Không tìm thấy đơn **" + code + "**.\n\n" +
            "Quý khách kiểm tra lại:\n" +
            "✓ Mã đặt chỗ có đúng không? (ví dụ: BK9f3a2c1b)\n" +
            "✓ Họ tên có khớp với khi đặt tour?\n\n" +
            "Nếu cần hỗ trợ: Hotline **1800 xxxx** (miễn phí) ạ.",
            List.of(
                QuickAction.of("Thử lại",    "tra cứu đơn hàng"),
                QuickAction.of("Tìm tour mới", "tìm tour")
            ), sessionId
        );
    }
}
```

### 7.4 OrderResultCard message

```java
private ChatMessageResponse buildOrderResultMessage(BookingDetailResponse b, String sessionId) {
    String statusText = switch (b.getStatus().toString()) {
        case "PENDING_PAYMENT"  -> "🟡 Chờ thanh toán";
        case "PENDING_CONFIRM"  -> "🔵 Chờ xác nhận";
        case "CONFIRMED"        -> "🟢 Đã xác nhận";
        case "CANCELLED"        -> "🔴 Đã hủy";
        case "COMPLETED"        -> "✅ Hoàn thành";
        default                 -> "⚪ " + b.getStatus();
    };

    String msg = String.format(
        "📋 **THÔNG TIN ĐẶT TOUR**\n\n" +
        "📋 Mã đặt chỗ: **%s**\n" +
        "🏞️ Tour: %s\n" +
        "📅 Khởi hành: %s\n" +
        "🚌 Từ: %s\n" +
        "👥 Số khách: %s\n" +
        "💰 Tổng tiền: **%s**\n" +
        "📊 Trạng thái: **%s**\n" +
        "%s",
        b.getBookingCode(),
        b.getTourName(),
        b.getDepartureDate(),
        b.getDepartureCity(),
        formatPassengers(b.getNumberOfAdults(), b.getNumberOfChildren()),
        formatCurrency(b.getTotalPrice()),
        statusText,
        "PENDING_PAYMENT".equals(b.getStatus().toString())
            ? "\n💳 **[Thanh toán ngay](" + generatePaymentLink(b.getBookingCode(), b.getTotalPrice()) + ")**"
            : ""
    );

    return buildTextResponse(msg, List.of(
        QuickAction.of("Tra cứu đơn khác",  "tra cứu đơn hàng"),
        QuickAction.of("Tìm tour mới",      "tìm tour"),
        QuickAction.of("Liên hệ hỗ trợ",   "tôi cần hỗ trợ")
    ), sessionId);
}
```

### 7.5 Pre-fill cho user đã đăng nhập

```java
// Nếu user đã login và hỏi "đơn hàng của tôi" (không có code cụ thể)
if (isGenericOrderQuery(msg) && state.getUserId() != null) {
    List<BookingDetailResponse> myBookings =
        chatbotBookingFeignClient.getMyRecentBookings(state.getUserId(), 3);

    if (myBookings.isEmpty()) {
        return buildTextResponse(
            "Quý khách chưa có đơn đặt tour nào. Muốn tìm tour không ạ?",
            List.of(QuickAction.of("Tìm tour ngay", "tìm tour")), sessionId
        );
    }

    StringBuilder sb = new StringBuilder("📋 **Đơn đặt tour gần đây:**\n\n");
    for (BookingDetailResponse b : myBookings) {
        sb.append(String.format("• **%s** — %s — %s\n",
            b.getBookingCode(), b.getTourName(), getStatusEmoji(b.getStatus().toString())));
    }
    sb.append("\nQuý khách muốn xem chi tiết đơn nào ạ?");

    return buildTextResponse(sb.toString(), List.of(), sessionId);
}
```

### 7.6 Booking-service endpoint mới

```java
// GET /api/bookings/chatbot/my-bookings?userId=X&limit=3
// booking-service/controller/BookingController.java — thêm endpoint

@GetMapping("/bookings/chatbot/my-bookings")
// @PermitAll — chatbot gọi từ analytics-service (internal)
public ResponseEntity<List<BookingResponse>> getMyBookingsForChatbot(
        @RequestParam Integer userId,
        @RequestParam(defaultValue = "3") int limit) {
    return ResponseEntity.ok(bookingService.getRecentByUser(userId, limit));
}
```

**Note:** `GET /api/bookings/payment/{bookingCode}` (existing endpoint) có thể đã đủ cho order lookup. Cần check security config xem endpoint này cần JWT không. Nếu cần, thêm `@PermitAll` hoặc bypass trong gateway config.

### 7.7 Edge cases order lookup

| Tình huống | Xử lý |
|-----------|-------|
| Code đúng, tên sai | "Thông tin không khớp..." |
| Code không tồn tại | "Không tìm thấy đơn..." + hotline |
| Booking đã hủy | Hiển thị đầy đủ với status "🔴 Đã hủy", không có link thanh toán |
| Booking đã hoàn thành | "✅ Hoàn thành" + gợi ý "Đặt tour lần tiếp" |
| User đang trong booking flow khi lookup | "Quý khách đang đặt tour [X]. Xác nhận trước hay hủy để tra cứu đơn?" |

---

## 8. FRONTEND CHANGES (ChatbotWidget)

### 8.1 sessionId persistence

```javascript
// THAY: const [sessionId] = useState(`session_${Date.now()}`);
// BẰNG:
const initSession = () => {
    let sid = localStorage.getItem('chatbot_session_id');
    if (!sid || sid.length < 10) {
        sid = `session_${Date.now()}_${Math.random().toString(36).substr(2,9)}`;
        localStorage.setItem('chatbot_session_id', sid);
    }
    return sid;
};
const [sessionId] = useState(initSession);
```

### 8.2 userId từ Redux

```javascript
// THAY: const userId = null;
// BẰNG:
const userId = useSelector(state => state.auth?.user?.id ?? null);
```

### 8.3 Load chat history khi mount

```javascript
useEffect(() => {
    const loadHistory = async () => {
        try {
            const resp = await axios.get(
                `${API_BASE_URL}/api/chatbot/history/${sessionId}`
            );
            if (resp.data && resp.data.length > 0) {
                setMessages(resp.data.map(h => ({
                    role: h.role,
                    content: h.content,
                    timestamp: h.timestamp
                })));
            }
        } catch (e) {
            console.log('No history or error loading history');
        }
    };
    loadHistory();
}, [sessionId]);
```

### 8.4 Render BookingConfirmCard

```javascript
// Trong message render loop
{messages.map((msg, idx) => (
    <div key={idx} className={`message ${msg.role}`}>
        {msg.type === 'BOOKING_CONFIRM' ? (
            <BookingConfirmCard
                data={msg.bookingConfirmData}
                onConfirm={() => sendMessage('xác nhận đặt tour')}
                onCancel={() => sendMessage('hủy đặt tour')}
            />
        ) : (
            <MessageBubble content={msg.content} />
        )}
    </div>
))}
```

### 8.5 BookingConfirmCard.jsx

```jsx
const BookingConfirmCard = ({ data, onConfirm, onCancel }) => {
    const totalStr = new Intl.NumberFormat('vi-VN').format(data.totalAmount) + ' ₫';

    return (
        <div className={styles.confirmCard}>
            {data.tourImage && (
                <img src={data.tourImage} alt={data.tourName} className={styles.tourImage} />
            )}
            <div className={styles.cardBody}>
                <h4>📋 Xác nhận đặt tour</h4>
                <div className={styles.infoGrid}>
                    <span>🏞️ Tour:</span>
                    <span><strong>{data.tourName}</strong></span>

                    <span>📅 Khởi hành:</span>
                    <span>{data.departureDate} từ {data.departureCity}</span>

                    <span>⏳ Thời gian:</span>
                    <span>{data.duration}</span>

                    <span>👥 Số khách:</span>
                    <span>{data.adults} người lớn{data.children > 0 ? ` + ${data.children} trẻ em` : ''}</span>

                    <span>💰 Tổng tiền:</span>
                    <span className={styles.totalPrice}><strong>{totalStr}</strong></span>

                    <span>📛 Người đặt:</span>
                    <span>{data.contactName}</span>

                    <span>📞 SĐT:</span>
                    <span>{data.contactPhone}</span>

                    {data.contactEmail && <>
                        <span>📧 Email:</span>
                        <span>{data.contactEmail}</span>
                    </>}
                </div>
                <div className={styles.actions}>
                    <button className={styles.confirmBtn} onClick={onConfirm}>
                        ✅ Xác nhận đặt tour
                    </button>
                    <button className={styles.cancelBtn} onClick={onCancel}>
                        ❌ Hủy
                    </button>
                </div>
            </div>
        </div>
    );
};
```

### 8.6 Bật lại tour cards

```javascript
// ChatbotWidget.jsx — uncomment phần tour card render (lines 326-360)
// Thêm date chips dựa theo departures
{msg.tourCards && msg.tourCards.map((tour, i) => (
    <TourCard
        key={tour.tourId}
        tour={tour}
        index={i + 1}
        onSelect={() => sendMessage(`tour ${i + 1}`)}
    />
))}
```

---

## 9. ROADMAP TRIỂN KHAI (4 PHASE)

### Phase 1 — Quick Wins (1-2 ngày)

> Không cần Redis, không cần RabbitMQ. Chỉ fix frontend.

| # | Việc làm | File |
|---|---------|------|
| 1 | sessionId → localStorage persist | `ChatbotWidget.jsx` |
| 2 | Bật lại tour card render | `ChatbotWidget.jsx` |
| 3 | userId từ Redux | `ChatbotWidget.jsx` |
| 4 | URL từ env var | `ChatbotWidget.jsx` |

### Phase 2 — Redis Session (2-3 ngày)

> Thêm Redis vào analytics-service.

| # | Việc làm | File |
|---|---------|------|
| 1 | Thêm Redis dependency vào pom.xml | `analytics-service/pom.xml` |
| 2 | Cấu hình Redis trong application.yaml | `analytics-service/src/main/resources/application.yaml` |
| 3 | Tạo ConversationState.java | `analytics-service/.../entity/` |
| 4 | Tạo ConversationStateService.java | `analytics-service/.../service/` |
| 5 | Tạo ChatHistoryService.java | `analytics-service/.../service/` |
| 6 | Update ChatbotController: GET /history/{sid} | `analytics-service/.../controller/` |
| 7 | Update ChatbotService: dùng ConversationStateService | `analytics-service/.../service/` |
| 8 | ChatbotWidget: load history on mount | `ChatbotWidget.jsx` |

### Phase 3 — RabbitMQ Sync (1-2 ngày)

> Thêm debounce sync Pinecone.

| # | Việc làm | File |
|---|---------|------|
| 1 | Tạo TourSyncEvent.java | `shared-library` hoặc `analytics-service` |
| 2 | Tạo ChatbotRabbitMQConfig.java | `analytics-service/.../config/` |
| 3 | Tạo ChatbotSyncEventListener.java | `analytics-service/.../listener/` |
| 4 | Update VectorSyncService: syncToursByIds(), deleteToursByIds() | `analytics-service/.../service/` |
| 5 | Publisher trong TourService | `tour-catalog-service/.../service/` |
| 6 | Test: add tour → 5 min → Pinecone updated | manual test |

### Phase 4 — Booking-in-Chat (3-5 ngày)

> Tích hợp booking + payment.

| # | Việc làm | File |
|---|---------|------|
| 1 | Tạo ChatbotBookingFeignClient.java | `analytics-service/.../feign/` |
| 2 | Tạo ChatbotPaymentFeignClient.java | `analytics-service/.../feign/` |
| 3 | Tạo ConversationStage.java enum | `analytics-service/.../entity/` |
| 4 | Tạo BookingConfirmData.java | `analytics-service/.../dto/` |
| 5 | Tạo ChatbotBookingService.java | `analytics-service/.../service/` |
| 6 | Update ChatbotService: route đến ChatbotBookingService | `analytics-service/.../service/` |
| 7 | Thêm GET /bookings/chatbot/my-bookings endpoint | `booking-service/.../controller/` |
| 8 | Check security: GET /api/bookings/payment/{code} cần JWT không | `booking-service/.../config/` |
| 9 | Tạo BookingConfirmCard.jsx + .module.scss | `tourism_frontend/client-side/src/` |
| 10 | Update ChatbotWidget: render BOOKING_CONFIRM card | `ChatbotWidget.jsx` |
| 11 | Test end-to-end booking flow | manual test |
| 12 | Test order lookup flow | manual test |

---

## 10. DANH SÁCH FILE CẦN TẠO/SỬA

### analytics-service

| File | Hành động | Mô tả |
|------|----------|-------|
| `pom.xml` | SỬA | Thêm spring-boot-starter-data-redis, spring-boot-starter-amqp |
| `application.yaml` | SỬA | Thêm redis.host/port, rabbitmq config, chatbot.sync config |
| `entity/ConversationStage.java` | TẠO | Enum: GREETING, SEARCHING, SELECTING_DEPARTURE, COLLECTING_CONTACT, AWAITING_CONFIRMATION, BOOKING_CONFIRMED, ORDER_LOOKUP |
| `entity/ConversationState.java` | TẠO | POJO: sessionId, stage, history, slots, lastTourIds, userId, userInfo |
| `dto/BookingConfirmData.java` | TẠO | DTO: tourName, tourCode, tourImage, departureDate, departureCity, duration, adults, children, adultPrice, childPrice, totalAmount, contactName, contactPhone, contactEmail |
| `dto/ChatMessageResponse.java` | SỬA | Thêm field: type (String), bookingConfirmData (BookingConfirmData) |
| `service/ConversationStateService.java` | TẠO | Redis CRUD với TTL 2h/24h |
| `service/ChatHistoryService.java` | TẠO | Redis List, appendMessage, getHistory, max 50 |
| `service/ChatbotBookingService.java` | TẠO | Full booking state machine |
| `service/ChatbotService.java` | SỬA | Route intent đến ChatbotBookingService, dùng ConversationStateService |
| `feign/ChatbotBookingFeignClient.java` | TẠO | createBooking, getBookingByCode, getMyRecentBookings |
| `feign/ChatbotPaymentFeignClient.java` | TẠO | createPayOSPayment, createVNPayPayment |
| `config/ChatbotRabbitMQConfig.java` | TẠO | Queue + Binding config |
| `listener/ChatbotSyncEventListener.java` | TẠO | @RabbitListener + debounce |
| `controller/ChatbotController.java` | SỬA | Thêm GET /history/{sessionId} |

### booking-service

| File | Hành động | Mô tả |
|------|----------|-------|
| `controller/BookingController.java` | SỬA | Thêm GET /chatbot/my-bookings endpoint |
| `config/SecurityConfig.java` | KIỂM TRA | GET /api/bookings/payment/** cần permit hoặc để chatbot gọi qua service account |

### tour-catalog-service

| File | Hành động | Mô tả |
|------|----------|-------|
| `service/TourServiceImpl.java` | SỬA | Publish TourSyncEvent sau create/update/delete |
| `config/RabbitMQConfig.java` | SỬA/TẠO | Thêm tourism.events exchange config |

### Frontend (tourism_frontend)

| File | Hành động | Mô tả |
|------|----------|-------|
| `ChatbotWidget/ChatbotWidget.jsx` | SỬA | sessionId persist, userId từ Redux, load history, render BookingConfirmCard, bật tour cards |
| `ChatbotWidget/BookingConfirmCard.jsx` | TẠO | Component confirm booking |
| `ChatbotWidget/BookingConfirmCard.module.scss` | TẠO | Styles cho confirm card |

---

## 11. CÁC LƯU Ý QUAN TRỌNG KHI IMPLEMENT

### 11.1 Security

1. **Guest booking:** `userId = null` là hợp lệ — đã hỗ trợ trong booking-service
2. **Order lookup:** Không nên expose booking info mà không verify danh tính. Verify bằng contactName (case-insensitive, strip diacritics)
3. **`GET /api/bookings/payment/{code}`:** Check xem endpoint này có require auth không. Nếu có → cần thêm service account hoặc create endpoint mới `@PermitAll`
4. **Chatbot không được store payment card info** — chỉ tạo payment link

### 11.2 Data constraints

1. **`contactAddress` REQUIRED (NotBlank):** Chatbot dùng `"Đặt qua chatbot"` as default
2. **`dateOfBirth` NOT NULL trong BookingPassenger:**
   - Adults: `LocalDate.of(1990, 1, 1)` — placeholder
   - Children: `LocalDate.of(2015, 1, 1)` — placeholder
   - **TODO Phase 4+:** Hỏi thêm ngày sinh nếu muốn chính xác
3. **`bookingCode` format:** `"BK" + UUID.substring(0,8)` — VD: `BK9f3a2c1b`
4. **`totalPrice` trong CreateBookingRequest:** Chatbot tính = `adultPrice * adults + childPrice * children`

### 11.3 Redis trong Docker

Kiểm tra `docker-compose.yml` đã có Redis chưa. Nếu chưa → thêm:
```yaml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
  volumes:
    - redis_data:/data
  networks:
    - tourism-network
```

### 11.4 Hotline fallback

Mọi error message trong chatbot phải include hotline:
```
"Nếu cần hỗ trợ ngay: Hotline **1800 xxxx** (miễn phí) ạ."
```

---

*Phiên bản 3.0 — 2026-05-25*  
*Dựa trên: 28 screenshots Tripi chatbot (travel.com.vn) + phân tích code booking-service + payment-service*  
*Tác giả: AI Analysis — Future Travel Dev Team*  
*Trạng thái: PLANNING ONLY — chưa implement code*
