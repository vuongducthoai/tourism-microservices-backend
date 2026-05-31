# BÁO CÁO TRIỂN KHAI CHATBOT STATEFUL BOOKING FLOW
**Dự án:** Tourism Microservices Backend  
**Ngày:** 25/05/2026  
**Phiên bản:** v3.1  
**Dựa trên kế hoạch:** `CHATBOT_PLAN_v3.1_2026-05-25.md`

---

## 1. TỔNG QUAN

Hệ thống chatbot được nâng cấp từ **stateless RAG chatbot** lên **stateful booking flow chatbot**, cho phép người dùng:
- Tìm kiếm tour theo điểm đến, số lượng hành khách
- Chọn tour và ngày khởi hành cụ thể
- Nhập thông tin hành khách và liên hệ
- Xác nhận đặt tour và thanh toán qua PayOS
- Tra cứu thông tin đơn hàng theo mã booking

---

## 2. KIẾN TRÚC HỆ THỐNG

### 2.1 Luồng xử lý

```
User Message → API Gateway (:8080)
               → analytics-service (:8087)
                  → ChatbotService
                     ├── BookingConversationService (stateful)
                     │    ├── RedisSessionService ← Redis
                     │    ├── VectorService ← Pinecone
                     │    ├── TourCatalogFeignClient → tour-catalog-service (:8081)
                     │    ├── ChatbotBookingFeignClient → booking-service (:8083)
                     │    └── ChatbotPaymentFeignClient → payment-service (:8086)
                     └── RAG (Gemini + Pinecone) — fallback khi IDLE
```

### 2.2 State Machine

```
IDLE → COLLECTING_SEARCH_INFO → SHOWING_SEARCH_RESULTS → SELECTING_DEPARTURE
     → COLLECTING_PASSENGERS → COLLECTING_CONTACT_NAME_PHONE → COLLECTING_CONTACT_EMAIL
     → CONFIRMING_BOOKING → BOOKING_SUCCESS
IDLE → COLLECTING_LOOKUP_CODE → (lookup) → IDLE
```

Session state được lưu trong Redis với TTL 30 phút. Key format: `chatbot:session:{sessionId}`.

---

## 3. TRIỂN KHAI BACKEND (analytics-service)

### 3.1 Dependencies thêm vào `pom.xml`
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### 3.2 Các class mới/sửa đổi

| Class | Trạng thái | Mô tả |
|-------|-----------|-------|
| `ConversationState.java` | Mới | State machine với 9 stage + inner classes PassengerData, TourGroupDisplay, DepartureMeta |
| `BookingConversationService.java` | Mới | Service xử lý toàn bộ booking flow stateful |
| `RedisSessionService.java` | Mới | Redis session với TTL 30 phút, serialize JSON |
| `ChatbotConfig.java` | Sửa | Thêm ObjectMapper bean với JavaTimeModule |
| `ChatbotService.java` | Sửa | Thêm stateful dispatch, giữ RAG fallback |
| `ChatMessageResponse.java` | Sửa | Thêm fields: messageType, conversationStage, bookingConfirmData, orderDetail |
| `TourCatalogFeignClient.java` | Sửa | Thêm `getDepartureOrderInfo(departureId)` |
| `ChatbotBookingFeignClient.java` | Mới | Feign client gọi booking-service |
| `ChatbotPaymentFeignClient.java` | Mới | Feign client gọi payment-service |

### 3.3 DTOs mới

| DTO | Mô tả |
|-----|-------|
| `BookingConfirmData.java` | Dữ liệu card xác nhận đặt tour |
| `ChatbotCreateBookingRequest.java` | Request tạo booking |
| `ChatbotCreateBookingResponse.java` | Response sau khi tạo booking |
| `ChatbotBookingDetailResponse.java` | Chi tiết đơn hàng (tra cứu) |
| `PayosCreateRequest.java` | Request tạo link thanh toán PayOS |
| `PaymentUrlResponse.java` | Response chứa checkout URL |
| `ChatbotDepartureInfoResponse.java` | Thông tin giá từ tour-catalog |

### 3.4 Booking Flow Logic

**IDLE** - Phát hiện intent:
- Booking: regex `đặt tour|dat tour|book tour|tìm tour|...` 
- Lookup: regex `tra cứu|kiểm tra` hoặc có mã BKxxxxxxxx

**COLLECTING_SEARCH_INFO** - Parse từ tin nhắn đầu tiên:
- Điểm đến: match với danh sách 20+ địa danh phổ biến
- Số người: regex `N người lớn|N trẻ em|...`
- Ngày: tháng 6/7/8, tuần sau, etc.

**SHOWING_SEARCH_RESULTS** - Tìm kiếm Pinecone:
- Query embedding → tìm 12 TOUR_DEPARTURE docs gần nhất
- Group theo tourId → hiển thị 3 tour đề xuất với ngày khởi hành

**SELECTING_DEPARTURE** - Người dùng chọn ngày (dd/MM hoặc dd/MM/yyyy):
- Gọi `GET /api/departures/order-info?departureId=X` để lấy bảng giá đầy đủ
- Init danh sách PassengerData

**COLLECTING_PASSENGERS** - Thu thập từng hành khách:
- Format: "Họ tên, Giới tính" (ví dụ: "Nguyễn Văn A, Nam")
- dateOfBirth tự động: ADULT=1990-01-01, CHILD=2015-06-01, TODDLER=2022-06-01, INFANT=2025-01-01

**COLLECTING_CONTACT_NAME_PHONE** / **COLLECTING_CONTACT_EMAIL** - Thông tin liên hệ

**CONFIRMING_BOOKING** - Hiện card xác nhận với tổng tiền ước tính

**BOOKING_SUCCESS** - Sau xác nhận:
1. Gọi `POST /api/bookings/create` → lấy bookingCode, bookingId
2. Gọi `POST /api/payment/payos/create` → lấy checkout URL
3. Trả về BOOKING_SUCCESS với link thanh toán PayOS

### 3.5 Cấu hình `application.yml` thêm
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 3000ms
app:
  frontend-url: ${FRONTEND_URL:http://localhost:3000}
```

---

## 4. TRIỂN KHAI FRONTEND (tourism_frontend)

### 4.1 Component mới

| Component | File | Mô tả |
|-----------|------|-------|
| BookingConfirmCard | `BookingConfirmCard.jsx` + `.module.scss` | Card xác nhận đặt tour với bảng giá, nút Xác nhận/Hủy |
| OrderDetailCard | `OrderDetailCard.jsx` + `.module.scss` | Card chi tiết đơn hàng: trạng thái, giá, danh sách hành khách |
| BookingSuccessCard | `BookingSuccessCard.jsx` + `.module.scss` | Card thành công với bookingCode và link PayOS |

### 4.2 ChatbotWidget.jsx sửa đổi
- Import 3 component mới
- Hàm `sendBotMessage(text)` — gửi tin nhắn từ quick actions
- Hàm `handleQuickAction(action)` — xử lý: CONFIRM_BOOKING, CANCEL, RESET_SEARCH, NEW_BOOKING, LOOKUP_*
- Render có điều kiện theo `messageType`:
  - `TOUR_SUGGESTIONS` → TourSuggestionCard list
  - `BOOKING_CONFIRM` → BookingConfirmCard
  - `ORDER_DETAIL` → OrderDetailCard
  - `BOOKING_SUCCESS` → BookingSuccessCard
  - `TEXT` → text bubble thông thường

---

## 5. DOCKER & DEPLOY

### 5.1 docker-compose.yml thay đổi
```yaml
analytics-service:
  environment:
    - REDIS_HOST=redis
    - REDIS_PORT=6379
  depends_on:
    redis:
      condition: service_healthy
```

### 5.2 Build & Deploy
```powershell
# Build
$mvn = "D:\Setting\MavenHome\apache-maven-3.9.6\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f analytics-service/pom.xml clean package -DskipTests

# Deploy
docker-compose up -d --build analytics-service
```

---

## 6. KẾT QUẢ KIỂM THỬ

### 6.1 API Testing (curl/PowerShell)

| Test Case | Input | Expected | Result |
|-----------|-------|----------|--------|
| RAG fallback | "xin chao" | TEXT[IDLE] | ✅ PASS |
| Booking intent | "dat tour da nang 2 nguoi lon" | TOUR_SUGGESTIONS[SHOWING_SEARCH_RESULTS] | ✅ PASS |
| Tour selection | "2" | TEXT[SELECTING_DEPARTURE] — hiển thị ngày KH | ✅ PASS |
| Departure date | "15/05" | TEXT[COLLECTING_PASSENGERS] | ✅ PASS |
| Passenger 1 | "Nguyen Van An, Nam" | TEXT[COLLECTING_PASSENGERS] | ✅ PASS |
| Passenger 2 | "Tran Thi Bich, Nu" | TEXT[COLLECTING_CONTACT_EMAIL] | ✅ PASS |
| Email | "antest@gmail.com" | BOOKING_CONFIRM[CONFIRMING_BOOKING] | ✅ PASS |
| Confirm | "Xac nhan" | BOOKING_SUCCESS — link PayOS | ✅ PASS |
| Lookup | "tra cuu BKxxxxxxxx" | ORDER_DETAIL[IDLE] | ✅ PASS |
| Cancel | "huy" (trong bất kỳ stage) | TEXT[IDLE] "Đã hủy..." | ✅ PASS |

### 6.2 Sample BOOKING_SUCCESS Response
```json
{
  "messageType": "BOOKING_SUCCESS",
  "conversationStage": "BOOKING_SUCCESS",
  "reply": "✅ Đặt tour thành công!\n\n🎫 Mã đặt tour: BKc2ca3ff9\n💰 Tổng tiền: 14,000,000đ\n...",
  "quickActions": [
    {"label": "🔍 Xem đơn hàng", "action": "LOOKUP_BKc2ca3ff9"},
    {"label": "🏖️ Đặt tour khác", "action": "NEW_BOOKING"}
  ]
}
```

### 6.3 Sample ORDER_DETAIL Response
```json
{
  "messageType": "ORDER_DETAIL",
  "conversationStage": "IDLE",
  "orderDetail": {
    "bookingId": 17,
    "bookingCode": "BKc2ca3ff9",
    "status": "PENDING_PAYMENT",
    "originalPrice": 14000000.00,
    "passengers": [...]
  }
}
```

---

## 7. VẤN ĐỀ ĐÃ XỬ LÝ

| Vấn đề | Nguyên nhân | Giải pháp |
|--------|-------------|-----------|
| Build lỗi `ChatbotDepartureInfoResponse` | Missing explicit import trong `BookingConversationService` | Thêm `import com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse` |
| 503 sau restart analytics | Eureka cache stale | `docker-compose restart api-gateway` |
| "dat tour" không match | Regex chỉ có unicode "đặt" | Thêm `dat\\s*tour`, `muon\\s*dat`, `tim\\s*tour` |
| "Xac nhan" không match | Regex chỉ có "xác nhận" unicode | Thêm `xac\\s*nhan`, `dong\\s*y` |
| Lookup trả về null tourName | `ChatbotBookingDetailResponse.originalPrice` là Long nhưng API trả BigDecimal | Đổi sang `BigDecimal`, thêm overload `fmt(BigDecimal)` |
| Booking bean conflict | Hai FeignClient cùng name `booking-service` | Thêm `contextId = "chatbotBookingClient"` |

---

## 8. HẠN CHẾ VÀ HƯỚNG PHÁT TRIỂN

### Hạn chế hiện tại
1. **tourName trong lookup** trả về `null` - do booking-service API `/api/bookings/payment/{code}` không join tour data đầy đủ
2. **Chỉ parse ngày dd/MM**, chưa hỗ trợ "ngày 15 tháng 5", "15 tháng năm"
3. **Xử lý lỗi PayOS**: nếu tạo link thanh toán thất bại, booking vẫn được tạo nhưng không có link
4. **Không hỗ trợ sửa thông tin** hành khách sau khi đã nhập

### Hướng phát triển
- Thêm tính năng sửa thông tin trước khi xác nhận
- Hỗ trợ coupon/mã giảm giá qua chatbot
- Push notification khi trạng thái booking thay đổi
- Tích hợp AI để parse thông tin hành khách tự nhiên hơn
- Thêm multi-language support (EN/VI)

---

## 9. CẤU TRÚC FILE

```
analytics-service/src/main/java/com/tourism/analytics/
├── dto/
│   ├── chatbot/
│   │   ├── ConversationState.java          [MỚI]
│   │   ├── BookingConfirmData.java          [MỚI]
│   │   ├── ChatbotCreateBookingRequest.java [MỚI]
│   │   ├── ChatbotCreateBookingResponse.java[MỚI]
│   │   ├── ChatbotBookingDetailResponse.java[MỚI]
│   │   ├── PayosCreateRequest.java          [MỚI]
│   │   └── PaymentUrlResponse.java          [MỚI]
│   └── feign/
│       └── ChatbotDepartureInfoResponse.java[MỚI]
├── feign/
│   ├── ChatbotBookingFeignClient.java       [MỚI]
│   ├── ChatbotPaymentFeignClient.java       [MỚI]
│   └── TourCatalogFeignClient.java          [SỬA]
├── service/
│   ├── BookingConversationService.java      [MỚI]
│   ├── RedisSessionService.java             [MỚI]
│   └── ChatbotService.java                  [SỬA]
└── config/
    └── ChatbotConfig.java                   [SỬA]

tourism_frontend/client-side/src/components/ChatbotWidget/
├── ChatbotWidget.jsx                        [SỬA]
├── BookingConfirmCard.jsx                   [MỚI]
├── BookingConfirmCard.module.scss           [MỚI]
├── OrderDetailCard.jsx                      [MỚI]
├── OrderDetailCard.module.scss              [MỚI]
├── BookingSuccessCard.jsx                   [MỚI]
└── BookingSuccessCard.module.scss           [MỚI]
```

---

*Báo cáo này được tạo tự động dựa trên quá trình triển khai thực tế.*
