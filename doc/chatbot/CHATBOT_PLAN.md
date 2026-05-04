# Kế Hoạch Thiết Kế AI Chatbot Chuẩn Hiện Đại — Future Travel

> **Ngày lập:** 04/05/2026  
> **Phiên bản:** 2.0 — Thiết kế chuẩn production  
> **Dự án:** `tourism-microservices-backend` → `analytics-service` (port 8087)  
> **Người đọc:** Developer triển khai feature chatbot  

---

## Tóm Tắt Nhanh (TL;DR)

| | Đã có (monolith) | Plan này (microservices) |
|---|---|---|
| **Kỹ thuật** | Naive RAG | **Advanced RAG** + Intent + Memory |
| **Search** | Vector only | **Hybrid Search** (vector + keyword) |
| **Context** | Không lưu | **Multi-turn** (Redis session) |
| **Query** | Gửi thẳng | **Query Enhancement** (LLM rewrite) |
| **Cache** | Không có | **Semantic Cache** (Redis) |
| **Data** | Tour + Location | Tour + Location + **Review + Coupon + FAQ** |
| **Streaming** | Không | **SSE streaming** response |
| **Guardrail** | Yếu | **Prompt guardrail** chặt |

---

## 1. Hiện Trạng & Điểm Xuất Phát

### 1.1 Đã có (trong `Tourism_Backend` — monolith)

| Thành phần | Vị trí | Trạng thái |
|---|---|---|
| `ChatbotController` | `controller/ChatbotController.java` | ✅ Hoạt động |
| `ChatbotService` | `service/chatbot/ChatbotService.java` | ✅ Hoạt động (700+ dòng) |
| `VectorService` | `service/chatbot/VectorService.java` | ✅ Hoạt động |
| `VectorSyncService` | `service/chatbot/VectorSyncService.java` | ✅ Sync mỗi 2AM |
| Pinecone index | `tourism-chatbot` | ✅ Đã tồn tại |
| Gemini API | `gemini-2.0-flash` + `text-embedding-004` | ✅ Đã config + key |
| Frontend `ChatbotWidget.jsx` | `src/components/ChatbotWidget/` | ✅ Đang gọi port 8080 |

### 1.2 Chưa có (trong `analytics-service` — microservices target)

| Thành phần | Vị trí | Trạng thái |
|---|---|---|
| Chatbot controllers, services | `analytics-service/src/main/.../` | ❌ Trống rỗng (chỉ `.gitkeep`) |
| Feign → tour-catalog, booking | analytics-service | ❌ Chưa có |
| API Gateway route `/api/chatbot/**` | `api-gateway/application.yml` | ❌ Chưa có |

### 1.3 Config đã sẵn sàng trong `analytics-service/application.yml`

```yaml
gemini:
  api.key: ${GEMINI_API_KEY}
  generation.model: gemini-2.0-flash

chatbot:
  vector-db:
    provider: pinecone
    pinecone:
      api-key: ${PINECONE_API_KEY}
      index-name: tourism-chatbot
  embedding:
    model: text-embedding-004
    dimension: 768
```

> Config đủ rồi — chỉ cần code logic, không cần thêm credentials mới.

---

## 2. Kỹ Thuật Chatbot Hiện Đại Nhất (2024–2026)

### 2.1 Bức tranh toàn cảnh các kỹ thuật

Dưới đây là toàn bộ kỹ thuật đang được áp dụng phổ biến nhất trong production chatbot doanh nghiệp, xếp theo mức độ quan trọng:

```
Tier 1 — Nền tảng (bắt buộc)
├── RAG (Retrieval-Augmented Generation)
├── Vector Embeddings (dense search)
└── LLM Generation với System Prompt + Guardrail

Tier 2 — Cải tiến chất lượng (nên có)
├── Hybrid Search (dense vector + sparse keyword BM25)
├── Re-ranking (cross-encoder sort lại top-K)
├── Query Enhancement / Query Rewriting
└── Intent Classification

Tier 3 — Trải nghiệm người dùng (quan trọng)
├── Multi-turn Conversation Memory (Redis session)
├── Streaming Response (SSE)
├── Semantic Cache (tránh gọi Gemini trùng lặp)
└── Structured Response (JSON với tourSuggestions, quickActions)

Tier 4 — Production hardening
├── Circuit Breaker (Resilience4j)
├── Rate Limiting
├── Hallucination Detection
└── Observability / Logging

Tier 5 — Nâng cao (future)
├── Function Calling / Tool Use
├── Personalization (theo user history)
├── Fine-tuning (nếu Gemini cho phép)
└── Sentiment Analysis từ reviews
```

### 2.2 Giải thích từng kỹ thuật áp dụng cho dự án này

#### A. RAG — Retrieval-Augmented Generation *(Tier 1)*

**Vấn đề giải quyết:** LLM (Gemini) biết rất nhiều về thế giới nhưng không biết gì về tour của Future Travel. RAG bơm dữ liệu thật vào context trước khi Gemini trả lời.

```
User: "Tour Phú Quốc tháng 6 bao nhiêu tiền?"
                    │
                    ▼
[1] Embed câu hỏi → float[768] (text-embedding-004)
                    │
                    ▼
[2] Pinecone search topK=10 → tìm 10 document giống nhất
    → Kết quả: "Tour HCM-PQ-5N4D, ngày 15/06, giá 8.500.000đ/người"
                    │
                    ▼
[3] Ghép vào prompt: "Dựa trên thông tin: [tour thật từ DB]..."
                    │
                    ▼
[4] Gemini trả lời: "Tour Phú Quốc 5N4Đ khởi hành 15/06 giá 8.5 triệu..."
                    (chỉ dựa vào data thật, không bịa)
```

**Rule chống bịa trong System Prompt:**
```
Bạn là trợ lý du lịch Future Travel.
CHỈ trả lời dựa trên thông tin được cung cấp trong [DỮ LIỆU HỆ THỐNG].
KHÔNG bịa, KHÔNG suy đoán giá tour, ngày, số chỗ.
Nếu không có thông tin: "Xin lỗi, tôi không có thông tin này.
Vui lòng liên hệ hotline 1900 2045."
```

---

#### B. Hybrid Search — Dense + Sparse *(Tier 2, quan trọng nhất)*

**Vấn đề với vector-only search:**
- Vector search giỏi ý nghĩa ("du lịch biển" ≈ "nghỉ dưỡng ven biển")
- Nhưng tệ với **số và mã cụ thể** ("HCM-PQ-5N4D", "15/06", "8.5 triệu")

**Hybrid = Dense (vector) + Sparse (BM25 keyword)**

```
Query: "Tour mã HCM-PQ tháng 6"
│
├── Dense search → tìm theo meaning ("Phú Quốc tháng hè")
│
└── Sparse search → exact match "HCM-PQ" + "tháng 6"
         │
         ▼
    Merge kết quả (Reciprocal Rank Fusion)1
         │
         ▼
    Top-K kết quả tốt hơn nhiều
```

**Áp dụng với Pinecone:** Pinecone hỗ trợ sparse-dense index. Cần generate sparse vector bằng BM25 encoder khi upsert.

---

#### C. Re-ranking *(Tier 2)*

**Vấn đề:** Vector search lấy top-20, nhưng thứ tự chưa chuẩn.

**Giải pháp:** Sau khi vector search lấy top-20, dùng một **cross-encoder** (hoặc Gemini) để score lại từng document theo query, rồi lấy top-5 tốt nhất.

```
Vector search: [doc3, doc7, doc1, doc15, ...top-20]
                    │
                    ▼
Cross-encoder score từng doc theo query
                    │
                    ▼
Re-ranked: [doc7(0.95), doc1(0.91), doc3(0.87)]  ← chính xác hơn
```

**Áp dụng đơn giản:** Dùng Gemini để score ("Trả về JSON {score: 0-1} cho document này liên quan đến query không?")

---

#### D. Query Enhancement / Query Rewriting *(Tier 2)*

**Vấn đề:** User thường gõ tắt, viết sai, thiếu ngữ cảnh.

```
User gõ: "pq t6"           → rewrite: "Tour Phú Quốc tháng 6"
User gõi: "rẻ thôi"        → rewrite: "Tour giá thấp dưới 5 triệu đồng"
User gõi: "giống hồi trước" → dùng conversation history → "Tour Đà Nẵng như đã hỏi"
```

**Implement:** Trước khi embed, gửi câu hỏi qua Gemini để rewrite thành câu hoàn chỉnh:
```
Prompt: "Rewrite câu hỏi du lịch sau thành câu đầy đủ, ngắn gọn (1 câu): '{userQuery}'"
```

---

#### E. Intent Classification *(Tier 2)*

**Phân loại ý định trước khi search** để dùng filter/topK phù hợp:

| Intent | Trigger keywords | Filter khi search |
|---|---|---|
| `FIND_TOUR` | "tour đi", "có tour", "muốn đi" | type=TOUR_SUMMARY |
| `CHECK_PRICE` | "bao nhiêu tiền", "giá", "chi phí" | type=TOUR_DEPARTURE |
| `FIND_DISCOUNT` | "giảm giá", "khuyến mãi", "coupon" | type=TOUR_DEPARTURE, couponDiscount>0 |
| `CHECK_SCHEDULE` | "tháng", "ngày", "lịch khởi hành" | type=TOUR_DEPARTURE |
| `ABOUT_LOCATION` | "có gì", "ở đâu", địa danh | type=LOCATION |
| `READ_REVIEW` | "đánh giá", "tốt không", "review" | type=REVIEW |
| `POLICY_FAQ` | "huỷ", "hoàn tiền", "cách đặt" | type=POLICY/FAQ |
| `OUT_OF_SCOPE` | "vé máy bay", "khách sạn riêng" | → redirect hotline |

---

#### F. Multi-turn Conversation Memory *(Tier 3)*

**Vấn đề:** Chatbot hiện tại **mỗi câu hỏi là 1 phiên độc lập**, không nhớ câu trước.

```
User: "Tour Đà Nẵng có không?"
Bot: "Có 3 tour Đà Nẵng..."
User: "Cái nào rẻ nhất?"  ← Bot sẽ không biết đang hỏi về tour Đà Nẵng!
```

**Giải pháp với Redis:**
```
key: chatbot:session:{sessionId}
value: [{role:user, content:...}, {role:bot, content:...}]  ← JSON list
TTL: 30 phút
```

Khi build prompt, ghép thêm **conversation history** vào context:
```
[LỊCH SỬ HỘI THOẠI]
User: Tour Đà Nẵng có không?
Bot: Có 3 tour...

[DỮ LIỆU HỆ THỐNG]
{retrieved docs}

[CÂU HỎI HIỆN TẠI]
Cái nào rẻ nhất?
```

**Redis đã có trong docker-compose** → chỉ cần thêm dependency và code.

---

#### G. Semantic Cache *(Tier 3)*

**Vấn đề:** Nhiều user hỏi câu gần giống nhau → gọi Gemini lặp lại → tốn tiền + chậm.

```
User A hỏi: "Tour Phú Quốc tháng 6 bao nhiêu?"
→ Tốn 1 lần gọi Gemini, lưu cache với vector của câu này

User B hỏi: "Phú Quốc tháng 6 giá bao nhiêu vậy?"  (câu tương tự)
→ Embed câu B → cosine similarity với cache > 0.92
→ Trả cache ngay, không gọi Gemini   (tiết kiệm ~200ms + API cost)
```

**Implement:** Redis với key = hash của câu hỏi, hoặc dùng vector similarity để tìm cache.

---

#### H. Streaming Response (SSE) *(Tier 3)*

**Vấn đề:** Gemini mất 2-4 giây mới có response đầy đủ. User thấy màn hình trống.

**Giải pháp:** Server-Sent Events — stream từng token về ngay khi có.

```
User gửi câu hỏi
→ Server bắt đầu stream: "Dạ..." → " có " → "3 tour..." → (token by token)
→ Frontend render từng chữ realtime (typing effect)
```

**Backend:** Spring `SseEmitter` hoặc `Flux<String>` (WebFlux)  
**Gemini:** Dùng streaming API endpoint (`streamGenerateContent`)  
**Frontend:** `EventSource` API hoặc `fetch` với `ReadableStream`

---

#### I. Hallucination Detection / Guardrail *(Tier 4)*

**Vấn đề:** Dù có RAG, đôi khi Gemini vẫn "sáng tạo" thêm thông tin không có trong context.

**Giải pháp:**
1. **Temperature thấp:** `temperature: 0.2` → ít sáng tạo hơn, bám context hơn
2. **Instruction chặt trong prompt:**
   ```
   Quy tắc bắt buộc:
   - Chỉ đề cập tour nếu TourCode xuất hiện trong [DỮ LIỆU HỆ THỐNG]
   - Chỉ nói giá nếu giá có trong dữ liệu
   - Không đề cập ngày khởi hành nếu không có trong dữ liệu
   ```
3. **Post-processing:** Parse response, nếu chứa giá/ngày không có trong docs → log warning

---

## 3. Kiến Trúc Pipeline Hoàn Chỉnh

```
┌──────────────────────────────────────────────────────────────────┐
│  Frontend: ChatbotWidget.jsx (đã có)                             │
│  POST /api/chatbot/chat  ← đang gọi localhost:8080               │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  API Gateway :8080                                               │
│  Route /api/chatbot/** → lb://analytics-service  (cần thêm)     │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  analytics-service :8087                                         │
│                                                                  │
│  ChatbotController                                               │
│       │                                                          │
│       ▼                                                          │
│  ChatbotOrchestrator (main service)                              │
│       │                                                          │
│       ├─ 1. IntentClassifier                                     │
│       │       └── regex + keyword → intent enum                  │
│       │                                                          │
│       ├─ 2. QueryEnhancer                                        │
│       │       └── Gemini rewrite "pq t6" → "Tour Phú Quốc t6"   │
│       │                                                          │
│       ├─ 3. SemanticCache (Redis)                                │
│       │       └── cosine(queryVec, cacheVec) > 0.92 → hit       │
│       │                                                          │
│       ├─ 4. ConversationMemory (Redis)                           │
│       │       └── GET chatbot:session:{sessionId} → history      │
│       │                                                          │
│       ├─ 5. EmbeddingService                                     │
│       │       └── Gemini text-embedding-004 → float[768]         │
│       │                                                          │
│       ├─ 6. HybridSearchService                                  │
│       │       ├── Dense: Pinecone vector search topK=20          │
│       │       └── Sparse: BM25 keyword search                    │
│       │             └── Merge (RRF) → top-10                     │
│       │                                                          │
│       ├─ 7. ContextBuilder                                       │
│       │       └── format docs thành text context                 │
│       │                                                          │
│       ├─ 8. PromptBuilder                                        │
│       │       └── system + history + context + question          │
│       │                                                          │
│       ├─ 9. GeminiGenerationService                              │
│       │       └── POST Gemini API → streaming response           │
│       │                                                          │
│       └─ 10. ResponseBuilder                                     │
│               ├── parse tourIds từ docs → TourSuggestion[]       │
│               ├── build QuickAction[]                            │
│               └── cache response (Redis)                         │
│                                                                  │
│  VectorSyncService (background)                                  │
│       ├── Feign → tour-catalog-service                           │
│       ├── Feign → booking-service                                │
│       └── @Scheduled 2AM + RabbitMQ events                      │
└──────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   Pinecone              Gemini API             Redis
   (vector store)        (embed + gen)          (session + cache)
```

---

## 4. Dữ Liệu Training — Vector DB

### 4.1 Các loại document

| Type | Nguồn | Feign tới | Nội dung text được embed |
|---|---|---|---|
| `TOUR_SUMMARY` | `tours` | tour-catalog | Tên tour, mô tả, điểm đến, thời gian, phương tiện, điểm nổi bật |
| `TOUR_DEPARTURE` | `tour_departures` + `departure_pricings` | tour-catalog | Ngày KH, giá ADULT/CHILD, số chỗ còn, coupon code, % giảm |
| `LOCATION` | `locations` | tour-catalog | Tên địa điểm, tỉnh/vùng, mô tả du lịch, điểm hấp dẫn |
| `REVIEW` | `reviews` (isVisible=true) | tour-catalog | Tên tour, rating, nội dung bình luận thật của khách |
| `POLICY` | `policy_templates` | tour-catalog | Chính sách huỷ tour, đổi lịch, điều kiện hoàn tiền |
| `PROMOTION` | `coupons` (còn hạn) | booking | Mã coupon, % giảm, điều kiện áp dụng, hạn dùng |
| `FAQ_STATIC` | Viết cứng 1 lần | — | "Cách đặt tour", "Cần giấy tờ gì", "Trẻ em tính giá ra sao" |

### 4.2 Metadata chuẩn kèm theo vector

```json
{
  "type": "TOUR_DEPARTURE",
  "entityId": "departure_123",
  "tourId": 5,
  "tourCode": "HCM-PQ-5N4D",
  "tourName": "TP.HCM → Phú Quốc 5 Ngày 4 Đêm",
  "locationName": "Phú Quốc",
  "departureDate": "2026-06-15",
  "salePrice": 8500000,
  "originalPrice": 10000000,
  "couponDiscount": 15,
  "couponCode": "SUMMER2026",
  "availableSlots": 12,
  "avgRating": 4.7,
  "reviewCount": 23,
  "imageUrl": "https://res.cloudinary.com/..."
}
```

### 4.3 Chiến lược Chunking (chia nhỏ document)

> Đây là kỹ thuật quan trọng — text quá dài sẽ mất thông tin khi embed.

| Loại | Cách chunk | Lý do |
|---|---|---|
| Tour summary | 1 vector/tour | Tour có mô tả ngắn (~200 words) |
| Tour departure | 1 vector/departure | Mỗi departure có ngày + giá riêng |
| Location | 1 vector/location | Ngắn, đủ dùng |
| Review | 1 vector/review | Mỗi review là 1 ý kiến độc lập |
| FAQ | 1 vector/câu Q&A | Q+A gộp vào 1 chunk |
| Policy | Chia theo đoạn | Nếu dài hơn 500 words thì chia |

### 4.4 Lịch sync

| Trigger | Thời điểm |
|---|---|
| **Scheduled** | Mỗi ngày 2:00 AM — full sync |
| **Manual** | `POST /api/chatbot/admin/sync` |
| **Event RabbitMQ** | `tour.created`, `departure.added`, `coupon.created` → sync ngay |
| **Incremental** | Mỗi 6 giờ sync các record có `updatedAt` > lastSyncTime |

---

## 5. Thiết Kế API

### 5.1 Endpoints (analytics-service)

```
POST   /api/chatbot/chat                → Chat chính (public)
GET    /api/chatbot/chat/stream         → Streaming SSE (public, Phase 2)
DELETE /api/chatbot/session/{sessionId} → Xoá lịch sử phiên (public)

POST   /api/chatbot/admin/sync          → Sync toàn bộ data (admin)
POST   /api/chatbot/admin/sync/{type}   → Sync theo type: TOUR/LOCATION/REVIEW (admin)
DELETE /api/chatbot/admin/clear-index   → Xoá toàn bộ Pinecone index (admin)
GET    /api/chatbot/admin/stats         → Thống kê: số vector, last sync time (admin)
GET    /api/chatbot/health              → Health check Pinecone + Gemini (public)
```

### 5.2 Request / Response DTO

**ChatMessageRequest** (giữ nguyên như monolith):
```json
{
  "message": "Tour Phú Quốc tháng 6 còn chỗ không?",
  "sessionId": "session_1746341234567",
  "userId": 42
}
```

**ChatMessageResponse** (nâng cấp):
```json
{
  "reply": "Hiện có **2 tour Phú Quốc** khởi hành tháng 6...",
  "tourSuggestions": [
    {
      "tourId": 5,
      "tourCode": "HCM-PQ-5N4D",
      "tourName": "TP.HCM → Phú Quốc 5N4Đ",
      "imageUrl": "https://res.cloudinary.com/...",
      "minPrice": 8500000,
      "duration": "5N4Đ",
      "availableSlots": 12,
      "rating": 4.7,
      "detailUrl": "/tours/5",
      "relevanceScore": 0.94
    }
  ],
  "quickActions": [
    { "label": "Xem lịch khởi hành", "url": "/tours/5" },
    { "label": "Đặt tour ngay", "url": "/tours/5/book" }
  ],
  "intent": "FIND_TOUR",
  "sessionId": "session_1746341234567",
  "timestamp": "2026-05-04T10:30:00",
  "cached": false
}
```

### 5.3 API Gateway route (thêm vào `api-gateway/application.yml`)

```yaml
- id: analytics-service
  uri: lb://analytics-service
  predicates:
    - Path=/api/chatbot/**, /api/analytics/**
  filters:
    - StripPrefix=0
```

---

## 6. Thiết Kế Frontend (ChatbotWidget)

### 6.1 File đã có (cần nâng cấp, không rewrite từ đầu)

```
client-side/src/components/ChatbotWidget/
├── ChatbotWidget.jsx        ← đang gọi localhost:8080/api/chatbot/chat
└── ChatbotWidget.module.scss
```

### 6.2 Thay đổi cần thiết

| Hạng mục | Hiện tại | Cần thêm |
|---|---|---|
| API URL | hardcode `localhost:8080` | dùng env variable |
| Tour cards | Commented out | Enable + dùng data thật |
| Quick actions | Commented out | Enable |
| Typing indicator | Loading text đơn giản | Typing dots animation |
| Streaming | Không | Phase 2: EventSource |
| Rating hiển thị | Không | Hiển thị ⭐ 4.7/5 trên tour card |
| Error retry | Alert | Nút "Thử lại" |

### 6.3 Flow tương tác hoàn chỉnh

```
[User] Nhập câu hỏi → Enter
        │
        ▼
[Widget] Hiển thị "typing..." animation
        │
        ▼
[API] POST /api/chatbot/chat
        │
        ▼
[Widget] Render reply (ReactMarkdown → bold, bullet list, link)
        │
        ├── Render TourCard[] nếu có (ảnh + tên + giá + slots + rating)
        │
        └── Render QuickAction buttons (nếu có)

[User] Click nút quick action → navigate đến trang tour
```

---

## 7. Các Component Cần Code Trong `analytics-service`

### 7.1 Package structure

```
analytics-service/src/main/java/com/tourism/analytics/
├── controller/
│   └── ChatbotController.java
├── service/
│   ├── ChatbotOrchestrator.java       ← điều phối toàn bộ pipeline
│   ├── IntentClassifierService.java   ← phân loại intent
│   ├── QueryEnhancerService.java      ← rewrite query bằng Gemini
│   ├── EmbeddingService.java          ← gọi text-embedding-004
│   ├── HybridSearchService.java       ← dense + sparse search
│   ├── ContextBuilderService.java     ← format docs → context text
│   ├── PromptBuilderService.java      ← ghép system + history + context
│   ├── GeminiGenerationService.java   ← gọi Gemini generate
│   ├── ConversationMemoryService.java ← Redis session
│   ├── SemanticCacheService.java      ← Redis semantic cache
│   ├── ResponseBuilderService.java    ← build TourSuggestion, QuickAction
│   └── VectorSyncService.java         ← sync data → Pinecone
├── feign/
│   ├── TourCatalogFeignClient.java
│   └── BookingFeignClient.java
├── dto/
│   ├── ChatMessageRequest.java
│   ├── ChatMessageResponse.java
│   └── VectorDocumentDTO.java
├── config/
│   ├── GeminiConfig.java
│   ├── PineconeConfig.java
│   └── RedisConfig.java
└── listener/
    └── TourEventListener.java         ← RabbitMQ: trigger sync khi có tour mới
```

### 7.2 Mô tả từng service

#### `IntentClassifierService`
```java
// Input: "Tour giảm giá tháng 6 Phú Quốc"
// Output: Intent.FIND_DISCOUNT
// Logic: regex + keyword matching theo bảng intent ở Section 2.2.E
```

#### `QueryEnhancerService`
```java
// Input: "pq t6 rẻ"
// Gọi Gemini: "Rewrite thành câu tìm kiếm du lịch đầy đủ (1 câu ngắn)"
// Output: "Tour Phú Quốc tháng 6 giá rẻ"
```

#### `EmbeddingService`
```java
// Gọi: POST https://generativelanguage.googleapis.com/v1beta/models/
//          text-embedding-004:embedContent
// Input:  String text
// Output: float[768]
// Cache: embed kết quả vào Redis (TTL 1h) để tránh gọi lại
```

#### `HybridSearchService`
```java
// Phase 1 (đơn giản): chỉ dense vector search, filter theo intent
// Phase 2: thêm sparse BM25 + merge bằng Reciprocal Rank Fusion
// Pinecone filter: {"type": {"$eq": "TOUR_DEPARTURE"}}
```

#### `ConversationMemoryService` (Redis)
```java
// Lưu: RPUSH chatbot:session:{sessionId} "{role:user, content:...}"
// Đọc: LRANGE chatbot:session:{sessionId} -6 -1  (lấy 6 message cuối = 3 turns)
// TTL: EXPIRE chatbot:session:{sessionId} 1800  (30 phút)
```

#### `SemanticCacheService`
```java
// Key: chatbot:cache:{hash16 của queryText}
// Value: JSON response đã có
// Lookup: embed query → cosine với cache vectors → if similarity > 0.92 → return cache
// TTL: 30 phút
```

#### `VectorSyncService`
```java
@Scheduled(cron = "0 0 2 * * *")  // 2AM daily
public void fullSync() {
    syncTours();        // Feign → tour-catalog GET /api/tours/chatbot-sync
    syncDepartures();   // Feign → tour-catalog GET /api/departures/active
    syncLocations();    // Feign → tour-catalog GET /api/locations
    syncReviews();      // Feign → tour-catalog GET /api/reviews/visible
    syncPromotions();   // Feign → booking GET /api/coupons/active
    syncStaticFAQ();    // load từ file resources/faq.json
}
```

---

## 8. Dependencies Cần Thêm

### `analytics-service/pom.xml`

```xml
<!-- Pinecone Java SDK -->
<dependency>
    <groupId>io.pinecone</groupId>
    <artifactId>pinecone-client</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Gson cho parse JSON Gemini/Pinecone -->
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.10.1</version>
</dependency>

<!-- Redis (đã có trong docker-compose, thêm Spring starter) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- OpenFeign (gọi tour-catalog, booking) -->
<!-- Đã có qua spring-cloud-starter-openfeign trong parent pom -->

<!-- WebFlux (nếu implement SSE streaming response) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

---

## 9. Biến Môi Trường

Thêm vào `docker-compose.yml` service `analytics-service`:

```yaml
analytics-service:
  environment:
    GEMINI_API_KEY: "AIzaSy..."
    PINECONE_API_KEY: "pcsk_..."
    PINECONE_HOST: "https://tourism-chatbot-xxxx.svc.pinecone.io"
    SPRING_REDIS_HOST: redis
    SPRING_REDIS_PORT: 6379
```

> Redis đã chạy trong docker-compose → dùng `redis` làm host name.

---

## 10. Roadmap Triển Khai

### Phase 1 — MVP: Chatbot cơ bản (1–2 tuần)

> **Mục tiêu:** Chat được, trả lời dựa trên data thật, route qua microservices.

- [ ] Tạo package structure đầy đủ trong `analytics-service`
- [ ] Implement `EmbeddingService` (gọi Gemini embed)
- [ ] Implement `VectorService` (Pinecone: upsert, search, delete)
- [ ] Implement `VectorSyncService` (Tours + Departures + Locations + Reviews + FAQ)
- [ ] Implement `IntentClassifierService` (regex 7 intents)
- [ ] Implement `ChatbotOrchestrator` — Naive RAG pipeline (embed → search → context → generate)
- [ ] Implement `ChatbotController` (4 endpoints: chat, sync, clear, health)
- [ ] Tạo `TourCatalogFeignClient` + `BookingFeignClient`
- [ ] Thêm route `/api/chatbot/**` vào API Gateway
- [ ] Update `ChatbotWidget.jsx` — dùng env variable thay hardcode URL
- [ ] Test end-to-end qua Postman
- [ ] Sync dữ liệu lần đầu, verify Pinecone có data

### Phase 2 — Quality: Nâng chất lượng search (tuần 3)

> **Mục tiêu:** Câu trả lời chính xác hơn, xử lý tốt câu hỏi phức tạp.

- [ ] Implement `ConversationMemoryService` (Redis multi-turn, 3 turns)
- [ ] Implement `QueryEnhancerService` (Gemini rewrite ngắn gọn)
- [ ] Implement `SemanticCacheService` (Redis cache response)
- [ ] Tăng guardrail trong system prompt (temperature=0.2, strict rules)
- [ ] Enable TourCard + QuickAction trong `ChatbotWidget.jsx`
- [ ] Thêm rating/slots vào tour suggestion display
- [ ] Implement event-driven sync qua RabbitMQ (khi tour mới được tạo)

### Phase 3 — UX: Streaming + Personalization (tuần 4)

> **Mục tiêu:** Trải nghiệm mượt mà, cảm giác AI "đang gõ".

- [ ] Implement SSE streaming response (Spring `SseEmitter`)
- [ ] Update Frontend nhận stream (`EventSource` hoặc `ReadableStream`)
- [ ] Personalization: nếu có `userId` → lấy booking history → ưu tiên tour phù hợp
- [ ] Typing indicator animation (3 chấm)
- [ ] Feedback buttons (👍👎) → log để phân tích sau

### Phase 4 — Production: Hardening (tuần 5+)

> **Mục tiêu:** Sẵn sàng production, không bị crash khi load cao.

- [ ] Resilience4j Circuit Breaker cho Feign clients
- [ ] Rate limiting: 10 req/phút/sessionId
- [ ] Logging + monitoring (log intent, topK, latency từng bước)
- [ ] Hybrid Search (Dense + BM25 sparse) — nâng chất lượng search tên mã tour
- [ ] Admin dashboard: số vector, last sync time, cache hit rate

---

## 11. So Sánh Monolith vs Microservices Plan

| | `Tourism_Backend` (có sẵn) | Plan này (microservices) |
|---|---|---|
| **RAG** | ✅ Naive RAG | ✅ Advanced RAG |
| **Multi-turn** | ❌ Không có | ✅ Redis session |
| **Query enhance** | ❌ Không | ✅ Gemini rewrite |
| **Semantic cache** | ❌ Không | ✅ Redis cache |
| **Intent filter** | ✅ Regex cơ bản | ✅ Regex + 7 intent types |
| **Streaming** | ❌ Không | ✅ Phase 2 SSE |
| **Data access** | JPA trực tiếp | Feign HTTP API |
| **Event sync** | ❌ Chỉ cron | ✅ Cron + RabbitMQ event |
| **Tour cards** | ✅ Có (code có) | ✅ Enable + nâng cấp |
| **Personalization** | ❌ Không | ✅ Phase 3 |

---

## 12. Rủi Ro & Giải Pháp

| Rủi ro | Xác suất | Giải pháp |
|---|---|---|
| Gemini API rate limit (free tier: 15 req/phút) | Cao | Semantic cache + queue request |
| Pinecone free tier giới hạn ~100MB | Trung bình | Chỉ sync tour active + departure tương lai |
| Feign timeout khi sync lớn | Thấp | Chạy sync async `@Async`, không block |
| Bot vẫn hallucinate dù có RAG | Trung bình | temperature=0.2, strict prompt, post-check |
| Redis không có dữ liệu session nếu restart | Thấp | TTL ngắn (30ph) → user chấp nhận được |
| Pinecone data stale (tour hết slot vẫn hiện) | Cao | Event-driven sync + cron 6h |

---

## 13. Tóm Tắt Kỹ Thuật

```
┌─────────────────────────────────────────────────────────────┐
│  Stack chính                                                │
│  ├── AI: Google Gemini 2.0 Flash (generate) + text-embedding-004 (embed) │
│  ├── Vector DB: Pinecone (index: tourism-chatbot, 768 dims) │
│  ├── Cache + Session: Redis (đã có trong docker-compose)    │
│  ├── Microservice: analytics-service :8087                  │
│  └── Frontend: ChatbotWidget.jsx (đã có, cần nâng cấp)      │
│                                                             │
│  Kỹ thuật áp dụng (theo priority)                          │
│  1. RAG (bắt buộc) — không bịa dữ liệu                     │
│  2. Intent Classification — search đúng loại data          │
│  3. Multi-turn Memory (Redis) — nhớ ngữ cảnh               │
│  4. Query Enhancement — hiểu câu gõ tắt                    │
│  5. Semantic Cache (Redis) — tránh gọi Gemini trùng         │
│  6. Guardrail Prompt — temperature=0.2 + strict rules       │
│  7. SSE Streaming — typing effect realtime                  │
│  8. Hybrid Search — Phase 4, nâng chất lượng tên/mã         │
│                                                             │
│  Không cần                                                  │
│  ✗ Fine-tuning (đắt, không cần thiết khi có RAG)           │
│  ✗ LangChain (Java wrapper phức tạp, tự code đơn giản hơn) │
│  ✗ Thêm LLM mới (Gemini đủ dùng, miễn phí)                │
└─────────────────────────────────────────────────────────────┘
```

---

*Plan viết dựa trên đọc toàn bộ code 3 dự án. Chatbot đã chạy trong `Tourism_Backend` — task là **migrate + nâng cấp** lên microservices với các kỹ thuật hiện đại.*  
*Bắt đầu từ Phase 1, từng bước, không cần làm hết một lúc.*
