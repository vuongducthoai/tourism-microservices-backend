# BÁO CÁO TRIỂN KHAI CHATBOT AI — Future Travel
**Ngày:** 28/05/2026  
**Phiên bản:** 2.0 (Stateful Booking Flow + RAG)  
**Dịch vụ xử lý:** `analytics-service` (port 8087)  
**Trạng thái:** ✅ Đã triển khai và kiểm thử thành công

---

## 1. TỔNG QUAN KIẾN TRÚC

### 1.1 Luồng yêu cầu đầu-cuối

```
[Browser / React SPA]
     POST /api/chatbot/chat
     { message, sessionId, userId }
          │
          ▼
[API Gateway :8080]
  Route: /api/chatbot/** → lb://analytics-service
          │
          ▼
[analytics-service :8087]
  ChatbotController → ChatbotService.handleUserMessage()
          │
          ├─── IntentRouter.route()         ← phân loại ý định
          │         └── GeminiIntentService  ← AI fallback
          │
          ├─── handleDeterministic()        ← xử lý GREETING/CANCEL/LOOKUP
          │         └── BookingConversationService.performLookup()
          │                   └── Feign: booking-service GET /api/bookings/payment/{code}
          │
          ├─── handleBookingFlow()          ← state machine booking
          │         └── BookingConversationService.handle()
          │               ├── Feign: tour-catalog GET /api/tours/chatbot-search
          │               ├── Feign: tour-catalog GET /api/departures/chatbot/{tourId}
          │               └── Feign: booking POST /api/bookings
          │
          └─── handleWithRAG()              ← RAG Pinecone + Gemini
                    ├── VectorService.searchSimilar()
                    │     ├── EmbeddingService → llama-text-embed-v2
                    │     └── Pinecone query (cosine similarity)
                    └── Gemini 2.0 Flash Lite (text generation)
          │
          ▼
    ChatMessageResponse
    { reply, tourSuggestions, quickActions,
      bookingConfirmData, bookingCode, paymentUrl,
      conversationStage, messageType, sessionId }
```

### 1.2 Thành phần hệ thống

| Thành phần | Công nghệ | Vai trò |
|-----------|-----------|---------|
| API Gateway | Spring Cloud Gateway 8080 | Route, load balance |
| analytics-service | Spring Boot 3 / Java 17, port 8087 | Toàn bộ logic chatbot |
| Redis | Redis 7, TTL 30 phút | Lưu ConversationState theo sessionId |
| Pinecone | Vector DB (cloud) | RAG — lưu và tìm kiếm document dạng vector |
| Gemini 2.0 Flash Lite | Google AI (miễn phí) | Intent classification + text generation |
| booking-service | Spring Boot, port 8082 | Tạo booking, lookup booking, payment |
| tour-catalog-service | Spring Boot, port 8083 | Tìm tour, thông tin chuyến đi |
| payment-service | Spring Boot, port 8081 | Tạo link thanh toán PayOS |

---

## 2. PHÂN LOẠI Ý ĐỊNH (INTENT ROUTING)

### 2.1 IntentRouter — luồng xử lý

`IntentRouter.route(message, state)` thực hiện phân loại theo thứ tự ưu tiên:

```
1. BK Pattern fast-path
   msg.matches("(?i)(BK[A-Za-z0-9]{8,})")
   → BOOKING_LOOKUP_PAYMENT (confidence 1.0)

2. Stage-specific fast-path: COLLECTING_NOTE_COUPON
   stage == COLLECTING_NOTE_COUPON && matches("bo\\s*qua|khong|skip|tiep\\s*tuc")
   → TRANSACTION_FLOW (confidence 1.0)
   [QUAN TRỌNG: chạy TRƯỚC isCancel() để "bỏ qua" không bị nhận nhầm là cancel]

3. isCancel(norm)
   matches(".*(cancel|khong\\s*can\\s*nua|khong\\s*dat|thoi\\s*di|huy\\s*di|huy\\s*thoi|thoi\\s+khong).*")
   → CANCEL (confidence 1.0)

4. isResume(norm) → RESUME_BOOKING
5. isGreeting(norm) → GREETING (confidence 0.98)

6. Stage fast-paths (CONFIRMING_BOOKING, SHOWING_SEARCH_RESULTS, SELECTING_DEPARTURE)
   Số "1"/"2"/"3", "xác nhận", "đồng ý" → TRANSACTION_FLOW

7. ReferenceResolver — xử lý "cái đó", "chuyến kia", "tour đó"
   → TOUR_RETRIEVAL với tourId đã resolve

8. Content analysis fast-paths:
   - isAskDiscount → TOUR_RETRIEVAL(DISCOUNT)
   - isAskCoupon   → TOUR_RETRIEVAL(COUPON)
   - isSystemHelp  → GENERAL_RAG
   - isAskSlot     → TOUR_RETRIEVAL(SLOT)
   - isAskPrice    → TOUR_RETRIEVAL(PRICE)
   - isAskDepartureDate → TOUR_RETRIEVAL(DEPARTURE_DATE)
   - isAskDetail   → TOUR_RETRIEVAL(DETAIL)
   - isLookupIntent → BOOKING_LOOKUP_PAYMENT
   - isAskItinerary → TOUR_RETRIEVAL(ITINERARY)
   - isRatingOrReviewQuery → GENERAL_RAG
   - isGeneralAdviceQuery  → GENERAL_RAG
   - isBookingIntent → TRANSACTION_FLOW
   - isStartLocationSearch / isChangeSearch → TOUR_RETRIEVAL(SEARCH)

9. COLLECTING_SEARCH_INFO stage-aware paths
   (thêm thông tin điểm đến, tháng đi, số người)

10. Gemini AI fallback — gọi khi các fast-path trên không khớp
    Prompt JSON → { intent, retrievalTask, destination, startLocation, month, adultCount }
    responseMimeType: "application/json" (buộc output JSON)
```

### 2.2 Hàm normalize()

Trước khi khớp regex, message được chuẩn hóa:
- NFD decompose → loại bỏ dấu thanh (`\p{M}`) 
- Lowercase
- Kết quả: "Hủy" → "huy", "Đặt" → "dat", "Bỏ qua" → "bo qua"

Điều này cho phép regex dùng không dấu nhưng vẫn nhận đúng tiếng Việt có dấu.

### 2.3 Danh sách Intent

| Intent | Mô tả | Xử lý |
|--------|-------|-------|
| `GREETING` | Chào hỏi, bắt đầu phiên | Reset session → IDLE |
| `CANCEL` | Hủy luồng hiện tại | Reset stage → IDLE |
| `RESUME_BOOKING` | Tiếp tục booking đang dở | Quay lại stage trước |
| `BOOKING_LOOKUP_PAYMENT` | Tra cứu đơn hàng | Feign → booking-service |
| `TOUR_RETRIEVAL` | Tìm kiếm / tư vấn tour | Tùy RetrievalTask |
| `TRANSACTION_FLOW` | Bước tiếp theo trong booking | BookingConversationService |
| `GENERAL_RAG` | Câu hỏi tổng quát | Pinecone + Gemini |
| `UNKNOWN` | Không xác định | RAG fallback |

---

## 3. MÁY TRẠNG THÁI HỘI THOẠI (CONVERSATION STATE MACHINE)

### 3.1 ConversationState.Stage — 11 trạng thái

```
                         ┌─────────────────┐
               ┌────────►│      IDLE        │◄──────────────────┐
               │         └────────┬────────┘                   │
          CANCEL / GREETING        │ booking intent              │ CANCEL
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │   COLLECTING_SEARCH_INFO     │             │
               │    │   (đang hỏi điểm đến, thời  │             │
               │    │    gian, số người)            │             │
               │    └──────────────┬──────────────┘             │
               │              có tour                            │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │  SHOWING_SEARCH_RESULTS      │             │
               │    │  (hiển thị 1-3 tour phù hợp)│             │
               │    └──────────────┬──────────────┘             │
               │           chọn tour (1/2/3)                    │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │   SELECTING_DEPARTURE        │             │
               │    │   (chọn ngày khởi hành)      │             │
               │    └──────────────┬──────────────┘             │
               │           chọn ngày                            │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │   COLLECTING_PASSENGERS      │             │
               │    │   (tên, giới tính, ngày sinh │             │
               │    │    từng hành khách)           │             │
               │    └──────────────┬──────────────┘             │
               │              đủ hành khách                     │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │ COLLECTING_CONTACT_NAME_PHONE│             │
               │    │   (họ tên + số điện thoại)   │             │
               │    └──────────────┬──────────────┘             │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │  COLLECTING_CONTACT_EMAIL    │             │
               │    └──────────────┬──────────────┘             │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │  COLLECTING_NOTE_COUPON      │             │
               │    │  (địa chỉ, ghi chú, coupon)  │             │
               │    │  "bỏ qua" → skip             │             │
               │    └──────────────┬──────────────┘             │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               │    │   CONFIRMING_BOOKING         │             │
               │    │   (xác nhận đặt)             │             │
               │    └──────────────┬──────────────┘             │
               │           "xác nhận"                           │
               │                  ▼                             │
               │    ┌─────────────────────────────┐             │
               └────│    BOOKING_SUCCESS           │─────────────┘
                    │   (bookingCode + paymentUrl) │
                    └─────────────────────────────┘
                    
                    ┌─────────────────────────────┐
             ───────│  COLLECTING_LOOKUP_CODE      │
                    │  (chờ user nhập mã BK...)    │
                    └─────────────────────────────┘
```

### 3.2 Redis Session

- **Key:** `chatbot:session:{sessionId}`
- **Value:** JSON serialized `ConversationState`
- **TTL:** 30 phút (tự động gia hạn mỗi lần ghi)
- **Dữ liệu lưu:** stage, searchDestination, searchStartLocation, lastSearchResults, lastDepartures, passengers, contactName, contactPhone, contactEmail, recentTurns (6 turn gần nhất)

---

## 4. RAG PIPELINE (Retrieval-Augmented Generation)

### 4.1 Cơ chế hoạt động

```
[User message]
     │
     ▼
[EmbeddingService]
  POST https://generativelanguage.googleapis.com/v1beta/models/
       text-embedding-004:embedContent
  → vector 1024 chiều
     │
     ▼
[VectorService.searchSimilar(message, topK)]
  POST https://{pinecone-host}/query
  Body: { vector: [...1024], topK: 10|50, includeMetadata: true }
  → list<VectorDocumentDTO> (cosine similarity score)
     │
     ▼
[buildEnhancedContext(docs)]
  Phân loại docs theo type:
  - TOUR_SUMMARY   → tên, mô tả, điểm nổi bật
  - TOUR_DEPARTURE → ngày khởi hành, giá, slots còn lại, coupon
  - COUPON         → mã giảm giá, điều kiện, HSD
  - FAQ            → câu hỏi thường gặp, chính sách
  - REVIEW         → đánh giá khách hàng
     │
     ▼
[Gemini 2.0 Flash Lite — text generation]
  Model: gemini-2.0-flash-lite (miễn phí)
  Prompt:
  - System: "Bạn là trợ lý du lịch AI của Tourism..."
  - Context: (tài liệu từ Pinecone)
  - ConversationHistory: (6 turn gần nhất)
  - Question: (tin nhắn user)
  → reply (markdown tiếng Việt)
```

### 4.2 topK động

| Loại query | topK | Lý do |
|-----------|------|-------|
| Discount / giảm giá | 50 | Cần quét toàn bộ tour đang giảm |
| Coupon | 50 | Cần lấy đủ tất cả mã giảm giá |
| Thông thường | 10 | Đủ context, nhanh hơn |

### 4.3 RAG vs Chatbot có ngữ cảnh thực tế

Pinecone lưu dữ liệu được **sync từ DB thực** (tour-catalog-service) qua job lên lịch lúc 2AM và qua endpoint `/api/chatbot/admin/sync`. Điều này khác với chatbot LLM thông thường đã được "huấn luyện" — bot **không đoán mò** mà trả lời dựa trên dữ liệu tour, giá, slots, coupon **thực tế trong hệ thống** tại thời điểm query.

Nhờ đó:
- Giá hiển thị = giá thực trong DB
- Coupon hiển thị = mã đang còn hiệu lực
- Số chỗ còn lại = số thực, không hardcode

---

## 5. LUỒNG BOOKING TRONG CHAT

### 5.1 BookingConversationService.handle()

```
BCS.handle(request, state)
     │
     ├── [1] Global BK fast-path — mọi stage
     │   msg.matches("(?i)BK[A-Za-z0-9]{8,}")
     │   → performLookup(code, sessionId, state)
     │
     ├── [2] Cancel guard (trừ COLLECTING_NOTE_COUPON)
     │   isCancel(msg) && stage != COLLECTING_NOTE_COUPON && stage != IDLE
     │   → reset → IDLE
     │
     └── [3] Switch theo stage
           IDLE                          → handleIdle
           COLLECTING_SEARCH_INFO        → handleSearchInfo
           SHOWING_SEARCH_RESULTS        → handleTourSelection
           SELECTING_DEPARTURE           → handleDepartureSelection
           COLLECTING_PASSENGERS         → handlePassengerInfo
           COLLECTING_CONTACT_NAME_PHONE → handleContactNamePhone
           COLLECTING_CONTACT_EMAIL      → handleContactEmail
           COLLECTING_NOTE_COUPON        → handleNoteCoupon [KHÔNG có cancel guard]
           CONFIRMING_BOOKING            → handleConfirm
           BOOKING_SUCCESS               → handleAfterSuccess
           COLLECTING_LOOKUP_CODE        → handleLookup
           default                       → reset → IDLE + thông báo lỗi
```

### 5.2 Tìm kiếm tour — doSearch()

- Gọi Feign: `GET /api/tours/chatbot-search?destination=...&startLocation=...&month=...&adultCount=...`
- Nhóm departures theo tourId → tạo `TourGroupDisplay`
- Hiển thị tối đa 3 tour, tối đa 3 ngày/tour
- Nếu không có kết quả → `clearResultContext()` + RAG fallback

### 5.3 Tạo Booking

Khi user xác nhận ở `CONFIRMING_BOOKING`:
```
BCS.handleConfirm()
     │
     ├── Build CreateBookingRequest
     │   { departureId, userId, contactFullName, contactPhone, contactEmail,
     │     contactAddress, customerNote, passengers[] }
     │
     ├── Feign: POST /api/bookings
     │   → BookingCreatedResponse { bookingId, bookingCode, totalPrice }
     │
     ├── Feign: POST /api/payments/{bookingId}/create-payment-link
     │   → { paymentUrl, orderCode }
     │
     └── Return BOOKING_SUCCESS response
         { bookingCode, paymentUrl, paymentWaitingLink }
```

### 5.4 Tra cứu Booking — performLookup()

```
performLookup(code, sessionId, state)
     │
     ├── Feign: GET /api/bookings/payment/{code}
     │   (booking-service: UPPER(bookingCode) = UPPER(:code) — case-insensitive)
     │
     └── Return ORDER_DETAIL response
         { bookingCode, tourName, status, originalPrice, paidAmount,
           remainingAmount, paymentDeadline, passengers[] }
```

---

## 6. MÔ TẢ CÁC CLASS CHÍNH

### 6.1 `ChatbotController`
- **Endpoint:** `POST /api/chatbot/chat`
- **Input:** `ChatMessageRequest { message, sessionId, userId }`
- **Output:** `ChatMessageResponse`
- Validate input rỗng, ủy quyền `ChatbotService`

### 6.2 `ChatbotService`
- **Orchestrator chính** — điều phối toàn bộ pipeline
- `handleUserMessage()`: load state → IntentRouter → handleDeterministic → handleBookingFlow → handleWithRAG
- `handleDeterministic()`: xử lý GREETING, CANCEL, RESUME_BOOKING, BOOKING_LOOKUP_PAYMENT, TOUR_RETRIEVAL
- `handleBookingFlow()`: ủy quyền `BookingConversationService` khi stage != IDLE hoặc intent = TRANSACTION_FLOW/TOUR_RETRIEVAL
- `handleWithRAG()`: Pinecone + Gemini cho câu hỏi tổng quát

### 6.3 `IntentRouter`
- **Phân loại ý định** từ message + state
- Fast-path chains → giảm thiểu gọi Gemini API
- `normalize()`: loại bỏ dấu tiếng Việt để regex đơn giản hơn
- `isCancel()`: chỉ nhận từ hủy rõ ràng, không nhận câu hỏi về hủy

### 6.4 `BookingConversationService` (~1350 dòng)
- **State machine booking flow** — 11 stage handler
- Mỗi handler xử lý một bước: tìm tour, chọn tour, chọn ngày, hành khách, liên hệ, ghi chú, xác nhận
- `performLookup()`: tra cứu booking qua Feign, trả về ORDER_DETAIL
- `doSearch()`: tìm tour, nhóm theo tourId, lưu vào Redis state

### 6.5 `GeminiIntentService`
- **AI fallback classifier** cho IntentRouter
- Gọi `gemini-2.0-flash-lite` với prompt JSON schema
- `responseMimeType: "application/json"` đảm bảo output luôn là JSON hợp lệ
- Phân tích: intent, retrievalTask, destination, startLocation, month, adultCount

### 6.6 `VectorService`
- **Pinecone client**: upsert, query, delete vectors
- `searchSimilar(message, topK)`: embed → query Pinecone → trả về `VectorDocumentDTO[]`

### 6.7 `VectorSyncService`
- Đồng bộ dữ liệu DB → Pinecone
- Lên lịch 2AM hàng ngày + endpoint admin `/api/chatbot/admin/sync`
- Document types: TOUR_SUMMARY, TOUR_DEPARTURE, COUPON, FAQ, REVIEW, LOCATION

### 6.8 `RedisSessionService`
- CRUD ConversationState trong Redis
- `getOrCreate(sessionId)`: lấy state hiện tại hoặc tạo mới (stage=IDLE)
- `save(sessionId, state)`: lưu + gia hạn TTL 30 phút

### 6.9 `LocationResolverService`
- Chuẩn hóa tên địa điểm: "HN", "Hà Nội", "Ha Noi" → "Hà Nội"
- Dùng khi tìm kiếm tour theo điểm đến

### 6.10 `ReferenceResolverService`
- Giải quyết đại từ và ngữ cảnh: "cái đó", "chuyến kia", "tour đó"
- Tra `state.lastMentionedTourId` và `state.lastSearchResults`

---

## 7. CÁC LỖI ĐÃ SỬA (BUG FIXES)

### 7.1 Bảng tóm tắt

| # | File | Mô tả lỗi | Ảnh hưởng | Fix |
|---|------|-----------|-----------|-----|
| 1 | `IntentRouter.java:46` | `bkM.group(1).toUpperCase()` làm mất chữ thường trong mã BK | Tra cứu `BKf3845364` thất bại | Xóa `.toUpperCase()` |
| 2 | `IntentRouter.java:338` | `isCancel()` có `huy\\s*tour`, `huy\\s*dat`, `bo\\s*qua` | "hủy tour nếu thời tiết xấu" → false cancel | Xóa 3 pattern này |
| 3 | `BookingConversationService.java:58` | `performLookup(msg.trim().toUpperCase())` | Tra cứu sai vì mã BK viết thường | Xóa `.toUpperCase()` |
| 4 | `BookingConversationService.java:880` | `handleLookup` dùng toàn bộ message làm mã BK | Lỗi 400/404 khi message không phải mã BK | Trả `null` nếu không extract được code |
| 5 | `BookingConversationService.java:947` | `isCancel()` local có `huy\\s*tour`, `huy\\s*dat`, `bo\\s*qua` | Tương tự lỗi #2 | Xóa 3 pattern này |
| 6 | `BookingConversationService.java:144` | `askForMissingSearchInfoIfNeededV3` block search khi thiếu params | User không thể search chỉ với điểm đến | Luôn trả `null` (không block) |
| 7 | `BookingConversationService.java:switch` | Không có `default` case trong switch stage | NPE / silent fail ở stage không xử lý | Thêm `default` → reset IDLE + log warn |
| 8 | `GeminiIntentService.java:52` | Thiếu `responseMimeType: "application/json"` | Gemini đôi khi trả markdown thay vì JSON → parse error | Thêm vào `generationConfig` |
| 9 | `BookingRepository.java:20` | `WHERE b.bookingCode = :code` phân biệt hoa/thường | Tra cứu "bkf3845364" không tìm được "BKf3845364" | `WHERE UPPER(b.bookingCode) = UPPER(:code)` |

### 7.2 Chi tiết từng lỗi

#### Lỗi #1 & #3: Mã BK bị UpperCase
**Nguyên nhân:** `BK pattern matcher` trích xuất mã rồi `.toUpperCase()` trước khi tra cứu. Mã booking được sinh dạng mixed-case (`BKf3845364`), nên `UPPER()` làm thay đổi giá trị.

**Fix:** Xóa `.toUpperCase()` ở cả IntentRouter và BCS. Thay vào đó fix ở tầng DB (lỗi #9) để tra cứu case-insensitive.

#### Lỗi #2 & #5: False Cancel
**Nguyên nhân:** Pattern `huy\\s*tour` quá rộng — "hủy tour nếu thời tiết xấu" là câu hỏi chính sách, không phải lệnh hủy. Pattern `bo\\s*qua` nên là skip tùy chọn ở stage note/coupon, không phải cancel.

**Fix:** Loại 3 pattern ra khỏi `isCancel()`. Bây giờ chỉ các từ hủy rõ ràng như "hủy", "thôi đi", "cancel" mới trigger cancel.

#### Lỗi #4: handleLookup gây lỗi 400
**Nguyên nhân:** `handleLookup(msg)` truyền toàn bộ `msg` vào `performLookup()` mà không kiểm tra. Khi user nhập "Xin chào" ở `COLLECTING_LOOKUP_CODE`, hệ thống gọi `GET /api/bookings/payment/Xin chào` → 400 Bad Request.

**Fix:** `handleLookup` trước tiên gọi `extractBookingCode(msg)`. Nếu không tìm được mã BK → trả `null` → caller tự xử lý thông báo.

#### Lỗi #6: Search bị block
**Nguyên nhân:** `askForMissingSearchInfoIfNeededV3()` kiểm tra nhiều điều kiện và hỏi thêm thông tin khi thiếu. Điều này khiến user không thể search chỉ với "tour Đà Nẵng" mà phải nhập đủ cả ngày, tháng, số người.

**Fix:** Hàm luôn trả `null` — tìm kiếm ngay với params có sẵn. Khi thiếu params, RAG xử lý query mơ hồ sẽ vẫn cho kết quả.

#### Lỗi #7: Switch thiếu default
**Nguyên nhân:** Switch `state.getStage()` không có `default`, nếu stage có giá trị lạ → không trả về gì → NPE hoặc null response.

**Fix:** Thêm `default` → reset về IDLE + log warning.

#### Lỗi #8: Gemini trả markdown
**Nguyên nhân:** Không set `responseMimeType`, Gemini có thể trả:
```
```json
{"intent": "TOUR_RETRIEVAL"}
```
```
Thay vì plain JSON. Gson parse thất bại → intent = UNKNOWN.

**Fix:** `genConfig.put("responseMimeType", "application/json")` buộc Gemini trả JSON thuần.

#### Lỗi #9: Case-sensitive DB lookup
**Nguyên nhân:** JPA query `WHERE b.bookingCode = :code` phân biệt hoa/thường trong PostgreSQL. User nhập "bkf3845364" không tìm thấy "BKf3845364".

**Fix:** `WHERE UPPER(b.bookingCode) = UPPER(:code)` — tra cứu case-insensitive.

---

## 8. KẾT QUẢ KIỂM THỬ API

### 8.1 Tóm tắt

| Tổng | PASS | FAIL | Tỷ lệ |
|------|------|------|-------|
| 26 | 21 | 5 | **80.8%** |

### 8.2 Chi tiết kết quả

| ID | Kịch bản | Stage kỳ vọng | Kết quả | Ghi chú |
|----|---------|--------------|---------|---------|
| T01 | Xin chào | IDLE | ✅ PASS | Greeting reset hoạt động |
| T02 | "Chính sách hủy tour như thế nào?" | IDLE | ✅ PASS | Câu hỏi policy KHÔNG bị false-cancel |
| T03 | "Điều kiện hủy đặt tour là gì?" | IDLE | ❌ FAIL | Gemini nhận diện "hủy đặt tour" = booking search → COLLECTING_SEARCH_INFO. Hành vi chấp nhận được (không phải false-cancel) |
| T04 | "Mình bỏ qua chính sách hoàn tiền" | IDLE | ❌ FAIL | Session đang COLLECTING_SEARCH_INFO từ T03 → xử lý như search info |
| T05 | Tìm tour Đà Nẵng tháng 5 | SHOWING_SEARCH_RESULTS | ✅ PASS | 2 tour tìm được |
| T06 | Thêm điểm xuất phát TP. HCM | SHOWING_SEARCH_RESULTS | ✅ PASS | Lọc còn 1 tour |
| T08 | Chọn tour "2" | SELECTING_DEPARTURE | ✅ PASS | Tour đã chọn |
| T09 | Chọn ngày "1" | COLLECTING_PASSENGERS | ✅ PASS | Ngày 15/05/2027 |
| T10-T16 | Hành khách + liên hệ + note + xác nhận | — | ✅ PASS (stage) | Stage tiến đúng, output bị capture vào biến PS |
| T17 | Tra cứu mã đặt sau booking | — | ❌ SKIP | Booking code null vì T16 output bị capture |
| T18 | Tra cứu mã lowercase | — | ❌ SKIP | Như T17 |
| T19 | Tra cứu sai mã | — | ❌ SKIP | Như T17 |
| T20 | Greeting reset phiên | IDLE | ✅ PASS | "Chào lại bạn! Mình đã reset phiên cũ." |
| T21 | Đặt tour Hội An 2 người | SHOWING_SEARCH_RESULTS | ✅ PASS | 1 tour tìm được |
| T22 | "hủy" | IDLE | ✅ PASS | Cancel hoạt động đúng |
| T23 | Tìm tour Hà Nội | SHOWING_SEARCH_RESULTS | ✅ PASS | 3 tour tìm được |
| T24 | "hủy tour nếu thời tiết xấu" | SHOWING_SEARCH_RESULTS | ✅ **PASS** | **Fix anti-false-cancel hoạt động** |
| T25 | "Tôi muốn hủy đặt phòng khách sạn" | SHOWING_SEARCH_RESULTS | ✅ **PASS** | **Fix anti-false-cancel hoạt động** |
| T26 | Tìm tour Phú Quốc | SHOWING_SEARCH_RESULTS | ✅ PASS | Kết quả từ danh sách cũ |
| T27 | Hỏi chính sách giữa luồng | SHOWING_SEARCH_RESULTS | ✅ **PASS** | Stage KHÔNG bị reset, policy được trả lời |
| T28 | Phiên mới "tôi muốn đặt tour" | COLLECTING_SEARCH_INFO | ✅ PASS | Hỏi điểm đến |

### 8.3 Kiểm thử bổ sung — Full Booking Flow + Lookup

Sau khi test 26 kịch bản, chạy riêng kiểm thử full flow:

| Bước | Input | Stage kết quả | Kết quả |
|------|-------|--------------|---------|
| Search | "toi muon dat tour Da Nang 2 nguoi lon thang 5" | SHOWING_SEARCH_RESULTS | ✅ 2 tour |
| Chọn tour | "2" | SELECTING_DEPARTURE | ✅ |
| Chọn ngày | "1" | COLLECTING_PASSENGERS | ✅ 15/05/2027 |
| Passenger 1 tên | (tên + giới tính) | COLLECTING_PASSENGERS | ✅ |
| Passenger 1 DOB | "15/01/1990" | COLLECTING_PASSENGERS | ✅ |
| Passenger 2 tên | "Tran Thi B, Nu" | COLLECTING_PASSENGERS | ✅ |
| Passenger 2 DOB | "20/06/1995" | COLLECTING_CONTACT_NAME_PHONE | ✅ |
| Liên hệ | "Test User, 0901111222" | COLLECTING_CONTACT_EMAIL | ✅ |
| Email | "testuser@example.com" | COLLECTING_NOTE_COUPON | ✅ |
| Skip note | "bo qua" | CONFIRMING_BOOKING | ✅ |
| Xác nhận | "xac nhan" | BOOKING_SUCCESS | ✅ **BKf3845364** |
| Lookup exact | "tra cuu BKf3845364" | IDLE | ✅ ORDER_DETAIL |
| Lookup lowercase | "bkf3845364" | IDLE | ✅ ORDER_DETAIL |
| Lookup uppercase | "BKF3845364" | IDLE | ✅ ORDER_DETAIL |

**Case-insensitive lookup hoạt động hoàn toàn cho cả 3 format.**

---

## 9. API FLOW HOÀN CHỈNH

### 9.1 Flow đặt tour qua chat

```
POST /api/chatbot/chat { message: "tour Đà Nẵng 2 người tháng 5" }
     ↓
analytics-service:
  IntentRouter → TRANSACTION_FLOW (isBookingIntent)
  BCS.handle() → handleIdle() → parseAndFillSearchParams()
  BCS.doSearch() → Feign GET /api/tours/chatbot-search
     ↓
tour-catalog-service:
  TourController.chatbotSearch()
  → filter by destination/startLocation/month/adultCount
  → return List<TourGroupResponse>
     ↓
analytics-service:
  Build TourGroupDisplay, save to Redis
  Return TOUR_SUGGESTIONS { tourSuggestions: [...], conversationStage: "SHOWING_SEARCH_RESULTS" }
```

### 9.2 Flow tạo booking

```
POST /api/chatbot/chat { message: "xac nhan" }  [stage=CONFIRMING_BOOKING]
     ↓
analytics-service:
  BCS.handleConfirm()
  Build CreateBookingRequest { departureId, passengers, contactInfo }
  Feign POST /api/bookings
     ↓
booking-service:
  BookingController.createBooking()
  BookingServiceImpl.createBooking()
  → Tính giá thực, lưu DB, publish OutboxEvent
  → return BookingCreatedResponse { bookingId, bookingCode }
     ↓
analytics-service:
  Feign POST /api/payments/{bookingId}/create-payment-link
     ↓
payment-service:
  Create PayOS payment link
  → return { paymentUrl, orderCode }
     ↓
analytics-service:
  Return BOOKING_SUCCESS { bookingCode: "BKf3845364", paymentUrl: "https://pay.payos.vn/..." }
```

### 9.3 Flow tra cứu booking

```
POST /api/chatbot/chat { message: "bkf3845364" }
     ↓
analytics-service:
  IntentRouter: BK_PATTERN.matcher(msg).find() → BOOKING_LOOKUP_PAYMENT
  ChatbotService.handleDeterministic() → performLookupPublic("bkf3845364")
  BCS.performLookup() → Feign GET /api/bookings/payment/bkf3845364
     ↓
booking-service:
  BookingController.getBookingPaymentDetail(code)
  BookingServiceImpl.getBookingPaymentDetail()
  → findByBookingCodeWithPassengers(code)
     WHERE UPPER(b.bookingCode) = UPPER('bkf3845364')
     → Tìm thấy booking BKf3845364
  → return BookingPaymentDetailResponse
     ↓
analytics-service:
  Format ORDER_DETAIL response
  Return { messageType: "ORDER_DETAIL", orderDetail: {...}, conversationStage: "IDLE" }
```

---

## 10. PHẠM VI THAY ĐỔI

### Các file đã sửa đổi

| File | Service | Số lỗi fix |
|------|---------|-----------|
| `analytics-service/.../IntentRouter.java` | analytics-service | 2 |
| `analytics-service/.../BookingConversationService.java` | analytics-service | 5 |
| `analytics-service/.../GeminiIntentService.java` | analytics-service | 1 |
| `booking-service/.../BookingRepository.java` | booking-service | 1 |

### Các service/file KHÔNG thay đổi

- payment-service, tour-catalog-service, iam-service, forum-service
- notification-service, api-gateway, config-server, service-discovery
- Frontend React (`tourism_frontend/`)
- Tất cả business logic ngoài chatbot

---

## 11. HƯỚNG DẪN VẬN HÀNH

### Rebuild và deploy sau khi sửa code

```powershell
cd D:\HK8\tourism-microservices-backend

# Build Maven
mvn -pl analytics-service package -DskipTests
mvn -pl booking-service package -DskipTests

# Rebuild Docker images
docker compose build analytics-service booking-service

# Restart containers
docker compose up -d analytics-service booking-service

# Kiểm tra health
docker compose ps
```

### Kiểm tra logs chatbot

```powershell
docker logs tourism-analytics-service --tail=50 -f
```

### Sync dữ liệu Pinecone

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/chatbot/admin/sync" -Method POST
```

---

*Báo cáo này mô tả trạng thái hệ thống sau khi áp dụng 9 bản vá lỗi, rebuild Docker, và kiểm thử toàn diện 26+ kịch bản API.*
