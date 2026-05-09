# CHATBOT SYSTEM — PHÂN TÍCH & KẾ HOẠCH NÂNG CẤP
## Dự án: Future Travel Tourism Microservices

---

## PHẦN 1: KIẾN TRÚC CHATBOT HIỆN TẠI

### 1.1 Công nghệ đang dùng

| Thành phần | Công nghệ | Mục đích |
|---|---|---|
| **Generation model** | Google Gemini 2.0 Flash | Sinh câu trả lời tiếng Việt |
| **Embedding model** | Pinecone `llama-text-embed-v2` | Chuyển text → vector 1024 chiều |
| **Vector database** | Pinecone | Lưu & tìm kiếm vector similarity |
| **Backend** | Spring Boot 3.2 (analytics-service) | Điều phối toàn bộ luồng |
| **Giao tiếp services** | OpenFeign | Lấy dữ liệu từ tour-catalog, booking-service |
| **Kiến trúc** | RAG (Retrieval-Augmented Generation) | Kết hợp tìm kiếm + sinh câu trả lời |

---

## PHẦN 2: LUỒNG THU THẬP DỮ LIỆU VÀO VECTOR DB (Data Sync Pipeline)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        DATA SYNC PIPELINE                           │
│                   (chạy 2:00 AM mỗi ngày)                          │
└─────────────────────────────────────────────────────────────────────┘

PostgreSQL Databases                  analytics-service
┌──────────────────┐                 ┌────────────────────────────┐
│ tour_catalog_db  │──Feign──────────│ VectorSyncService          │
│  - tours         │                 │                            │
│  - departures    │                 │  syncAll() gồm:            │
│  - locations     │                 │   1. syncAllTours()        │
│  - reviews       │                 │   2. syncAllLocations()    │
└──────────────────┘                 │   3. syncAllReviews()      │
                                     │   4. syncAllCoupons()      │
┌──────────────────┐                 └────────────┬───────────────┘
│ booking_db       │──Feign────────────────────────┘
│  - coupons       │                              │
└──────────────────┘                              │ Với mỗi record:
                                                  ▼
                                    ┌─────────────────────────────┐
                                    │ 1. Build text document      │
                                    │    (tên tour, giá, mô tả,  │
                                    │     điểm đến, đánh giá...)  │
                                    └──────────────┬──────────────┘
                                                   │
                                                   ▼
                                    ┌─────────────────────────────┐
                                    │ 2. Pinecone Inference API   │
                                    │    llama-text-embed-v2      │
                                    │    text → vector[1024]      │
                                    └──────────────┬──────────────┘
                                                   │
                                                   ▼
                                    ┌─────────────────────────────┐
                                    │ 3. Pinecone Vector DB       │
                                    │    upsert(id, vector,       │
                                    │           metadata)         │
                                    │                             │
                                    │  Loại document:             │
                                    │  - TOUR_SUMMARY             │
                                    │  - TOUR_DEPARTURE           │
                                    │  - LOCATION                 │
                                    │  - REVIEW                   │
                                    │  - COUPON                   │
                                    └─────────────────────────────┘
```

### 2.1 Chi tiết từng loại document được sync

#### TOUR_SUMMARY
```
Text document được embed:
"Tour: [tên tour]
Mã tour: [tourCode]
Điểm khởi hành: [departure]
Điểm đến: [locations]
Thời gian: [duration] ngày
Giá từ: [minPrice] VND
Đánh giá: [rating]/5
Mô tả: [description]"
```

#### TOUR_DEPARTURE
```
Text document được embed:
"Tour: [tên tour] — Khởi hành: [date]
Giá gốc: [originalPrice] | Giá ưu đãi: [salePrice]
Số chỗ còn: [availableSlots]
Coupon: [couponCode] giảm [couponDiscount]%"
```

#### LOCATION / REVIEW / COUPON
```
Tương tự — mỗi record thành 1 document riêng với metadata JSON đầy đủ
```

---

## PHẦN 3: LUỒNG XỬ LÝ CÂU HỎI (RAG Pipeline)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CLIENT → CHATBOT RESPONSE                            │
│                   Kiến trúc: RAG (Retrieval-Augmented Generation)       │
└─────────────────────────────────────────────────────────────────────────┘

User: "Tour Đà Lạt dưới 5 triệu có không?"
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 1: PHÂN TÍCH CÂU HỎI (Pattern Matching)          │
│  ChatbotService.handleUserMessage()                     │
│                                                         │
│  Phát hiện intent bằng regex:                          │
│  - DISCOUNT_PATTERN  → topK = 50 (nhiều hơn)           │
│  - COUPON_PATTERN    → topK = 50 (lọc coupon docs)     │
│  - Câu thường        → topK = 10                       │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 2: EMBEDDING CÂU HỎI                             │
│  VectorService.searchSimilar(userMessage, topK)         │
│                                                         │
│  userMessage → Pinecone Inference API                   │
│  (llama-text-embed-v2, input_type="query")             │
│  → vector[1024] (đại diện ngữ nghĩa câu hỏi)          │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 3: VECTOR SIMILARITY SEARCH                       │
│  Pinecone.query(vector, topK, filter)                   │
│                                                         │
│  Thuật toán: Cosine Similarity                          │
│  → Trả về topK documents gần nhất về ngữ nghĩa         │
│  → Mỗi doc có: score, text, metadata (JSON)            │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 4: POST-PROCESSING / RE-RANKING                   │
│  buildEnhancedContext()                                 │
│                                                         │
│  Nếu isCouponQuery: lọc docs có couponDiscount > 0     │
│                     sort DESC theo couponDiscount       │
│  Nếu isDiscountQuery: lọc originalPrice > salePrice     │
│                       sort theo tỷ lệ giảm giá         │
│  → Xây chuỗi context text từ docs                      │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 5: PROMPT ENGINEERING                             │
│  buildEnhancedPrompt(userMessage, context)              │
│                                                         │
│  System prompt tiếng Việt:                             │
│  - Vai trò: tư vấn viên du lịch Future Travel          │
│  - Context: [nội dung từ bước 4]                       │
│  - Câu hỏi: [câu hỏi gốc của user]                    │
│  - Hướng dẫn: trả lời dựa vào context, có giá cụ thể  │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 6: GENERATION — GOOGLE GEMINI 2.0 FLASH           │
│  callGeminiAPI(prompt)                                  │
│                                                         │
│  POST https://generativelanguage.googleapis.com/        │
│       v1beta/models/gemini-2.0-flash:generateContent   │
│                                                         │
│  Config: temperature=0.7, maxTokens=1000               │
│  → Sinh câu trả lời tiếng Việt tự nhiên               │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  BƯỚC 7: BUILD RESPONSE                                 │
│                                                         │
│  ChatMessageResponse gồm:                              │
│  - reply: câu trả lời text từ Gemini                   │
│  - tourSuggestions: danh sách tour từ metadata         │
│  - quickActions: gợi ý câu hỏi tiếp theo               │
│  - sessionId, timestamp                                 │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
                    User nhận được response
```

### 3.1 Tóm tắt công nghệ theo từng bước

| Bước | Tên | Công nghệ | Độ trễ ước tính |
|---|---|---|---|
| 1 | Intent Detection | Java Regex | < 1ms |
| 2 | Query Embedding | Pinecone llama-text-embed-v2 | ~200-400ms |
| 3 | Vector Search | Pinecone Cosine Similarity | ~100-300ms |
| 4 | Post-processing | Java Stream | < 5ms |
| 5 | Prompt build | Java String | < 1ms |
| 6 | Text Generation | Gemini 2.0 Flash | ~500-2000ms |
| 7 | Response build | Java | < 5ms |
| **Tổng** | | | **~1-3 giây** |

---

## PHẦN 4: VẤN ĐỀ & ĐIỂM YẾU HIỆN TẠI

### 4.1 Điểm yếu kỹ thuật

| # | Vấn đề | Tác động |
|---|---|---|
| 1 | **Không có conversation memory** — mỗi request độc lập, không nhớ ngữ cảnh hội thoại trước | User hỏi "tour đó giá bao nhiêu?" sau câu trước → chatbot không biết "tour đó" là tour nào |
| 2 | **Intent detection bằng regex** — giòn, không scale | Sai với cách diễn đạt mới, thiếu sót nhiều intent |
| 3 | **Sync batch 1 lần/ngày** — dữ liệu có thể lỗi thời 24h | Tour mới thêm vào trưa sẽ không được chatbot biết đến đến 2AM hôm sau |
| 4 | **Không re-ranking thông minh** — chỉ sort đơn giản | Kết quả retrieve chưa phải lúc nào cũng liên quan nhất |
| 5 | **Không có caching** — mỗi câu hỏi đều gọi Pinecone + Gemini | Câu hỏi giống nhau vẫn tốn API call |
| 6 | **Context window cứng** — topK=10 hoặc 50 cố định | Có thể thiếu hoặc thừa context tùy câu hỏi |
| 7 | **Không lưu lịch sử chat** — không phân tích được hành vi | Không cải thiện được chatbot theo thời gian |
| 8 | **Single-turn** — không hỗ trợ follow-up question | UX kém so với ChatGPT, Gemini Advanced |
| 9 | **Không có guardrails** — có thể hallucinate giá/thông tin | User tin sai thông tin → bad experience |
| 10 | **Không có fallback** — nếu Gemini lỗi → crash | Độ tin cậy thấp |

### 4.2 So sánh với chatbot tân tiến nhất hiện nay

| Tính năng | Future Travel Bot (hiện tại) | ChatGPT 4o / Gemini Ultra / Claude 3.5 |
|---|---|---|
| Conversation memory | ❌ Không có | ✅ Nhớ cả cuộc trò chuyện |
| Multi-turn dialog | ❌ Single-turn | ✅ Multi-turn, context-aware |
| Intent understanding | ⚠️ Regex | ✅ LLM-based semantic understanding |
| RAG | ✅ Có (Pinecone) | ✅ Có + re-ranking tiên tiến |
| Latency | ~1-3s | ~1-2s (streaming) |
| Streaming response | ❌ Không | ✅ Token-by-token |
| Multimodal | ❌ Không | ✅ Hình ảnh, voice |
| Tool use / Function calling | ❌ Không | ✅ Có thể gọi API ngoài |
| Guardrails | ❌ Không | ✅ Safety filters |
| Personalization | ❌ Không | ✅ Theo lịch sử user |

---

## PHẦN 5: KẾ HOẠCH NÂNG CẤP

### 5.1 Roadmap tổng quan

```
Phase 1 (1-2 tuần)  — Quick wins, không thay đổi kiến trúc lớn
Phase 2 (2-4 tuần)  — Nâng cấp RAG + thêm conversation memory
Phase 3 (4-8 tuần)  — Nâng cấp generation model + personalization
Phase 4 (8+ tuần)   — Advanced features: streaming, tool use, multimodal
```

---

### PHASE 1 — QUICK WINS

#### 1.1 Thêm Conversation Memory (Redis)
**Vấn đề giải quyết:** Chatbot hiện không nhớ hội thoại trước.

```
Cách làm:
- Lưu lịch sử hội thoại vào Redis với key = sessionId
- TTL = 30 phút (session timeout)
- Mỗi lần gọi: lấy history → gắn vào prompt
- Giới hạn: giữ 5-10 turns gần nhất (tránh context quá dài)

Stack thêm: Spring Data Redis (đã có Redis trong hệ thống)

Prompt structure mới:
[System prompt]
[History: User: ... | Bot: ...]  ← THÊM MỚI
[Context từ Pinecone]
[Câu hỏi hiện tại]
```

**Lợi ích:** User có thể hỏi "tour đó giá bao nhiêu?" sau khi đã hỏi "tour Đà Lạt nào rẻ nhất?"

#### 1.2 Streaming Response
**Vấn đề giải quyết:** User phải chờ 1-3 giây mới thấy kết quả.

```
Cách làm:
- Dùng Gemini streaming API (Server-Sent Events)
- Backend: ResponseBodyEmitter hoặc SseEmitter
- Frontend: EventSource API
- User thấy text xuất hiện từng từ như ChatGPT

Stack thêm: Spring Web SSE (có sẵn trong Spring Boot)
```

#### 1.3 Cache câu hỏi phổ biến (Redis)
```
Cách làm:
- Hash câu hỏi → cache key
- Nếu hit: trả về ngay, không gọi Pinecone + Gemini
- TTL = 1 giờ
- Phù hợp: "Tour Đà Lạt bao nhiêu ngày?" — câu hỏi phổ biến
```

#### 1.4 Real-time Sync (Event-driven)
**Vấn đề giải quyết:** Dữ liệu mới chỉ sync 1 lần/ngày.

```
Cách làm:
- Khi tour-catalog tạo/sửa tour → publish event qua RabbitMQ
- analytics-service consume event → sync ngay lên Pinecone
- Không cần chờ đến 2AM

Stack: RabbitMQ (đã có trong hệ thống)
```

---

### PHASE 2 — NÂNG CẤP RAG

#### 2.1 Hybrid Search (Keyword + Vector)
**Vấn đề giải quyết:** Vector search đôi khi miss kết quả chính xác theo keyword.

```
Cách làm:
Thay vì chỉ dùng vector search:
  BM25 (keyword match) + Vector Search → Reciprocal Rank Fusion (RRF) → Re-rank

Ví dụ:
- User hỏi "tour HA GIANG" (gõ không dấu)
- Vector search: có thể miss (embedding khác nhau)
- BM25: match chính xác "HA GIANG" trong text
- Hybrid: kết hợp → kết quả tốt hơn

Stack: Pinecone Hybrid Search (đã hỗ trợ sparse + dense vector)
```

#### 2.2 Cross-Encoder Re-ranking
**Vấn đề giải quyết:** Retrieve 10 docs nhưng không phải lúc nào cũng liên quan nhất.

```
Pipeline mới:
  Câu hỏi → Retrieve 50 docs (bi-encoder, nhanh)
           → Re-rank top 10 (cross-encoder, chính xác hơn)
           → Build context từ top 5

Cross-encoder model gợi ý:
  - Cohere Rerank API (dễ tích hợp, trả phí)
  - BGE-Reranker (open-source, self-host)
  - ms-marco-MiniLM (nhỏ gọn, có thể chạy trong service)
```

#### 2.3 Query Expansion
**Vấn đề giải quyết:** User gõ ngắn → thiếu context cho embedding.

```
Cách làm:
- Trước khi embed, dùng Gemini để mở rộng câu hỏi:
  "tour đà lạt" → "Tour du lịch Đà Lạt, điểm tham quan Đà Lạt, 
                    tour ngắn ngày Đà Lạt, tour 2 ngày 1 đêm Đà Lạt"
- Embed câu mở rộng → search → kết quả phong phú hơn
```

#### 2.4 Self-RAG (Reflection)
```
Cách làm:
- Sau khi Gemini sinh xong → kiểm tra lại xem câu trả lời có dựa vào context không
- Nếu hallucinate → retrieve lại với query khác → generate lại
- Tốn thêm 1 lần Gemini call nhưng chính xác hơn rất nhiều
```

---

### PHASE 3 — NÂNG CẤP MODEL & PERSONALIZATION

#### 3.1 Nâng cấp lên Gemini 2.5 Pro / Flash Thinking
```
Gemini 2.5 Flash Thinking:
- Hỗ trợ "thinking" — model suy luận trước khi trả lời
- Phù hợp câu hỏi phức tạp: "So sánh 3 tour Hà Giang dưới 3 triệu"
- API giống hệt 2.0 Flash → dễ nâng cấp

Config thay đổi:
  gemini.generation.model: gemini-2.5-flash-preview
```

#### 3.2 LLM-based Intent Classification
**Thay thế regex bằng Gemini:**

```
Cách làm:
Trước mỗi query → gọi Gemini nhẹ (1 call nhỏ) để classify intent:

Prompt: "Phân loại câu hỏi sau vào 1 category:
  SEARCH_TOUR | PRICE_QUERY | DISCOUNT_QUERY | COUPON_QUERY |
  BOOKING_HELP | COMPARE_TOURS | GENERAL
  
  Câu hỏi: [userMessage]
  Output: JSON {intent, confidence}"

Dựa vào intent → customize pipeline:
- COMPARE_TOURS: retrieve nhiều hơn, dùng bảng so sánh
- BOOKING_HELP: guide user đến trang booking
- COUPON_QUERY: lọc docs coupon
```

#### 3.3 Personalization theo User
```
Nếu user đã đăng nhập → lấy profile:
- Tỉnh/thành phố (điểm khởi hành gần)
- Lịch sử booking (sở thích loại tour)
- Coin balance (gợi ý tour phù hợp giá)

Thêm vào prompt:
"User: [tên], ở [tỉnh], đã đặt [loại tour], budget [ước tính]
Ưu tiên gợi ý tour từ [tỉnh] và phù hợp sở thích."
```

#### 3.4 Lưu trữ và phân tích lịch sử chat
```
Lưu vào chat_history table (PostgreSQL):
  - session_id, user_id, message, response, intent, timestamp
  - Dùng để: fine-tune model, cải thiện prompt, phân tích phổ biến
  
Analytics:
  - Top 20 câu hỏi phổ biến nhất
  - Intent distribution
  - Satisfaction score (user feedback)
```

---

### PHASE 4 — ADVANCED FEATURES

#### 4.1 Function Calling / Tool Use
```
Cho phép Gemini tự gọi API khi cần:

Tools định nghĩa:
  - searchTours(location, maxPrice, duration)
  - checkAvailability(tourCode, date, numPeople)
  - getCouponInfo(code)
  - getUserBookingHistory(userId)

Khi user hỏi "Còn chỗ tour Đà Lạt tuần sau không?" →
  Gemini nhận ra cần gọi checkAvailability() →
  Gọi API thật → trả về kết quả thật → sinh câu trả lời
  
Đây là điểm khác biệt lớn nhất so với RAG thuần túy.
```

#### 4.2 Voice Input / Output
```
- Speech-to-Text: Google Speech API / Whisper
- Text-to-Speech: Google Cloud TTS / ElevenLabs
- Cho phép user chat bằng giọng nói
```

#### 4.3 Multimodal — Nhận diện ảnh
```
- User upload ảnh điểm du lịch → chatbot nhận ra → gợi ý tour
- Dùng Gemini Vision (đã hỗ trợ multimodal)
- "Tôi muốn đi đây" + [ảnh ruộng bậc thang] → gợi ý tour Sapa/Hà Giang
```

#### 4.4 Proactive Suggestions
```
- Khi user vào trang tour Đà Lạt → chatbot tự gợi ý:
  "Bạn đang xem tour Đà Lạt! Tôi có thể giúp gì?"
- Push notification: "Tour [X] vừa giảm giá, phù hợp sở thích của bạn"
```

---

## PHẦN 6: STACK KỸ THUẬT ĐỀ XUẤT (TARGET ARCHITECTURE)

```
┌────────────────────────────────────────────────────────────────────┐
│                      TARGET CHATBOT STACK                          │
└────────────────────────────────────────────────────────────────────┘

LAYER 1: QUERY UNDERSTANDING
  ├── LLM Intent Classifier (Gemini Flash)
  ├── Query Expansion (Gemini)
  └── Conversation Memory (Redis, TTL 30min)

LAYER 2: RETRIEVAL (Hybrid RAG)
  ├── Dense Vector Search (Pinecone, llama-text-embed-v2)
  ├── Sparse Keyword Search (BM25, Pinecone sparse)
  ├── Fusion: Reciprocal Rank Fusion
  └── Cross-Encoder Re-ranking (Cohere Rerank / BGE)

LAYER 3: AUGMENTATION
  ├── Context Assembly (top-5 docs)
  ├── Personalization injection (user profile)
  ├── System prompt (role, style, constraints)
  └── Self-RAG check (optional)

LAYER 4: GENERATION
  ├── Gemini 2.5 Flash Thinking (primary)
  ├── Function Calling Tools (booking, availability)
  └── Streaming output (SSE)

LAYER 5: POST-PROCESSING
  ├── Cache result (Redis)
  ├── Save to history (PostgreSQL)
  ├── Build tour suggestions from metadata
  └── Build quick actions

LAYER 6: SYNC PIPELINE
  ├── Scheduled sync (2AM, full)
  ├── Event-driven sync (RabbitMQ, real-time)
  └── Admin manual trigger endpoint
```

---

## PHẦN 7: ƯU TIÊN THỰC HIỆN (Priority Matrix)

| Tính năng | Impact | Effort | Ưu tiên |
|---|---|---|---|
| Conversation Memory (Redis) | ⭐⭐⭐⭐⭐ | Thấp | 🔴 P0 — Làm ngay |
| Streaming Response | ⭐⭐⭐⭐ | Thấp | 🔴 P0 — Làm ngay |
| Real-time Sync (RabbitMQ) | ⭐⭐⭐⭐ | Thấp | 🔴 P0 — Làm ngay |
| Cache kết quả (Redis) | ⭐⭐⭐ | Thấp | 🟡 P1 |
| Hybrid Search | ⭐⭐⭐⭐ | Trung bình | 🟡 P1 |
| LLM Intent Classification | ⭐⭐⭐⭐ | Thấp | 🟡 P1 |
| Cross-Encoder Re-ranking | ⭐⭐⭐⭐ | Trung bình | 🟡 P1 |
| Gemini 2.5 Flash Thinking | ⭐⭐⭐⭐ | Rất thấp | 🟡 P1 |
| Personalization | ⭐⭐⭐⭐⭐ | Cao | 🟢 P2 |
| Lưu lịch sử chat | ⭐⭐⭐ | Thấp | 🟢 P2 |
| Function Calling / Tool Use | ⭐⭐⭐⭐⭐ | Cao | 🔵 P3 |
| Voice Input/Output | ⭐⭐⭐ | Cao | 🔵 P3 |
| Multimodal (ảnh) | ⭐⭐⭐ | Trung bình | 🔵 P3 |

---

## PHẦN 8: KẾT LUẬN

Chatbot hiện tại đã có nền tảng tốt với **kiến trúc RAG chuẩn** (Pinecone + Gemini). Điểm yếu lớn nhất cần fix ngay là **không có conversation memory** — đây là thứ khiến user cảm thấy chatbot "ngốc" nhất.

**3 việc làm ngay (1-2 tuần) để cải thiện rõ rệt:**
1. ✅ Conversation memory → Redis session
2. ✅ Streaming response → user thấy kết quả ngay
3. ✅ Real-time sync → dữ liệu luôn mới nhất

**Sau đó (Phase 2-3) để vượt mặt chatbot thông thường:**
4. Hybrid Search + Re-ranking → kết quả chính xác hơn
5. LLM Intent Classification → hiểu câu hỏi đa dạng hơn
6. Personalization → gợi ý đúng người đúng tour

---

*Phân tích & kế hoạch — 2026-05-09 · Future Travel Chatbot v2.0 Roadmap*
