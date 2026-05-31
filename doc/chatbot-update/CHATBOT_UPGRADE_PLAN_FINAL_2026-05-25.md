# CHATBOT UPGRADE PLAN — FINAL CONSOLIDATED
**Version:** 4.0 — Ngày 25/05/2026  
**Trạng thái:** Kế hoạch hoàn chỉnh — chưa implement  
**Mục tiêu:** Nâng cấp chatbot du lịch từ state machine đơn giản thành trợ lý du lịch thông minh, nhớ ngữ cảnh, phong cách Tripi

---

## MỤC LỤC

0. [**CORRECTIONS TRƯỚC KHI IMPLEMENT** — 6 điểm sai trong bản draft cũ](#0-corrections-trước-khi-implement)
1. [Vấn đề hiện tại (đã xác nhận trong code)](#1-vấn-đề-hiện-tại)
2. [Vấn đề cốt lõi: Chatbot không nhớ ngữ cảnh](#2-vấn-đề-cốt-lõi-chatbot-không-nhớ-ngữ-cảnh)
3. [Kiến trúc đề xuất — Option A (AI Router + State Machine)](#3-kiến-trúc-đề-xuất)
4. [AI Router — Lớp xử lý ý định](#4-ai-router)
5. [Pipeline tìm kiếm tour (Search Pipeline Fix)](#5-pipeline-tìm-kiếm-tour)
6. [Chat Memory — Hệ thống bộ nhớ hội thoại](#6-chat-memory)
7. [RabbitMQ — Đồng bộ Pinecone realtime](#7-rabbitmq-sync)
8. [Quick Wins — Bug fix ngay](#8-quick-wins)
9. [ReferenceResolverService — Bắt buộc để sửa lỗi "tour đó"](#9-referenceresolver)
10. [UX — Nhân viên tư vấn du lịch thực thụ](#10-ux-tư-vấn-thực-thụ)
11. [Lộ trình triển khai (Phased Roadmap)](#11-lộ-trình-triển-khai)
12. [Test Plan](#12-test-plan)

---

## 0. CORRECTIONS TRƯỚC KHI IMPLEMENT

> Bản draft v4.0 đúng khoảng **75% về hướng** nhưng có 6 điểm sai kỹ thuật. Nếu code theo nguyên văn sẽ vướng ngay. Mục này ghi rõ sai chỗ nào, đúng là gì, đã sửa vào đâu trong plan này.

---

### C1 — ConversationState mô tả sai (đã xác nhận trong code)

**Sai (bản cũ nói):** "ConversationState không lưu danh sách tour đang hiển thị"

**Thực tế code** (`ConversationState.java`):
```java
// Đã CÓ sẵn:
private List<TourGroupDisplay> lastSearchResults;   // tour nhóm theo tourId
private List<DepartureMeta>    lastDepartures;       // các departure chi tiết
```

**Vấn đề thật sự là chưa có:**
- `List<ChatTurn> recentTurns` — lịch sử hội thoại gần nhất
- `Integer lastMentionedTourId` — tour được nhắc đến gần nhất
- `Integer lastMentionedDepartureId` — departure đang được chọn/nhắc
- `ReferenceResolverService` — service giải quyết "tour đó", "chuyến này"

**Đã sửa:** Section 2, 6, 9

---

### C2 — Pinecone hard filter không khả thi với metadata hiện tại

**Sai (bản cũ nói):**
```java
filter.put("endLocationName", destination);  // gọi trực tiếp Pinecone filter
filter.put("departureMonth", month);
```

**Thực tế code `VectorService.upsertVector()`:**
```java
metadataMap.put("type",     document.getType());
metadataMap.put("entityId", String.valueOf(document.getEntityId()));
metadataMap.put("content",  document.getContent());
metadataMap.put("metadata", document.getMetadata()); // ← JSON STRING, không phải object
```
Pinecone chỉ filter được top-level fields. `endLocationName`, `departureDate`, `availableSlots` nằm bên trong chuỗi JSON `metadata` → **Pinecone filter API không có tác dụng trên các field này.**

**Hai hướng fix (đã đưa vào Section 5):**
- **Quick (Phase 1):** `searchSimilar(query, topK=30)` → parse JSON metadata trong Java → filter/re-rank
- **Proper (Phase 4):** Đổi `upsertVector()` để flatten các field quan trọng lên top-level Pinecone metadata

**Đã sửa:** Section 5

---

### C3 — PayOS returnUrl & transactionId logic sai

**Sai (bản cũ nói):**
```java
returnUrl = frontendUrl + "/payment-waiting?orderCode=" + transactionId + "&bookingCode=" + bookingCode;
```
Vấn đề: `transactionId` chỉ tồn tại **sau** khi gọi `paymentClient.createPayosPayment()`, không thể đưa vào request gửi cho PayOS.

**Thực tế flow đúng** (theo BookingPayment.jsx và payment-service):
1. `returnUrl` gửi PayOS = `frontendUrl + "/payment-waiting?bookingCode=" + bookingCode`
2. PayOS gọi xong → trả về `PaymentUrlResponse { checkoutUrl, transactionId, ... }`
3. `transactionId` = orderCode của PayOS
4. Booking success card dùng `transactionId` để hiển thị link: `/payment-waiting?orderCode={transactionId}&bookingCode={bookingCode}`

**Còn 1 bug thêm:** analytics-service `PaymentUrlResponse.java` chỉ có `checkoutUrl` + `paymentUrl`, **thiếu `transactionId`** mặc dù payment-service đã trả về field này.

**Đã sửa:** Section 8

---

### C4 — Route /payment-cancel không tồn tại trong frontend

**Sai (bản cũ nói):** `cancelUrl = frontendUrl + "/payment-cancel?bookingCode=..."`

**Thực tế App.tsx** chỉ định nghĩa:
```
/payment-booking   ✅
/payment-success   ✅
/payment-failed    ✅
/payment-waiting   ✅
/payment-error     ✅
/payment-cancel    ❌ KHÔNG TỒN TẠI
```

**Fix chọn một trong hai:**
- **Option A (nhanh):** `cancelUrl = frontendUrl + "/payment-failed?cancelled=true"` — frontend check query param để hiển thị message phù hợp
- **Option B (sạch):** Tạo `PaymentCancelPage.jsx` + thêm `<Route path="/payment-cancel" .../>` (làm trong Phase UX)

**Đã sửa:** Section 8

---

### C5 — RabbitMQ routing key không khớp topology hiện tại

**Sai (bản cũ nói):** routing keys `tour.created`, `departure.updated`, `review.created`...

**Thực tế topology** (`booking-service/RabbitMQConfig.java`):
```
Exchange: tourism.events (TopicExchange, durable)
Queue: booking.notification.queue ← binding: booking.notification.*
Queue: booking.analytics.queue    ← binding: booking.analytics.*
```
Nếu dùng key `tour.created` hay `departure.updated` → **không route vào queue nào** trong exchange hiện tại.

**Chuẩn hóa routing keys mới (prefix `chatbot.sync.`):**
```
chatbot.sync.tour        → upsert/re-embed tour lên Pinecone
chatbot.sync.departure   → update slot/price metadata
chatbot.sync.review      → update review count/score
chatbot.sync.coupon      → update promotion
```
Kèm khai báo queue mới `chatbot.vector.sync.queue` với binding `chatbot.sync.*` trong analytics-service.

**Đã sửa:** Section 7

---

### C6 — Thiếu ReferenceResolverService (mục bắt buộc)

**Sai (bản cũ):** Chỉ nhắc "Gemini coreference" mơ hồ, không quyết định implement.

**Cần:**
- `ReferenceResolverService` cụ thể — xem Section 9
- Intent granular thay vì `CONTEXT_QUESTION` gộp: `ASK_SLOT`, `ASK_PRICE`, `ASK_CHILD_PRICE`, `ASK_DEPARTURE_DATE`, `ASK_ITINERARY`, `ASK_POLICY`

**Đã sửa:** Section 4, 9

---

---

## 1. Vấn đề hiện tại

Danh sách bug/limitation đã xác nhận trong code (analytics-service):

### 1.1 Không nhớ ngữ cảnh (ưu tiên cao nhất)
- User hỏi "tour đó còn mấy slot" sau khi đã được show kết quả → bot không hiểu "tour đó" là tour nào → trả lời ngẫu nhiên hoặc tìm kiếm lại
- User hỏi "tour Hạ Long đi tháng mấy" → bot đang ở SHOWING_SEARCH_RESULTS nhưng không nhớ kết quả đang hiển thị
- Xem ảnh đính kèm: "tour đó còn mấy slot" → bot trả lời về tour Cần Thơ thay vì tour Hạ Long vừa hỏi
- **Nguyên nhân kỹ thuật (đã sửa lại so với bản draft):** `ConversationState` **đã có** `lastSearchResults` (List\<TourGroupDisplay\>) và `lastDepartures` (List\<DepartureMeta\>) — được set trong `doSearch` của `BookingConversationService.java`. Vấn đề thật là hai field này **chưa được dùng** khi xử lý câu follow-up; thiếu lịch sử hội thoại ngắn hạn (`recentTurns`) và cơ chế map pronoun → entity ID cụ thể (`ReferenceResolverService`)

### 1.2 Bug xác nhận trong BookingConversationService.java
| # | Bug | Vị trí | Mức độ |
|---|-----|---------|--------|
| 1 | PayOS return URL sai | line ~485 dùng `/payment/success?code=`. `returnUrl` gửi PayOS phải là `/payment-waiting?bookingCode=...` (KHÔNG có orderCode — chưa biết lúc này). `paymentWaitingLink` với `orderCode` chỉ build được sau khi PayOS trả về `transactionId` | HIGH |
| 2 | BK lookup chỉ hoạt động từ IDLE | ChatbotService.java: khi stage≠IDLE, lookup bị bỏ qua | HIGH |
| 3 | `departureCity` không bao giờ được set | `buildConfirmCard` dùng field này nhưng không có `setDepartureCity()` trong flow | MEDIUM |
| 4 | Phone validation không có | "Nu" được chấp nhận là số điện thoại hợp lệ | MEDIUM |
| 5 | `isBookingIntent` over-match | regex `muốn\s*đi` match cả "muốn đi Đà Lạt chơi" (du lịch thường) thành booking intent | MEDIUM |
| 6 | SHOWING_SEARCH_RESULTS trap | input không phải 1/2/3 → "chọn tour nào?" loop mãi, không thoát được | MEDIUM |
| 7 | Pinecone không có hard filter | `doSearch` tìm topK không filter theo `endLocationName` → kết quả lung tung | MEDIUM |

### 1.3 Bug Frontend (ChatbotWidget.jsx)
| # | Bug | Vị trí |
|---|-----|--------|
| 1 | `sessionId = session_${Date.now()}` | line 125 — F5 tạo sessionId mới → mất toàn bộ context |
| 2 | `userId: null` hardcoded | lines 197, 263 — booking tạo ra luôn không có userId |
| 3 | Không lưu lịch sử chat | F5 → mất hết tin nhắn |

### 1.4 DTO mismatch
- `transactionId`/`orderCode` không có trong `PaymentUrlResponse` → không build được URL `/payment-waiting?orderCode=X&bookingCode=Y` đúng
- (Đã fix session trước: `ChatbotBookingDetailResponse` Long→BigDecimal, String transport→Map)

---

## 2. Vấn đề cốt lõi: Chatbot không nhớ ngữ cảnh

### 2.1 Mô tả vấn đề

Đây là vấn đề nghiêm trọng nhất. Hiện tại chatbot hoạt động như một **state machine không có trí nhớ ngắn hạn**:

```
User: "tour nào đi Hạ Long?"
Bot: [hiển thị Tour A, Tour B]

User: "tour đó còn mấy slot?"
Bot: ??? (không biết "tour đó" là tour nào)
     → tìm kiếm lại bằng RAG với query "tour đó còn mấy slot"
     → trả về kết quả ngẫu nhiên
```

### 2.2 Nguyên nhân kỹ thuật

**ConversationState (Redis) đã có sẵn:**
```java
private List<TourGroupDisplay> lastSearchResults;  // ✅ tour đang hiển thị
private List<DepartureMeta>    lastDepartures;      // ✅ các departure chi tiết
private Integer selectedTourId;                    // ✅ tour đã chọn trong booking flow
private Integer selectedDepartureId;               // ✅ departure đã chọn
// ... các slot booking, giá, contact
```

**Nhưng THIẾU 4 thứ quan trọng:**
- `List<ChatTurn> recentTurns` — lịch sử hội thoại (last 6 turns) để truyền cho Gemini
- `Integer lastMentionedTourId` — tour được nhắc cuối cùng (kể cả ngoài booking flow)
- `Integer lastMentionedDepartureId` — departure được nhắc cuối (để trả lời "tour đó còn mấy slot")
- Không có `ReferenceResolverService` để giải quyết pronoun → entity cụ thể

**Kết quả thực tế:**
- `lastSearchResults` có sẵn nhưng **không được dùng** khi user hỏi follow-up
- Khi user gõ "tour đó còn mấy slot", flow đi thẳng vào RAG với query nguyên văn → Pinecone không hiểu → kết quả ngẫu nhiên
- `lastMentionedTourId` không tồn tại → không có cơ chế link "tour đó" với tour cụ thể trong `lastSearchResults`

### 2.3 Giải pháp: Conversation History + Context Window

**Giải pháp 3 tầng:**

**Tầng 1 — ConversationState (Redis, TTL 30 phút):**
- Thêm field `List<ChatMessage> recentMessages` (giữ 6 turns gần nhất = 3 cặp hỏi-đáp)
- **Dùng field đã có** `lastSearchResults` (List\<TourGroupDisplay\>) — KHÔNG tạo field mới tên `currentSearchResults`
- Thêm field `Long lastMentionedDepartureId` (departure đang được nhắc/xem)

**Tầng 2 — Gemini Context-Aware Routing:**
- Khi gọi Gemini để xác định intent, truyền cả 6 turns gần nhất
- Gemini hiểu "tour đó" = tour trong `lastSearchResults[0]`
- Gemini giải quyết coreference trước khi state machine xử lý

**Tầng 3 — DB lưu lịch sử dài hạn (optional, Phase 2):**
- Bảng `chat_history(session_id, user_id, role, content, created_at)`
- Dùng cho "tìm lại booking tuần trước", "xem lịch sử chat"

---

## 3. Kiến trúc đề xuất

### 3.1 Option A — Được chọn (giữ state machine, thêm AI Router)

```
User message
     │
     ▼
┌─────────────────────────────────────┐
│         Fast-Path Classifier         │
│  (regex, không gọi Gemini)           │
│  - BKxxxxxxxx → LOOKUP               │
│  - "1"/"2"/"3" → SELECTION          │
│  - email@... → EMAIL_INPUT           │
│  - xác nhận/hủy → CONFIRM/CANCEL    │
└──────────────┬──────────────────────┘
               │ không match fast-path
               ▼
┌─────────────────────────────────────┐
│         AI Router (Gemini)           │
│  Input: message + last 6 turns      │
│  + lastSearchResults                │
│  Output: IntentResult{               │
│    intent, entities, resolvedRef    │
│  }                                  │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│      State Machine (giữ nguyên)      │
│  + nhận IntentResult từ AI Router   │
│  + có context window                │
└─────────────────────────────────────┘
```

### 3.2 Tại sao Option A (không phải thuần Gemini)

| Tiêu chí | Option A (State Machine + AI) | Option B (thuần Gemini) |
|---------|-------------------------------|------------------------|
| Độ trễ | ~200ms (fast-path 0ms, Gemini ~400ms) | ~800ms mọi tin nhắn |
| Chi phí Gemini | Chỉ gọi khi cần | Mọi tin nhắn |
| Booking flow | Chính xác, có validation | Dễ hallucination |
| Ngữ cảnh | Gemini giải quyết coreference | Gemini làm hết |
| Rủi ro | Thấp | Cao (Gemini có thể skip bước) |

---

## 4. AI Router

### 4.1 Intent Enum

```
# ── Tìm kiếm ──
TOUR_SEARCH           - tìm/hỏi về tour mới
CHANGE_SEARCH         - muốn tìm lại / đổi điểm đến

# ── Đặt tour ──
BOOKING_FLOW          - bắt đầu đặt tour
BOOKING_LOOKUP        - tra cứu booking bằng mã BK

# ── Hỏi về entity đang hiển thị (KHÔNG gom vào CONTEXT_QUESTION) ──
ASK_SLOT              - "tour đó còn mấy chỗ?", "còn slot không?"
ASK_PRICE             - "giá bao nhiêu?", "tour này giá mấy?"
ASK_CHILD_PRICE       - "trẻ em giá thế nào?", "em bé mấy tiền?"
ASK_DEPARTURE_DATE    - "khởi hành ngày nào?", "đi tháng mấy?"
ASK_ITINERARY         - "lịch trình thế nào?", "mấy ngày mấy đêm?"
ASK_POLICY            - "chính sách hủy tour?", "điều kiện đặt cọc?"

# ── Thanh toán & hỗ trợ ──
PAYMENT_HELP          - hỏi về thanh toán, PayOS, trạng thái payment
GENERAL_TRAVEL_ADVICE - tư vấn chung về du lịch, thời tiết, visa...
SYSTEM_HELP           - hỏi cách dùng chatbot
UNKNOWN               - không phân loại được
```

> **Lý do không dùng CONTEXT_QUESTION gộp:** Mỗi intent có handler khác nhau.  
> `ASK_SLOT` → `GET /api/departures/order-info?departureId=X`  
> `ASK_PRICE` → đọc từ `lastSearchResults` (không cần API call)  
> `ASK_ITINERARY` → `GET /api/tours/{tourId}` (nếu có endpoint)  
> Gộp lại → handler phải đoán tiếp → dễ sai.

### 4.2 Entity Slots

```json
{
  "destination": "Hạ Long",
  "startLocation": "Hà Nội",
  "travelMonth": "tháng 3",
  "adultCount": 2,
  "childCount": 0,
  "infantCount": 0,
  "budget": 5000000,
  "preferences": ["biển", "cáp treo"],
  "bookingCode": null,
  "resolvedTourRef": "tour_id_123",   // AI giải quyết "tour đó" → id cụ thể
  "contactName": null,
  "contactPhone": null,
  "contactEmail": null
}
```

### 4.3 Fast-Path Rules (không gọi Gemini)

| Pattern | Action |
|---------|--------|
| `BK[a-zA-Z0-9]{6,12}` | → intent BOOKING_LOOKUP |
| `^[123]$` khi stage = SHOWING_SEARCH_RESULTS | → selection |
| `.*@.*\..*` khi stage = COLLECTING_CONTACT_EMAIL | → email input |
| `(xác nhận\|đồng ý\|ok\|yes)` khi stage = CONFIRMING_BOOKING | → confirm |
| `(hủy\|thôi\|thoát\|cancel)` | → cancel |
| `^\d{10,11}$` khi stage = COLLECTING_CONTACT_NAME_PHONE | → phone input |

### 4.4 Gemini Prompt cho Context Resolution

```
Bạn là AI Router cho chatbot du lịch. Phân tích tin nhắn và trả về JSON.

Lịch sử hội thoại gần nhất:
{last_6_turns}

Kết quả tìm kiếm tour đang hiển thị:
{lastSearchResults | null}

Tin nhắn mới: "{userMessage}"

Trả về JSON (KHÔNG giải thích):
{
  "intent": "TOUR_SEARCH|BOOKING_FLOW|...",
  "entities": {...},
  "resolvedRef": "tour_id nếu user đề cập 'tour đó'|null",
  "suggestedResponse": null
}

Lưu ý:
- "tour đó", "chuyến đó", "cái đó" → tìm trong `lastSearchResults` (field đã có trong ConversationState)
- temperature=0.1 (nhất quán, không sáng tạo)
- Nếu không chắc intent → UNKNOWN
```

### 4.5 Fallback

- Nếu Gemini không trả về JSON hợp lệ → fallback sang RAG (hành vi hiện tại)
- Nếu Gemini timeout (>2s) → fast-path với intent UNKNOWN → RAG

---

## 5. Pipeline tìm kiếm tour

### 5.1 Flow hiện tại (có vấn đề)

```
User: "tour Hạ Long" 
→ doSearch(query="tour Hạ Long", topK=5)
→ Pinecone trả về top 5 (có thể không liên quan đến Hạ Long)
→ Hiển thị
```

### 5.2 Giới hạn kỹ thuật Pinecone (QUAN TRỌNG — ảnh hưởng cách implement)

`VectorService.upsertVector()` hiện lưu metadata theo cấu trúc:
```java
metadataMap.put("type",     "TOUR_DEPARTURE");
metadataMap.put("entityId", "123");
metadataMap.put("content",  "Tour Hà Nội - Hạ Long 3N2Đ...");
metadataMap.put("metadata", "{\"endLocationName\":\"Hạ Long\", \"availableSlots\":12, ...}");
//                            ↑ JSON STRING — Pinecone KHÔNG filter được bên trong này
```

Pinecone chỉ filter được **top-level metadata fields**. `endLocationName`, `availableSlots`, `departureDate` nằm trong JSON string → **Pinecone filter API không hoạt động trên các field này.**

### 5.3 Hai hướng giải quyết

**Option A — Quick (Phase 1, 1 ngày):** Search topK lớn + filter trong Java
```java
// Search nhiều hơn để có đủ để filter:
List<VectorDocumentDTO> raw = vectorService.searchSimilar(queryText, 30);
Gson gson = new Gson();
List<VectorDocumentDTO> filtered = raw.stream()
    .filter(doc -> {
        Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
        boolean matchDest = destination == null
            || String.valueOf(meta.getOrDefault("endLocationName", "")).contains(destination);
        boolean hasSlots = ((Number) meta.getOrDefault("availableSlots", 0)).intValue() > 0;
        return matchDest && hasSlots;
    })
    .limit(5)
    .collect(Collectors.toList());
```

**Option B — Proper (Phase 4, 3 ngày):** Flatten metadata lên Pinecone top-level khi upsert
```java
// Trong upsertVector(), parse metadata string và đưa field lên top-level:
if (document.getMetadata() != null) {
    Map<String, Object> parsedMeta = gson.fromJson(document.getMetadata(), Map.class);
    List.of("endLocationName", "startLocationName", "availableSlots", "departureDate", "adultSalePrice")
        .forEach(field -> {
            if (parsedMeta.containsKey(field))
                metadataMap.put(field, parsedMeta.get(field));
        });
}
// Sau đó Pinecone filter thật sự hoạt động được
```
> **Recommendation:** Làm Option A để unblock ngay. Option B khi re-sync toàn bộ Pinecone (Phase 4).

### 5.4 Flow mới (đề xuất)

```
User: "tour Hạ Long tháng 3 từ Hà Nội"
→ AI Router extract: destination="Hạ Long", startLocation="Hà Nội", month="tháng 3"
→ searchSimilar(query, topK=30)     ← lấy nhiều để đủ filter
→ Parse JSON metadata trong Java
→ Filter: endLocationName contains "Hạ Long", availableSlots > 0, departureMonth = 3
→ Re-rank: date proximity → price
→ Nếu 0 kết quả sau filter → "chưa có tour Hạ Long phù hợp, gợi ý:"
→ Hiển thị top 3 sau re-rank
→ Lưu vào state.lastSearchResults, cập nhật state.lastMentionedTourId = kết quả[0].tourId
```

### 5.4 Re-ranking logic

```
Score = (availableSlots > 0 ? 100 : 0)
      + (isDateInMonth(date, requestedMonth) ? 50 : 0)  
      + (price <= budget ? 30 : 0)
      + semanticScore * 20
```

### 5.5 TourSuggestion DTO cần cập nhật

```java
// Thêm vào TourSuggestion
List<DepartureOption> departureOptions;  // các chuyến khởi hành
String startLocationName;
String endLocationName;

// DepartureOption
Long departureId;
String departureDate;
Integer availableSlots;
Long adultPrice;
Long childPrice;
```

---

## 6. Chat Memory

### 6.1 Vấn đề hiện tại

- F5 → mất sessionId → mất toàn bộ context
- ConversationState không lưu lịch sử tin nhắn
- Không có lịch sử chat dài hạn

### 6.2 Giải pháp 3 tầng

#### Tầng 1: Frontend LocalStorage

```javascript
// sessionId — persist across F5
const [sessionId] = useState(() => {
  const saved = localStorage.getItem('chatbot_session_id');
  if (saved) return saved;
  const newId = 'session_' + Date.now();
  localStorage.setItem('chatbot_session_id', newId);
  return newId;
});

// messages — persist across F5  
const [messages, setMessages] = useState(() => {
  const saved = localStorage.getItem('chatbot:messages:' + sessionId);
  return saved ? JSON.parse(saved) : [welcomeMessage];
});

// userId — từ AuthContext
const { user } = useContext(AuthContext);
const userId = user?.userId || user?.userID || null;
```

#### Tầng 2: Redis ConversationState — Fields cần thêm (3 fields mới)

> ConversationState **đã có** `lastSearchResults` (List<TourGroupDisplay>) và `lastDepartures` (List<DepartureMeta>). Chỉ cần thêm 3 fields sau:

```java
// THÊM VÀO ConversationState.java:
@Builder.Default
private List<ChatTurn> recentTurns = new ArrayList<>();  // giữ 6 turns
private Integer lastMentionedTourId;       // tour được nhắc gần nhất (kể cả ngoài booking)
private Integer lastMentionedDepartureId;  // departure đang được nhắc/xem

// Inner class cũng thêm vào ConversationState:
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public static class ChatTurn implements Serializable {
    private String role;       // "user" | "assistant"
    private String content;
    private long   timestamp;  // System.currentTimeMillis()
}
```

**Logic cập nhật recentTurns sau mỗi message:**
```java
state.getRecentTurns().add(new ChatTurn("user", userMessage, System.currentTimeMillis()));
// ... xử lý ...
state.getRecentTurns().add(new ChatTurn("assistant", response.getReply(), System.currentTimeMillis()));
// Trim: giữ tối đa 6 turns (3 cặp hỏi-đáp)
while (state.getRecentTurns().size() > 6) state.getRecentTurns().remove(0);
```

**Logic cập nhật lastMentionedTourId:**
```java
// Sau mỗi doSearch trả về kết quả:
if (!state.getLastSearchResults().isEmpty()) {
    state.setLastMentionedTourId(state.getLastSearchResults().get(0).getTourId());
}
// Sau khi user chọn tour cụ thể (1/2/3):
state.setLastMentionedTourId(selectedTour.getTourId());
state.setLastMentionedDepartureId(selectedDeparture.getDepartureId());
```

#### Tầng 3: DB lưu lịch sử dài hạn (Phase 2)

```sql
CREATE TABLE chat_history (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(100) NOT NULL,
    user_id     BIGINT,               -- nullable (guest)
    role        VARCHAR(20) NOT NULL, -- 'user' | 'assistant'
    content     TEXT NOT NULL,
    message_type VARCHAR(50),         -- 'text' | 'tour_card' | 'booking_confirm' | ...
    created_at  TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_chat_history_session ON chat_history(session_id);
CREATE INDEX idx_chat_history_user ON chat_history(user_id);
```

**Dùng cho:**
- "xem lịch sử chat của tôi"
- "tìm lại booking tuần trước"
- Analytics: thống kê intent phổ biến

### 6.3 Context Window khi gọi Gemini

```java
private String buildContextWindow(ConversationState state) {
    if (state.getRecentTurns().isEmpty()) return "(chưa có hội thoại)";
    
    StringBuilder sb = new StringBuilder();
    for (ChatTurn turn : state.getRecentTurns()) {
        sb.append(turn.role().equals("user") ? "Khách: " : "Bot: ")
          .append(turn.content())
          .append("\n");
    }
    return sb.toString();
}

private String buildSearchResultsContext(ConversationState state) {
    if (state.getLastSearchResults() == null || state.getLastSearchResults().isEmpty()) return "null";
    // Dùng getLastSearchResults() — field có sẵn trong ConversationState (KHÔNG phải currentSearchResults)
    return state.getLastSearchResults().stream()
        .map(g -> String.format("- %s (tourId:%d, giáTừ:%,.0fđ, departures:%d chuyến)",
            g.getTourName(), g.getTourId(),
            g.getAdultSalePrice() != null ? g.getAdultSalePrice().doubleValue() : 0.0,
            g.getDepartures() != null ? g.getDepartures().size() : 0))
        .collect(Collectors.joining("\n"));
}
```

---

## 7. RabbitMQ Sync

### 7.1 Hiện trạng

- Exchange `tourism.events` (TopicExchange, durable) — có trong booking-service/RabbitMQConfig.java
- Queue `booking.analytics.queue` binding `booking.analytics.event` — dùng cho booking analytics
- analytics-service có config RabbitMQ trong application.yml **nhưng không có `@RabbitListener`**
- Kết quả: Pinecone không được cập nhật khi tour/departure thay đổi

### 7.2 Thiết kế queue mới (chuẩn hóa theo topology hiện tại)

> Topology hiện tại dùng prefix `booking.*`. Queue chatbot dùng prefix `chatbot.*` để tránh conflict và không ảnh hưởng các queue đang có.

```
Exchange: tourism.events (dùng chung, KHÔNG tạo mới)

Queue mới: chatbot.vector.sync.queue
Binding:   chatbot.sync.*  →  chatbot.vector.sync.queue
DLQ:       chatbot.vector.sync.dlq

Routing keys (publishers dùng):
  chatbot.sync.tour        → tour CRUD → re-embed và upsert Pinecone
  chatbot.sync.departure   → slot/price thay đổi → update metadata Pinecone
  chatbot.sync.review      → review mới → update score/count
  chatbot.sync.coupon      → khuyến mãi thay đổi
```

**Config analytics-service `RabbitMQConfig.java` (cần tạo mới file này):**
```java
public static final String QUEUE_CHATBOT_SYNC = "chatbot.vector.sync.queue";
public static final String DLQ_CHATBOT_SYNC   = "chatbot.vector.sync.dlq";

@Bean
public Queue chatbotSyncQueue() {
    return QueueBuilder.durable(QUEUE_CHATBOT_SYNC)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", DLQ_CHATBOT_SYNC)
        .build();
}

@Bean
public Binding chatbotSyncBinding(Queue chatbotSyncQueue,
                                   TopicExchange tourismEventsExchange) {
    return BindingBuilder.bind(chatbotSyncQueue)
        .to(tourismEventsExchange)
        .with("chatbot.sync.*");  // wildcard binding
}
```

**Publishers trong tour-catalog-service** dùng routing key `chatbot.sync.tour` hoặc `chatbot.sync.departure` — KHÔNG dùng `tour.created` hay `departure.updated` (sẽ không route được).

### 7.3 Debounce 5 phút

```
tour_id_123 updated
tour_id_123 updated lại (30s sau)
tour_id_456 updated
        ↓ 5 phút
flush → sync tour_id_123 một lần, sync tour_id_456 một lần
```

**Dùng Redis Set để debounce:**
```
Redis key: chatbot:sync:pending:tours    (Set of tourIds)
Redis key: chatbot:sync:pending:departures (Set of departureIds)
TTL: không set (xử lý bởi @Scheduled)
```

**@Scheduled mỗi 5 phút:**
```java
@Scheduled(fixedDelay = 300_000)
public void flushPendingSyncs() {
    Set<String> tourIds = redisTemplate.opsForSet().members("chatbot:sync:pending:tours");
    // sync từng tourId lên Pinecone
    // xóa Set sau khi sync xong
}
```

### 7.4 Publishers cần thêm

| Service | Hiện tại | Cần thêm |
|---------|---------|----------|
| booking-service | Có RabbitTemplate | Đã publish booking events |
| tour-catalog-service | Không có | Cần thêm RabbitTemplate + publish khi CRUD tour/departure |
| analytics-service | Có config | Cần thêm `@RabbitListener` + VectorSyncService |

### 7.5 Cron full sync giữ lại

```java
@Scheduled(cron = "0 0 2 * * *")  // 2AM mỗi ngày
public void fullPineconeSync() { ... }  // safety net, giữ nguyên
```

---

## 8. Quick Wins

Các bug cần fix ngay, TRƯỚC khi làm các tính năng lớn:

### 8.1 Fix PayOS return URLs + transactionId flow

**File:** `BookingConversationService.java` line ~485

**Sai hiện tại:**
```java
.returnUrl(frontendUrl + "/payment/success?code=" + bookingResp.getBookingCode())
.cancelUrl(frontendUrl + "/payment/cancel?code=" + bookingResp.getBookingCode())
```

**Fix 3 bước:**

**Bước 1 — Thêm `transactionId` vào analytics-service `PaymentUrlResponse.java`** (field bị thiếu):
```java
// Hiện chỉ có checkoutUrl + paymentUrl. Payment-service đã trả về transactionId nhưng DTO bị thiếu:
private String transactionId;   // thêm field này
```

**Bước 2 — Fix returnUrl và cancelUrl gửi cho PayOS:**
```java
// returnUrl: gửi cho PayOS để redirect về sau khi thanh toán
// KHÔNG chứa orderCode (không biết trước), PayOS sẽ tự append khi redirect
.returnUrl(frontendUrl + "/payment-waiting?bookingCode=" + bookingResp.getBookingCode())

// cancelUrl: /payment-cancel KHÔNG tồn tại trong App.tsx → dùng /payment-failed?cancelled=true
.cancelUrl(frontendUrl + "/payment-failed?cancelled=true&bookingCode=" + bookingResp.getBookingCode())
```

**Bước 3 — Sau khi gọi PayOS thành công, dùng transactionId từ response:**
```java
PaymentUrlResponse payResp = paymentClient.createPayosPayment(payReq);
String checkoutUrl    = payResp.getCheckoutUrl();    // link PayOS cho user click
String transactionId  = payResp.getTransactionId();  // = orderCode của PayOS

// Build link trực tiếp đến payment-waiting (hiển thị trong booking success card):
String paymentWaitingLink = frontendUrl + "/payment-waiting?orderCode=" + transactionId
                          + "&bookingCode=" + bookingResp.getBookingCode();
// Hiển thị checkoutUrl cho user nhấn → thanh toán
// Hiển thị paymentWaitingLink → theo dõi trạng thái
```

**Note:** Nếu muốn thêm `/payment-cancel` route (Phase UX): tạo `PaymentCancelPage.jsx` + `<Route path="/payment-cancel" .../>` trong `App.tsx`.

**⚠️ Bug bổ sung — `PaymentWaitingPage.jsx` phải handle thiếu `orderCode` (Phase 1):**

Hiện tại `PaymentWaitingPage.jsx` (line ~30):
```javascript
if (!orderCode) { navigate('/'); return; }  // ← Ngay lập tức về '/' nếu không có orderCode
```

Scenario nguy hiểm: PayOS redirect về `/payment-waiting?bookingCode=BKxxx` **không kèm `orderCode`** (mạng chậm, edge case, v.v.) → trang `navigate('/')` → user mất thông tin thanh toán.

Hai nguồn vào `/payment-waiting` cần phân biệt:
- **Nguồn 1 — Chatbot success card link:** chatbot tự build `/payment-waiting?orderCode={transactionId}&bookingCode={code}` sau khi có `transactionId` từ PayOS response → luôn có `orderCode`
- **Nguồn 2 — PayOS redirect tự động:** PayOS thường append `orderCode` vào `returnUrl` khi redirect, nhưng không đảm bảo 100%

**Fix cần thêm vào Phase 1 (`PaymentWaitingPage.jsx`):**
```javascript
// Thay: if (!orderCode) { navigate('/'); return; }
// Bằng:
if (!orderCode && !bookingCode) {
    navigate('/');
    return;
}
if (!orderCode) {
    // Có bookingCode nhưng không có orderCode → hiển thị cảnh báo, cho retry
    setStatus('PENDING');
    setMessage('Không tìm thấy mã giao dịch. Vui lòng kiểm tra email xác nhận hoặc tra cứu đơn hàng.');
    // Không poll /payment/check-status/{orderCode} (không có gì để poll)
    // Hiển thị nút "Tra cứu đơn BKxxx" → navigate('/booking-lookup?code=' + bookingCode)
    return;
}
```

> **Tóm tắt điểm 3:** `returnUrl` gửi PayOS = `/payment-waiting?bookingCode=...` (không có `orderCode`). Chatbot success card dùng `transactionId` từ response để build link đầy đủ `/payment-waiting?orderCode=...&bookingCode=...`. `PaymentWaitingPage` cần guard cả hai case.

### 8.2 Fix BK lookup — Global intercept

**File:** `BookingConversationService.java` trong method `handle()`  
**Vấn đề:** Switch-case theo stage → khi stage≠IDLE, case BOOKING_LOOKUP không đến lượt  
**Fix:** Thêm check ở đầu method trước switch:
```java
public ChatbotResponse handle(String sessionId, String message, ConversationState state) {
    // Global BK lookup — hoạt động ở MỌI stage
    if (message.matches("(?i)BK[a-zA-Z0-9]{6,12}")) {
        return performLookup(message.trim().toUpperCase(), state);
    }
    // ... switch-case bình thường
}
```

### 8.3 Fix sessionId — LocalStorage

**File:** `ChatbotWidget.jsx` line 125  
**Fix:** Xem section 6.2 Tầng 1

### 8.4 Fix userId — AuthContext

**File:** `ChatbotWidget.jsx` lines 197, 263  
```javascript
// Thay userId: null thành:
userId: user?.userId || user?.userID || null,
```

### 8.5 Phone validation

**File:** `BookingConversationService.java` trong `handleContactNamePhone`
```java
// Validate phone: phải là 10-11 chữ số, bắt đầu bằng 0
if (!phone.matches("^0\\d{9,10}$")) {
    return buildTextResponse("Số điện thoại không hợp lệ. Vui lòng nhập số điện thoại 10-11 chữ số (VD: 0901234567)");
}
```

### 8.6 Fill departureCity

**File:** `BookingConversationService.java` trong `doSearch`/`handleTourSelection`  
Sau khi search xong, set `state.setDepartureCity(state.getStartLocation())`.

### 8.7 Thoát SHOWING_SEARCH_RESULTS trap

Khi user ở SHOWING_SEARCH_RESULTS nhập gì không phải 1/2/3:
- Nếu có chứa keyword tìm kiếm mới → reset về COLLECTING_SEARCH_INFO, xử lý lại
- Nếu là câu hỏi → gọi AI Router để phân loại
- Không loop "chọn tour nào?" mãi

---

## 9. ReferenceResolverService

> Đây là component **bắt buộc** để sửa triệt để lỗi "tour đó còn mấy slot". Không có service này, dù đã có `lastSearchResults`, `recentTurns`, `lastMentionedTourId` cũng không đủ — vì không có gì map message "tour đó" sang entity ID cụ thể rồi route đúng handler.

### 9.1 Trách nhiệm

```
ReferenceResolverService.resolve(message, state) → ResolvedContext
```

| Input | Output |
|-------|--------|
| Tin nhắn user + ConversationState | `ResolvedContext { tourId, departureId, resolvedFrom, isAmbiguous }` |

### 9.2 Logic ưu tiên (thứ tự từ cao xuống thấp)

```
1. lastMentionedDepartureId (state)   ← "chuyến đó", "ngày đó", "đơn đó"
2. selectedDepartureId (state)        ← đang trong booking flow
3. lastMentionedTourId (state)        ← "tour đó", "tour này", "cái đó"
4. lastSearchResults[0] (state)       ← fallback: tour đầu tiên trong kết quả vừa show
5. Ambiguous → hỏi lại user          ← khi lastSearchResults rỗng hoặc không rõ
```

### 9.3 Pattern matching (không gọi Gemini, chạy trước AI Router)

```java
public boolean isPronounReference(String message) {
    return message.matches(
        "(?i).*(tour đó|tour này|chuyến đó|chuyến này|cái đó|cái đầu tiên|" +
        "nó|đơn đó|booking đó|tour số [123]|cái [123]).*"
    );
}

public boolean isContextualShortQuestion(String message) {
    // Câu hỏi ngắn không có entity rõ → có thể là follow-up
    return message.matches(
        "(?i)(còn (mấy|bao nhiêu) (slot|chỗ|vé)?|" +
        "giá (bao nhiêu|mấy)|trẻ em giá|em bé giá|" +
        "lịch trình|đặt cọc|hủy được không|đi (ngày|tháng) mấy|" +
        "khởi hành khi nào|mấy ngày mấy đêm)"
    );
}
```

### 9.4 Tích hợp vào dispatch flow

```
User message
   ↓
[Fast-path classifier]      ← BK lookup, 1/2/3 selection, email, phone
   ↓ không match
[isPronounReference || isContextualShortQuestion?]
   ↓ YES
[ReferenceResolverService.resolve(message, state)]
   ↓
ResolvedContext
  ├─ isAmbiguous=true  → hỏi lại (xem 9.5)
  └─ tourId/departureId resolved → route tới intent handler:
       ASK_SLOT         → GET /api/departures/order-info?departureId={id}
       ASK_PRICE        → đọc lastSearchResults / lastDepartures (không gọi API)
       ASK_CHILD_PRICE  → đọc lastDepartures.childPrice
       ASK_DEPARTURE_DATE → đọc lastDepartures.departureDate
       ASK_ITINERARY    → GET /api/tours/{tourId} (nếu endpoint tồn tại)
       ASK_POLICY       → trả lời từ RAG với context tourId
       BOOKING_FLOW     → bắt đầu booking với tourId/departureId đã resolve
   ↓ NO
[AI Router (Gemini)] → intent bình thường
```

### 9.5 Xử lý ambiguous

```
State: lastSearchResults = [Tour A, Tour B, Tour C], lastMentionedTourId = null
User: "tour đó còn slot không" (sau khi vừa show 3 kết quả, chưa chọn)

→ ReferenceResolver: lastMentionedTourId = null → ambiguous
→ Bot hỏi lại:
  "Bạn đang hỏi về tour nào?
   1️⃣ Tour Hà Nội - Hạ Long 3N2Đ (còn 12 chỗ)
   2️⃣ Tour Hà Nội - Hạ Long 2N1Đ (còn 3 chỗ)
   3️⃣ Tour Hà Nội - Hạ Long 4N3Đ (hết chỗ)"
→ User: "1"
→ Resolve tourId = Tour A → ASK_SLOT handler → trả lời slot Tour A
```

### 9.6 Scenario end-to-end mục tiêu (nhân viên tư vấn thực thụ)

```
User: "tour nào đi Hạ Long tháng 6?"
Bot:  [Tour A: 12 slot, Tour B: 3 slot, Tour C: hết slot]
      → state.lastMentionedTourId = A, state.lastMentionedDepartureId = A.departures[0]

User: "tour đó còn mấy chỗ?"
→ ReferenceResolver: lastMentionedTourId = A
→ ASK_SLOT → GET /api/departures/order-info?departureId={A.departureId}
Bot: "Tour Hà Nội - Hạ Long 3N2Đ khởi hành 05/06 còn **12 chỗ**. Bạn muốn đặt không?"

User: "trẻ em giá bao nhiêu?"
→ isPronounReference = false (không có "tour đó"), nhưng isContextualShortQuestion = true
→ Resolve: lastMentionedDepartureId → đọc childPrice từ state.lastDepartures
Bot: "Trẻ em (2-11 tuổi) **1,200,000đ**, em bé dưới 2 tuổi **miễn phí**."

User: "đặt cái tour đó đi"
→ isPronounReference = true
→ Resolve: lastMentionedTourId = A → BOOKING_FLOW
Bot: [bắt đầu booking flow cho Tour A với departure đã biết]
```

---

## 10. UX — Nhân viên tư vấn du lịch thực thụ

### 10.1 Quick Reply Chips

Khi mở chatbot lần đầu, hiển thị chips:

```
[🔍 Tìm tour]  [📋 Tra cứu booking]  [💰 Tour giảm giá]  [❓ Tư vấn]
```

Khi xong booking, hiển thị:
```
[📋 Xem booking vừa đặt]  [🔍 Tìm tour khác]  [🏠 Trang chủ]
```

### 10.2 Hiển thị slot realtime

Trong TourCard, hiển thị:
- `Còn X chỗ` với màu đỏ nếu ≤5, vàng nếu ≤15, xanh nếu >15
- Ngày khởi hành gần nhất

### 10.3 Hỏi điểm khởi hành từ bước đầu (Tripi differentiator)

Thay vì chỉ hỏi "đi đâu?", hỏi ngay cả điểm đi:
```
Bot: "Bạn muốn đi đâu? Và xuất phát từ thành phố nào?"
```
Hoặc sau khi user nói "đi Hạ Long":
```
Bot: "Bạn đi từ thành phố nào? (Hà Nội, TP.HCM, Đà Nẵng...)"
```

### 10.4 Tra cứu theo userId (khi đã đăng nhập)

```
User (đã login): "xem booking của tôi"
Bot: [gọi GET /api/bookings?userId=X] → list booking của user
```

### 10.5 Pricing mid-flow

```
User: "tour này trẻ em giá bao nhiêu?"
Bot: [gọi GET /api/departures/order-info?departureId=X]
→ "Tour này trẻ em (2-11 tuổi) giá 1,400,000đ, em bé (<2 tuổi) miễn phí"
```

---

## 11. Lộ trình triển khai

### Phase 1 — Quick Wins (1-2 ngày, làm NGAY)

> Prerequisite: Xem lại 6 corrections ở Section 0 trước khi code.

| # | Task | File | Độ ưu tiên |
|---|------|------|------------|
| 1 | Fix analytics `PaymentUrlResponse.java`: thêm `transactionId` | dto/chatbot/PaymentUrlResponse.java | 🔴 CRITICAL |
| 2 | Fix returnUrl PayOS: chỉ bookingCode, không có orderCode | BookingConversationService.java | 🔴 CRITICAL |
| 3 | Fix cancelUrl: dùng `/payment-failed?cancelled=true` | BookingConversationService.java | 🔴 CRITICAL |
| 4 | Dùng transactionId từ response để build paymentWaitingLink | BookingConversationService.java | 🔴 HIGH |
| 5 | BK lookup global intercept (mọi stage) | BookingConversationService.java | 🔴 HIGH |
| 6 | Fix sessionId → localStorage | ChatbotWidget.jsx | 🔴 HIGH |
| 7 | Fix userId → AuthContext | ChatbotWidget.jsx | 🟡 MEDIUM |
| 8 | Phone validation regex | BookingConversationService.java | 🟡 MEDIUM |
| 9 | Fix departureCity | BookingConversationService.java | 🟡 MEDIUM |
| 10 | Search topK=30 + Java filter (Option A Pinecone) | doSearch method | 🟡 MEDIUM |
| 11 | Thoát SHOWING_SEARCH_RESULTS trap | BookingConversationService.java | 🟡 MEDIUM |
| 12 | Fix `PaymentWaitingPage.jsx`: guard thiếu `orderCode` (Section 8.1) | PaymentWaitingPage.jsx | 🟡 MEDIUM |

### Phase 2 — Conversation Memory + ReferenceResolver (3-5 ngày)

| # | Task | File |
|---|------|------|
| 1 | Thêm `ChatTurn` inner class vào ConversationState | ConversationState.java |
| 2 | Thêm `recentTurns`, `lastMentionedTourId`, `lastMentionedDepartureId` | ConversationState.java |
| 3 | Cập nhật recentTurns + lastMentionedTourId sau mỗi message | BookingConversationService.java |
| 4 | Implement `ReferenceResolverService` (Section 9) | analytics-service |
| 5 | Tích hợp ReferenceResolver vào ChatbotService dispatch | ChatbotService.java |
| 6 | Truyền recentTurns vào Gemini khi gọi RAG | ChatbotService.java |
| 7 | Lưu messages + sessionId vào localStorage FE | ChatbotWidget.jsx |
| 8 | Tạo bảng `chat_history` trong analytics DB | migration |

### Phase 3 — AI Router với granular intents (5-7 ngày)

| # | Task | File |
|---|------|------|
| 1 | Tạo `IntentRouter.java` với fast-path rules | analytics-service |
| 2 | Tạo `GeminiIntentService.java` với structured JSON output | analytics-service |
| 3 | Tạo `IntentResult.java` DTO với enum granular (Section 4.1) | analytics-service |
| 4 | Tích hợp IntentRouter vào ChatbotService | ChatbotService.java |
| 5 | Handlers riêng: `ASK_SLOT`, `ASK_PRICE`, `ASK_CHILD_PRICE`, `ASK_DEPARTURE_DATE`, `ASK_ITINERARY`, `ASK_POLICY` | BookingConversationService.java |
| 6 | Update doSearch để nhận extracted entities từ AI Router | BookingConversationService.java |

### Phase 4 — Search Pipeline + RabbitMQ Sync (5-7 ngày)

| # | Task | File |
|---|------|------|
| 1 | Pinecone Option B: flatten metadata khi upsert (Section 5.3) | VectorService.java |
| 2 | Re-run full sync sau khi đổi upsert format | VectorSyncService.java |
| 3 | Re-ranking logic sau Pinecone | doSearch method |
| 4 | Tạo `RabbitMQConfig.java` trong analytics-service | analytics-service |
| 5 | Khai báo `chatbot.vector.sync.queue` + binding `chatbot.sync.*` | analytics-service RabbitMQConfig.java |
| 6 | Thêm `@RabbitListener` cho queue chatbot.sync.* | VectorSyncConsumer.java |
| 7 | Debounce với Redis Set | VectorSyncService.java |
| 8 | Publisher trong tour-catalog-service dùng routing key `chatbot.sync.tour`, `chatbot.sync.departure` | TourService.java, DepartureService.java |

### Phase 5 — UX Polish (3-4 ngày)

| # | Task | File |
|---|------|------|
| 1 | Quick reply chips | ChatbotWidget.jsx |
| 2 | Slot indicator màu sắc (Còn X chỗ) | TourCard component |
| 3 | Hỏi startLocation từ bước đầu | BookingConversationService.java |
| 4 | Lookup theo userId khi đã login | ChatbotService.java |
| 5 | Pricing mid-flow query | BookingConversationService.java |
| 6 | Tạo `/payment-cancel` route (Option B C4) | App.tsx + PaymentCancelPage.jsx |

---

## 12. Test Plan

### 12.1 Phase 1 Tests (Quick Wins)

| Test | Input | Expected |
|------|-------|----------|
| F5 sau chat | Reload trang | sessionId giữ nguyên, messages vẫn còn |
| PayOS checkout link | Complete booking | `checkoutUrl` từ PayOS hiển thị đúng |
| PayOS redirect (success) | User thanh toán xong | PayOS redirect đến `/payment-waiting?bookingCode=BKxxx` |
| PayOS redirect (cancel) | User bấm hủy | Redirect đến `/payment-failed?cancelled=true&bookingCode=BKxxx` |
| Payment-waiting link từ chatbot | Booking success card | Link `/payment-waiting?orderCode={transactionId}&bookingCode={code}` đúng |
| BK lookup từ SHOWING_SEARCH_RESULTS | "BKc2ca3ff9" khi đang ở kết quả tìm kiếm | Hiển thị ORDER_DETAIL |
| Phone invalid | Nhập "Nu" khi hỏi số điện thoại | "Số điện thoại không hợp lệ" |
| Guest booking | userId=null | Booking tạo thành công |
| Logged-in booking | userId=123 | Booking có userId đúng |

### 12.2 Phase 2 Tests (Memory + ReferenceResolver)

| Test | Input | Expected |
|------|-------|----------|
| Coreference rõ | "tour Hạ Long?" → [kết quả A] → "tour đó còn mấy slot?" | Gọi API slot tour A, KHÔNG RAG lại |
| Coreference sau selection | Chọn tour 1 → "trẻ em giá bao nhiêu?" | Đọc childPrice từ lastDepartures |
| Ambiguous reference | Show 3 tour → "tour đó" (chưa chọn) | Hỏi lại "Bạn đang hỏi tour 1, 2, hay 3?" |
| Resolve sau clarify | Hỏi lại → user chọn "2" → resolve tour B | Trả lời đúng về tour B |
| No context F5 | F5 mới → "tour đó giá mấy?" | "Bạn muốn hỏi tour nào? Hãy cho mình biết điểm đến" |
| Context nhiều turns | Hỏi 5-6 câu liên tiếp | Bot nhớ context xuyên suốt |

### 12.3 Phase 3 Tests (AI Router + granular intent)

| Test | Input | Expected |
|------|-------|----------|
| "đi biển gần HCM" | IDLE | TOUR_SEARCH, destination=biển, startLocation=HCM |
| "muốn đi Đà Lạt chơi" | IDLE | TOUR_SEARCH (không phải BOOKING_FLOW) |
| "tour đó còn slot không" | sau search | ASK_SLOT → gọi API, KHÔNG gọi Gemini route |
| "trẻ em giá thế nào" | sau chọn tour | ASK_CHILD_PRICE → đọc lastDepartures |
| "đặt cái tour đó đi" | sau search | BOOKING_FLOW với resolvedRef=lastMentionedTourId |
| "tôi muốn đổi tìm tour khác" | bất kỳ stage | CHANGE_SEARCH → reset về IDLE |

### 12.4 Phase 4 Tests (Search + Sync)

| Test | Input | Expected |
|------|-------|----------|
| Java filter sau topK=30 | "tour Hạ Long" | Chỉ kết quả endLocationName="Hạ Long", không lẫn tour khác |
| Tìm tour không có | "tour Phú Quốc từ Cần Thơ tháng 6" | "Chưa có tour Phú Quốc phù hợp, gợi ý:" |
| RabbitMQ routing | Admin emit `chatbot.sync.departure` | Đến đúng `chatbot.vector.sync.queue` (không route lạc) |
| Debounce | 5 lần update trong 1 phút | Pinecone sync 1 lần sau 5 phút |
| Full sync 2AM | Sau cron | Tất cả tour sync lên Pinecone |

---

## PHỤ LỤC A: State Machine Hiện tại

```
IDLE
 ├─ booking intent → COLLECTING_SEARCH_INFO
 └─ RAG query

COLLECTING_SEARCH_INFO
 └─ đủ slots (destination, month, passengers) → doSearch → SHOWING_SEARCH_RESULTS

SHOWING_SEARCH_RESULTS
 ├─ 1/2/3 → SELECTING_DEPARTURE (chọn ngày đi)
 └─ (hiện tại bị trap nếu input khác)

SELECTING_DEPARTURE
 └─ chọn ngày → COLLECTING_PASSENGERS

COLLECTING_PASSENGERS
 └─ số người → COLLECTING_CONTACT_NAME_PHONE

COLLECTING_CONTACT_NAME_PHONE
 └─ tên + phone → COLLECTING_CONTACT_EMAIL

COLLECTING_CONTACT_EMAIL
 └─ email → CONFIRMING_BOOKING

CONFIRMING_BOOKING
 ├─ xác nhận → gọi API booking + payment → BOOKING_SUCCESS
 └─ hủy → IDLE

BOOKING_SUCCESS (terminal, reset về IDLE sau 30s)

COLLECTING_LOOKUP_CODE (từ IDLE khi user hỏi tra cứu)
 └─ BKxxxxxxxx → performLookup → IDLE
```

---

## PHỤ LỤC B: API Endpoints chatbot dùng

| Endpoint | Service | Port | Auth |
|----------|---------|------|------|
| `POST /api/bookings/create` | booking-service | 8083 | No |
| `GET /api/bookings/payment/{bookingCode}` | booking-service | 8083 | No |
| `GET /api/departures/order-info?departureId=X` | tour-catalog-service | 8081 | No |
| `POST /api/payment/payos/create` | payment-service | 8086 | No |
| `GET /api/bookings?userId=X` | booking-service | 8083 | JWT (Phase 5) |

---

## PHỤ LỤC C: File quan trọng

```
analytics-service/src/main/java/com/tourism/analytics/
├── service/
│   ├── ChatbotService.java              -- dispatch logic
│   ├── BookingConversationService.java  -- state machine (file lớn nhất)
│   └── VectorSyncService.java           -- Pinecone upsert (hiện chỉ có cron)
├── dto/chatbot/
│   ├── ConversationState.java           -- Redis state
│   ├── ChatbotBookingDetailResponse.java -- ORDER_DETAIL lookup DTO
│   └── TourSuggestion.java             -- search result DTO
└── config/
    └── RabbitMQConfig.java              -- cần thêm queue config

tourism_frontend/client-side/src/
└── components/ChatbotWidget/
    └── ChatbotWidget.jsx               -- FE chatbot widget
```

---

*Plan này được viết và reviewed ngày 25/05/2026 dựa trên đọc code trực tiếp:*
- *`ConversationState.java` — xác nhận lastSearchResults/lastDepartures đã có*
- *`VectorService.upsertVector()` — xác nhận metadata là JSON string, không flatten*
- *`BookingConversationService.java` — xác nhận returnUrl sai, không có transactionId*
- *`payment-service/PaymentUrlResponse.java` — xác nhận transactionId có trong payment-service*
- *`analytics-service/PaymentUrlResponse.java` — xác nhận thiếu transactionId*
- *`booking-service/RabbitMQConfig.java` — xác nhận topology và routing keys hiện tại*
- *`App.tsx` — xác nhận /payment-cancel không tồn tại*
- *Ảnh demo chatbot cho thấy lỗi không nhớ ngữ cảnh*

**Trạng thái: READY TO IMPLEMENT** — 6 corrections đã được tích hợp vào từng section.
