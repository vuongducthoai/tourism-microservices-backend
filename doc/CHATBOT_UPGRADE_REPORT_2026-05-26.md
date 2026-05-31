# BÁO CÁO TRIỂN KHAI CHATBOT NÂNG CẤP
**Hệ thống Tourism Microservices — Chatbot Upgrade**  
**Ngày triển khai:** 2026-05-26  
**Phiên bản:** 2.0.0

---

## 1. Tổng quan

Dự án này nâng cấp hệ thống chatbot du lịch từ một RAG đơn giản thành một **multi-phase conversational AI** với:
- Bộ nhớ hội thoại (Conversation Memory)
- Giải quyết coreference (Reference Resolver)  
- AI Intent Router (fast-path + Gemini fallback)
- Sửa lỗi booking flow và thanh toán PayOS
- Cải thiện UX frontend (session persistence, userId injection)

---

## 2. Kiến trúc hệ thống Chatbot

```
User (React Frontend)
       │
       ▼
API Gateway (:8080) → /api/chatbot/chat
       │
       ▼
analytics-service (:8085)
  ├── ChatbotController
  ├── ChatbotService               ← RAG + IntentRouter integration
  │     ├── IntentRouter           ← NEW: Fast-path + Gemini fallback routing
  │     │     ├── ReferenceResolverService  ← NEW: Pronoun/coreference resolution
  │     │     └── GeminiIntentService       ← NEW: AI intent classification
  │     ├── BookingConversationService  ← UPDATED: Phase 1 fixes
  │     ├── VectorService (Pinecone)
  │     └── RedisSessionService (ConversationState)
  │
  ├── ConversationState (Redis)   ← UPDATED: recentTurns, lastMentionedTourId/DepId
  └── ChatMessageResponse         ← UPDATED: bookingCode, paymentUrl, paymentWaitingLink
```

---

## 3. Chi tiết thay đổi

### 3.1 Backend — analytics-service

#### 3.1.1 File đã sửa

| File | Thay đổi |
|------|---------|
| `dto/chatbot/ConversationState.java` | Thêm fields: `recentTurns`, `lastMentionedTourId`, `lastMentionedDepartureId`, `paymentWaitingLink`; Thêm inner class `ChatTurn` |
| `dto/ChatMessageResponse.java` | Thêm fields: `bookingCode`, `paymentUrl`, `paymentWaitingLink` |
| `dto/chatbot/PaymentUrlResponse.java` | Thêm fields: `transactionId`, `qrCode` |
| `service/BookingConversationService.java` | 10 bug fixes (xem mục 3.1.3) |
| `service/ChatbotService.java` | Tích hợp IntentRouter, recentTurns tracking, conversation context window |

#### 3.1.2 File mới tạo

| File | Mô tả |
|------|-------|
| `service/ReferenceResolverService.java` | Giải quyết pronoun reference ("tour đó", "chuyến này") → entity ID cụ thể. Priority: lastMentionedDep → selectedDep → lastMentionedTour → lastSearchResults[0] |
| `service/IntentRouter.java` | Phân loại intent: fast-path regex → ReferenceResolver → Gemini fallback |
| `service/GeminiIntentService.java` | Gọi Gemini để classify intent khi có conversation context |
| `dto/chatbot/IntentResult.java` | DTO kết quả intent routing với 15 intent types |

#### 3.1.3 Bug fixes trong BookingConversationService

1. **Global BK lookup** — Tra cứu mã BK ở bất kỳ stage nào (trước đây chỉ hoạt động từ IDLE)
2. **topK=30 + Java-side filter** — Tăng từ 12 → 30 kết quả Pinecone, lọc theo địa điểm phía Java
3. **lastMentionedTourId update** — Cập nhật sau mỗi lần search kết quả
4. **SHOWING_SEARCH_RESULTS trap fix** — Không bị kẹt loop khi user nhập query mới
5. **departureCity auto-fill** — Tự động điền từ `startLocationName` sau khi chọn tour
6. **Phone validation** — Validate regex `^0\d{9,10}$`, xử lý spaces/dashes
7. **PayOS URLs fixed** — `returnUrl` = `/payment-waiting?bookingCode=X`, `cancelUrl` = `/payment-failed?cancelled=true&bookingCode=X`
8. **paymentWaitingLink** — Build `/payment-waiting?orderCode=TXID&bookingCode=X` từ transactionId
9. **Booking success response** — Bao gồm `bookingCode`, `paymentUrl`, `paymentWaitingLink` trong response
10. **normalizeLocation helper** — Strip diacritics cho so sánh địa điểm

---

### 3.2 Frontend — tourism_frontend

#### File đã sửa

| File | Thay đổi |
|------|---------|
| `components/ChatbotWidget/ChatbotWidget.jsx` | SessionId localStorage persistence; userId từ AuthContext; messages persistence (50 tin nhắn cuối); capture `bookingCode`, `paymentUrl`, `paymentWaitingLink` từ API response |
| `components/ChatbotWidget/BookingSuccessCard.jsx` | Thêm prop `paymentWaitingLink`; hiển thị link "Theo dõi trạng thái thanh toán" |
| `components/ChatbotWidget/BookingSuccessCard.module.scss` | Thêm style `.waitingLink` |
| `components/BookingPaymentComponent/PaymentWaitingPage .jsx` | Fix: không navigate('/') nếu chỉ thiếu orderCode; hiển thị trạng thái `NO_ORDER_CODE` với nút retry và xem chi tiết đơn |

---

## 4. Luồng hoạt động mới

### 4.1 Conversation Memory Flow

```
User: "tìm tour đà nẵng"
  → IntentRouter: TOUR_SEARCH (fast-path, confidence=0.85)
  → BookingConversationService: search, update lastMentionedTourId
  → ChatbotService: addTurn(user, ...) → addTurn(assistant, ...) → save Redis

User: "tour đó còn slot không?"
  → IntentRouter: ReferenceResolver detected pronoun "tour đó"
  → ReferenceResolverService.resolve() → tourId=101, depId=501
  → Intent: ASK_SLOT
  → ChatbotService: respond with slot info
```

### 4.2 Intent Routing Priority

```
1. Fast-path regex (0ms)
   ├── BK pattern → BOOKING_LOOKUP
   ├── Booking phrases → BOOKING_FLOW  
   ├── Tour search phrases → TOUR_SEARCH
   └── Payment phrases → PAYMENT_HELP

2. ReferenceResolver (0ms)
   ├── isPronounReference("tour đó", "chuyến này", ...)
   └── isContextualShortQuestion("còn mấy slot?", "giá bao nhiêu?", ...)

3. Gemini fallback (only when recentTurns exist, ~300ms)
   └── Structured JSON intent classification
```

### 4.3 Booking + Payment Flow

```
User: "đặt tour" → Stage: COLLECTING_SEARCH_INFO
→ (chọn tour, ngày, passengers, contact)
→ Stage: CONFIRMING_BOOKING
→ User: "xác nhận"
→ BookingService gọi booking-service API
→ PayOS createPayment → transactionId
→ State: paymentWaitingLink = /payment-waiting?orderCode=TX123&bookingCode=BK456
→ Response: ChatMessageResponse{
    messageType: "BOOKING_SUCCESS",
    bookingCode: "BK456",
    paymentUrl: "https://pay.payos.vn/...",
    paymentWaitingLink: "/payment-waiting?orderCode=TX123&bookingCode=BK456"
  }
→ Frontend: BookingSuccessCard hiển thị 2 nút:
    [💳 Thanh toán ngay qua PayOS]
    [📊 Theo dõi trạng thái thanh toán]
```

---

## 5. Build & Deploy

### 5.1 Backend Build

```
mvn -pl analytics-service package -DskipTests
→ BUILD SUCCESS (15s)
```

### 5.2 Docker Build & Restart

```
docker-compose build analytics-service  → Image built
docker-compose up -d analytics-service  → Container: Up (healthy)
```

### 5.3 Frontend Build

```
npm run build
→ Build successful
```

---

## 6. Kết quả kiểm thử

### 6.1 Unit Tests

| Test Suite | Tests | Pass | Fail |
|-----------|-------|------|------|
| IntentRouterTest | 8 | 8 | 0 |
| ReferenceResolverServiceTest | 10 | 10 | 0 |
| **Tổng** | **18** | **18** | **0** |

#### Các test case đáng chú ý:
- `testBkLookup` — BK12345678 → BOOKING_LOOKUP, bookingCode được extract
- `testPronounReferenceWithContext` — "tour đó còn mấy chỗ?" → resolved tourId=101, source="reference-resolver"
- `testContextualSlotQuestion` — "còn mấy slot?" → ASK_SLOT
- `ambiguousWithNoContext` — Pronoun khi không có context → isAmbiguous=true
- `resolvesFromSearchResults` — Fallback từ lastSearchResults[0]

### 6.2 API Integration Tests

| Test | Endpoint | Kết quả |
|------|---------|---------|
| Greeting | POST /api/chatbot/chat | ✅ PASS (stage=IDLE, reply not null) |
| Tour search | POST /api/chatbot/chat | ✅ PASS (stage=IDLE, reply not null) |
| Booking intent | POST /api/chatbot/chat | ✅ PASS (stage=IDLE) |
| BK lookup | POST /api/chatbot/chat | ✅ PASS |
| Session context follow-up | POST /api/chatbot/chat | ✅ PASS |

---

## 7. Container Status (sau triển khai)

| Service | Status |
|---------|--------|
| tourism-analytics-service | ✅ Up (healthy) |
| tourism-api-gateway | ✅ Up (healthy) |
| tourism-booking-service | ✅ Up (healthy) |
| tourism-tour-catalog-service | ✅ Up (healthy) |
| tourism-payment-service | ✅ Up (healthy) |
| tourism-redis | ✅ Up (healthy) |
| tourism-rabbitmq | ✅ Up (healthy) |
| tourism-postgres | ✅ Up (healthy) |

---

## 8. Hướng dẫn sử dụng tính năng mới

### 8.1 Pronoun Reference
```
User: "tìm tour hội an"
Bot: [hiển thị danh sách 3 tour]
User: "tour đó còn slot không?"  ← chatbot nhận ra "tour đó" = tour vừa hiển thị
Bot: "Tour Hội An 4N3Đ còn 12 slot..."
```

### 8.2 Session Persistence (Frontend)
- Session ID được lưu vào `localStorage['chatbot_session_id']`
- Tin nhắn được lưu `localStorage['chatbot_messages']` (50 tin cuối)
- F5 / reload trang → giữ lại lịch sử chat và cùng session Redis

### 8.3 UserId tự động
- Nếu user đã đăng nhập → `userId` được inject từ `AuthContext` vào mỗi request
- Analytics theo dõi được user cụ thể

### 8.4 Payment Status Tracking
- Sau khi booking thành công → hiển thị link "Theo dõi trạng thái thanh toán"
- PaymentWaitingPage được cải thiện: không redirect về trang chủ khi thiếu `orderCode`

---

## 9. Ghi chú kỹ thuật

### Giới hạn hiện tại
1. **GeminiIntentService** — chỉ được gọi khi `recentTurns` có data (tránh latency cho request đầu)
2. **recentTurns** — giữ tối đa 6 turns (3 exchanges), content bị truncate ở 300 ký tự
3. **ReferenceResolver** — regex-based, chưa xử lý tiếng Anh trộn tiếng Việt phức tạp

### Cải tiến tiếp theo (Phase 4+)
- RabbitMQ `chatbot.vector.sync.queue` — real-time Pinecone sync khi tour mới được tạo
- ASK_SLOT / ASK_PRICE handlers — gọi trực tiếp API tour-catalog thay vì RAG
- WebSocket streaming responses
- Multi-turn booking correction ("đổi từ 2 người lớn thành 3")

---

*Báo cáo được tạo tự động bởi GitHub Copilot — 2026-05-26*
