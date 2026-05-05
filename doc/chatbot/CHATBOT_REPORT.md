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
- **Augmented** — các document tìm được (tour, địa điểm, review) được format thành **context** cấu trúc, đính kèm vào prompt
- **Generation** — **Gemini 2.0 Flash** nhận prompt + context, sinh ra câu trả lời bằng tiếng Việt có Markdown

**Điểm quan trọng:** Chatbot KHÔNG truy vấn database trực tiếp. Toàn bộ thông tin (tên tour, giá, mã giảm giá, địa điểm...) đều đến từ metadata đã được đồng bộ sẵn vào Pinecone.

---

## 2. Kiến trúc và luồng dữ liệu

```
+----------------------------------------------------------+
|  CLIENT (React Frontend)                                  |
|  POST /api/chatbot/chat                                   |
+---------------------------+------------------------------+
                            |
                            v
+----------------------------------------------------------+
|  API GATEWAY :8080                                        |
|  Route: /api/chatbot/** -> analytics-service             |
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
|               | (Spring Cloud OpenFeign)                  |
|               v                                           |
|       TOUR-CATALOG-SERVICE :8086                          |
|           /api/tours/chatbot-sync                         |
|           /api/locations/chatbot-sync                     |
|           /api/reviews/chatbot-sync                       |
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

| Service | URL | Muc dich |
|---------|-----|---------|
| Pinecone Inference API | `https://api.pinecone.io/embed` | Tao vector embedding tu van ban |
| Pinecone Vector DB | `https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io` | Luu tru va tim kiem vector |
| Gemini AI | `https://generativelanguage.googleapis.com/v1beta/models/` | Sinh cau tra loi |
| tour-catalog-service | `http://tour-catalog-service/api/...` | Lay du lieu de sync |

---

## 3. RAG Pipeline — từng bước chi tiết

```
User gui: "tour giam gia khuyen mai"
         |
         v
[Buoc 1] Xac dinh loai cau hoi
         isDiscountQuery = true (khop regex DISCOUNT_PATTERN)
         topK = 50 (discount query lay nhieu hon)
         |
         v
[Buoc 2] Embed cau hoi (VectorService.searchSimilar)
         POST https://api.pinecone.io/embed
         Body: { model: "llama-text-embed-v2", 
                 inputs: [{text: "tour giam gia khuyen mai"}],
                 parameters: {input_type: "query"} }
         -> nhan ve vector [0.12, -0.03, ..., 0.87] (1024 gia tri)
         |
         v
[Buoc 3] Tim kiem Pinecone (cosine similarity)
         POST https://tourism-chatbot-g2idbvy.../query
         Body: { vector: [...1024 values], topK: 50, includeMetadata: true }
         -> tra ve danh sach document co score cao nhat
         |
         v
[Buoc 4] Build context (ChatbotService.buildEnhancedContext)
         - Loc chi lay TOUR_DEPARTURE co giam gia (couponDiscount > 0 hoac sale < original)
         - Sap xep theo muc giam tu cao -> thap
         - Emit header: "QUAN TRONG: Co CHINH XAC 9 tour co ma giam gia coupon"
         - Voi moi tour: emit "[Ten tour:..., Ma tour:..., Ngay:..., Gia ADULT: X VND, MÃ GIẢM GIÁ: Y VND]"
         - Voi dia diem: emit "[Dia diem:..., LocationID: X]"
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
         - TourSuggestions: lay tu metadata Pinecone, deduplicate theo tourId
         - QuickActions: nut goi y dua tren tu khoa trong cau hoi
         |
         v
[Ket qua] ChatMessageResponse
         {
           reply: "Hien tai co 9 tour dang co uu dai...",
           tourSuggestions: [{tourId, tourCode, tourName, minPrice, detailUrl}],
           quickActions: [{label, action, url}],
           sessionId: "...",
           timestamp: "2026-05-05T10:30:00"
         }
```

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

| Truong | Bat buoc | Mo ta |
|--------|----------|-------|
| `message` | Có | Cau hoi cua user (khong duoc rong) |
| `sessionId` | Không | ID phien hoi thoai; tu sinh UUID neu thieu |
| `userId` | Không | ID user (dung cho log, chua personalize) |

**Response body:**
```json
{
  "reply": "Ban co the tham khao mot so tour Da Lat...",
  "tourSuggestions": [
    {
      "tourId": 5,
      "tourCode": "DL-3N2D",
      "tourName": "Tour Da Lat 3 Ngay 2 Dem",
      "imageUrl": "/images/dalat.jpg",
      "minPrice": 3500000.0,
      "duration": "3 Ngay 2 Dem",
      "detailUrl": "/tour/DL-3N2D",
      "relevanceScore": 0.92
    }
  ],
  "quickActions": [
    { "label": "Xem tour giam gia", "action": "navigate", "url": "/tours?sort=discount" }
  ],
  "sessionId": "session-abc123",
  "timestamp": "2026-05-05T10:30:15"
}
```

| Truong | Kieu | Mo ta |
|--------|------|-------|
| `reply` | String | Cau tra loi Markdown tu Gemini |
| `tourSuggestions` | List | Toi da 6 tour goi y tu metadata Pinecone |
| `quickActions` | List | Nut goi y tiep theo (dua tren context cau hoi) |
| `sessionId` | String | ID phien hoi thoai (echo lai) |
| `timestamp` | ISO 8601 | Thoi diem phan hoi |

---

### 4.2. `POST /api/chatbot/admin/sync` — Đồng bộ dữ liệu lên Pinecone

**Mục đích:** Admin trigger để lấy dữ liệu mới nhất từ `tour-catalog-service` và upsert lên Pinecone.

**Request:** Không có body.

**Response:**
```json
{
  "status": "success",
  "message": "Dong bo du lieu thanh cong",
  "elapsed": "41807ms",
  "timestamp": "2026-05-05T10:30:37"
}
```

**Luồng xử lý nội bộ:**
```
/admin/sync
    -> VectorSyncService.syncAll()
         +-- syncAllTours()
         |     +-- GET tour-catalog-service/api/tours/chatbot-sync
         |     Voi moi tour:
         |         +-- syncTourSummary()    -> 1 document TOUR_SUMMARY_<id>
         |         +-- syncTourDepartures() -> N document TOUR_DEPARTURE_<id>
         |
         +-- syncAllLocations()
         |     +-- GET tour-catalog-service/api/locations/chatbot-sync
         |     Voi moi location: syncLocation() -> LOCATION_<id>
         |
         +-- syncAllReviews()
               +-- GET tour-catalog-service/api/reviews/chatbot-sync
               Voi moi review (co comment): syncReview() -> REVIEW_<id>
```

**Ket qua sync dien hinh:** 9 tours -> 24 documents (9 TOUR_SUMMARY + 15 TOUR_DEPARTURE),  
12 LOCATION, 1 REVIEW = **37 documents tong**

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

> Sau khi clear, chatbot se khong tim duoc gi cho den khi chay sync lai.

---

### 4.4. `GET /api/chatbot/health` — Kiểm tra trạng thái

**Response:**
```json
{
  "status": "UP",
  "service": "analytics-service",
  "timestamp": "2026-05-05T10:30:00"
}
```

---

## 5. Mô tả từng Class / Service

### 5.1. ChatbotController

Lop `@RestController`, dieu phoi 4 endpoints. Khong chua business logic — delegate toan bo sang `ChatbotService` va `VectorSyncService`.

---

### 5.2. ChatbotService — RAG Pipeline chinh

#### `handleUserMessage(request)` — Method chinh

Diem vao cua pipeline RAG:
1. Xac dinh `isDiscountQuery` bang regex `DISCOUNT_PATTERN`
2. Chon `topK` (50 neu la discount query, 10 neu khong)
3. Goi `vectorService.searchSimilar()` lay documents
4. Goi `buildEnhancedContext()` tao context
5. Goi `buildEnhancedPrompt()` tao prompt
6. Goi `callGeminiAPI()` sinh cau tra loi
7. Goi `buildTourSuggestions()` va `buildQuickActions()`

---

#### `buildEnhancedContext(docs, userMessage)` — Xay dung context

Chuyen doi danh sach VectorDocumentDTO thanh chuoi co cau truc cho Gemini doc:

- **Discount query:** loc chi TOUR_DEPARTURE co `couponDiscount > 0` HOAC `salePrice < originalPrice`, sap xep theo `extractDiscountAmount()` giam dan
- **Header emit:** `"QUAN TRONG: Co CHINH XAC X tour co ma giam gia coupon"` de Gemini khong bo sot
- **TOUR_DEPARTURE annotation:** `[Ten tour:..., Ma tour:..., Ngay:..., Gia ADULT: X VND, MÃ GIẢM GIÁ ĐẶC BIỆT: Y VND (Ma: ...)]`
- **LOCATION annotation:** `[Dia diem:..., LocationID: X]`
- Gioi han toi da 3500 ky tu de tranh vuot token limit

---

#### `buildEnhancedPrompt(userMessage, context)` — Tao prompt

Prompt gom:
- **System instruction** (~2500 ky tu): vai tro, quy tac gia, link format, yeu cau liet ke du tour giam gia
- **Context block**: du lieu tu Pinecone
- **Cau hoi cua user**
- **Yeu cau tra loi Markdown**

---

#### `callGeminiAPI(prompt)` — Goi Gemini

`POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent`

Config: `temperature=0.2` (bam sat du lieu, giam hallucination), `maxOutputTokens=1000`.

---

#### `buildTourSuggestions(docs)` — TourSuggestion

Toi da 6 tour tu metadata Pinecone. Deduplicate theo tourId, chon departure gia thap nhat.

---

#### `buildQuickActions(request)` — QuickAction

Nut goi y theo tu khoa:
- "giam gia/khuyen mai/uu dai/coupon" -> "Tours giam gia soc"
- "yeu thich/danh gia cao/tot nhat" -> "Tours duoc yeu thich"  
- "gan nhat/sap khoi hanh" -> "Khoi hanh gan nhat"
- Khong khop gi -> 4 nut mac dinh (giam gia, mien Bac, mien Nam, tat ca)

---

### 5.3. VectorService — Embedding + Pinecone

#### `createEmbedding(text)` — Embed passage (public)

Dung de embed **noi dung can luu** (`input_type = "passage"`).  
Duoc goi boi `VectorSyncService` khi sync du lieu.

#### `createEmbeddingForQuery(text)` — Embed query (private)

Dung de embed **cau hoi cua user** (`input_type = "query"`).  
Duoc goi boi `searchSimilar()`.

> **Ly do phan biet:** `llama-text-embed-v2` toi uu hoa rieng cho passage (noi dung dai) va query (cau hoi ngan), giup tang do chinh xac semantic search.

#### `callPineconeEmbed(text, inputType)` — Goi Pinecone Inference API

```
POST https://api.pinecone.io/embed
Headers: Api-Key: <key>, X-Pinecone-API-Version: 2025-04
Body: {
  "model": "llama-text-embed-v2",
  "inputs": [{"text": "..."}],
  "parameters": { "input_type": "passage|query", "truncate": "END" }
}
Response: {"data": [{"values": [0.12, -0.03, ..., 0.87]}]}  <- 1024 gia tri
```

#### `upsertVector(document)` — Luu vao Pinecone

```
POST <host>/vectors/upsert
Body: { "vectors": [{ "id": "TOUR_DEPARTURE_15", "values": [...1024], "metadata": {...} }] }
```

#### `searchSimilar(queryText, topK)` — Tim kiem tuong tu

```
POST <host>/query
Body: { "vector": [...1024 values], "topK": 10, "includeMetadata": true }
Response: matches[] sap xep theo score (cosine similarity 0-1)
```

#### `deleteVectorsByEntityId(type, entityId)` — Xoa theo entity

Xoa vector co filter `{entityId, type}`. Dung truoc khi re-sync entity cu the.

#### `deleteAll()` — Xoa toan bo

`POST <host>/vectors/delete` voi `{deleteAll: true}`.

---

### 5.4. VectorSyncService — Dong bo du lieu

#### `syncAll()` — Dong bo toan bo

Goi tuan tu: `syncAllTours()` -> `syncAllLocations()` -> `syncAllReviews()`.

#### `syncAllTours()` — Lay tu `/api/tours/chatbot-sync`

Voi moi `TourSyncDTO`:
- `syncTourSummary()` -> **1 document** `TOUR_SUMMARY_<tourID>` (tong hop thong tin tour)
- `syncTourDepartures()` -> **N document** `TOUR_DEPARTURE_<departureID>` (tung lich khoi hanh: gia, ngay, coupon)

**Content TOUR_SUMMARY duoc embed:**
```
Tour: Ha Noi - Ha Long 3 Ngay 2 Dem | Code: HN-HL-3N2D | Thoi gian: 3 Ngay 2 Dem
| Diem khoi hanh: Ha Noi | Diem den: Ha Long | Phuong tien: Xe du lich
| Diem tham quan: Vinh Ha Long, Hang Dau Go | Danh gia trung binh: 4.5/5 (12 luot)
```

**Content TOUR_DEPARTURE duoc embed:**
```
Lich khoi hanh tour Ha Noi - Ha Long 3N2D (HN-HL-3N2D) | Ngay: 2027-04-15
| Con 20 cho | Gia nguoi lon: 2,900,000 VND (giam 10% so voi gia goc 3,200,000 VND)
| Coupon SUMMER2027 giam them 300,000 VND | Tu Ha Noi den Ha Long
```

#### `syncAllLocations()` — Lay tu `/api/locations/chatbot-sync`

Moi `LocationSyncDTO` -> document `LOCATION_<locationID>`:
```
Diem den du lich: Da Nang | Khu vuc: CENTRAL | Thanh pho bien soi dong...
| San bay: San bay Quoc te Da Nang (DAD)
```

#### `syncAllReviews()` — Lay tu `/api/reviews/chatbot-sync`

Moi `ReviewSyncDTO` co comment -> document `REVIEW_<reviewID>`.

#### `@Scheduled(cron = "0 0 2 * * *")`

Tu dong chay `syncAll()` luc **2:00 AM moi ngay**.

---

### 5.5. TourCatalogFeignClient

Feign client ket noi sang `tour-catalog-service` qua Eureka (khong hardcode URL).

| Method | Endpoint | Mo ta |
|--------|----------|-------|
| `getAllToursForChatbotSync()` | `GET /api/tours/chatbot-sync` | Tat ca tour active + departures + pricing + coupon |
| `getLocationsForChatbotSync()` | `GET /api/locations/chatbot-sync` | Tat ca diem den active |
| `getAllVisibleReviews()` | `GET /api/reviews/chatbot-sync` | Tat ca review visible co comment |

---

## 6. Cấu trúc dữ liệu Pinecone

### Index: `tourism-chatbot`

- **Dimension:** 1024 (llama-text-embed-v2 default)
- **Metric:** cosine
- **Host:** `https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io`

### Loại document (field `type` trong metadata)

| Type | ID format | So luong dien hinh | Dung de |
|------|-----------|--------------------|----|
| `TOUR_SUMMARY` | `TOUR_SUMMARY_<tourID>` | 1 / tour | Tim kiem tour theo ten, diem den, dac diem |
| `TOUR_DEPARTURE` | `TOUR_DEPARTURE_<departureID>` | 1-3 / tour | Tim gia, ngay khoi hanh, khuyen mai |
| `LOCATION` | `LOCATION_<locationID>` | 1 / dia diem | Tim dia diem du lich theo ten/vung |
| `REVIEW` | `REVIEW_<reviewID>` | 1 / review | Tim danh gia chat luong |

### Metadata fields theo type

**TOUR_SUMMARY:**
```json
{
  "tourId": 1, "tourCode": "HN-HL-3N2D", "tourName": "Ha Noi - Ha Long 3N2D",
  "duration": "3 Ngay 2 Dem", "startLocationName": "Ha Noi", "startLocationID": 1,
  "endLocationName": "Ha Long", "endLocationID": 2,
  "avgRating": 4.5, "reviewCount": 12, "minPrice": 2900000
}
```

**TOUR_DEPARTURE:**
```json
{
  "tourId": 1, "tourCode": "HN-HL-3N2D", "tourName": "Ha Noi - Ha Long 3N2D",
  "departureID": 15, "departureDate": "2027-04-15", "availableSlots": 20,
  "salePrice": 2900000, "originalPrice": 3200000,
  "couponCode": "SUMMER2027", "couponDiscount": 300000,
  "discountPercentage": 9
}
```

**LOCATION:**
```json
{
  "locationID": 3, "name": "Da Nang", "region": "CENTRAL",
  "airportCode": "DAD", "airportName": "San bay Quoc te Da Nang"
}
```

---

## 7. Cấu hình (application.yml)

```yaml
gemini:
  api:
    key: ${GEMINI_API_KEY:AIzaSyA8F9E9UK4dFGzWxlGN0u0Pk1IoQir0n7I}
  generation:
    model: gemini-2.0-flash

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
```

**Luu y quan trong ve embedding:**
- Model `llama-text-embed-v2` tra ve vector **1024 chieu** (khong phai 768)
- Pinecone index phai duoc tao voi `dimension: 1024`
- Khong dung Gemini `text-embedding-004` (endpoint nay 404 voi free API key)

---

## 8. Những thứ Chatbot có thể trả lời

Chatbot tra loi duoc bat ky cau hoi nao co du lieu trong Pinecone:

| Cau hoi mau | Document tim thay | Thong tin tra loi |
|-------------|-------------------|-------------------|
| "tour Da Nang co gi" | TOUR_SUMMARY, LOCATION | Ten tour, dac diem, danh gia, link |
| "tour giam gia khuyen mai" | TOUR_DEPARTURE (coupon) | Danh sach tour co coupon, gia, ma giam |
| "tour re nhat tu Ha Noi" | TOUR_DEPARTURE, TOUR_SUMMARY | Gia thap nhat, ten tour, ngay khoi hanh |
| "tour mien Nam sap khoi hanh" | TOUR_DEPARTURE | Lich cu the, gia, so cho con |
| "danh gia tour Phu Quoc" | REVIEW, TOUR_SUMMARY | Rating, binh luan cua khach |
| "bay tu Da Nang di dau" | LOCATION | San bay, tour xuat phat tu DAD, startLocationID |
| "tour 3 ngay 2 dem" | TOUR_SUMMARY | Tat ca tour co duration phu hop |
| "tour duoi 3 trieu" | TOUR_DEPARTURE | Tour co salePrice thap nhat |

**Chatbot KHONG biet:**
- Thong tin booking, thanh toan, trang thai dat tour (booking-service chua sync)
- Lich su hoi thoai (stateless, moi request doc lap)
- Thong tin account/user (iam-service chua sync)
- Du lieu moi hon lan sync cuoi cung

---

## 9. Hướng dẫn vận hành

### Sync dữ liệu

```powershell
# Trigger sync qua analytics-service truc tiep
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/admin/sync"

# Hoac qua API Gateway
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/chatbot/admin/sync"
```

### Test chatbot

```powershell
# Tour giam gia
$body = '{"message":"tour co khuyen mai giam gia","sessionId":"test1"}'
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/chat" `
  -Headers @{"Content-Type"="application/json"} -Body $body | Select -Exp reply

# Tour theo dia diem
$body = '{"message":"tour da nang","sessionId":"test2"}'
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/chat" `
  -Headers @{"Content-Type"="application/json"} -Body $body | Select -Exp reply
```

### Build và deploy sau khi sửa code

```powershell
cd D:\HK8\tourism-microservices-backend

# Build JAR
mvn clean package -pl analytics-service -am -DskipTests

# Copy JAR vao container va restart
docker cp analytics-service/target/analytics-service-1.0.0-SNAPSHOT.jar `
         tourism-analytics-service:/app.jar
docker restart tourism-analytics-service

# Doi 30s roi sync lai
Start-Sleep -Seconds 30
Invoke-RestMethod -Method POST -Uri "http://localhost:8087/api/chatbot/admin/sync"
```

---

## 10. Unit Tests

| Test class | So test | Coverage |
|-----------|---------|----------|
| `VectorServiceTest` | 9 | createEmbedding, upsertVector, searchSimilar, deleteAll |
| `ChatbotServiceTest` | 11 | buildEnhancedContext, buildEnhancedPrompt, buildTourSuggestions, buildQuickActions |
| `VectorSyncServiceTest` | 8 | syncAllTours, syncAllLocations, syncAllReviews |
| `ChatbotControllerTest` | 7 | @WebMvcTest cho 4 endpoints |
| **Tong** | **35** | **35/35 PASS** |

```powershell
mvn test -pl analytics-service
```

---

## 11. Troubleshooting

### Chatbot tra loi "khong co thong tin" mac du co du lieu

**Nguyen nhan 1:** Pinecone index 0 records — chua sync hoac sync that bai.
```powershell
# Kiem tra index
$h = @{"Api-Key"="<pinecone-key>"; "X-Pinecone-API-Version"="2025-04"}
Invoke-RestMethod -Uri "https://api.pinecone.io/indexes/tourism-chatbot" -Headers $h
# Neu totalVectorCount = 0 -> can sync
```

**Nguyen nhan 2:** Dimension mismatch — model embed ra 1024 dims nhung index tao voi 768.
```
ERROR: Vector dimension 1024 does not match the dimension of the index 768
```
Fix: Xoa index cu, tao lai voi `dimension: 1024`.

---

### Loi `429 Too Many Requests` tu Gemini

Gemini free tier co gioi han RPM. Cho 1 phut roi thu lai.  
Dai han: nang cap Gemini API hoac implement retry voi exponential backoff.

---

### Sync that bai — `Connection refused` den tour-catalog-service

```powershell
docker ps | Select-String "tour-catalog"
Invoke-RestMethod -Uri "http://localhost:8086/api/tours/chatbot-sync"
```

---

*Bao cao duoc tao ngay 05/05/2026 — analytics-service 1.0.0-SNAPSHOT*