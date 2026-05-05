# Báo cáo Chatbot AI — Tourism Microservices

> **Ngày cập nhật:** 05/05/2026  
> **Service:** `analytics-service` (port 8087)  
> **Package:** `com.tourism.analytics`

---

## Mục lục

1. [Tổng quan hệ thống](#1-tổng-quan-hệ-thống)
2. [Kiến trúc và luồng dữ liệu](#2-kiến-trúc-và-luồng-dữ-liệu)
3. [RAG Pipeline — từng bước chi tiết](#3-rag-pipeline--từng-bước-chi-tiết)
4. [Danh sách API (ChatbotController)](#4-danh-sách-api-chatbotcontroller)
5. [Mô tả từng Class / Service](#5-mô-tả-từng-class--service)
6. [Cấu trúc dữ liệu Pinecone](#6-cấu-trúc-dữ-liệu-pinecone)
7. [Cấu hình (application.yml)](#7-cấu-hình-applicationyml)
8. [Những thứ Chatbot có thể trả lời](#8-những-thứ-chatbot-có-thể-trả-lời)
9. [Hướng dẫn vận hành](#9-hướng-dẫn-vận-hành)
10. [Unit Tests](#10-unit-tests)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Tổng quan hệ thống

Chatbot được xây dựng theo mô hình **RAG (Retrieval-Augmented Generation)**:

- **Retrieval** — khi user hỏi, câu hỏi được embed thành vector 1024 chiều, sau đó tìm kiếm các document gần nhất trong **Pinecone Vector DB**
- **Augmented** — các document tìm được (tour, địa điểm, review, coupon) được format thành **context** có cấu trúc, đính kèm vào prompt
- **Generation** — **Gemini 2.0 Flash** nhận prompt + context, sinh ra câu trả lời bằng tiếng Việt có Markdown

**Điểm quan trọng:** Chatbot **KHÔNG truy vấn database trực tiếp**. Toàn bộ thông tin (tên tour, giá, mã giảm giá, địa điểm, coupon...) đều đến từ metadata đã được đồng bộ sẵn vào Pinecone.

---

## 2. Kiến trúc và luồng dữ liệu

```
+----------------------------------------------------------+
|  CLIENT (React Frontend)                                  |
|  POST localhost:3000 -> http://localhost:8080/api/chatbot |
+---------------------------+------------------------------+
                            |
                            v
+----------------------------------------------------------+
|  API GATEWAY :8080                                        |
|  Route: /api/chatbot/** -> analytics-service (Eureka)    |
+---------------------------+------------------------------+
                            |
                            v
+----------------------------------------------------------+
|  ANALYTICS-SERVICE :8087                                  |
|                                                           |
|  ChatbotController                                        |
|       |                                                   |
|       +-- ChatbotService (RAG pipeline)                   |
|              |                                            |
|              +-- VectorService.searchSimilar()            |
|              |       |                                    |
|              |       +-- Pinecone Inference API           |
|              |       |   (embed query -> 1024-dim vector) |
|              |       |                                    |
|              |       +-- Pinecone Vector DB               |
|              |           (cosine similarity search)       |
|              |                                            |
|              +-- Gemini 2.0 Flash API                     |
|                  (generateContent voi context)            |
|                                                           |
|  VectorSyncService                                        |
|       |                                                   |
|       +-- TourCatalogFeignClient                          |
|       |       | (Spring Cloud OpenFeign + Eureka)         |
|       |       v                                           |
|       |  TOUR-CATALOG-SERVICE :8082                       |
|       |    /api/tours/chatbot-sync                        |
|       |    /api/locations/chatbot-sync                    |
|       |    /api/reviews/chatbot-sync                      |
|       |                                                   |
|       +-- BookingFeignClient                              |
|               | (Spring Cloud OpenFeign + Eureka)         |
|               v                                           |
|          BOOKING-SERVICE :8083                            |
|            /api/bookings/coupons/chatbot-sync             |
+----------------------------------------------------------+
                            |
                +---------- +----------+
                v                      v
  Pinecone Inference API       Gemini AI API
  api.pinecone.io/embed        generativelanguage.googleapis.com
  Model: llama-text-embed-v2   Model: gemini-2.0-flash
  Output: 1024-dim vector      Output: cau tra loi Markdown
```

### Các external dependency

| Service | URL | Mục đích |
|---------|-----|---------|
| Pinecone Inference API | `https://api.pinecone.io/embed` | Tạo vector embedding từ văn bản |
| Pinecone Vector DB | `https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io` | Lưu trữ và tìm kiếm vector |
| Gemini AI | `https://generativelanguage.googleapis.com/v1beta/models/` | Sinh câu trả lời |
| tour-catalog-service | `http://tour-catalog-service/api/...` | Lấy dữ liệu tour, địa điểm, review |
| booking-service | `http://booking-service/api/bookings/coupons/...` | Lấy danh sách mã coupon |

---

## 3. RAG Pipeline — từng bước chi tiết

```
User gui: "he thong co coupon giam gia nao"
         |
         v
[Buoc 1] Xac dinh loai cau hoi (regex)
         isCouponQuery   = true  (khop COUPON_PATTERN: "coupon|ma giam|voucher...")
         isDiscountQuery = false
         topK = 50
         |
         v
[Buoc 2] Embed cau hoi (VectorService.searchSimilar)
         POST https://api.pinecone.io/embed
         Body: { model: "llama-text-embed-v2",
                 inputs: [{text: "he thong co coupon giam gia nao"}],
                 parameters: {input_type: "query"} }
         -> nhan ve vector [0.12, -0.03, ..., 0.87] (1024 gia tri)
         |
         v
[Buoc 3] Tim kiem Pinecone (cosine similarity)
         POST https://tourism-chatbot-g2idbvy.../query
         Body: { vector: [...1024 values], topK: 50, includeMetadata: true }
         -> tra ve danh sach document (COUPON, TOUR_DEPARTURE, TOUR_SUMMARY...)
         |
         v
[Buoc 4] Build context (ChatbotService.buildEnhancedContext)
         isCouponQuery = true:
           - Loc TOUR_DEPARTURE co couponDiscount > 0, sort theo couponDiscount DESC
           - Loc them tat ca document type = COUPON
           - Header: "QUAN TRONG: Co CHINH XAC X tour co ma giam gia coupon"
           - TOUR_DEPARTURE: "[Ten tour:..., Gia ADULT: X VND, GIAM GIA: Y VND (Z%),
                              MÃ COUPON: CODE giam them A VND (HSD: ...)]"
           - COUPON doc: "[COUPON: CODE, Giam: X VND, Ap dung: tat ca tour, HSD: ...]"
         |
         v
[Buoc 5] Build prompt (ChatbotService.buildEnhancedPrompt)
         Ghep system instruction + context + cau hoi user
         -> prompt ~3000-4000 ky tu
         |
         v
[Buoc 6] Goi Gemini (ChatbotService.callGeminiAPI)
         POST https://generativelanguage.googleapis.com/.../gemini-2.0-flash:generateContent
         Config: temperature=0.2, maxOutputTokens=1000
         -> nhan ve cau tra loi Markdown
         |
         v
[Buoc 7] Build TourSuggestions & QuickActions
         - TourSuggestions: lay tu metadata Pinecone, deduplicate theo tourId, max 6 tour
         - QuickActions: nut goi y dua tren tu khoa trong cau hoi
         |
         v
[Ket qua] ChatMessageResponse
         {
           reply: "He thong dang co 4 ma coupon...",
           tourSuggestions: [{tourId, tourCode, tourName, minPrice, detailUrl}],
           quickActions: [{label, action, url}],
           sessionId: "...",
           timestamp: "2026-05-05T10:30:00"
         }
```

### Phân biệt hai loại query

| Loại | Regex pattern | topK | Filter logic | Sort |
|------|--------------|------|-------------|------|
| **Discount** | `giam gia`, `giam sau`, `uu dai`, `khuyen mai`, `re nhat`, `sale`... (có & không dấu) | 50 | `TOUR_DEPARTURE` có `originalPrice > salePrice && salePrice > 0` | `(originalPrice - salePrice)` DESC |
| **Coupon** | `coupon`, `ma giam`, `voucher`, `promo code`... (có & không dấu) | 50 | `TOUR_DEPARTURE` có `couponDiscount > 0` + document type `COUPON` | `couponDiscount` DESC |
| **Thông thường** | không khớp | 10 | Tất cả document | score Pinecone |

---

## 4. Danh sách API (ChatbotController)

### 4.1. `POST /api/chatbot/chat` — Gửi tin nhắn

**Mục đích:** Điểm vào chính cho người dùng hỏi chatbot.

**Request body:**
```json
{
  "message": "tour Da Lat co khuyen mai khong?",
  "sessionId": "session-abc123",
  "userId": 42
}
```

| Trường | Bắt buộc | Mô tả |
|--------|----------|-------|
| `message` | Có | Câu hỏi của user (không được rỗng) |
| `sessionId` | Không | ID phiên hội thoại; tự sinh UUID nếu thiếu |
| `userId` | Không | ID user (dùng cho log, chưa personalize) |

**Response body:**
```json
{
  "reply": "Hien tai co 5 tour dang co uu dai giam gia dac biet:\n\n**Tour Ha Noi...**",
  "tourSuggestions": [
    {
      "tourId": 5,
      "tourCode": "HN-HL-3N2D",
      "tourName": "Ha Noi - Ha Long 3 Ngay 2 Dem",
      "imageUrl": "/images/halong.jpg",
      "minPrice": 2800000.0,
      "duration": "3 Ngay 2 Dem",
      "detailUrl": "/tour/HN-HL-3N2D",
      "relevanceScore": 0.92
    }
  ],
  "quickActions": [
    { "label": "Tour giam gia soc", "action": "VIEW_DEALS", "url": "/tour?filter=discount" }
  ],
  "sessionId": "session-abc123",
  "timestamp": "2026-05-05T10:30:15"
}
```

| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `reply` | String | Câu trả lời Markdown từ Gemini |
| `tourSuggestions` | List | Tối đa 6 tour gợi ý từ metadata Pinecone |
| `quickActions` | List | Nút gợi ý tiếp theo (dựa trên context câu hỏi) |
| `sessionId` | String | ID phiên hội thoại (echo lại) |
| `timestamp` | ISO 8601 | Thời điểm phản hồi |

---

### 4.2. `POST /api/chatbot/admin/sync` — Đồng bộ dữ liệu lên Pinecone

**Mục đích:** Admin trigger để lấy dữ liệu mới nhất từ các service và upsert lên Pinecone.

**Request:** Không có body.

**Response:**
```json
{
  "status": "success",
  "message": "Dong bo du lieu thanh cong",
  "elapsed": "37860ms",
  "timestamp": "2026-05-05T10:30:37"
}
```

**Luồng xử lý nội bộ:**
```
/admin/sync
    -> VectorSyncService.syncAll()
         +-- syncAllTours()
         |     GET tour-catalog-service/api/tours/chatbot-sync
         |     Voi moi tour:
         |         syncTourSummary()    -> 1 doc TOUR_SUMMARY_<id>
         |         syncTourDepartures() -> N doc TOUR_DEPARTURE_<id>
         |
         +-- syncAllLocations()
         |     GET tour-catalog-service/api/locations/chatbot-sync
         |     syncLocation() -> LOCATION_<id>
         |
         +-- syncAllReviews()
         |     GET tour-catalog-service/api/reviews/chatbot-sync
         |     syncReview() -> REVIEW_<id>
         |
         +-- syncAllCoupons()  [MOI]
               GET booking-service/api/bookings/coupons/chatbot-sync
               syncCoupon() -> COUPON_<id>
```

**Kết quả sync điển hình:**
- 9 tours → 24 documents (9 TOUR_SUMMARY + 15 TOUR_DEPARTURE)
- 12 LOCATION
- 1 REVIEW
- 4 COUPON
- **Tổng: 41 documents**

---

### 4.3. `DELETE /api/chatbot/admin/clear` — Xoá toàn bộ vector

**Mục đích:** Reset Pinecone index về trạng thái rỗng.

**Response:**
```json
{
  "status": "success",
  "message": "Da xoa toan bo vector. Can chay sync lai.",
  "timestamp": "2026-05-05T10:25:00"
}
```

> ⚠️ Sau khi clear, chatbot sẽ không tìm được gì cho đến khi chạy sync lại.

---

### 4.4. `GET /api/chatbot/health` — Kiểm tra trạng thái

**Response:**
```json
{
  "status": "UP",
  "service": "chatbot",
  "timestamp": "2026-05-05T10:30:00"
}
```

---

## 5. Mô tả từng Class / Service

### 5.1. ChatbotController

Lớp `@RestController`, điều phối 4 endpoint. Không chứa business logic — delegate toàn bộ sang `ChatbotService` và `VectorSyncService`.

---

### 5.2. ChatbotService — RAG Pipeline chính

#### Regex Pattern — Phân loại câu hỏi

```java
// Giảm giá theo giá (originalPrice - salePrice), khớp có/không dấu
DISCOUNT_PATTERN = ".*(giảm (giá|sâu)|giam (gia|sau)|ưu đãi|uu dai
                    |khuyến mãi|khuyen mai|rẻ nhất|re nhat
                    |tiết kiệm|tiet kiem|sale|giá tốt|gia tot
                    |tỷ lệ giảm|ty le giam).*"

// Coupon / mã code cụ thể, khớp có/không dấu
COUPON_PATTERN   = ".*(coupon|mã giảm|ma giam|voucher
                    |mã khuyến|ma khuyen|promo code|discount code).*"
```

#### `handleUserMessage(request)` — Method chính

1. Kiểm tra `isCouponQuery` và `isDiscountQuery` qua regex
2. Chọn `topK`: **50** nếu là discount/coupon query, **10** nếu thông thường
3. Gọi `vectorService.searchSimilar()` lấy documents từ Pinecone
4. Gọi `buildEnhancedContext()` tạo context
5. Gọi `buildEnhancedPrompt()` tạo prompt
6. Gọi `callGeminiAPI()` sinh câu trả lời
7. Gọi `buildTourSuggestions()` và `buildQuickActions()`

---

#### `buildEnhancedContext(docs, userMessage)` — Xây dựng context

Chuyển đổi danh sách `VectorDocumentDTO` thành chuỗi có cấu trúc cho Gemini đọc:

**Khi `isCouponQuery = true`:**
- Lọc `TOUR_DEPARTURE` có `couponDiscount > 0`, sort theo couponDiscount DESC
- Đồng thời giữ lại tất cả document type `COUPON`
- Header: `"QUAN TRỌNG: Có CHÍNH XÁC X tour đang có mã giảm giá coupon (sắp xếp theo mức coupon từ cao đến thấp)"`
- Annotation TOUR_DEPARTURE: `[Tên tour:..., Giá ADULT: X VND, GIẢM GIÁ: Y VND (Z%), 🎁 MÃ COUPON: CODE giảm thêm A VND (HSD: ...)]`
- Annotation COUPON: `[🎁 COUPON: CODE, Giảm: X VND, Áp dụng: tất cả tour, HSD: ...]`

**Khi `isDiscountQuery = true` (không phải coupon):**
- Lọc `TOUR_DEPARTURE` có `originalPrice > salePrice && salePrice > 0`
- Sort theo `(originalPrice - salePrice)` DESC — mức tiết kiệm tuyệt đối cao nhất
- Header: `"QUAN TRỌNG: Có CHÍNH XÁC X tour đang được giảm giá (sắp xếp theo mức giảm sâu từ cao đến thấp, tính theo giá gốc trừ giá bán)"`

**Annotation TOUR_DEPARTURE (cả hai loại query):**
```
[Tên tour: Hà Nội - Hạ Long 3N2D, Mã tour: HN-HL-3N2D, Ngày: 2027-04-15,
 Giá ADULT: 2,800,000 VND,
 Giá gốc: 3,200,000 VND, GIẢM GIÁ: 400,000 VND (13%),       <- nếu có giảm giá
 🎁 MÃ COUPON: SUMMER2026 giảm thêm 500,000 VND (HSD: 2026-12-31)]   <- nếu có coupon
```

- Giới hạn tối đa **3500 ký tự** để tránh vượt token limit Gemini

---

#### `extractDiscountAmount(doc)` — Tính mức giảm giá

```java
// Chỉ dùng originalPrice - salePrice (KHÔNG tính couponDiscount)
return Math.max(0, originalPrice - salePrice);
```

Đảm bảo sort "giảm giá sâu nhất" dựa trên chênh lệch giá thực tế, không bị coupon ảnh hưởng.

---

#### `buildEnhancedPrompt(userMessage, context)` — Tạo prompt

Prompt gồm:
- **System instruction** (~2500 ký tự): vai trò, quy tắc giá, link format, phân biệt GIẢM GIÁ vs COUPON
- **Context block**: dữ liệu từ Pinecone
- **Câu hỏi của user**
- **Yêu cầu trả lời Markdown**

**Format tour trong response Gemini:**
```
**[Tên Tour]**
[Thời lượng] | [Ngày khởi hành]
💰 Giá bán: X VND | Giá gốc: Y VND | Tiết kiệm: Z VND (W%)
🎁 Coupon: [MÃ] giảm thêm [số tiền] VND (HSD: ...)   ← chỉ hiện nếu có
**[Xem chi tiết](/tour/TOUR-CODE)**
```

---

#### `callGeminiAPI(prompt)` — Gọi Gemini

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=<key>
Config: temperature=0.2, maxOutputTokens=1000
```

`temperature=0.2` — bám sát dữ liệu, giảm hallucination.

---

#### `buildTourSuggestions(docs)` — TourSuggestion

Tối đa 6 tour từ metadata Pinecone. Deduplicate theo `tourId`, chọn departure có giá thấp nhất.

---

#### `buildQuickActions(request)` — QuickAction

| Từ khóa trong message | Quick action |
|----------------------|-------------|
| giảm giá / khuyến mãi / ưu đãi / coupon | "💰 Tours giảm giá sốc" |
| yêu thích / đánh giá cao / tốt nhất | "⭐ Tours được yêu thích" |
| gần nhất / sắp khởi hành | "📅 Khởi hành gần nhất" |
| (không khớp) | 4 nút mặc định: giảm giá, miền Bắc, miền Nam, tất cả |

---

### 5.3. VectorService — Embedding + Pinecone

#### `createEmbedding(text)` — Embed passage

Dùng để embed **nội dung cần lưu** (`input_type = "passage"`).
Được gọi bởi `VectorSyncService` khi sync dữ liệu.

#### `createEmbeddingForQuery(text)` — Embed query

Dùng để embed **câu hỏi của user** (`input_type = "query"`).
Được gọi bởi `searchSimilar()`.

> **Lý do phân biệt:** `llama-text-embed-v2` tối ưu hoá riêng cho passage (nội dung dài) và query (câu hỏi ngắn), giúp tăng độ chính xác semantic search.

#### `callPineconeEmbed(text, inputType)` — Gọi Pinecone Inference API

```
POST https://api.pinecone.io/embed
Headers: Api-Key: <key>, X-Pinecone-API-Version: 2025-04
Body: {
  "model": "llama-text-embed-v2",
  "inputs": [{"text": "..."}],
  "parameters": { "input_type": "passage|query", "truncate": "END" }
}
Response: {"data": [{"values": [0.12, -0.03, ..., 0.87]}]}  // 1024 giá trị
```

#### `upsertVector(document)` — Lưu vào Pinecone

```
POST <host>/vectors/upsert
Body: { "vectors": [{ "id": "TOUR_DEPARTURE_15", "values": [...1024], "metadata": {...} }] }
```

#### `searchSimilar(queryText, topK)` — Tìm kiếm tương tự

```
POST <host>/query
Body: { "vector": [...1024 values], "topK": 10, "includeMetadata": true }
Response: matches[] sắp xếp theo score (cosine similarity 0-1)
```

#### `deleteAll()` — Xoá toàn bộ

`POST <host>/vectors/delete` với `{deleteAll: true}`.

---

### 5.4. VectorSyncService — Đồng bộ dữ liệu

#### `syncAll()` — Đồng bộ toàn bộ

Gọi tuần tự: `syncAllTours()` → `syncAllLocations()` → `syncAllReviews()` → `syncAllCoupons()`

#### `syncAllTours()` — Lấy từ `/api/tours/chatbot-sync`

Với mỗi `TourSyncDTO`:
- `syncTourSummary()` → **1 document** `TOUR_SUMMARY_<tourID>`
- `syncTourDepartures()` → **N document** `TOUR_DEPARTURE_<departureID>`

**Content TOUR_DEPARTURE được embed:**
```
Lich khoi hanh tour Ha Noi - Ha Long 3N2D (HN-HL-3N2D) | Ngay: 2027-04-15
| Con 20 cho | Gia nguoi lon: 2,800,000 VND (giam 400,000 VND so voi gia goc 3,200,000 VND)
| Tu Ha Noi den Ha Long
```

#### `syncAllLocations()` — Lấy từ `/api/locations/chatbot-sync`

Mỗi `LocationSyncDTO` → document `LOCATION_<locationID>`:
```
Diem den du lich: Da Nang | Khu vuc: CENTRAL | San bay: DAD
```

#### `syncAllReviews()` — Lấy từ `/api/reviews/chatbot-sync`

Mỗi `ReviewSyncDTO` có comment → document `REVIEW_<reviewID>`.

#### `syncAllCoupons()` — Lấy từ `/api/bookings/coupons/chatbot-sync` *(Mới)*

Gọi `BookingFeignClient.getCouponsForChatbotSync()` → booking-service trả về tất cả coupon còn hiệu lực.

Với mỗi `CouponSyncDTO` → document `COUPON_<couponID>`:

**Content COUPON được embed:**
```
Ma giam gia: SUMMER2026 | Giam: 500,000 VND | Loai: Ap dung cho tat ca tour
| Mo ta: Khuyen mai mua he | Han su dung: 2026-12-31 | Con lai: 42 luot
```

**Metadata COUPON:**
```json
{
  "couponID": 1,
  "couponCode": "SUMMER2026",
  "description": "Khuyến mãi mùa hè",
  "discountAmount": 500000,
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "usageLimit": 50,
  "usageCount": 8,
  "couponType": "GLOBAL",
  "departureId": null
}
```

#### `@Scheduled(cron = "0 0 2 * * *")`

Tự động chạy `syncAll()` lúc **2:00 AM mỗi ngày**.

---

### 5.5. TourCatalogFeignClient

Feign client kết nối sang `tour-catalog-service` qua Eureka (không hardcode URL).

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `getAllToursForChatbotSync()` | `GET /api/tours/chatbot-sync` | Tất cả tour active + departures + pricing |
| `getLocationsForChatbotSync()` | `GET /api/locations/chatbot-sync` | Tất cả điểm đến active |
| `getAllVisibleReviews()` | `GET /api/reviews/chatbot-sync` | Tất cả review visible có comment |

---

### 5.6. BookingFeignClient *(Mới)*

Feign client kết nối sang `booking-service` qua Eureka để lấy dữ liệu coupon.

```java
@FeignClient(name = "booking-service")
public interface BookingFeignClient {
    @GetMapping("/api/bookings/coupons/chatbot-sync")
    List<CouponSyncDTO> getCouponsForChatbotSync();
}
```

**Endpoint phía booking-service** (`GET /api/bookings/coupons/chatbot-sync`):
- Trả về tất cả coupon `isDeleted = false` và `endDate > NOW()`
- Nguồn dữ liệu: bảng `coupons` trong `booking_db`

---

## 6. Cấu trúc dữ liệu Pinecone

### Index: `tourism-chatbot`

- **Dimension:** 1024 (llama-text-embed-v2 default)
- **Metric:** cosine
- **Host:** `https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io`

### Loại document (field `type` trong metadata)

| Type | ID format | Số lượng điển hình | Dùng để |
|------|-----------|--------------------|----|
| `TOUR_SUMMARY` | `TOUR_SUMMARY_<tourID>` | 1 / tour | Tìm kiếm tour theo tên, điểm đến, đặc điểm |
| `TOUR_DEPARTURE` | `TOUR_DEPARTURE_<departureID>` | 1–3 / tour | Tìm giá, ngày khởi hành, khuyến mãi |
| `LOCATION` | `LOCATION_<locationID>` | 1 / địa điểm | Tìm điểm du lịch theo tên/vùng |
| `REVIEW` | `REVIEW_<reviewID>` | 1 / review | Tìm đánh giá chất lượng |
| `COUPON` | `COUPON_<couponID>` | 1 / coupon | Tra cứu mã giảm giá đang hiệu lực |

### Metadata fields theo type

**TOUR_SUMMARY:**
```json
{
  "tourId": 1, "tourCode": "HN-HL-3N2D", "tourName": "Ha Noi - Ha Long 3N2D",
  "duration": "3 Ngay 2 Dem", "startLocationName": "Ha Noi", "startLocationID": 1,
  "endLocationName": "Ha Long", "endLocationID": 2,
  "avgRating": 4.5, "reviewCount": 12, "minPrice": 2800000
}
```

**TOUR_DEPARTURE:**
```json
{
  "tourId": 1, "tourCode": "HN-HL-3N2D", "tourName": "Ha Noi - Ha Long 3N2D",
  "departureID": 15, "departureDate": "2027-04-15", "availableSlots": 20,
  "salePrice": 2800000, "originalPrice": 3200000,
  "couponCode": null, "couponDiscount": 0,
  "discountPercentage": 13
}
```

**LOCATION:**
```json
{
  "locationID": 3, "name": "Da Nang", "region": "CENTRAL",
  "airportCode": "DAD", "airportName": "San bay Quoc te Da Nang"
}
```

**COUPON:**
```json
{
  "couponID": 1, "couponCode": "SUMMER2026", "description": "Khuyen mai mua he",
  "discountAmount": 500000, "startDate": "2026-01-01", "endDate": "2026-12-31",
  "usageLimit": 50, "usageCount": 8, "couponType": "GLOBAL", "departureId": null
}
```

---

## 7. Cấu hình (application.yml)

```yaml
server:
  port: 8087

gemini:
  api:
    key: ${GEMINI_API_KEY:AIzaSyBk-m9iJiVoIfT9x-2kgGWtd9JdxZVKIEg}
  generation:
    model: gemini-2.0-flash
    temperature: 0.7
    max-tokens: 1000

chatbot:
  vector-db:
    provider: pinecone
    pinecone:
      api-key: ${PINECONE_API_KEY:pcsk_4NU2Hz_...}
      index-name: tourism-chatbot
      host: ${PINECONE_HOST:https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io}
  embedding:
    model: llama-text-embed-v2    # NVIDIA model qua Pinecone Inference API
    dimension: 1024               # llama-text-embed-v2 mac dinh la 1024

management:
  endpoints:
    web:
      exposure:
        include: health,info
  health:
    eureka:
      enabled: false    # Tat Eureka health indicator de tranh vong tron healthcheck
```

**Lưu ý quan trọng về embedding:**
- Model `llama-text-embed-v2` trả về vector **1024 chiều** (không phải 768)
- Pinecone index phải được tạo với `dimension: 1024`
- Không dùng Gemini `text-embedding-004` (endpoint này 404 với free API key)

**Lưu ý về `management.health.eureka.enabled: false`:**
- Nếu bật, Docker healthcheck gọi `/actuator/health` → actuator hỏi Eureka → Eureka báo DOWN → actuator trả 503 → Docker mark unhealthy → Eureka mark DOWN → vòng tròn
- Disable để actuator chỉ kiểm tra DB + RabbitMQ, không phụ thuộc vào trạng thái Eureka

---

## 8. Những thứ Chatbot có thể trả lời

| Câu hỏi mẫu | Document tìm thấy | Thông tin trả lời |
|-------------|-------------------|-------------------|
| "tour Da Nang co gi" | TOUR_SUMMARY, LOCATION | Tên tour, đặc điểm, đánh giá, link |
| "tour giam gia sau nhat" | TOUR_DEPARTURE (discount) | Sort theo (orig-sale) DESC, % tiết kiệm |
| "he thong co coupon nao" | COUPON, TOUR_DEPARTURE | Mã code, số tiền giảm, hạn dùng |
| "tour re nhat tu Ha Noi" | TOUR_DEPARTURE, TOUR_SUMMARY | Giá thấp nhất, tên tour, ngày khởi hành |
| "tour mien Nam sap khoi hanh" | TOUR_DEPARTURE | Lịch cụ thể, giá, số chỗ còn |
| "danh gia tour Phu Quoc" | REVIEW, TOUR_SUMMARY | Rating, bình luận của khách |
| "bay tu Da Nang di dau" | LOCATION | Sân bay, tour xuất phát từ DAD, startLocationID |
| "tour 3 ngay 2 dem" | TOUR_SUMMARY | Tất cả tour có duration phù hợp |
| "ma giam gia voucher" | COUPON | Danh sách coupon code đang hiệu lực |

**Chatbot KHÔNG biết:**
- Thông tin booking, thanh toán, trạng thái đặt tour (chưa sync)
- Lịch sử hội thoại (stateless, mỗi request độc lập)
- Thông tin account/user (iam-service chưa sync)
- Dữ liệu mới hơn lần sync cuối cùng

---

## 9. Hướng dẫn vận hành

### Sync dữ liệu

```powershell
# Trigger sync qua analytics-service trực tiếp
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/admin/sync"

# Hoặc qua API Gateway
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/chatbot/admin/sync"
```

### Test chatbot

```powershell
# Tour giảm giá sâu nhất
$body = '{"message":"tour giam gia sau nhat ty le lon nhat","sessionId":"test1"}'
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/chat" `
  -Headers @{"Content-Type"="application/json"} -Body $body | Select -Exp reply

# Hỏi về coupon
$body = '{"message":"he thong co nhung ma coupon giam gia nao","sessionId":"test2"}'
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/chat" `
  -Headers @{"Content-Type"="application/json"} -Body $body | Select -Exp reply

# Tour theo địa điểm
$body = '{"message":"tour da nang co gi hay","sessionId":"test3"}'
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/chat" `
  -Headers @{"Content-Type"="application/json"} -Body $body | Select -Exp reply
```

### Build và deploy sau khi sửa code

```powershell
cd D:\HK8\tourism-microservices-backend

# Build chỉ analytics-service
mvn clean package -pl analytics-service -am -DskipTests

# Build cả analytics + booking (khi sửa cả hai)
mvn clean package -pl booking-service,analytics-service -am -DskipTests

# Deploy analytics-service
docker cp analytics-service/target/analytics-service-1.0.0-SNAPSHOT.jar `
         tourism-analytics-service:/app.jar
docker restart tourism-analytics-service

# Deploy booking-service
docker cp booking-service/target/booking-service-1.0.0-SNAPSHOT.jar `
         tourism-booking-service:/app.jar
docker restart tourism-booking-service

# Đợi service UP rồi sync lại
Start-Sleep -Seconds 30
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/admin/sync"
```

### Thêm coupon vào DB

```powershell
docker exec -it tourism-postgres psql -U postgres -d booking_db -c "
INSERT INTO coupons (coupon_code, description, discount_amount, start_date, end_date,
                     usage_limit, usage_count, coupon_type, departure_id, is_deleted,
                     created_at, updated_at)
VALUES ('NEWCODE', 'Mo ta khuyen mai', 200000, '2026-01-01 00:00:00', '2026-12-31 23:59:59',
        100, 0, 'GLOBAL', null, false, NOW(), NOW());"

# Sau đó sync lại để chatbot nhận dữ liệu mới
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/admin/sync"
```

---

## 10. Unit Tests

| Test class | Số test | Coverage |
|-----------|---------|----------|
| `VectorServiceTest` | 9 | createEmbedding, upsertVector, searchSimilar, deleteAll |
| `ChatbotServiceTest` | 11 | buildEnhancedContext, buildEnhancedPrompt, buildTourSuggestions, buildQuickActions |
| `VectorSyncServiceTest` | 8 | syncAllTours, syncAllLocations, syncAllReviews |
| `ChatbotControllerTest` | 7 | @WebMvcTest cho 4 endpoints |
| **Tổng** | **35** | **35/35 PASS** |

```powershell
mvn test -pl analytics-service
```

---

## 11. Troubleshooting

### Chatbot trả lời "không có thông tin" mặc dù có dữ liệu

**Nguyên nhân 1:** Pinecone index 0 records — chưa sync hoặc sync thất bại.
```powershell
$h = @{"Api-Key"="pcsk_4NU2Hz_..."; "X-Pinecone-API-Version"="2025-04"}
Invoke-RestMethod -Uri "https://api.pinecone.io/indexes/tourism-chatbot" -Headers $h
# Nếu totalVectorCount = 0 -> cần sync
Invoke-RestMethod -Method POST "http://localhost:8087/api/chatbot/admin/sync"
```

**Nguyên nhân 2:** Dimension mismatch — model embed ra 1024 dims nhưng index tạo với 768.
```
ERROR: Vector dimension 1024 does not match the dimension of the index 768
```
Fix: Xoá index cũ, tạo lại với `dimension: 1024`.

---

### Analytics-service unhealthy — API Gateway 503

**Nguyên nhân:** Circular dependency giữa Docker healthcheck và Eureka:
```
Docker healthcheck -> /actuator/health -> Spring hoi Eureka
-> Eureka bao DOWN -> actuator tra 503
-> Docker mark unhealthy -> Eureka mark DOWN -> lap vo tan
```

**Fix:** Đã thêm vào `application.yml`:
```yaml
management:
  health:
    eureka:
      enabled: false
```

Sau đó rebuild + redeploy analytics-service.

---

### Lỗi `429 Too Many Requests` từ Gemini

Gemini free tier có giới hạn RPM. Chờ 1 phút rồi thử lại.
Dài hạn: nâng cấp Gemini API hoặc implement retry với exponential backoff.

---

### Coupon không hiển thị trong chatbot

**Bước kiểm tra:**
```powershell
# 1. Kiểm tra booking-service có trả coupon không
Invoke-RestMethod "http://localhost:8083/api/bookings/coupons/chatbot-sync"

# 2. Nếu rỗng -> insert coupon vào DB (xem phần 9)

# 3. Trigger sync lại
Invoke-RestMethod -Method POST "http://localhost:8087/api/chatbot/admin/sync"

# 4. Xác nhận Pinecone có COUPON docs
docker logs tourism-analytics-service --tail 5
# Phải thấy: "Sync completed: X tour docs, Y location docs, Z review docs, N coupon docs"
```

---

### Sort "giảm giá sâu nhất" sai thứ tự

**Nguyên nhân:** `extractDiscountAmount()` tính nhầm couponDiscount vào sort key.

**Fix hiện tại** (đã áp dụng):
```java
// Chỉ dùng originalPrice - salePrice
private double extractDiscountAmount(VectorDocumentDTO doc) {
    double sale = toDouble(meta.get("salePrice"));
    double orig = toDouble(meta.get("originalPrice"));
    return Math.max(0, orig - sale);
}
```