# CHATBOT UPGRADE PLAN — Future Travel vs Vietravel (travel.com.vn)
> **Ngày:** 2026-05-21 | **Dựa trên:** Phân tích code thực tế + so sánh Tripi (Vietravel)

---

## 1. HIỆN TRẠNG HỆ THỐNG

### 1.1 Kiến trúc hiện tại (đang chạy)

```
[ChatbotWidget.jsx]
      │  POST /api/chatbot/chat
      ▼
[analytics-service :8087]
  ChatbotService.handleUserMessage()
    ├── 1. Regex intent (DISCOUNT / COUPON / other) → topK
    ├── 2. VectorService.searchSimilar() → Pinecone (llama-text-embed-v2, 1024d)
    ├── 3. buildEnhancedContext() — re-rank, filter coupon/discount docs
    ├── 4. buildEnhancedPrompt() — system prompt tiếng Việt
    ├── 5. callGeminiAPI() — Gemini 2.0 Flash (temp=0.7)
    └── 6. buildTourSuggestions() + buildQuickActions()

[VectorSyncService] — cron 2:00 AM
  Feign → tour-catalog, booking-service → upsert Pinecone
  5 namespace: TOUR_SUMMARY, TOUR_DEPARTURE, LOCATION, REVIEW, COUPON
```

### 1.2 Những gì đang HOẠT ĐỘNG tốt ✅
- RAG pipeline: Pinecone + Gemini trả lời tiếng Việt chất lượng tốt
- Mascot video (chroma-key canvas), speech bubble xoay
- Intent detection discount/coupon với regex
- Context building re-rank giá giảm, coupon
- Unit test + controller test đầy đủ

### 1.3 Những gì THIẾU ❌

| Tính năng | Trạng thái | So với Tripi |
|---|---|---|
| Tour cards (ảnh + giá + link) | ❌ Commented out | Tripi có rich cards |
| Quick actions buttons | ❌ Commented out | Tripi có |
| Multi-turn context (nhớ lịch sử) | ❌ Mỗi câu độc lập | Tripi nhớ toàn session |
| Slot-filling (hỏi thiếu info) | ❌ Không | Tripi hỏi: điểm đến, tháng, số người |
| Booking flow trong chat | ❌ Không | Tripi đặt tour → sinh mã booking |
| Tra cứu đơn hàng | ❌ Không | Tripi có lookup mã + tên |
| Lead capture (tên/SĐT) | ❌ Không | Tripi thu thập |
| Welcome chips gợi ý câu hỏi | ❌ Không | Tripi có |
| Like/Dislike feedback | ❌ Không | Tripi có |
| User auth (gửi userId) | ❌ userId=null hardcoded | — |

---

## 2. SO SÁNH VỚI TRAVEL.COM.VN (TRIPI - VIETRAVEL)

### 2.1 Điểm tương đương
- Chatbot AI tiếng Việt tự nhiên
- Mascot/branding nhân vật
- Trả lời thông tin tour, giá

### 2.2 Khoảng cách lớn nhất (Gap Priority)

```
Gap 1 — RẤT LỚN: Không có booking flow trong chat
  Tripi: tìm tour → chọn ngày → nhập tên/SĐT → xác nhận → mã booking → link thanh toán
  Future Travel: chỉ tư vấn text, không thể đặt

Gap 2 — LỚN: Không multi-turn context
  Tripi: nhớ "tao ở HCM, đi biển tháng 7, 2 người" trong toàn session
  Future Travel: hỏi lại từ đầu mỗi câu

Gap 3 — LỚN: Tour cards bị comment
  Tripi: card ảnh + mã tour + nhiều ngày khởi hành + giá đỏ + nút "Xem ngay"
  Future Travel: code có sẵn nhưng bị {/* */} comment

Gap 4 — VỪA: Slot-filling thông minh
  Tripi: tự detect "tao ở HCM" → departureCity, "tháng 7" → travelMonth
  Future Travel: không detect
```

---

## 3. PHÂN TÍCH: CÓ CẦN RABBITMQ VÀ REDIS KHÔNG?

### 3.1 RabbitMQ — Đánh giá: KHÔNG cần cho chatbot core, CÓ ích cho data sync

**Hiện trạng RabbitMQ trong hệ thống:**
- ✅ **booking-service**: Dùng cho outbox relay → notification/analytics events
- ✅ **notification-service**: Consumer `booking.notification.queue`
- ⚠️ **analytics-service**: Có `spring-boot-starter-amqp` trong pom.xml nhưng **KHÔNG dùng** trong chatbot logic hiện tại (chỉ khai báo dependency)

**Chatbot có cần RabbitMQ không?**

| Use case | Cần RabbitMQ? | Giải pháp thay thế |
|---|---|---|
| Multi-turn context | ❌ Không | Redis session store |
| Tour card data | ❌ Không | Pinecone đã có data |
| Booking flow trong chat | ❌ Không (sync Feign OK) | Feign → booking-service |
| Event-driven sync Pinecone | ✅ **CÓ ÍCH** (Phase 4) | Cron job 1h (đơn giản hơn) |
| Chatbot feedback analytics | ⚠️ Optional | Lưu DB trực tiếp OK |

**Kết luận RabbitMQ:**
> ❌ **Phase 1-3: KHÔNG cần thêm** — chatbot gọi booking-service qua Feign đồng bộ là đủ  
> ✅ **Phase 4 (optional)**: Dùng event từ tour-catalog-service khi tour/departure thay đổi → trigger re-sync Pinecone thay vì nightly cron. Nhưng cron mỗi 1 giờ cũng đủ dùng cho đến Phase 4.

### 3.2 Redis — Đánh giá: **CẦN THIẾT** cho Phase 2

**Hiện trạng Redis:**
- ✅ Redis container đang chạy (`tourism-redis :6379`)
- ✅ IAM service, API gateway, booking service đã dùng Redis
- ❌ **analytics-service CHƯA có** `spring-boot-starter-data-redis` trong pom.xml

**Chatbot cần Redis cho gì?**

| Use case | Redis key pattern | TTL | Priority |
|---|---|---|---|
| **Multi-turn conversation state** | `chatbot:session:{sessionId}` | 2 giờ | 🔴 P1 — Phase 2 |
| **Semantic cache** (tránh gọi Gemini trùng) | `chatbot:cache:{hash(question)}` | 30 phút | 🟡 P2 — Phase 2 |
| **Rate limiting** (chống spam) | `chatbot:ratelimit:{ip}` | 1 phút | 🟡 P2 — Phase 4 |

**Trước Phase 2**: Dùng `ConcurrentHashMap` in-memory (Option A) — đơn giản, không cần Redis. Migrate sang Redis khi cần scale.

**Kết luận Redis:**
> ✅ **Phase 2: CẦN** — thêm `spring-boot-starter-data-redis` vào analytics-service, implement ConversationStateService  
> ✅ **Phase 1: Dùng in-memory** — không cần thay đổi pom.xml ngay

---

## 4. KẾ HOẠCH TRIỂN KHAI (4 PHASE)

### ═══ PHASE 1: Quick Wins — 3-5 ngày ═══
**Mục tiêu:** Kích hoạt những thứ đã code xong, cải thiện UX ngay lập tức

#### Task 1.1 — Bật Tour Cards (0.5 ngày) 🔴 P0

**File:** `tourism_frontend/client-side/src/components/ChatbotWidget.jsx`

Việc cần làm:
- Bỏ comment khối `{/* tourSuggestions */}` và `{/* quickActions */}`
- Verify `tourSuggestions` response có đủ: `tourId`, `tourName`, `imageUrl`, `duration`, `minPrice`, `detailUrl`
- Test với câu: "tour Đà Nẵng có gì?" → phải thấy card ảnh + giá + link

Backend check — `ChatbotService.buildTourSuggestions()`:
```java
// Đảm bảo imageUrl có fallback:
.imageUrl(imageUrl.isEmpty() ? "/images/tour-default.jpg" : imageUrl)
// Thêm tourCode vào TourSuggestion DTO
```

#### Task 1.2 — Welcome Chips gợi ý câu hỏi (1 ngày) 🟡 P2

**File:** `ChatbotWidget.jsx` + `ChatbotWidget.module.scss`

```jsx
// Hiển thị khi messages.length === 1 (chỉ có tin chào)
const WELCOME_CHIPS = [
  "Tour giá rẻ cuối tuần 🏖️",
  "Tour nước ngoài tháng 7 ✈️",
  "Có mã giảm giá không? 🎁",
  "Tra cứu đơn hàng 📋",
];
```

#### Task 1.3 — Like/Dislike Feedback (1 ngày) 🟡 P2

**Backend — analytics-service:**
```java
// POST /api/chatbot/feedback
// Body: { sessionId, messageIndex, rating: "LIKE"|"DISLIKE", botReply }
// Lưu vào bảng chatbot_feedback
```

**SQL:**
```sql
CREATE TABLE chatbot_feedback (
  id BIGSERIAL PRIMARY KEY,
  session_id VARCHAR(100),
  message_index INT,
  rating VARCHAR(10),
  bot_reply TEXT,
  created_at TIMESTAMP DEFAULT NOW()
);
```

#### Task 1.4 — Chuyển API Keys ra environment variables (1 ngày) 🔴 P0 — BẢO MẬT

**File:** `docker-compose.yml` — analytics-service section:
```yaml
analytics-service:
  environment:
    - GEMINI_API_KEY=${GEMINI_API_KEY}
    - PINECONE_API_KEY=${PINECONE_API_KEY}
    - PINECONE_HOST=${PINECONE_HOST}
    - PINECONE_ENV=${PINECONE_ENV}
```

Tạo file `.env` (gitignore) hoặc đưa vào CI/CD secrets.  
> ⚠️ API keys đang hardcode trong `application.yml` — lỗ hổng OWASP A02

#### Task 1.5 — Thêm nhiều ngày khởi hành vào tour card (1 ngày) 🟠 P1

**Backend — `ChatbotService.buildTourSuggestions()`:**
```java
// Thêm field departureDates: List<String> (tối đa 4 ngày gần nhất)
// Thêm field tourCode
// Thêm field departureId (để pre-fill khi bấm vào ngày)
```

**Frontend:**
```jsx
// Render date buttons dưới tour card
{tour.departureDates?.slice(0, 4).map(date => (
  <button key={date} className={styles.dateChip}
    onClick={() => window.open(`/tours/${tour.tourId}?date=${date}`)}>
    {formatDate(date)}
  </button>
))}
```

---

### ═══ PHASE 2: Multi-turn Context + Slot-filling — 1-2 tuần ═══
**Mục tiêu:** Chatbot nhớ ngữ cảnh, chủ động hỏi thiếu info như Tripi

**Cần thêm dependency:**
```xml
<!-- analytics-service/pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Không cần RabbitMQ. Không cần rebuild service khác.**

#### Task 2.1 — ConversationState (in-memory trước, Redis sau)

```java
// ConversationState.java (POJO)
public class ConversationState {
  private List<Map<String,String>> history;  // [{role:user/bot, content:...}]
  private Map<String,String> slots;          // destination, month, people, budget
  private String stage;                      // GREETING|COLLECTING|SEARCHING|BOOKING
  private List<TourSuggestion> lastTours;    // kết quả tìm gần nhất
  private LocalDateTime lastActivity;
}

// ConversationStateService.java
@Service
public class ConversationStateService {
  // Phase 2a: ConcurrentHashMap + scheduled cleanup mỗi 30 phút
  private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();

  public ConversationState getOrCreate(String sessionId) { ... }
  public void save(String sessionId, ConversationState state) { ... }
  public void cleanup() { /* xóa session quá 2 giờ không hoạt động */ }
}
```

Sau Phase 2 ổn định → migrate `ConversationStateService` sang Redis:
```java
// Phase 2b: Redis
@Autowired StringRedisTemplate redisTemplate;
// key: "chatbot:session:" + sessionId, TTL: 2h
```

#### Task 2.2 — Cập nhật ChatbotService để nhớ lịch sử

```java
public ChatMessageResponse handleUserMessage(ChatMessageRequest request) {
  // Load state
  ConversationState state = stateService.getOrCreate(request.getSessionId());
  
  // Build prompt với history
  String prompt = buildPromptWithHistory(request.getMessage(), context, state.getHistory());
  
  // Gọi Gemini
  String reply = callGeminiAPI(prompt);
  
  // Update history (giữ tối đa 10 lượt gần nhất)
  state.getHistory().add(Map.of("role","user","content", request.getMessage()));
  state.getHistory().add(Map.of("role","bot","content", reply));
  if (state.getHistory().size() > 20) {
    state.setHistory(state.getHistory().subList(state.getHistory().size()-20, state.getHistory().size()));
  }
  
  // Save state
  stateService.save(request.getSessionId(), state);
}
```

**Cập nhật system prompt** để Gemini nhận history:
```
=== LỊCH SỬ HỘI THOẠI ===
{history}

=== DỮ LIỆU HỆ THỐNG ===
{context}

=== CÂU HỎI HIỆN TẠI ===
{userMessage}

QUY TẮC BỔ SUNG:
- Nếu user hỏi tìm tour nhưng chưa nói điểm đến/tháng/số người, hãy hỏi thêm lịch sự (1 câu)
- Nhớ thông tin đã cung cấp trước đó, không hỏi lại
- Detect tự nhiên: "tao ở HCM" = khởi hành HCM, "tháng 7" = tháng 7, "2 người" = 2 người
```

#### Task 2.3 — Slot-filling qua prompt engineering (không cần rule engine phức tạp)

Gemini đủ thông minh để:
- Detect slot từ câu tự nhiên
- Hỏi thêm nếu thiếu
- Không hỏi lại slot đã có

Chỉ cần cập nhật system prompt + thêm history. Không cần code rule engine phức tạp.

---

### ═══ PHASE 3: Booking Flow trong Chat — 2-3 tuần ═══
**Mục tiêu:** User đặt tour hoàn chỉnh không cần rời chat

**KHÔNG cần RabbitMQ** — booking-service đã có REST API, dùng Feign là đủ.

#### Task 3.1 — Backend: ChatbotBookingService

```java
// analytics-service: thêm Feign client
@FeignClient(name = "booking-service")
public interface ChatbotBookingFeignClient {
  @PostMapping("/api/bookings")
  BookingCreatedResponse createBooking(@RequestBody ChatbotBookingRequest req);
}

// Endpoint mới
POST /api/chatbot/booking
Body: { sessionId, tourId, departureId, numberOfPeople, contactName, contactPhone, contactEmail }
→ Feign → booking-service POST /api/bookings
→ Trả về: { bookingCode, paymentUrl, totalPrice }
```

#### Task 3.2 — Frontend: BookingConfirmCard component

```jsx
// Khi state.stage === 'CONFIRMING' → hiển thị card xác nhận
const BookingConfirmCard = ({ bookingInfo, onConfirm, onCancel }) => (
  <div className={styles.confirmCard}>
    <h4>📋 Xác nhận đặt tour</h4>
    <p>Tour: {bookingInfo.tourName}</p>
    <p>Ngày: {bookingInfo.departureDate}</p>
    <p>Số người: {bookingInfo.numberOfPeople}</p>
    <p>Tổng tiền: {formatCurrency(bookingInfo.totalPrice)}</p>
    <p>Họ tên: {bookingInfo.contactName}</p>
    <p>SĐT: {bookingInfo.contactPhone}</p>
    <div className={styles.confirmActions}>
      <button onClick={onConfirm} className={styles.btnConfirm}>✅ Xác nhận đặt</button>
      <button onClick={onCancel} className={styles.btnCancel}>❌ Hủy</button>
    </div>
  </div>
);
```

#### Task 3.3 — State machine booking flow

```
Stage SEARCHING → user: "đặt tour này"
  → Bot: "Quý khách đi mấy người?"
  → Chuyển stage: COLLECTING_BOOKING_INFO
  
Stage COLLECTING_BOOKING_INFO
  → Bot hỏi từng thông tin còn thiếu (tên, SĐT, email)
  → Khi đủ → Chuyển stage: CONFIRMING
  
Stage CONFIRMING
  → Frontend render BookingConfirmCard
  → User bấm "Xác nhận" → gọi POST /api/chatbot/booking
  
Stage BOOKING_CONFIRMED
  → Bot: "Đặt thành công! Mã: FT26XXXXX
          💳 Thanh toán: [link]"
```

---

### ═══ PHASE 4: Order Lookup + Production Hardening — 2-3 tuần ═══

#### Task 4.1 — Tra cứu đơn hàng

```java
// GET /api/chatbot/order-lookup?code=FT123&name=NguyenVanA
// Feign → booking-service GET /api/bookings/search
// Trả về: { tourName, departureDate, status, totalPrice, paymentStatus }
```

#### Task 4.2 — User Auth Integration

```jsx
// ChatbotWidget.jsx — lấy userId từ Redux/Context
const { user } = useSelector(state => state.auth);
// POST /api/chatbot/chat { ..., userId: user?.id ?? null }
```

#### Task 4.3 — Semantic Cache với Redis

```java
// Tránh gọi Gemini 2 lần cho câu hỏi tương tự
// key: "chatbot:cache:" + sha256(normalizedQuestion)
// TTL: 30 phút
// Threshold: cosine similarity > 0.92 → trả cache
```

#### Task 4.4 — Rate Limiting

```java
// analytics-service RateLimitFilter
// Max 60 requests/phút/IP
// Dùng Redis: INCR chatbot:ratelimit:{ip} → EXPIRE 60s
```

#### Task 4.5 — Streaming Response (SSE) — Optional

```java
// Thay callGeminiAPI() → callGeminiAPIStream()
// Spring SseEmitter → stream token-by-token
// Frontend EventSource → typing effect
// Tác động: UX tốt hơn rõ rệt (Gemini mất 2-4s)
```

---

## 5. TÓM TẮT: CÓ CẦN RABBITMQ VÀ REDIS?

```
┌─────────────────────────────────────────────────────────────────┐
│                    KẾT LUẬN CUỐI CÙNG                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  RabbitMQ cho Chatbot:                                          │
│  ❌ KHÔNG CẦN (Phase 1-3)                                       │
│     - Booking: dùng Feign đồng bộ OK                           │
│     - Data sync: cron job 1h là đủ                              │
│     - RabbitMQ đang dùng tốt cho booking/notification          │
│       nhưng chatbot không cần thêm                              │
│                                                                  │
│  ✅ CÓ ÍCH (Phase 4 - Optional):                                │
│     - Event-driven sync Pinecone khi tour thay đổi             │
│     - Nhưng cron 1h là giải pháp đơn giản hơn                  │
│                                                                  │
│  Redis cho Chatbot:                                             │
│  ✅ CẦN (Phase 2)                                               │
│     - Multi-turn conversation state (bắt buộc)                  │
│     - Redis đã có trong docker-compose                          │
│     - Chỉ cần thêm spring-boot-starter-data-redis              │
│       vào analytics-service/pom.xml                            │
│     - Phase 1: dùng ConcurrentHashMap (không cần Redis ngay)   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. CHECKLIST THEO PHASE

### Phase 1 — Quick Wins (không cần RabbitMQ, không cần Redis)
- [ ] Bỏ comment tour cards + quick actions trong `ChatbotWidget.jsx`
- [ ] Thêm `departureDates[]` + `tourCode` vào `TourSuggestion` DTO
- [ ] Backend `buildTourSuggestions()`: imageUrl fallback, tourCode
- [ ] CSS tour card responsive trong chat window (max-width: 380px)
- [ ] Welcome chips 4 câu gợi ý khi mở chat
- [ ] Like/Dislike buttons + backend endpoint + bảng `chatbot_feedback`
- [ ] API keys ra `.env` / docker-compose env vars

### Phase 2 — Multi-turn (cần Redis)
- [ ] Thêm `spring-boot-starter-data-redis` vào `analytics-service/pom.xml`
- [ ] Tạo `ConversationState.java` (POJO)
- [ ] Tạo `ConversationStateService` (in-memory → Redis)
- [ ] Refactor `ChatbotService.handleUserMessage()` để dùng history
- [ ] Cập nhật system prompt: history + slot-filling instructions
- [ ] Test: "Tour Đà Nẵng?" → "Cái nào rẻ nhất?" (phải nhớ Đà Nẵng)

### Phase 3 — Booking Flow (không cần RabbitMQ)
- [ ] Thêm `ChatbotBookingFeignClient` (analytics → booking-service)
- [ ] Endpoint `POST /api/chatbot/booking`
- [ ] Booking state machine trong `ChatbotService` (stage tracking)
- [ ] Frontend `BookingConfirmCard` component
- [ ] Test full flow: tìm tour → chọn ngày → nhập info → xác nhận → mã booking

### Phase 4 — Production
- [ ] `GET /api/chatbot/order-lookup`
- [ ] userId từ auth context vào request
- [ ] Semantic cache Redis (cosine threshold 0.92)
- [ ] Rate limiting (60 req/phút/IP)
- [ ] Input sanitization (strip HTML/script)
- [ ] Log masking PII (SĐT, email)
- [ ] Streaming SSE (optional nhưng UX tốt)

---

## 7. THỨ TỰ ƯU TIÊN

| # | Task | Phase | Effort | Impact |
|---|---|---|---|---|
| 1 | Bật tour cards (uncomment) | 1 | 0.5 ngày | 🔥 Cao ngay |
| 2 | API keys ra env vars | 1 | 0.5 ngày | 🔒 Bảo mật |
| 3 | Nhiều ngày khởi hành trong card | 1 | 1 ngày | Cao |
| 4 | Welcome chips | 1 | 0.5 ngày | Vừa |
| 5 | Like/Dislike | 1 | 1 ngày | Vừa |
| 6 | ConversationState in-memory | 2 | 1 ngày | 🔥 Rất cao |
| 7 | Slot-filling qua prompt | 2 | 1 ngày | Rất cao |
| 8 | Migrate sang Redis | 2 | 1 ngày | Cao |
| 9 | Booking flow (Feign) | 3 | 5 ngày | 🔥 Rất cao |
| 10 | BookingConfirmCard UI | 3 | 2 ngày | Rất cao |
| 11 | Order lookup | 4 | 2 ngày | Vừa |
| 12 | Semantic cache Redis | 4 | 1.5 ngày | Vừa |
| 13 | Rate limiting | 4 | 1 ngày | Vừa |
| 14 | Streaming SSE | 4 | 3 ngày | Cao (UX) |

---

## 8. LƯU Ý KỸ THUẬT QUAN TRỌNG

### analytics-service hiện TẠI có AMQP nhưng không dùng
```xml
<!-- pom.xml analytics-service — dependency có nhưng không có @RabbitListener nào -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```
→ Nếu không dùng RabbitMQ trong chatbot, dependency này có thể giữ nguyên (không gây lỗi) hoặc xóa để giảm overhead.

### Chatbot không phụ thuộc vào booking flow RabbitMQ
- Booking-service publish event qua outbox → RabbitMQ → notification-service
- Chatbot tạo booking qua Feign **đồng bộ** (REST) → nhận `bookingCode` ngay
- Không cần chatbot lắng nghe RabbitMQ để biết booking thành công

### Data sync Pinecone vẫn cron 2AM
- Có thể giảm xuống 1 giờ nếu cần data mới hơn (đặc biệt `availableSlots`)
- Chỉ chuyển sang event-driven (RabbitMQ) ở Phase 4 nếu thực sự cần real-time

### Bug slot không hoàn trả khi hủy
- Ảnh hưởng trực tiếp đến data trong Pinecone (số slot sai)
- Cần fix `booking-service` trước Phase 2 để chatbot báo slot chính xác
- Xem `PLAN-chatbot-deploy-2026-05-18.md` phần Task 1 để biết cách fix
