# PLAN: Triển khai Chatbot + Sửa Slot Hoàn Trả Khi Hủy Tour

**Ngày lập:** 2026-05-18  
**Trạng thái:** Chuẩn bị triển khai  
**Branch hiện tại:** `chatbot` (HEAD = `186b38c`)  
**KHÔNG sửa code** — đây là tài liệu phân tích và kế hoạch triển khai

---

## 1. Tóm tắt những gì vừa pull về

### PR #8 — `chatbot` (merge `21774ba`)
Tính năng RabbitMQ Outbox cho booking-service:
- Thêm entity `OutboxEvent`, enum `OutboxStatus`
- Thêm field `coinRefundStatus` vào `Booking` entity
- Thêm `CoinRefundRelayScheduler`, `OutboxRelayScheduler`, `OutboxEventFactory`
- Thêm `DeadEventAdminController`, `QueueHealthService`
- Thêm entity `CoinTransaction` trong iam-service (idempotent coin refund)
- Thêm `ProcessedEvent` entity trong notification-service
- booking-service: bổ sung logic coin refund khi hủy booking (qua outbox)

### PR #9 + #10 — `feature-tour-detail` (merge `67c3acc` → `186b38c`)
Tính năng chi tiết tour, forum, đặt tour:
- booking-service: thêm `appliedCouponCodes`, `RefundInformation`, `BookingPassenger`, coupon flow
- tour-catalog-service: `ItineraryDay`, `BranchContact`, `PolicyTemplate`, admin tour detail
- forum-service: `ForumPost`, `PostComment`, `PostLike`, `Tag`, `PostCategory`
- notification-service: thêm `UserNotificationController` (lấy, đọc thông báo)
- iam-service: `AuthController` Keycloak login/register
- Frontend: Forum, Admin tour form, Login page mới

---

## 2. Kiểm tra Chatbot — Kết quả phân tích

### 2.1 Code chatbot KHÔNG thay đổi sau khi pull

| Thành phần | Last commit | Có thay đổi sau pull? |
|---|---|---|
| `analytics-service/ChatbotService.java` | `9f73621` (chatbot branch cũ) | ❌ Không |
| `analytics-service/VectorSyncService.java` | `9f73621` | ❌ Không |
| `analytics-service/VectorService.java` | `9f73621` | ❌ Không |
| `ChatbotWidget.jsx` (frontend) | `e77e3c2` | ❌ Không |
| `TourChatbotSyncResponse.java` (tour-catalog) | `9f73621` | ❌ Không |
| `TourSyncDTO.java` (analytics) | `9f73621` | ❌ Không |

**→ Chatbot code không bị ảnh hưởng trực tiếp bởi 2 PR vừa pull.**

---

### 2.2 Vấn đề phát hiện trong Chatbot

#### ⚠️ VẤN ĐỀ 1: GEMINI_API_KEY và PINECONE_API_KEY THIẾU trong docker-compose.yml

**Mô tả:**  
`analytics-service` trong `docker-compose.yml` **KHÔNG** có các biến môi trường sau:
```
GEMINI_API_KEY
PINECONE_API_KEY
PINECONE_HOST
PINECONE_ENV
```

Hiện tại chỉ có trong `analytics-service/src/main/resources/application.yml` dưới dạng default value:
```yaml
gemini:
  api:
    key: ${GEMINI_API_KEY:AIzaSyCigeXq2FoA4cD3VNqepX5QShV9aq_yn04}

chatbot:
  vector-db:
    pinecone:
      api-key: ${PINECONE_API_KEY:pcsk_4NU2Hz_...}
      host:    ${PINECONE_HOST:https://tourism-chatbot-g2idbvy...}
      environment: ${PINECONE_ENV:us-east-1-aws}
```

**Rủi ro:**
- Nếu API key Gemini hoặc Pinecone hết hạn/bị thu hồi → chatbot crash hoàn toàn
- API key hardcode trong source code là lỗ hổng bảo mật (OWASP A02: Cryptographic Failures)
- Khi deploy Docker production, không cấu hình được key qua env

**Cần làm (chỉ trong docker-compose.yml):**
```yaml
analytics-service:
  environment:
    - GEMINI_API_KEY=<your-key>
    - PINECONE_API_KEY=<your-key>
    - PINECONE_HOST=https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io
    - PINECONE_ENV=us-east-1-aws
```

---

#### ⚠️ VẤN ĐỀ 2: availableSlots trong Pinecone sẽ sai do bug hủy tour không hoàn slot

Chatbot đọc `availableSlots` từ Pinecone Vector DB (được sync từ tour-catalog-service).  
Vì slot chưa được hoàn trả khi hủy (xem Mục 3), số chỗ trống trong Pinecone sẽ **thấp hơn thực tế** → chatbot gợi ý tour còn ít chỗ hoặc hết chỗ khi thực ra vẫn còn.

**Ảnh hưởng:** Chatbot trả lời thông tin slot sai cho khách hàng.

---

#### ℹ️ VẤN ĐỀ 3 (Nhỏ): URL chatbot frontend hardcode localhost

Trong `ChatbotWidget.jsx`:
```javascript
const response = await fetch('http://localhost:8080/api/chatbot/chat', { ... });
```

**Ảnh hưởng:** Chỉ lỗi khi deploy production (nếu domain thay đổi). Dev local OK.  
Không ảnh hưởng hiện tại.

---

### 2.3 Luồng Chatbot hiện tại hoạt động như thế nào

```
User → ChatbotWidget → POST /api/chatbot/chat (gateway port 8080)
                     → analytics-service:8087 ChatbotController
                     → ChatbotService.handleUserMessage()
                          ├─ VectorService.searchSimilar(message, topK)
                          │    └─ Pinecone query bằng embedding (llama-text-embed-v2)
                          ├─ buildEnhancedContext(docs) 
                          ├─ buildEnhancedPrompt(message, context)
                          ├─ callGeminiAPI(prompt) → Gemini 2.0 Flash
                          ├─ buildTourSuggestions(docs)
                          └─ trả về ChatMessageResponse

VectorSyncService (cron 2:00 AM):
   → GET /api/tours/chatbot-sync (tour-catalog-service)
   → GET /api/locations/chatbot-sync
   → GET /api/reviews/chatbot-sync
   → GET coupons từ booking-service
   → upsert lên Pinecone
```

---

## 3. Booking Hủy Tour — BUG NGHIÊM TRỌNG: Slot KHÔNG được hoàn trả

### 3.1 Kết quả phân tích code

#### Luồng tạo booking (ĐÚNG):
```
BookingServiceImpl.createBooking()
  → tourCatalogClient.decreaseSlots(departureId, seatCount)   ✅ Giảm slot
  → lưu Booking vào DB
  → outbox notification
```

#### Luồng hủy booking (SAI - THIẾU hoàn slot):
```
BookingServiceImpl.cancelBooking()
  → booking.setBookingStatus(CANCELLED)                       ✅
  → tính refundableAmount                                     ✅  
  → outbox COIN_REFUND                                        ✅
  → outbox notification                                       ✅
  → ❌ KHÔNG gọi tourCatalogClient.increaseSlots()           ← BUG
```

#### Luồng gửi yêu cầu hoàn tiền (SAI - THIẾU hoàn slot):
```
BookingServiceImpl.submitRefundRequest()
  → booking.setBookingStatus(PENDING_REFUND)                  ✅
  → tạo RefundInformation                                     ✅
  → outbox REFUND_REQUESTED                                   ✅
  → ❌ KHÔNG gọi tourCatalogClient.increaseSlots()           ← BUG
```

### 3.2 Tầm ảnh hưởng

| Thành phần | Vấn đề |
|---|---|
| `TourCatalogFeignClient` (booking-service) | Chỉ có `decreaseSlots()`, KHÔNG có `increaseSlots()` |
| `DepartureController` (tour-catalog-service) | Chỉ có `POST /decreaseSlots`, KHÔNG có `increaseSlots` |
| `TourDepartureRepository` | Chỉ có `decreaseAvailableSlots()`, KHÔNG có `increaseAvailableSlots()` |
| Frontend `TourBooking.jsx` | Hiển thị `availableSlots` từ API — sẽ hiện số sai sau mỗi lần hủy |
| Chatbot (Pinecone) | Sync `availableSlots` sai vào vector DB |

**Hậu quả thực tế:**
- Khách hủy tour → slot đó mất vĩnh viễn
- Người khác không thể đặt slot đó dù tour vẫn còn chỗ
- Số chỗ trống hiển thị trên frontend ngày càng nhỏ hơn thực tế
- Sau nhiều lần hủy, tour hiển thị "Hết chỗ" dù thực ra còn chỗ

### 3.3 Kết luận: Bạn CÓ bị ảnh hưởng không?

**TRẢ LỜI: CÓ.**

Mỗi lần khách hủy tour, chỗ trống trên departure đó giảm đi vĩnh viễn cho đến khi sửa code. Người khác không thể đặt vào chỗ đó nữa.

---

## 4. Kế hoạch sửa — Để triển khai (CẦN LÀM)

> ⚠️ TÀI LIỆU NÀY CHỈ LÀ KẾ HOẠCH — CHƯA SỬA CODE

### Task 1: Sửa bug slot không hoàn trả khi hủy

**Phạm vi sửa: 4 file**

#### File 1: `TourDepartureRepository.java` (tour-catalog-service)
```
Thêm query:
@Modifying
@Query("""
    UPDATE TourDeparture d
    SET d.availableSlots = d.availableSlots + :count
    WHERE d.departureID = :departureId
    """)
int increaseAvailableSlots(@Param("departureId") Integer departureId, @Param("count") int count);
```

#### File 2: `DepartureController.java` (tour-catalog-service)
```
Thêm endpoint:
POST /api/departures/{departureId}/increase-slots?count=N
→ gọi tourDepartureRepository.increaseAvailableSlots(departureId, count)
→ trả 200 OK
```

#### File 3: `TourCatalogFeignClient.java` (booking-service)
```
Thêm method:
@PostMapping("/{departureId}/increase-slots")
ResponseEntity<Void> increaseSlots(
    @PathVariable Integer departureId,
    @RequestParam int count);
```

#### File 4: `BookingServiceImpl.java` (booking-service) — 2 chỗ cần sửa

**Trong `cancelBooking()`**: Sau khi tính `seatCount` từ `booking.getPassengers()`, thêm:
```java
int seatCount = /* đếm adult + child + toddler từ booking.getPassengers() */;
try {
    tourCatalogClient.increaseSlots(booking.getDepartureId(), seatCount);
} catch (Exception e) {
    log.warn("Could not release slots for departure {}: {}", booking.getDepartureId(), e.getMessage());
    // không rollback booking cancel — slot sẽ được xử lý bởi admin nếu cần
}
```

**Trong `submitRefundRequest()`**: Tương tự — hoàn slot khi chuyển sang PENDING_REFUND.

> **Lưu ý quan trọng:** Cần tính `seatCount` từ `booking.getPassengers()` — đếm số hành khách chiếm ghế (adult + child + toddler, KHÔNG tính infant). Kiểm tra xem `Booking` entity có lazy hay eager load `passengers` không.

---

### Task 2: Bổ sung env vars chatbot vào docker-compose.yml

**Phạm vi sửa: 1 file** — `docker-compose.yml`

```yaml
analytics-service:
  environment:
    - TZ=Asia/Ho_Chi_Minh
    - JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Ho_Chi_Minh
    - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/analytics_db
    - SPRING_DATASOURCE_USERNAME=postgres
    - SPRING_DATASOURCE_PASSWORD=postgres
    - EUREKA_HOST=service-discovery
    - RABBITMQ_HOST=rabbitmq
    # --- THÊM MỚI ---
    - GEMINI_API_KEY=${GEMINI_API_KEY:-AIzaSyCigeXq2FoA4cD3VNqepX5QShV9aq_yn04}
    - PINECONE_API_KEY=${PINECONE_API_KEY:-pcsk_4NU2Hz_FNyvrV1c4YHNEJi1rdEvkEnJLE7wUAihBLpizDAvMbg9UwzhY7i5hr4U4NToVM7}
    - PINECONE_HOST=${PINECONE_HOST:-https://tourism-chatbot-g2idbvy.svc.aped-4627-b74a.pinecone.io}
    - PINECONE_ENV=${PINECONE_ENV:-us-east-1-aws}
```

> Dùng `.env` file để override key nếu cần production key khác.

---

### Task 3: Sau khi fix slot — cần sync lại Pinecone

Sau khi fix bug và deploy, `availableSlots` trong DB sẽ được cập nhật đúng. Cần trigger sync lại Pinecone để chatbot đọc data mới:

```
POST /api/chatbot/admin/sync  (AdminChatbotController endpoint trong analytics-service)
```

Hoặc chờ cron job 2:00 AM ngày hôm sau chạy.

---

## 5. Danh sách file cần sửa (tổng hợp)

| # | File | Service | Thay đổi |
|---|---|---|---|
| 1 | `tour-catalog-service/.../repository/TourDepartureRepository.java` | tour-catalog | Thêm `increaseAvailableSlots()` |
| 2 | `tour-catalog-service/.../controller/DepartureController.java` | tour-catalog | Thêm endpoint `POST /increase-slots` |
| 3 | `booking-service/.../feign/TourCatalogFeignClient.java` | booking | Thêm `increaseSlots()` Feign method |
| 4 | `booking-service/.../service/impl/BookingServiceImpl.java` | booking | Gọi `increaseSlots()` trong `cancelBooking()` và `submitRefundRequest()` |
| 5 | `docker-compose.yml` | infra | Thêm GEMINI_API_KEY + PINECONE_* env vars |

---

## 6. Thứ tự triển khai

```
1. Sửa TourDepartureRepository → thêm increaseAvailableSlots
2. Sửa DepartureController    → thêm endpoint POST /increase-slots
3. Sửa TourCatalogFeignClient → thêm Feign method increaseSlots
4. Sửa BookingServiceImpl     → gọi increaseSlots trong cancelBooking + submitRefundRequest
5. Sửa docker-compose.yml     → thêm env vars chatbot
6. Build + deploy toàn bộ
7. Test: đặt tour → hủy tour → kiểm tra availableSlots tăng lại
8. Trigger sync Pinecone
9. Test chatbot: hỏi về số chỗ còn lại
```

---

## 7. Kiểm tra sau triển khai (Test Checklist)

### Booking Cancellation:
- [ ] Đặt tour với 2 người lớn → `availableSlots` giảm 2
- [ ] Hủy booking đó → `availableSlots` tăng lại 2
- [ ] Người khác có thể đặt 2 chỗ vừa hoàn trả
- [ ] Hủy booking trạng thái PENDING → slot hoàn trả đúng
- [ ] Gửi yêu cầu hoàn tiền (PENDING_REFUND) → slot hoàn trả đúng
- [ ] Hủy booking trạng thái CONFIRMED → slot hoàn trả đúng

### Chatbot:
- [ ] Docker container analytics-service khởi động không lỗi API key
- [ ] Gọi `POST /api/chatbot/chat` với message "tour còn chỗ không" → có kết quả
- [ ] Gọi `POST /api/chatbot/admin/sync` → sync thành công lên Pinecone
- [ ] Sau sync, chatbot trả đúng số chỗ trống

### Regression:
- [ ] Tạo booking mới vẫn giảm slot đúng
- [ ] Coin refund khi hủy vẫn chạy qua outbox
- [ ] Notification vẫn gửi khi hủy

---

## 8. Ghi chú kỹ thuật

### Về `seatCount` khi hoàn slot:
- `Booking` entity có `List<BookingPassenger> passengers` (OneToMany, cascade ALL)
- Infant không chiếm ghế → chỉ đếm ADULT + CHILD + TODDLER
- Cần đảm bảo `passengers` được fetch trước khi đếm (tránh LazyInitializationException)
- Có thể dùng `booking.getTotalPassengers()` nếu nó không đếm infant, hoặc đếm từ list

### Về idempotency của `increaseSlots`:
- Không có cơ chế idempotency cho slot increase hiện tại
- Nếu `cancelBooking()` được gọi 2 lần (race condition) → slot tăng 2 lần
- Nên check `booking.getBookingStatus() == CANCELLED` trước khi tăng slot (đã có check này)

### Về chatbot + stale Pinecone data:
- Pinecone chứa snapshot tại thời điểm sync (2:00 AM) — không real-time
- Sau fix bug slot, cần trigger manual sync 1 lần để data đúng ngay

---

*Tài liệu này được tạo tự động từ phân tích code ngày 2026-05-18.*  
*Tham chiếu: booking-service commit `186b38c`, analytics-service commit `9f73621`*
