# CHATBOT UPGRADE PLAN v3.1 — Future Travel Microservices
**Ngày:** 2026-05-25  
**Phiên bản:** 3.1 — Đọc sâu code: BookingServiceImpl, CreateBookingRequest, BookingPassenger entity, PaymentGatewayController, VectorSyncService (Pinecone metadata), ChatbotService (analytics-service)  
**Trạng thái:** PLANNING ONLY — chưa implement  
**Tác giả:** Phân tích bởi AI dựa trên code thực tế của hệ thống

---

## MỤC LỤC

1. [Phân tích hệ thống hiện tại](#1-phân-tích-hệ-thống-hiện-tại)
2. [Pinecone Metadata — Phát hiện quan trọng](#2-pinecone-metadata--phát-hiện-quan-trọng)
3. [API Reference chính xác từ code](#3-api-reference-chính-xác-từ-code)
4. [Booking Flow State Machine](#4-booking-flow-state-machine)
5. [ConversationState — Slots cần lưu](#5-conversationstate--slots-cần-lưu)
6. [Thu thập thông tin — Chi tiết từng bước](#6-thu-thập-thông-tin--chi-tiết-từng-bước)
7. [Backend Logic — ChatbotService Dispatch](#7-backend-logic--chatbotservice-dispatch)
8. [Feign Clients mới cần thêm vào analytics-service](#8-feign-clients-mới-cần-thêm-vào-analytics-service)
9. [Order Lookup Flow](#9-order-lookup-flow)
10. [Redis Session + Chat History](#10-redis-session--chat-history)
11. [RabbitMQ Debounce Sync (Pinecone)](#11-rabbitmq-debounce-sync-pinecone)
12. [Frontend — ChatbotWidget + BookingConfirmCard](#12-frontend--chatbotwidget--bookingconfirmcard)
13. [Roadmap 4 Phase](#13-roadmap-4-phase)
14. [Danh sách file cần tạo/sửa](#14-danh-sách-file-cần-tạosửa)
15. [Ràng buộc dữ liệu quan trọng](#15-ràng-buộc-dữ-liệu-quan-trọng)

---

## 1. Phân tích hệ thống hiện tại

### 1.1 analytics-service — ChatbotService (stateless RAG)

Hiện tại `ChatbotService.java` hoàn toàn **stateless**, không có session state:

```
ChatMessageRequest { message, sessionId, userId }
  → keyword detect (DISCOUNT_PATTERN, COUPON_PATTERN, TOUR_DETAIL_PATTERN)
  → nếu không match → Pinecone search top-3-5 docs
  → build context string
  → Gemini generate reply
  → return ChatMessageResponse { reply, tourSuggestions, quickActions, sessionId, timestamp }
```

**Hệ quả:** Bot không thể nhớ "user đang ở bước nào của luồng đặt tour." Mọi turn là độc lập.

### 1.2 Luồng hiện tại không có

- ❌ Booking-in-chat (đặt tour trong cửa sổ chat)
- ❌ Thanh toán inline
- ❌ Tra cứu đơn hàng
- ❌ Hỏi về giá CHILD/TODDLER (Pinecone chỉ có adultSalePrice)
- ❌ Feign client tới booking-service/payment-service từ analytics

### 1.3 Những gì đã có, tận dụng được

| Có sẵn | Dùng cho |
|--------|----------|
| Pinecone TOUR_DEPARTURE index với `departureID` trong metadata | Sau khi user chọn tour+ngày → đã có `departureId`, không cần hỏi thêm |
| TourCatalogFeignClient (chatbot-sync endpoints) | Sync tours, locations vào Pinecone |
| `GET /api/departures/order-info?departureId=X` trong tour-catalog-service | Lấy adultPrice, childPrice, toddlerPrice, infantPrice, singleRoomSurcharge |
| `GET /api/bookings/payment/{bookingCode}` (NO JWT) | Tra cứu đơn hàng nhanh |
| PayOS/VNPay payment endpoints | Tạo link thanh toán |

---

## 2. Pinecone Metadata — Phát hiện quan trọng

Đọc `VectorSyncService.java` trong analytics-service, phương thức `syncTourDeparture()`:

```java
// Document type: "TOUR_DEPARTURE"
meta.put("tourId", tour.getTourId());
meta.put("tourCode", tour.getTourCode());
meta.put("tourName", tour.getTourName());
meta.put("imageUrl", tour.getImageUrl());
meta.put("duration", tour.getDuration());
meta.put("startLocationName", dep.getStartLocationName());
meta.put("startLocationID", dep.getStartLocationID());
meta.put("endLocationName", dep.getEndLocationName());
meta.put("endLocationID", dep.getEndLocationID());
meta.put("departureID", dep.getDepartureID());     // ← KEY: có sẵn!
meta.put("departureDate", dep.getDepartureDate().toString());
meta.put("availableSlots", dep.getAvailableSlots());
meta.put("salePrice", dep.getSalePrice());          // ← CHỈ là adultSalePrice
meta.put("originalPrice", dep.getOriginalPrice());
meta.put("couponCode", dep.getCouponCode());
meta.put("couponDiscount", dep.getCouponDiscount());
```

### Hệ quả thiết kế quan trọng

1. **`departureID` đã có trong Pinecone** → khi user tìm kiếm tour qua chat, mỗi kết quả đã mang `departureID`. Chatbot KHÔNG cần thêm bước "fetch departure list."

2. **`salePrice` = adultSalePrice ONLY** → để hiển thị giá cho CHILD, TODDLER, INFANT phải gọi thêm:
   ```
   GET /api/departures/order-info?departureId={departureId}
   → TourBookingInfoResponse {
       adultPrice, childPrice, toddlerPrice, infantPrice,
       singleRoomSurcharge, availableSlots, tourName, tourCode, image
     }
   ```
   Endpoint này đã tồn tại trong `tour-catalog-service/DepartureController.java` nhưng **chưa được thêm vào `TourCatalogFeignClient.java` của analytics-service.**

3. **Grouping khi hiển thị:** Pinecone trả về docs TOUR_DEPARTURE (mỗi departure là 1 doc). Frontend cần nhóm theo `tourId`, hiển thị tối đa 3 tour cards, mỗi card có danh sách date chips từ các departure của tour đó.

---

## 3. API Reference chính xác từ code

### 3.1 Tạo booking — `POST /api/bookings/create`

**Service:** booking-service (:8083)  
**Auth:** Bearer token (nếu có userId) hoặc không cần (guest booking khi `userId = null`)

**Request body — `CreateBookingRequest.java`:**
```json
{
  "departureId": 42,               // Integer, REQUIRED
  "userId": null,                  // Integer, NULLABLE → null = guest booking ✅
  "contactFullName": "Nguyễn Văn A",  // String, @NotBlank (validation ở Booking entity)
  "contactPhone": "0901234567",    // String, @NotBlank
  "contactEmail": "a@gmail.com",   // String, @NotBlank @Email ← PHẢI THU THẬP
  "contactAddress": "Đặt qua chatbot", // String, @NotBlank → chatbot auto-fill
  "customerNote": "",              // String, optional
  "passengers": [                  // List<PassengerRequest>, REQUIRED
    {
      "fullName": "Nguyễn Văn A",
      "gender": "MALE",            // "MALE" | "FEMALE" | "OTHER"
      "dateOfBirth": "1990-01-01", // String "YYYY-MM-DD", NOT NULL in DB
      "type": "ADULT",             // "ADULT" | "CHILD" | "TODDLER" | "INFANT"
      "singleRoom": false          // boolean
    }
  ],
  "couponCode": [],                // List<String>, optional → chatbot không collect
  "pointsUsed": 0                  // Integer, optional → chỉ dùng nếu có userId
}
```

**Response — `CreateBookingResponse.java`:**
```json
{
  "bookingCode": "BK3f7a9c12",    // "BK" + UUID.substring(0,8) — tạo bởi @PrePersist
  "bookingId": 101,
  "totalPrice": 12500000,
  "status": "PENDING_PAYMENT"
}
```

**Logic trong `BookingServiceImpl.java`:**
- Gọi `tourCatalogClient.getOrderInfo(null, departureId)` để lấy đơn giá
- Tính `subtotalPrice = Σ(passenger.basePrice theo type)` — bot KHÔNG cần tính đúng, server tự tính
- INFANT passengers **KHÔNG tính** vào `availableSlots`
- Đặt `bookingStatus = PENDING_PAYMENT` lúc tạo
- `paymentDeadline = createdDate + 24h`

---

### 3.2 Chi tiết thanh toán booking — `GET /api/bookings/payment/{bookingCode}`

**Service:** booking-service (:8083)  
**Auth:** ❌ KHÔNG cần JWT — public endpoint

**Response — `BookingPaymentDetailResponse.java`:**
```json
{
  "bookingId": 101,
  "bookingCode": "BK3f7a9c12",
  "createdDate": "2026-05-25T10:00:00",
  "status": "PENDING_PAYMENT",
  "originalPrice": 12500000,
  "paidAmount": 0,
  "remainingAmount": 12500000,
  "paymentDeadline": "2026-05-26T10:00:00",
  "appliedCouponCodes": [],
  "tourName": "Tour Đà Nẵng 4N3Đ",
  "tourCode": "NDH555",
  "tourImage": "https://...",
  "duration": "4N3Đ",
  "outboundTransport": "Máy bay",
  "inboundTransport": "Máy bay",
  "passengers": [...]
}
```

---

### 3.3 Thông tin giá theo loại khách — `GET /api/departures/order-info?departureId={id}`

**Service:** tour-catalog-service (:8081)  
**Auth:** Không cần

**Response — `TourBookingInfoResponse`:**
```json
{
  "adultPrice": 4990000,
  "childPrice": 3990000,
  "toddlerPrice": 1990000,
  "infantPrice": 500000,
  "singleRoomSurcharge": 800000,
  "availableSlots": 15,
  "tourName": "Tour Đà Nẵng 4N3Đ",
  "tourCode": "NDH555",
  "image": "https://..."
}
```

> **Lưu ý:** Endpoint đã có trong tour-catalog-service nhưng **chưa** có trong `TourCatalogFeignClient.java` của analytics-service → cần thêm.

---

### 3.4 Tạo link thanh toán PayOS — `POST /api/payment/payos/create`

**Service:** payment-service (:8086)  
**Auth:** Không cần (public từ gateway)

**Request — `PayosCreateRequest.java`:**
```json
{
  "bookingCode": "BK3f7a9c12",
  "amount": 12500000,
  "description": "Thanh toan tour BK3f7a9c12",
  "returnUrl": "https://yourdomain.com/payment/success",
  "cancelUrl": "https://yourdomain.com/payment/cancel"
}
```

**Response — `PaymentUrlResponse`:**
```json
{
  "checkoutUrl": "https://pay.payos.vn/web/..."
}
```

---

### 3.5 Tạo link thanh toán VNPay — `POST /api/payment/vnpay/create`

**Service:** payment-service (:8086)

**Request:**
```json
{
  "bookingCode": "BK3f7a9c12",
  "amount": 12500000,
  "bankCode": "NCB",
  "language": "vn"
}
```

**Response:**
```json
{
  "paymentUrl": "https://sandbox.vnpayment.vn/..."
}
```

---

### 3.6 Danh sách booking của user — `GET /api/bookings/user/{userId}`

**Service:** booking-service (:8083)  
**Auth:** Bearer token

**Response:** `List<BookingResponse>` — danh sách tóm tắt booking của user

---

### 3.7 BookingStatus enum (chính xác từ `BookingStatus.java`)

```
PENDING_PAYMENT       → Chờ thanh toán (24h)
OVERDUE_PAYMENT       → Quá hạn thanh toán
PENDING_CONFIRMATION  → Đã thanh toán, chờ xác nhận
PAID                  → Đã thanh toán và xác nhận
CANCELLED             → Đã hủy
PENDING_REVIEW        → Tour đã hoàn thành, chờ đánh giá
REVIEWED              → Đã đánh giá
PENDING_REFUND        → Đang chờ hoàn tiền
```

---

### 3.8 PassengerType enum (chính xác từ `PassengerType.java`)

```
ADULT            → Người lớn (từ 12 tuổi)
CHILD            → Trẻ em (2–11 tuổi)
TODDLER          → Trẻ nhỏ (1–2 tuổi)
INFANT           → Em bé (dưới 1 tuổi) — KHÔNG tính vào availableSlots
SINGLE_SUPPLEMENT → Phụ thu phòng đơn (loại đặc biệt, chatbot dùng flag singleRoom=true)
```

---

## 4. Booking Flow State Machine

```
IDLE
  ↓ user nói "đặt tour" / "book tour" / "tôi muốn đi..."
COLLECTING_SEARCH_INFO          ← hỏi destination, date range, số người
  ↓ đủ info → gọi Pinecone search
SHOWING_SEARCH_RESULTS          ← hiển thị max 3 tour cards
  ↓ user chọn tour (số 1/2/3 hoặc tên)
SELECTING_DEPARTURE             ← hiển thị date chips của tour đã chọn
  ↓ user chọn ngày khởi hành
FETCHING_PRICING                ← gọi /api/departures/order-info?departureId=X
  ↓ có đủ giá theo loại
COLLECTING_PASSENGERS           ← hỏi thông tin từng hành khách (bắt đầu từ adult 1)
  ↓ đủ hành khách
COLLECTING_CONTACT              ← hỏi họ tên + SĐT (cùng 1 tin), sau đó hỏi email
  ↓ có contactName + contactPhone + contactEmail
CONFIRMING_BOOKING              ← hiển thị BookingConfirmCard với đầy đủ thông tin + tổng tiền ước tính
  ↓ user xác nhận "Đặt ngay" / "OK" / "Xác nhận"
CREATING_BOOKING                ← gọi POST /api/bookings/create
  ↓ thành công → có bookingCode
CREATING_PAYMENT_LINK           ← gọi POST /api/payment/payos/create
  ↓ thành công → có checkoutUrl
BOOKING_SUCCESS                 ← hiển thị bookingCode + link thanh toán + nhắc 24h
  ↓ user nói gì khác
IDLE

Nhánh hủy:
Bất kỳ stage nào → user nói "hủy" / "thôi" / "thoát" → IDLE + xóa state
```

---

## 5. ConversationState — Slots cần lưu

```typescript
interface ConversationState {
  // Stage
  stage: BookingStage;   // enum 11 stage ở trên

  // Search
  searchDestination?: string;
  searchDateRange?: string;
  searchAdults: number;
  searchChildren: number;
  searchToddlers: number;
  searchInfants: number;

  // Tour đã chọn (từ Pinecone metadata)
  selectedTourId?: number;
  selectedTourCode?: string;
  selectedTourName?: string;
  selectedTourImage?: string;
  selectedDuration?: string;          // "4N3Đ"
  departureCity?: string;             // "TP. Hồ Chí Minh"

  // Departure đã chọn (departureID có trong Pinecone!)
  selectedDepartureId?: number;
  departureDateDisplay?: string;      // "18/06/2026" — hiển thị cho user
  departureDateRaw?: string;          // "2026-06-18" — dùng trong API

  // Pricing (lấy từ /api/departures/order-info)
  adultPrice?: number;
  childPrice?: number;
  toddlerPrice?: number;
  infantPrice?: number;
  singleRoomSurcharge?: number;
  availableSlots?: number;

  // Passengers (thu thập từng người)
  passengers: PassengerData[];
  currentPassengerIndex: number;

  // Contact
  contactName?: string;
  contactPhone?: string;
  contactEmail?: string;

  // Kết quả booking
  bookingCode?: string;
  bookingId?: number;
  totalPrice?: number;
  paymentUrl?: string;
  paymentDeadline?: string;

  // Order lookup
  lookupCode?: string;

  // Danh sách tour từ kết quả search gần nhất (để user chọn 1/2/3)
  lastSearchResults?: TourGroupDisplay[];

  // Pinecone departures cache cho tour đã chọn
  lastDepartures?: PineconeDepartureDoc[];
}

interface PassengerData {
  type: "ADULT" | "CHILD" | "TODDLER" | "INFANT";
  index: number;          // 1-based trong loại (adult 1, adult 2, child 1...)
  fullName?: string;
  gender?: string;        // "MALE" | "FEMALE" | "OTHER"
  dateOfBirth?: string;   // "YYYY-MM-DD" — dùng placeholder nếu user không cung cấp
  singleRoom: boolean;
}
```

---

## 6. Thu thập thông tin — Chi tiết từng bước

### Bước 1: Detect booking intent

**Trigger keywords:** "đặt tour", "book tour", "muốn đi", "tôi cần đặt", "đặt chỗ"

**Bot hỏi (1 tin):**
```
Bạn muốn đặt tour đi đâu? Cho tôi biết:
1. Điểm đến (ví dụ: Đà Nẵng, Phú Quốc, Hội An...)
2. Khoảng thời gian dự kiến (ví dụ: tháng 6, tuần sau...)
3. Số người: mấy người lớn? Có trẻ em không?
```

**Parse từ reply của user:**
- Destination: NLP extract địa danh
- Date: NLP extract tháng/ngày/tuần
- Số người: số chữ số + từ "người lớn", "trẻ em", "em bé"

---

### Bước 2: Tìm kiếm Pinecone → Hiển thị kết quả

**Chatbot gọi:** Pinecone vector search với query = `"tour {destination} {dateHint} {adults} người lớn"`  
**Filter:** `{ "type": "TOUR_DEPARTURE", "availableSlots": { "$gte": totalPeople } }`  
**Top-K:** 9 (sau đó group theo tourId → tối đa 3 tours, mỗi tour tối đa 3 departure dates)

**Nhóm kết quả:**
```javascript
// Group Pinecone docs by tourId
const tourGroups = groupBy(docs, d => d.metadata.tourId);
// Lấy tối đa 3 tourGroups
// Mỗi tourGroup: hiển thị tourName, duration, startLocation, salePrice (adult)
// + list date chips từ departures của tour đó
```

**Lưu vào state:**
- `lastSearchResults` = tourGroups (để user chọn số 1/2/3)
- `lastDepartures` = tất cả departure docs (để lookup departureID khi user chọn ngày)

**Response format:**
```
Tôi tìm được 3 tour phù hợp cho bạn:

[Tour 1] 🏖️ Tour Đà Nẵng – Hội An 4N3Đ
  ✈️ TP. HCM → Đà Nẵng | 4 ngày 3 đêm
  💰 Từ 4.990.000đ/người lớn
  📅 Ngày khởi hành: [18/06] [25/06] [02/07]

[Tour 2] ...
[Tour 3] ...

Bạn thích tour nào? (nhập 1, 2 hoặc 3)
```

**Message type:** `TOUR_SUGGESTIONS` → frontend render TourCard components

---

### Bước 3: User chọn tour → Chọn ngày khởi hành

**Parse input:** "1" → chọn tour đầu tiên; "tour 2" → tour thứ 2; tên tour → fuzzy match

**Bot hiển thị:** date chips của tour được chọn (từ `lastDepartures`)

```
Bạn đã chọn: Tour Đà Nẵng – Hội An 4N3Đ
Chọn ngày khởi hành:
  [18/06/2026 — còn 15 chỗ]  [25/06/2026 — còn 8 chỗ]  [02/07/2026 — còn 20 chỗ]
```

**Khi user chọn ngày:**
- Tìm departure doc trong `lastDepartures` theo `departureDate`
- Extract `departureID` từ metadata → lưu `selectedDepartureId`
- Chuyển sang bước 4

---

### Bước 4: Fetch pricing chi tiết

**Gọi:** `GET /api/departures/order-info?departureId={selectedDepartureId}`

**Lưu vào state:** `adultPrice, childPrice, toddlerPrice, infantPrice, singleRoomSurcharge`

**Kiểm tra slots:** Nếu `availableSlots < searchAdults + searchChildren` → báo lỗi, hỏi lại

---

### Bước 5: Thu thập thông tin hành khách

**Số lượng hành khách** = `searchAdults + searchChildren + searchToddlers + searchInfants`

**Thu thập từng người:**
```
Hành khách 1 (Người lớn):
Cho tôi biết họ tên đầy đủ và giới tính (ví dụ: Nguyễn Văn A, Nam)
```

**Parse từ reply:**
- fullName: phần trước dấu phẩy
- gender: "Nam/Nữ/Khác" → map sang "MALE"/"FEMALE"/"OTHER"

**Ngày sinh:** Bot **không hỏi** ngày sinh (để UX đơn giản). Dùng placeholder:
- ADULT → `"1990-01-01"`
- CHILD → `"2015-06-01"`
- TODDLER → `"2022-06-01"`
- INFANT → `"2025-01-01"`

**Phòng đơn:** Nếu adults > 1, hỏi: "Ai cần đặt phòng đơn không? (phụ thu +800.000đ)"

**Thu thập tuần tự:** adult 1 → adult 2 → ... → child 1 → child 2 → ... → toddler → infant

---

### Bước 6: Thu thập thông tin liên hệ

**Tin 1 (gộp họ tên + SĐT):**
```
Cho tôi biết thông tin người liên hệ:
Họ tên và số điện thoại (ví dụ: Nguyễn Thị B, 0901234567)
```

**Tin 2 (email — bắt buộc vì `@Email @NotBlank` trên Booking entity):**
```
Địa chỉ email để nhận xác nhận đặt tour?
```

**Validate email:** regex cơ bản trước khi gọi API

---

### Bước 7: Hiển thị BookingConfirmCard

**Bot reply:**
```
📋 XÁC NHẬN ĐẶT TOUR

🏖️ Tour Đà Nẵng – Hội An 4N3Đ
📅 Khởi hành: 18/06/2026 | TP. HCM → Đà Nẵng
⏱️ Thời gian: 4 ngày 3 đêm

👥 Hành khách:
  • Người lớn × 2: 2 × 4.990.000đ = 9.980.000đ
  • Trẻ em × 1: 1 × 3.990.000đ = 3.990.000đ

👤 Người liên hệ: Nguyễn Thị B | 0901234567 | b@gmail.com

💰 TỔNG DỰ TÍNH: ~13.970.000đ
   (Giá chính xác sẽ được hệ thống tính và xác nhận)

⚠️ Hạn thanh toán: 24h kể từ khi đặt

[Xác nhận đặt tour] [Hủy]
```

**Message type:** `BOOKING_CONFIRM` → frontend render BookingConfirmCard component

---

### Bước 8: Gọi API tạo booking

**Sau khi user bấm "Xác nhận":**

```java
// POST /api/bookings/create
CreateBookingRequest request = {
  departureId: state.selectedDepartureId,
  userId: currentUserId,          // null nếu guest
  contactFullName: state.contactName,
  contactPhone: state.contactPhone,
  contactEmail: state.contactEmail,
  contactAddress: "Đặt qua chatbot",
  customerNote: "",
  passengers: state.passengers.map(p -> {
    fullName: p.fullName,
    gender: p.gender,
    dateOfBirth: getPlaceholderDob(p.type),  // placeholder
    type: p.type,
    singleRoom: p.singleRoom
  }),
  couponCode: [],
  pointsUsed: 0
}
```

**Nhận được:** `{ bookingCode, bookingId, totalPrice, status="PENDING_PAYMENT" }`

---

### Bước 9: Tạo link thanh toán

```java
// POST /api/payment/payos/create
{
  bookingCode: bookingCode,
  amount: totalPrice,    // từ CreateBookingResponse
  description: "Thanh toan tour " + bookingCode,
  returnUrl: "https://yourdomain.com/payment/success?code=" + bookingCode,
  cancelUrl: "https://yourdomain.com/payment/cancel?code=" + bookingCode
}
```

---

### Bước 10: Hiển thị kết quả thành công

```
✅ Đặt tour thành công!

🎫 Mã đặt tour: BK3f7a9c12
💰 Tổng tiền: 13.970.000đ
⏰ Hạn thanh toán: trước 18/06/2026 10:00

👉 [Thanh toán ngay qua PayOS]
   hoặc copy link: https://pay.payos.vn/web/...

📩 Xác nhận sẽ được gửi về b@gmail.com

Để kiểm tra đơn hàng, gõ: "tra cứu BK3f7a9c12"
```

---

## 7. Backend Logic — ChatbotService Dispatch

### 7.1 Kiến trúc mới: Stateful ChatbotService

```
ChatMessageRequest { message, sessionId, userId }
  ↓
ChatbotService.processMessage()
  ├── 1. Load ConversationState từ Redis (key = "chatbot:session:{sessionId}")
  ├── 2. Dispatch theo state.stage:
  │   ├── IDLE → detectIntent(message)
  │   │   ├── BOOKING_INTENT → stage = COLLECTING_SEARCH_INFO, hỏi destination+date+people
  │   │   ├── LOOKUP_INTENT → stage = COLLECTING_LOOKUP_CODE, hỏi mã đặt tour
  │   │   └── GENERAL_QUERY → RAG Pinecone + Gemini (giữ nguyên như cũ)
  │   ├── COLLECTING_SEARCH_INFO → parseSearchParams(message) → Pinecone search
  │   ├── SHOWING_SEARCH_RESULTS → parseTourSelection(message) → stage = SELECTING_DEPARTURE
  │   ├── SELECTING_DEPARTURE → parseDepartureSelection(message) → fetchPricing() → stage = COLLECTING_PASSENGERS
  │   ├── COLLECTING_PASSENGERS → parsePassengerInfo(message) → nếu xong → stage = COLLECTING_CONTACT
  │   ├── COLLECTING_CONTACT → parseContactInfo(message) → nếu xong → stage = CONFIRMING_BOOKING
  │   ├── CONFIRMING_BOOKING → parseConfirmation(message)
  │   │   ├── YES → createBooking() → createPaymentLink() → stage = BOOKING_SUCCESS
  │   │   └── NO / "hủy" → stage = IDLE, clear state
  │   ├── BOOKING_SUCCESS → stage = IDLE (clear booking state, giữ user prefs)
  │   ├── COLLECTING_LOOKUP_CODE → parseLookupCode(message) → lookupBooking() → trả kết quả
  │   └── * (bất kỳ stage) + "hủy"/"thôi"/"thoát" → IDLE, clear state
  ├── 3. Save ConversationState vào Redis (TTL = 30 phút)
  └── 4. Return ChatMessageResponse
```

### 7.2 Intent Detection (keyword-based + Gemini fallback)

```java
// BOOKING intents
BOOKING_KEYWORDS: ["đặt tour", "book tour", "muốn đi", "tôi cần đặt", "đặt chỗ", "mua tour", "tìm tour để đặt"]

// LOOKUP intents
LOOKUP_KEYWORDS: ["tra cứu", "kiểm tra đơn", "xem đơn", "mã đặt", "BK", "tình trạng booking"]

// CANCEL intents (trong bất kỳ stage nào)
CANCEL_KEYWORDS: ["hủy", "thôi", "thoát", "bỏ qua", "không đặt nữa", "exit"]
```

### 7.3 ChatMessageResponse — Fields mới cần thêm

**File:** `analytics-service/src/main/java/com/tourism/analytics/dto/response/ChatMessageResponse.java`

```java
// Fields hiện có:
String reply;
List<TourSuggestionDTO> tourSuggestions;
List<String> quickActions;
String sessionId;
LocalDateTime timestamp;

// Fields MỚI cần thêm:
String messageType;              // "TEXT" | "TOUR_SUGGESTIONS" | "BOOKING_CONFIRM" | "BOOKING_SUCCESS" | "ORDER_DETAIL"
BookingConfirmData bookingConfirmData;  // null nếu không phải BOOKING_CONFIRM stage
String conversationStage;        // để frontend biết đang ở đâu trong flow
```

### 7.4 BookingConfirmData DTO (mới)

```java
public class BookingConfirmData {
  String tourName;
  String tourCode;
  String tourImage;
  String duration;
  String departureDate;     // "18/06/2026"
  String departureCity;
  List<PassengerSummary> passengers;
  String contactName;
  String contactPhone;
  String contactEmail;
  long estimatedTotal;      // tổng ước tính = Σ(count × price)
  int adultCount;
  int childCount;
  int toddlerCount;
  int infantCount;
  long adultPrice;
  long childPrice;
  long toddlerPrice;
  long infantPrice;
  long singleRoomSurcharge;
}
```

---

## 8. Feign Clients mới cần thêm vào analytics-service

### 8.1 Thêm vào `TourCatalogFeignClient.java`

**File:** `analytics-service/src/main/java/com/tourism/analytics/feign/TourCatalogFeignClient.java`

```java
// Thêm method mới — endpoint đã có trong tour-catalog-service/DepartureController.java
@GetMapping("/api/departures/order-info")
ChatbotDepartureInfoResponse getDepartureOrderInfo(@RequestParam("departureId") Integer departureId);
```

**Response DTO mới cần tạo:**

```java
// analytics-service/src/main/java/com/tourism/analytics/dto/feign/ChatbotDepartureInfoResponse.java
public class ChatbotDepartureInfoResponse {
  private BigDecimal adultPrice;
  private BigDecimal childPrice;
  private BigDecimal toddlerPrice;
  private BigDecimal infantPrice;
  private BigDecimal singleRoomSurcharge;
  private Integer availableSlots;
  private String tourName;
  private String tourCode;
  private String image;
}
```

---

### 8.2 Tạo mới `ChatbotBookingFeignClient.java`

**File:** `analytics-service/src/main/java/com/tourism/analytics/feign/ChatbotBookingFeignClient.java`

```java
@FeignClient(name = "booking-service", path = "/api/bookings")
public interface ChatbotBookingFeignClient {

  // Tạo booking (MAIN)
  @PostMapping("/create")
  ChatbotCreateBookingResponse createBooking(@RequestBody ChatbotCreateBookingRequest request);

  // Tra cứu đơn hàng theo mã (NO JWT)
  @GetMapping("/payment/{bookingCode}")
  ChatbotBookingDetailResponse getBookingDetail(@PathVariable("bookingCode") String bookingCode);

  // Danh sách booking của user (cần Bearer token)
  @GetMapping("/user/{userId}")
  List<ChatbotBookingSummaryResponse> getUserBookings(@PathVariable("userId") Integer userId);
}
```

---

### 8.3 Tạo mới `ChatbotPaymentFeignClient.java`

**File:** `analytics-service/src/main/java/com/tourism/analytics/feign/ChatbotPaymentFeignClient.java`

```java
@FeignClient(name = "payment-service", path = "/api/payment")
public interface ChatbotPaymentFeignClient {

  @PostMapping("/payos/create")
  PaymentUrlResponse createPayosPayment(@RequestBody PayosCreateRequest request);

  @PostMapping("/vnpay/create")
  PaymentUrlResponse createVnpayPayment(@RequestBody VnpayCreateRequest request);
}
```

---

## 9. Order Lookup Flow

### Trigger

User nhắn: "tra cứu BK3f7a9c12" hoặc "kiểm tra đơn BK3f7a9c12" hoặc chỉ "BK3f7a9c12"

### Luồng

```
1. Parse bookingCode từ message (regex: /BK[A-Za-z0-9]{8}/)
2. Gọi GET /api/bookings/payment/{bookingCode}  ← NO JWT cần
3. Nếu found → hiển thị BookingDetailCard
4. Nếu 404 → "Không tìm thấy mã BK... Bạn có thể kiểm tra lại không?"
```

### Response hiển thị

```
📋 CHI TIẾT ĐƠN HÀNG

Mã đặt tour: BK3f7a9c12
🏖️ Tour Đà Nẵng – Hội An 4N3Đ
📅 Khởi hành: 18/06/2026
⏱️ 4 ngày 3 đêm

💰 Tổng tiền: 13.970.000đ
✅ Đã thanh toán: 0đ
🔴 Còn lại: 13.970.000đ

📌 Trạng thái: Chờ thanh toán
⏰ Hạn thanh toán: 26/05/2026 10:00

👥 Hành khách:
  • Nguyễn Văn A (Nam, Người lớn)
  • Trần Thị B (Nữ, Trẻ em)

👉 [Thanh toán ngay]
```

**Message type:** `ORDER_DETAIL` → frontend render OrderDetailCard

---

## 10. Redis Session + Chat History

### Session key schema

```
chatbot:session:{sessionId}     → ConversationState (JSON, TTL = 30 phút)
chatbot:history:{sessionId}     → List<ChatMessage> (TTL = 2 giờ, max 50 messages)
chatbot:user:{userId}:prefs     → UserPreferences (persistent)
```

### ConversationState serialization

```java
@RedisHash
public class ConversationSession {
  @Id String sessionId;
  ConversationState state;
  List<ChatMessage> history;
  @TimeToLive Long ttl = 1800L;  // 30 phút
}
```

### Khi nào reset state

| Sự kiện | Hành động |
|---------|-----------|
| User nói "hủy" / "thôi" | Reset stage = IDLE, xóa booking fields |
| Booking thành công | Reset sang IDLE sau khi hiển thị success |
| Session TTL hết (30 phút) | Redis tự xóa |
| User thoát browser | Giữ nguyên Redis TTL |

### Chat history dùng cho gì

- Cung cấp context cho Gemini ở mode RAG (tối đa 5 messages gần nhất)
- Hiển thị lại lịch sử chat khi user reload page

---

## 11. RabbitMQ Debounce Sync (Pinecone)

### Vấn đề hiện tại

Khi admin cập nhật tour/departure trong tour-catalog-service, Pinecone chưa được cập nhật real-time. VectorSyncService hiện tại chỉ sync thủ công (hoặc qua scheduled job nếu có).

### Giải pháp đề xuất

```
tour-catalog-service
  → publish event "TOUR_UPDATED" / "DEPARTURE_UPDATED" lên RabbitMQ
  
analytics-service
  → consume event
  → debounce 5 giây (để batch updates)
  → gọi lại VectorSyncService.syncTourDeparture(tourId)
```

### Exchange/Queue design

```
Exchange: tourism.events (topic)
Routing keys:
  tour.updated.{tourId}
  departure.updated.{departureId}
  tour.deleted.{tourId}
  departure.deleted.{departureId}

Queue (analytics-service):
  analytics.pinecone.sync
  Binding: tour.updated.*, departure.updated.*
  Binding: tour.deleted.*, departure.deleted.*
```

### Debounce implementation

```java
// Trong analytics-service
@RabbitListener(queues = "analytics.pinecone.sync")
public void handleTourUpdate(TourUpdateEvent event) {
  // Debounce: schedule sync sau 5s, cancel nếu có event mới cho cùng tourId
  debounceScheduler.schedule(
    "sync-tour-" + event.getTourId(),
    () -> vectorSyncService.syncTourById(event.getTourId()),
    5, TimeUnit.SECONDS
  );
}
```

---

## 12. Frontend — ChatbotWidget + BookingConfirmCard

### 12.1 ChatbotWidget changes

**File:** `tourism_frontend/client-side/src/components/Chatbot/ChatbotWidget.tsx` (hoặc tương tự)

```typescript
// Xử lý messageType mới
function renderMessage(msg: ChatMessageResponse) {
  switch (msg.messageType) {
    case "TOUR_SUGGESTIONS":
      return <TourSuggestionCards tours={msg.tourSuggestions} onSelect={handleTourSelect} />;
    case "BOOKING_CONFIRM":
      return <BookingConfirmCard data={msg.bookingConfirmData} onConfirm={handleConfirm} onCancel={handleCancel} />;
    case "ORDER_DETAIL":
      return <OrderDetailCard data={msg.orderDetailData} />;
    case "BOOKING_SUCCESS":
      return <BookingSuccessCard bookingCode={msg.bookingCode} paymentUrl={msg.paymentUrl} />;
    default:
      return <TextMessage text={msg.reply} />;
  }
}
```

### 12.2 TourSuggestionCards

```typescript
interface TourSuggestionCardProps {
  tours: TourGroupDisplay[];   // tối đa 3
  onSelect: (tourId: number, departureId: number) => void;
}

// Mỗi card:
// - Ảnh tour (tourImage từ Pinecone)
// - Tên tour + duration
// - Điểm xuất phát → điểm đến
// - Giá từ X.XXX.XXXđ/người lớn
// - Date chips (clickable) — mỗi chip = 1 departureDate + availableSlots
// - Khi click chip → gửi message "Chọn ngày [departureDate]" tự động
```

### 12.3 BookingConfirmCard

```typescript
interface BookingConfirmCardProps {
  data: BookingConfirmData;
  onConfirm: () => void;
  onCancel: () => void;
}
// Hiển thị: tour info, danh sách hành khách, contact info, bảng giá, tổng ước tính
// 2 nút: "Xác nhận đặt tour" (primary) và "Hủy" (secondary)
```

### 12.4 QuickAction buttons (cập nhật)

Thêm quick actions mới vào initial state:
```
["🔍 Tìm tour", "✈️ Đặt tour ngay", "📋 Tra cứu đơn hàng", "💡 Tư vấn", "🏷️ Khuyến mãi"]
```

---

## 13. Roadmap 4 Phase

### Phase 1 — Backend Foundation (2 tuần)

**Mục tiêu:** Chatbot biết trạng thái, có thể gọi API booking/payment

**analytics-service:**
- [ ] Thêm Redis dependency + config
- [ ] Tạo `ConversationState.java` + `ConversationSession.java` (Redis entity)
- [ ] Tạo `RedisSessionService.java` (load/save session)
- [ ] Cập nhật `ChatMessageResponse.java` (thêm messageType, bookingConfirmData, conversationStage)
- [ ] Thêm `getDepartureOrderInfo()` vào `TourCatalogFeignClient.java`
- [ ] Tạo `ChatbotBookingFeignClient.java` + request/response DTOs
- [ ] Tạo `ChatbotPaymentFeignClient.java` + request/response DTOs
- [ ] Refactor `ChatbotService.java` → `ChatbotOrchestrationService.java` với dispatch logic

**Các DTO cần tạo trong analytics-service:**
- `ConversationState.java`
- `BookingConfirmData.java`
- `ChatbotCreateBookingRequest.java` (mirror của booking-service)
- `ChatbotCreateBookingResponse.java`
- `ChatbotBookingDetailResponse.java`
- `ChatbotDepartureInfoResponse.java`
- `PassengerData.java`
- `TourGroupDisplay.java`

---

### Phase 2 — Core Booking Flow (2 tuần)

**Mục tiêu:** Hoàn thành luồng đặt tour end-to-end

**analytics-service:**
- [ ] `IntentDetectionService.java` — phát hiện booking/lookup/cancel intent
- [ ] `SearchParamParser.java` — extract destination, date, people count từ natural language
- [ ] `TourSelectionHandler.java` — xử lý user chọn tour 1/2/3
- [ ] `PassengerCollectionHandler.java` — thu thập hành khách từng người
- [ ] `ContactCollectionHandler.java` — thu thập contact info + validate email
- [ ] `BookingCreationHandler.java` — gọi API tạo booking + payment link
- [ ] `OrderLookupHandler.java` — tra cứu đơn hàng

**testing:**
- [ ] Unit test từng handler
- [ ] Integration test full booking flow

---

### Phase 3 — Frontend Components (1 tuần)

**tourism_frontend:**
- [ ] `BookingConfirmCard.tsx` component
- [ ] `OrderDetailCard.tsx` component
- [ ] `BookingSuccessCard.tsx` component
- [ ] Cập nhật `ChatbotWidget.tsx` — render theo messageType
- [ ] Cập nhật `TourSuggestionCards.tsx` — thêm date chips, click → auto-send message
- [ ] Quick actions update

---

### Phase 4 — RabbitMQ Sync + Optimization (1 tuần)

**tour-catalog-service:**
- [ ] Publish events khi tour/departure thay đổi (POST/PUT/DELETE)

**analytics-service:**
- [ ] Consumer `PineconeSyncConsumer.java`
- [ ] Debounce scheduler `DebounceSchedulerService.java`
- [ ] Update `VectorSyncService.java` để support sync theo tourId đơn lẻ

**Optimization:**
- [ ] Caching: cache kết quả `/api/departures/order-info` trong Redis (TTL = 5 phút)
- [ ] Rate limiting: max 10 booking attempts per sessionId per hour
- [ ] Error handling: retry 3 lần khi Feign call fail

---

## 14. Danh sách file cần tạo/sửa

### analytics-service (phần lớn thay đổi)

| File | Hành động | Mô tả |
|------|-----------|-------|
| `ChatbotService.java` | SỬA | Refactor sang stateful dispatch |
| `ChatMessageResponse.java` | SỬA | Thêm messageType, bookingConfirmData, conversationStage |
| `TourCatalogFeignClient.java` | SỬA | Thêm getDepartureOrderInfo() |
| `ChatbotBookingFeignClient.java` | TẠO MỚI | Feign client tới booking-service |
| `ChatbotPaymentFeignClient.java` | TẠO MỚI | Feign client tới payment-service |
| `ConversationState.java` | TẠO MỚI | State machine data |
| `ConversationSession.java` | TẠO MỚI | Redis entity |
| `RedisSessionService.java` | TẠO MỚI | Load/save session |
| `IntentDetectionService.java` | TẠO MỚI | Detect booking/lookup/cancel |
| `BookingConfirmData.java` | TẠO MỚI | DTO cho confirm card |
| `ChatbotCreateBookingRequest.java` | TẠO MỚI | Request DTO |
| `ChatbotCreateBookingResponse.java` | TẠO MỚI | Response DTO |
| `ChatbotBookingDetailResponse.java` | TẠO MỚI | Order lookup DTO |
| `ChatbotDepartureInfoResponse.java` | TẠO MỚI | Pricing DTO |
| `application.yaml` (analytics) | SỬA | Thêm Redis config |
| `pom.xml` (analytics) | SỬA | Thêm spring-data-redis, spring-boot-starter-data-redis |

### tourism_frontend

| File | Hành động | Mô tả |
|------|-----------|-------|
| `ChatbotWidget.tsx` | SỬA | Xử lý messageType mới |
| `BookingConfirmCard.tsx` | TẠO MỚI | UI confirm card |
| `OrderDetailCard.tsx` | TẠO MỚI | UI order detail |
| `BookingSuccessCard.tsx` | TẠO MỚI | UI success card |
| `TourSuggestionCards.tsx` | SỬA | Thêm date chips |

### tour-catalog-service (minimal)

| File | Hành động | Mô tả |
|------|-----------|-------|
| `TourEventPublisher.java` | TẠO MỚI (Phase 4) | Publish RabbitMQ events |

---

## 15. Ràng buộc dữ liệu quan trọng

### 15.1 Validation từ Booking entity

Những field có `@NotBlank` hoặc `@Email` trên `Booking.java` entity (KHÔNG phải DTO):

| Field | Constraint | Hành động chatbot |
|-------|-----------|-------------------|
| `contactFullName` | @NotBlank | PHẢI thu thập |
| `contactPhone` | @NotBlank | PHẢI thu thập |
| `contactEmail` | @NotBlank + @Email | PHẢI thu thập + validate |
| `contactAddress` | @NotBlank | Auto-fill = "Đặt qua chatbot" |

> ⚠️ `contactEmail` là trường hay bị bỏ sót nhưng REQUIRED. Chatbot PHẢI hỏi email.

### 15.2 dateOfBirth NOT NULL

`BookingPassenger.dateOfBirth` là `NOT NULL` ở DB schema. Chatbot không hỏi ngày sinh vì làm UX phức tạp → dùng placeholders:

| Loại khách | Placeholder |
|-----------|-------------|
| ADULT | `1990-01-01` |
| CHILD | `2015-06-01` |
| TODDLER | `2022-06-01` |
| INFANT | `2025-01-01` |

### 15.3 INFANT không tính slot

Trong `BookingServiceImpl.java`:
```java
long nonInfantCount = passengers.stream()
    .filter(p -> p.getType() != PassengerType.INFANT)
    .count();
if (nonInfantCount > availableSlots) {
    throw new ResourceUnavailableException("Không đủ chỗ");
}
```

→ Chatbot kiểm tra: `adults + children + toddlers <= availableSlots` (KHÔNG tính infants)

### 15.4 bookingCode format

`bookingCode = "BK" + UUID.randomUUID().toString().replace("-","").substring(0,8)`

→ Pattern để parse từ user message: `/BK[A-Za-z0-9]{8}/`

### 15.5 Payment deadline

`paymentDeadline = createdAt + 24 giờ`

→ Chatbot luôn nhắc: "Vui lòng thanh toán trong vòng 24 giờ"

### 15.6 Tổng giá — chatbot ước tính, server tính thật

Chatbot hiển thị **ước tính** = Σ(count × price) lấy từ `/api/departures/order-info`.  
Tổng giá **thực tế** do `BookingServiceImpl.calculateTotal()` tính, có thể khác nếu:
- Có coupon tự động áp dụng
- Có discount đặc biệt

→ Chatbot hiển thị: "~13.970.000đ (tổng chính xác sẽ được hệ thống xác nhận)"

### 15.7 Guest booking vs. Logged-in booking

| Mode | userId | Header Authorization |
|------|--------|----------------------|
| Guest (không đăng nhập) | `null` | Không cần |
| Logged-in | `currentUser.id` | Bearer token (qua api-gateway) |

Chatbot nên **ưu tiên gửi userId** nếu user đang đăng nhập (để booking liên kết với account), nhưng vẫn hoạt động khi guest.

### 15.8 Pinecone salePrice chỉ là giá người lớn

`meta.put("salePrice", dep.getSalePrice())` trong `VectorSyncService.java` → đây là `adultSalePrice`.

→ Khi Pinecone search trả về `salePrice = 4.990.000`, đây là giá/người lớn.  
→ PHẢI gọi thêm `/api/departures/order-info` để có childPrice, toddlerPrice, infantPrice.

---

*End of CHATBOT_PLAN v3.1*  
*Tất cả API endpoints, field names, enum values, business logic đã được verify từ source code thực tế.*
