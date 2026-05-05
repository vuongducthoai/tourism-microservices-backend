# Kế hoạch cải tiến cơ chế Sync dữ liệu Chatbot

> **Ngày:** 05/05/2026  
> **Người viết:** Analytics Team  
> **Trạng thái:** Đề xuất — chưa triển khai

---

## 1. Phân tích hiện trạng

### 1.1. Cơ chế sync hiện tại

```
[Nguồn dữ liệu]              [Analytics-service]           [Pinecone]
tour-catalog-service  ──────► VectorSyncService           ──► Vector DB
booking-service                .syncAll()                    41 docs
                                   │
                         ┌─────────┴──────────┐
                         │ Chạy khi nào?       │
                         │ 1. Thủ công POST    │
                         │    /admin/sync      │
                         │ 2. Tự động 2:00 AM  │
                         │    mỗi ngày         │
                         └─────────────────────┘
```

**Mỗi lần sync full sẽ:**
1. Gọi `tour-catalog-service` lấy tất cả tour → embed từng document → upsert Pinecone
2. Gọi `tour-catalog-service` lấy locations → embed → upsert
3. Gọi `tour-catalog-service` lấy reviews → embed → upsert
4. Gọi `booking-service` lấy coupons → embed → upsert
5. **Tổng: ~41 lần gọi Pinecone Inference API** mỗi lần sync

---

### 1.2. Vấn đề với cơ chế hiện tại

| Vấn đề | Ảnh hưởng |
|--------|-----------|
| **Độ trễ dữ liệu tối đa 24h** | Admin thêm tour mới lúc 3:00 AM → chatbot không biết cho đến 2:00 AM hôm sau |
| **Sync full mỗi lần** | Chỉ thay 1 departure giá → vẫn embed lại toàn bộ 41 documents |
| **Không có trigger tự động khi có thay đổi** | Phải nhớ gọi `/admin/sync` thủ công sau khi thay dữ liệu |
| **Không biết sync thành công hay lỗi** | Không có notification, phải xem logs thủ công |

---

### 1.3. Chi phí hiện tại

#### Pinecone (Vector DB + Inference API)

| Gói | Giới hạn | Chi phí | Hiện tại dùng |
|-----|---------|---------|--------------|
| **Starter (Free)** | 1 index, 100K vectors, 2GB storage | **$0/tháng** | ~41 vectors ✅ |
| Serverless | Trả theo usage | ~$0.10/1M vector writes | — |

**Pinecone Inference API** (embed model `llama-text-embed-v2`):
- Được tích hợp sẵn trong Pinecone Starter → **hiện tại miễn phí**
- Nếu chuyển sang trả phí: ~$0.000016/1K tokens
- 41 docs × ~200 tokens/doc = ~8,200 tokens/sync
- 365 sync/năm = ~3M tokens = **~$0.05/năm** → gần như $0

#### Gemini AI (sinh câu trả lời)

| Gói | Giới hạn | Chi phí |
|-----|---------|---------|
| **Free Tier** | 1,500 request/ngày, 15 RPM | **$0/tháng** |
| Pay-as-you-go | $0.10/1M input tokens (Flash) | Rất rẻ |

**Kết luận về chi phí:**
> ✅ Với quy mô hiện tại (41 docs, dưới 1,500 chat/ngày), **tổng chi phí = $0/tháng**.  
> Chỉ cần quan tâm đến chi phí khi hệ thống có >100K docs hoặc >1,500 chat/ngày.

---

## 2. Đề xuất cải tiến — 3 Phase

### Phase 1 (Làm ngay) — Tối ưu không cần sửa code nhiều

**Mục tiêu:** Giảm độ trễ dữ liệu từ 24h xuống còn hợp lý, không tốn thêm chi phí.

#### 2.1.A. Tăng tần suất scheduled sync

Thay đổi trong `VectorSyncService.java`:

```java
// Hiện tại: chỉ chạy lúc 2:00 AM
@Scheduled(cron = "0 0 2 * * *")

// Đề xuất: chạy 4 lần/ngày (2AM, 8AM, 2PM, 8PM)
@Scheduled(cron = "0 0 2,8,14,20 * * *")
```

**Chi phí thêm:** 41 docs × 4 lần/ngày × 365 ngày = ~60K embed requests/năm → vẫn trong Starter free tier ✅

---

#### 2.1.B. Thêm sync partial cho từng loại data

Thêm các endpoint admin để sync riêng từng phần:

```
POST /api/chatbot/admin/sync/tours    → chỉ sync tours
POST /api/chatbot/admin/sync/coupons  → chỉ sync coupons  
POST /api/chatbot/admin/sync/reviews  → chỉ sync reviews
```

Khi admin cập nhật tour → chỉ cần gọi `/sync/tours` (~15 embed calls) thay vì sync full (~41 calls).

---

### Phase 2 (Ngắn hạn, 1-2 tuần) — Event-Driven Sync qua RabbitMQ (KHÔNG dùng Kafka)

**Mục tiêu:** Dữ liệu thay đổi → chatbot biết ngay trong vòng vài giây. Không cần thủ công.

**RabbitMQ đã có sẵn trong hệ thống** (`tourism-rabbitmq:5672`) nhưng chưa được dùng cho sync chatbot. Đây là cơ hội triển khai không cần thêm infrastructure.

#### Luồng đề xuất

```
[Admin cập nhật tour]
        │
        ▼
[tour-catalog-service]
  TourServiceImpl.updateTour()
  DepartureServiceImpl.updateDeparture()
        │ publish event
        ▼
[RabbitMQ Exchange: chatbot.sync]
  Routing keys:
  • chatbot.sync.tour        ← khi tạo/sửa/xoá tour
  • chatbot.sync.departure   ← khi thay giá/ngày departure
  • chatbot.sync.location    ← khi thêm/sửa địa điểm
  • chatbot.sync.review      ← khi có review mới được duyệt
  • chatbot.sync.coupon      ← khi thêm/sửa coupon
        │ consume event
        ▼
[analytics-service]
  ChatbotSyncEventListener
  → syncSingleTour(tourId)     ← chỉ embed lại 1-2 docs
  → syncSingleDeparture(id)
  → syncSingleCoupon(couponId)
```

#### Nội dung event message

```json
{
  "eventType": "TOUR_UPDATED",
  "entityId": 5,
  "entityType": "TOUR",
  "action": "UPDATE",
  "timestamp": "2026-05-05T10:30:00"
}
```

#### Ưu điểm

| Tiêu chí | Trước (Scheduled) | Sau (Event-driven) |
|----------|-------------------|--------------------|
| Độ trễ | Tối đa 24h | < 5 giây |
| Số embed call/thay đổi | ~41 (full sync) | 1-2 (chỉ entity thay đổi) |
| Cần thủ công | Có | Không |
| Phức tạp implement | — | Trung bình (~3 ngày) |

---

### Phase 3 (Dài hạn) — Incremental Sync + Monitoring

**Mục tiêu:** Tối ưu hoá cho khi hệ thống lớn hơn (>100 tours, >1K departures).

#### 2.3.A. Theo dõi `lastSyncedAt`

Thêm bảng `vector_sync_log` trong `analytics_db`:

```sql
CREATE TABLE vector_sync_log (
    id          SERIAL PRIMARY KEY,
    entity_type VARCHAR(50),   -- TOUR, DEPARTURE, COUPON, ...
    entity_id   INTEGER,
    synced_at   TIMESTAMP,
    status      VARCHAR(20)    -- SUCCESS, FAILED
);
```

Khi scheduled sync chạy → chỉ lấy entity có `updated_at > lastSyncedAt` → giảm số embed call đáng kể.

#### 2.3.B. Dead Letter Queue

Nếu sync một entity thất bại (Pinecone timeout, network lỗi) → đẩy vào Dead Letter Queue → retry sau 5 phút → tối đa 3 lần → alert nếu vẫn thất bại.

#### 2.3.C. Sync Status Dashboard

Admin endpoint mới:
```
GET /api/chatbot/admin/sync/status
→ {
    lastFullSync: "2026-05-05T02:00:00",
    totalDocs: 41,
    pendingSync: 0,
    failedSync: 0,
    pineconeStats: { vectorCount: 41, indexFullness: "0.04%" }
  }
```

---

## 3. So sánh các phương án

| | Phase 1 (4x/ngày) | Phase 2 (Event-driven) | Phase 3 (Incremental) |
|--|------------------|----------------------|----------------------|
| **Độ trễ** | 6h | < 5 giây | < 5 giây |
| **Embed calls/ngày** | 164 (41×4) | ~5-20 (chỉ khi thay đổi) | ~10-30 (chỉ delta) |
| **Chi phí** | $0 (vẫn free) | $0 (vẫn free) | $0 (vẫn free) |
| **Effort implement** | 30 phút | 2-3 ngày | 1 tuần |
| **Infrastructure mới** | Không | Không (RabbitMQ có sẵn) | Cần thêm bảng DB |
| **Khuyến nghị** | ✅ Làm ngay | ✅ Sprint tiếp theo | ⏳ Khi cần thiết |

---

## 4. Kế hoạch triển khai

### Sprint hiện tại — Phase 1 (30 phút)

- [ ] Đổi cron từ `0 0 2 * * *` → `0 0 2,8,14,20 * * *` trong `VectorSyncService.java`
- [ ] Thêm 3 endpoint partial sync vào `ChatbotController.java`
- [ ] Rebuild + deploy analytics-service

### Sprint tiếp theo — Phase 2 (~3 ngày)

**Ngày 1:**
- [ ] Tạo `RabbitMQConfig.java` trong analytics-service (khai báo Queue + Exchange + Binding)
- [ ] Tạo `ChatbotSyncEventDTO.java` trong shared-library
- [ ] Thêm `syncSingleTour(id)`, `syncSingleDeparture(id)`, `syncSingleCoupon(id)` vào `VectorSyncService`

**Ngày 2:**
- [ ] Tạo `ChatbotSyncEventListener.java` trong analytics-service
- [ ] Thêm publish event trong `TourServiceImpl.java` (tour-catalog-service)
- [ ] Thêm publish event trong `DepartureServiceImpl.java`

**Ngày 3:**
- [ ] Thêm publish event trong `booking-service` (khi thêm/sửa coupon)
- [ ] Test end-to-end: cập nhật tour → chatbot nhận dữ liệu mới trong vòng 5 giây
- [ ] Rebuild + deploy cả tour-catalog-service, booking-service, analytics-service

### Tương lai — Phase 3 (khi cần)

- [ ] Khi số tour > 200 hoặc sync mất > 2 phút mới cần làm
- [ ] Thêm `vector_sync_log` table
- [ ] Incremental sync theo `updated_at`
- [ ] Dead Letter Queue + retry

---

## 5. Tóm tắt — Khuyến nghị

> **Câu hỏi: "Sync tốn gì không?"**
>
> Với quy mô hiện tại (41 docs, Pinecone Starter free tier, Gemini free tier):  
> **Tổng chi phí = $0/tháng.** Không tốn gì.
>
> Chỉ cần lo về chi phí khi:
> - Vượt 100K vectors trong Pinecone (hiện tại 41/100,000)
> - Chat request > 1,500/ngày (Gemini free tier)

> **Câu hỏi: "Thay đổi dữ liệu phải sync lại không?"**
>
> **Hiện tại: Có** — phải gọi thủ công `POST /admin/sync` hoặc đợi 2:00 AM.
>
> Nên làm ngay **(Phase 1)**: tăng lên 4 lần/ngày → độ trễ tối đa 6h.  
> Nên làm sprint sau **(Phase 2)**: event-driven → dữ liệu thay đổi tức thì chatbot biết ngay.
