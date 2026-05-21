# CHATBOT ROADMAP 2026
## Future Travel — Phân tích hiện trạng & Lộ trình phát triển hướng đến Tripi (travel.com.vn)

> **Ngày viết:** 2026-05-20  
> **Tác giả:** AI-generated analysis  
> **Mục đích:** So sánh chatbot Future Travel với Tripi của Vietravel (travel.com.vn), từ đó lập lộ trình phát triển có ưu tiên rõ ràng.

---

## PHẦN 1: CHATBOT HIỆN TẠI — FUTURE TRAVEL

### 1.1 Kiến trúc tổng thể

```
┌──────────────────────────────────────────────────────────────────────┐
│                     FUTURE TRAVEL CHATBOT (hiện tại)                 │
│                     Kiến trúc: RAG (Text Q&A Only)                   │
└──────────────────────────────────────────────────────────────────────┘

[React Frontend]                     [analytics-service - Spring Boot]
ChatbotWidget.jsx                     ChatbotController.java
      │                                         │
      │  POST /api/chatbot/chat                 │
      │  { message, sessionId, userId:null }    │
      │ ──────────────────────────────────────► │
      │                                         │
      │                              ChatbotService.handleUserMessage()
      │                                         │
      │                              1. Intent Detection (Java Regex)
      │                                 - DISCOUNT_PATTERN → topK=50
      │                                 - COUPON_PATTERN   → topK=50
      │                                 - Other            → topK=10
      │                                         │
      │                              2. Embed câu hỏi
      │                                 Pinecone Inference API
      │                                 llama-text-embed-v2
      │                                 text → vector[1024]
      │                                         │
      │                              3. Vector Similarity Search
      │                                 Pinecone Cosine Similarity
      │                                 → topK documents
      │                                         │
      │                              4. Post-processing / Re-ranking
      │                                 buildEnhancedContext()
      │                                 - Filter coupon docs
      │                                 - Filter discount docs
      │                                 - Sort by discount DESC
      │                                         │
      │                              5. Prompt Engineering
      │                                 buildEnhancedPrompt()
      │                                 - System prompt tiếng Việt
      │                                 - Context từ Pinecone
      │                                 - Câu hỏi user
      │                                         │
      │                              6. Google Gemini 2.0 Flash
      │                                 temperature=0.7, maxTokens=1000
      │                                 → Sinh text tiếng Việt
      │                                         │
      │                              7. Build Response
      │                                 - reply (text)
      │  ◄────────────────────────────────────  │ - tourSuggestions (list)
      │                                         │ - quickActions (list)
      │                                         │ - sessionId, timestamp
```

### 1.2 Data Sync Pipeline

```
PostgreSQL (tour_catalog_db, booking_db)
         │
         │  Feign client (mỗi ngày 2:00 AM)
         ▼
VectorSyncService.syncAll()
  ├── syncAllTours()      → TOUR_SUMMARY documents
  ├── syncAllDepartures() → TOUR_DEPARTURE documents (giá, slot, coupon)
  ├── syncAllLocations()  → LOCATION documents
  ├── syncAllReviews()    → REVIEW documents
  └── syncAllCoupons()    → COUPON documents
         │
         │  Pinecone Inference API (embed)
         ▼
Pinecone Vector DB (index: tourism-chatbot)
  Namespace: default
  Dimension: 1024
  Metric: cosine
```

### 1.3 Frontend UI — ChatbotWidget.jsx

| Thành phần | Trạng thái | Mô tả |
|---|---|---|
| Mascot video | ✅ Hoạt động | Video mp4 với chroma-key real-time bằng Canvas API |
| Speech bubble xoay | ✅ Hoạt động | 4 câu chào, tự đổi mỗi 3.5s với fade animation |
| Chat window | ✅ Hoạt động | Header, messages area, input field |
| ReactMarkdown render | ✅ Hoạt động | Bot trả lời hỗ trợ markdown |
| Tour cards (cards UI) | ❌ **COMMENTED OUT** | Code có nhưng bị `{/* ... */}` comment |
| Quick actions buttons | ❌ **COMMENTED OUT** | Code có nhưng bị `{/* ... */}` comment |
| User authentication | ❌ Không có | `userId: null` hardcoded |
| Conversation history | ❌ Không có | Chỉ lưu trong component state (mất khi refresh) |
| Feedback (like/dislike) | ❌ Không có | - |

### 1.4 Hạn chế hiện tại

| # | Hạn chế | Mức độ ảnh hưởng |
|---|---|---|
| 1 | **Không có multi-turn context** — mỗi câu hỏi xử lý độc lập, không nhớ context hội thoại trước | Cao |
| 2 | **Không có booking flow** — user chỉ nhận được thông tin text, không đặt tour được | Rất cao |
| 3 | **Tour cards bị comment** — UI đẹp hơn nếu hiển thị card ảnh kèm thông tin | Cao |
| 4 | **Không lookup đơn hàng** — không thể tra cứu booking qua chat | Trung bình |
| 5 | **Không xác thực user** — chatbot không biết user đang đăng nhập hay không | Trung bình |
| 6 | **Sync data 1 lần/ngày** — thông tin slot, giá có thể outdated | Trung bình |
| 7 | **API key hardcoded** trong source (bảo mật) | Cao |
| 8 | **Không có "Gợi ý câu hỏi"** hiển thị cho user | Thấp |
| 9 | **Không có feedback mechanism** (like/dislike) | Thấp |

---

## PHẦN 2: CHATBOT TRIPI — TRAVEL.COM.VN (VIETRAVEL)

### 2.1 Tổng quan

**Tripi** là chatbot AI của Vietravel (travel.com.vn), được đặt tên và thiết kế như một nhân vật "trợ lý ảo" với mascot avatar hoạt hình (mèo robot). Tripi không chỉ là Q&A đơn giản mà là **một sales funnel hoàn chỉnh ngay trong cửa sổ chat**.

### 2.2 Luồng hội thoại chính của Tripi

```
┌────────────────────────────────────────────────────────────────────┐
│                    TRIPI CONVERSATION FLOW                         │
│              (Slot-Filling Conversational Agent)                   │
└────────────────────────────────────────────────────────────────────┘

USER INPUT (bất kỳ)
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│  SLOT-FILLING ENGINE                                    │
│  Thu thập thông tin còn thiếu qua hội thoại:            │
│                                                         │
│  Slot 1: Điểm đến    (trong nước / nước ngoài)         │
│  Slot 2: Điểm khởi hành  (thành phố)                   │
│  Slot 3: Tháng dự kiến đi                              │
│  Slot 4: Số lượng người                                 │
└──────────────────┬──────────────────────────────────────┘
                   │ Khi đủ slots
                   ▼
┌─────────────────────────────────────────────────────────┐
│  TÌM KIẾM TOUR                                          │
│  → Hiển thị danh sách tour phù hợp                      │
│  → Mỗi tour có:                                         │
│     - Ảnh đại diện (full width)                         │
│     - Badge mã tour (góc dưới trái ảnh)                 │
│     - Tên tour đầy đủ                                   │
│     - Icon địa điểm khởi hành + thời gian              │
│     - Ngày khởi hành dạng button (24/05, 07/06...)     │
│     - Giá từ (màu đỏ nổi bật)                          │
│     - Nút "Xem ngay" (màu xanh dương)                  │
└──────────────────┬──────────────────────────────────────┘
                   │ User chọn tour
                   ▼
┌─────────────────────────────────────────────────────────┐
│  THU THẬP THÔNG TIN BOOKING                             │
│  → Hỏi: "Quý khách đi mấy người?"                      │
│  → Hỏi: "Chị cho em xin tên và số điện thoại?"         │
│  → Hỏi: "Chị cho em email để nhận xác nhận?"           │
└──────────────────┬──────────────────────────────────────┘
                   │ Đủ thông tin
                   ▼
┌─────────────────────────────────────────────────────────┐
│  XÁC NHẬN BOOKING                                       │
│  → Hiển thị thông tin đặt tour đầy đủ để xác nhận      │
│    - Tên tour, mã tour, ngày khởi hành                  │
│    - Số người, tổng tiền                                │
│    - Họ tên, số điện thoại, email                       │
└──────────────────┬──────────────────────────────────────┘
                   │ User xác nhận
                   ▼
┌─────────────────────────────────────────────────────────┐
│  TẠO BOOKING THÀNH CÔNG                                 │
│  → Sinh mã booking (VD: 260517X4S77C)                  │
│  → Cung cấp link thanh toán online:                     │
│     https://travel.com.vn/payment-booking/{bookingCode}│
│  → Nhắc lưu mã booking                                 │
│  → Hotline hỗ trợ: 1800646888 (miễn phí, 24/7)        │
└────────────────────────────────────────────────────────┘
```

### 2.3 Luồng tra cứu đơn hàng

```
User: "tôi đang có đơn hàng nào ko"
  │
  ▼
Tripi yêu cầu:
  - Mã booking (VD: VTV123456...)
  - Họ tên đầy đủ (theo đúng tên khi đặt tour)
  │
  ▼
Lookup API → Trả về thông tin booking
(Nếu không tìm thấy → hướng dẫn kiểm tra lại + hotline)
```

### 2.4 UI Components của Tripi

| Component | Mô tả |
|---|---|
| **Header** | Avatar mascot + tên "Tripi" + trạng thái online (xanh) + nút đóng |
| **Tour card** | Ảnh full-width, badge mã tour overlay, tên tour, điểm đi, thời gian, ngày khởi hành dạng buttons, giá đỏ, nút "Xem ngay" |
| **Text message** | Bubble trắng với bullet list, emoji để tăng thân thiện |
| **"Gợi ý câu hỏi"** | Panel ở dưới cùng có thể mở rộng/thu gọn |
| **Like/Dislike** | 👍 👎 dưới mỗi tin nhắn bot |
| **Timestamp** | HH:MM dưới mỗi tin |
| **Double tick** | ✓✓ đã đọc dưới tin user |

### 2.5 Khả năng nổi bật của Tripi

1. **Multi-turn context** — nhớ toàn bộ hội thoại, không hỏi lại thông tin đã có
2. **Slot-filling thông minh** — tự động detect thông tin từ câu nói tự nhiên ("tao ở hcm thì đi biển ở đâu gần nhất")
3. **Booking hoàn chỉnh trong chat** — từ tìm tour → đặt tour → thanh toán không cần rời chat
4. **Tra cứu đơn hàng** — look up booking bằng mã + họ tên
5. **Tour card giàu thông tin** — ảnh + nhiều ngày khởi hành + giá, không chỉ text
6. **Lead capture** — thu thập tên/SĐT/email trong hội thoại
7. **Fallback graceful** — khi không hiểu, xin clarification; khi không xử lý được, đưa hotline

---

## PHẦN 3: SO SÁNH GAP ANALYSIS

| Tính năng | Future Travel (hiện tại) | Tripi (Vietravel) | Gap |
|---|---|---|---|
| Q&A tư vấn tour | ✅ RAG + Gemini | ✅ AI tổng hợp | Tương đương |
| Multi-turn context | ❌ Mỗi câu độc lập | ✅ Nhớ toàn cuộc trò chuyện | **LỚN** |
| Slot-filling (thu thập yêu cầu) | ❌ Không | ✅ Tự động hỏi thiếu info | **LỚN** |
| Tour cards (ảnh + giá + ngày) | ❌ Commented out | ✅ Rich cards | **LỚN** |
| Nhiều ngày khởi hành trong card | ❌ Không | ✅ Buttons chọn ngày | Vừa |
| Booking flow trong chat | ❌ Không | ✅ Hoàn chỉnh | **RẤT LỚN** |
| Tra cứu đơn hàng | ❌ Không | ✅ Có | Vừa |
| Lead capture (tên/SĐT) | ❌ Không | ✅ Có | Vừa |
| Payment link | ❌ Không | ✅ Có | Vừa |
| Gợi ý câu hỏi | ❌ Không | ✅ Panel gợi ý | Nhỏ |
| Feedback like/dislike | ❌ Không | ✅ Có | Nhỏ |
| User auth integration | ❌ userId=null | N/A (guest) | Nhỏ |
| Mascot/branding | ✅ Video chroma-key | ✅ Avatar animation | Tương đương |
| Tiếng Việt tự nhiên | ✅ Tốt (Gemini) | ✅ Tốt | Tương đương |

---

## PHẦN 4: LỘ TRÌNH PHÁT TRIỂN (ROADMAP)

### Tổng quan timeline

```
Hiện tại            Phase 1        Phase 2           Phase 3           Phase 4
(May 2026)         (1-2 tuần)     (3-4 tuần)         (4-6 tuần)       (6-8 tuần)
    │                  │               │                  │                │
    ▼                  ▼               ▼                  ▼                ▼
 Text Q&A         Tour Cards     Multi-turn          Booking          Order Lookup
 Only             + Quick        Context +           in Chat          + Lead
                  Actions        Slot-filling        + Payment        Capture
```

---

### Phase 1: Quick Wins — Kích hoạt tính năng đã có (1-2 tuần)

**Mục tiêu:** Bật những tính năng đã code xong nhưng đang bị comment.

#### 1.1 Bật Tour Cards trong ChatbotWidget.jsx

**Vấn đề hiện tại:** Đoạn code hiển thị tour cards đang bị comment:
```jsx
{/* Gợi ý Tour (Cards) */}
{/* {message.tourSuggestions && message.tourSuggestions.length > 0 && (
  <div className={styles.tourGrid}>
    {message.tourSuggestions.map((tour) => (
      <a key={tour.tourId} href={tour.detailUrl} ...>
```

**Việc cần làm:**
- [ ] Bỏ comment khối `tourSuggestions` cards
- [ ] Bỏ comment khối `quickActions` buttons
- [ ] Kiểm tra `tourSuggestions` có `imageUrl`, `tourName`, `duration`, `minPrice`, `detailUrl` đầy đủ không
- [ ] Cập nhật CSS `tourGrid`, `tourCard` để responsive trong chat window (width ~380px)
- [ ] Test với data thực từ backend

**Kết quả mong đợi:** Sau khi chat, user thấy card tour có ảnh + giá + link chi tiết.

#### 1.2 Cải thiện Quick Actions

**Việc cần làm:**
- [ ] Bỏ comment khối `quickActions`
- [ ] Backend `ChatbotService.buildQuickActions()` cần trả về các câu hỏi gợi ý phù hợp ngữ cảnh
- [ ] Ví dụ: sau khi hỏi "tour Đà Lạt" → gợi ý: "Xem lịch khởi hành", "So sánh giá", "Tour tương tự"

#### 1.3 Thêm "Gợi ý câu hỏi" ban đầu (Welcome chips)

**Việc cần làm:**
- [ ] Hiển thị các chip câu hỏi nhanh khi user mới mở chat:
  - "Tour giá rẻ cuối tuần"
  - "Du lịch biển hè này"
  - "Tour nước ngoài tháng 7"
  - "Tra cứu đơn hàng"
- [ ] CSS chips nhỏ gọn dưới tin nhắn chào đầu tiên

#### 1.4 Thêm Like/Dislike feedback

**Việc cần làm:**
- [ ] Thêm 👍 👎 icon dưới mỗi tin bot
- [ ] Backend endpoint: `POST /api/chatbot/feedback { messageId, sessionId, rating }`
- [ ] Lưu vào DB (bảng `chatbot_feedback`) — dùng để cải thiện sau này

**Độ phức tạp:** Thấp  
**Tác động:** Cao (cải thiện UX rõ rệt)

---

### Phase 2: Multi-turn Context & Slot-filling (3-4 tuần)

**Mục tiêu:** Chatbot nhớ ngữ cảnh hội thoại, chủ động hỏi thêm khi thiếu thông tin.

#### 2.1 Conversation State Management

**Thiết kế:**

```
┌─────────────────────────────────────────────────────────────┐
│  ConversationState (lưu per sessionId)                      │
│                                                             │
│  - conversationHistory: List<{role, content}>               │
│  - slots:                                                   │
│      destination: String   (null nếu chưa biết)            │
│      departureCity: String                                  │
│      travelMonth: String                                    │
│      numberOfPeople: Integer                                │
│      budget: Long                                           │
│  - stage: GREETING | COLLECTING | SEARCHING | BOOKING      │
│  - lastTours: List<TourSuggestion>  (kết quả tìm gần nhất) │
│  - userInfo:                                                │
│      name: String                                           │
│      phone: String                                          │
│      email: String                                          │
└─────────────────────────────────────────────────────────────┘
```

**Lưu trữ:**
- **Option A (đơn giản):** In-memory `ConcurrentHashMap<sessionId, ConversationState>` + TTL 2 giờ
- **Option B (production):** Redis với TTL 2 giờ

> Gợi ý: Option A đủ dùng cho giai đoạn này, migrate sang Redis ở Phase 4.

**Việc cần làm — Backend:**
- [ ] Tạo class `ConversationState` (POJO)
- [ ] Tạo `ConversationStateService` (quản lý in-memory store + TTL)
- [ ] Refactor `ChatbotService.handleUserMessage()`:
  - Load state từ sessionId
  - Thêm `conversationHistory` vào prompt Gemini
  - Update state sau mỗi lượt
- [ ] Cập nhật prompt template để Gemini nhận history dạng:
  ```
  === LỊCH SỬ HỘI THOẠI ===
  User: [tin 1]
  Assistant: [trả lời 1]
  User: [tin 2]
  ...
  ```

**Việc cần làm — Frontend:**
- [ ] Không cần thay đổi nhiều — `sessionId` đã được gửi mỗi request

#### 2.2 Intent-Aware Slot-filling

**Logic slot-filling:**

```
Khi user hỏi về tour nhưng thiếu thông tin:
  → Gemini được hướng dẫn trong system prompt:
    "Nếu user hỏi tìm tour nhưng chưa cho biết [điểm đến / điểm đi / tháng / số người],
     hãy hỏi thêm thông tin đó một cách tự nhiên, lịch sự.
     Hỏi 1-2 thông tin mỗi lần, không hỏi tất cả cùng lúc."

Khi đủ thông tin:
  → Pinecone search với filter theo destination/month
  → Hiển thị kết quả tour
```

**Cải thiện system prompt:**
- [ ] Thêm hướng dẫn slot-filling vào `buildEnhancedPrompt()`
- [ ] Thêm context về city hiện tại của user (nếu user cung cấp)
- [ ] Gemini cần detect và extract thông tin từ câu tự nhiên:
  - "tao ở hcm" → departureCity = "TP. HCM"
  - "đi biển tháng 7" → destination = "biển", travelMonth = "07"
  - "2 người lớn 1 trẻ em" → numberOfPeople = 3

#### 2.3 Tour Card Enhancement

**Cập nhật tour card để giống Tripi:**
- [ ] Thêm nhiều ngày khởi hành dưới dạng date buttons (lấy từ `tourSuggestions`)
- [ ] Badge mã tour overlay trên ảnh
- [ ] Nút "Xem ngay" màu primary
- [ ] Khi click ngày cụ thể → mở trang đặt tour với departure date pre-filled

**Backend — `buildTourSuggestions()`:**
- [ ] Thêm trường `departureDates: List<String>` (tối đa 4 ngày khởi hành gần nhất)
- [ ] Thêm trường `tourCode`
- [ ] Đảm bảo `imageUrl` luôn có giá trị (fallback ảnh default)

---

### Phase 3: Booking Flow trong Chat (4-6 tuần)

**Mục tiêu:** User có thể đặt tour hoàn chỉnh mà không cần rời chat.

#### 3.1 Luồng thiết kế

```
┌──────────────────────────────────────────────────────────────────┐
│  BOOKING FLOW TRONG CHAT                                         │
└──────────────────────────────────────────────────────────────────┘

Stage: SEARCHING → User chọn tour
  Bot: "Quý khách đi mấy người ạ?"
  Bot: "Ngày khởi hành nào phù hợp? [button 24/05] [button 07/06]"

Stage: COLLECTING_BOOKING_INFO
  Bot: "Cho em xin tên và số điện thoại để đặt tour nhé!"
  User: "Nguyễn Văn A, 0901234567"
  Bot: "Chị vui lòng cung cấp thêm email để nhận xác nhận ạ!"
  User: "abc@gmail.com"

Stage: CONFIRMING
  Bot hiển thị summary card:
  ┌─────────────────────────────────────┐
  │ 📋 Thông tin đặt tour:              │
  │ • Tour: [tên tour]                  │
  │ • Mã tour: NDSGN150                 │
  │ • Khởi hành: 24/05/2026             │
  │ • Số người: 2                       │
  │ • Giá: 3.790.000₫/người            │
  │ • Tổng: 7.580.000₫                 │
  │ • Họ tên: Nguyễn Văn A             │
  │ • SĐT: 0901234567                   │
  │ • Email: abc@gmail.com              │
  │                                     │
  │  [✅ Xác nhận đặt]  [❌ Hủy]       │
  └─────────────────────────────────────┘

Stage: BOOKING_CONFIRMED
  Bot: "Đặt tour thành công! 🎉
        Mã booking: FT260517X4S77C
        💳 Thanh toán ngay: [link]
        Hotline hỗ trợ: 1800XXXXXX"
```

#### 3.2 Việc cần làm — Backend

**ChatbotController — Thêm endpoint booking:**
```java
POST /api/chatbot/booking
Body: { sessionId, tourId, departureId, numberOfPeople, name, phone, email }
→ Gọi booking-service qua Feign
→ Trả về { bookingCode, paymentUrl, confirmationDetails }
```

**ChatbotService — Thêm booking stage handling:**
- [ ] Detect intent "đặt tour" từ user
- [ ] Chuyển stage → `COLLECTING_BOOKING_INFO`
- [ ] Thu thập slots: `tourId`, `departureId`, `numberOfPeople`, `name`, `phone`, `email`
- [ ] Validate thông tin trước khi tạo booking
- [ ] Gọi `booking-service` qua Feign: `POST /api/bookings`
- [ ] Trả về booking code + payment URL

**booking-service — API for chatbot:**
- [ ] Đảm bảo `POST /api/bookings` nhận `source: "CHATBOT"` để phân biệt
- [ ] Trả về `paymentUrl` dạng `/payment/{bookingCode}` hoặc link đầy đủ

#### 3.3 Việc cần làm — Frontend

**Thêm component `BookingConfirmCard`:**
- [ ] Card xác nhận với thông tin đặt tour đầy đủ
- [ ] Nút "Xác nhận đặt" gọi API chatbot booking
- [ ] Nút "Hủy" quay về tìm kiếm
- [ ] Sau booking thành công: hiển thị mã + link thanh toán

**Security considerations:**
- [ ] Không gửi thông tin nhạy cảm trong URL
- [ ] Validate phone/email format trước khi submit
- [ ] HTTPS only cho payment link

---

### Phase 4: Order Lookup + Lead Capture + Production Hardening (6-8 tuần)

#### 4.1 Tra cứu đơn hàng trong chat

**Luồng:**
```
User: "tôi muốn kiểm tra đơn hàng"
  ↓
Bot: "Quý khách vui lòng cung cấp:
      • Mã booking (VD: FT123456...)
      • Họ tên đầy đủ (theo tên khi đặt tour)"
  ↓
User: "FT260517X4S77C, Nguyễn Văn A"
  ↓
Bot tra cứu booking-service
  ↓
Hiển thị thông tin booking hoặc hướng dẫn liên hệ
```

**Backend:**
- [ ] Endpoint: `GET /api/chatbot/booking-lookup?code=XXX&name=YYY`
- [ ] Chatbot-service gọi booking-service Feign `GET /api/bookings/search?code=&name=`
- [ ] Trả về: tên tour, ngày đi, trạng thái, tổng tiền, trạng thái thanh toán

#### 4.2 Lead Capture & CRM Integration

**Mục tiêu:** Lưu thông tin khách hàng tiềm năng từ chat.

- [ ] Khi user cung cấp tên + SĐT (dù chưa đặt tour), lưu vào bảng `chat_leads`:
  ```sql
  CREATE TABLE chat_leads (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100),
    name VARCHAR(200),
    phone VARCHAR(20),
    email VARCHAR(200),
    interested_destination VARCHAR(200),
    travel_month VARCHAR(10),
    number_of_people INT,
    created_at TIMESTAMP,
    status VARCHAR(50) DEFAULT 'NEW'  -- NEW, CONTACTED, CONVERTED
  );
  ```
- [ ] Admin dashboard có thể xem leads từ chatbot

#### 4.3 User Authentication Integration

**Khi user đã đăng nhập:**
- [ ] Frontend gửi `userId` (Keycloak user ID) trong request thay vì `null`
- [ ] Backend load profile user → pre-fill tên/SĐT/email khi booking
- [ ] Bot: "Chào [Tên]! Em thấy thông tin của anh/chị đã có, xác nhận đặt tour luôn nhé?"

#### 4.4 Real-time Data Sync

**Vấn đề hiện tại:** Sync 1 lần/ngày, slot và giá có thể outdated.

**Giải pháp:**
- [ ] **Option A:** Giảm tần suất sync xuống mỗi 1 giờ (đơn giản, ít chi phí API)
- [ ] **Option B:** Event-driven sync — khi tour/departure thay đổi trong tour-catalog-service, publish event → analytics-service re-sync record đó

#### 4.5 Redis Session Store

- [ ] Migrate `ConversationState` từ in-memory sang Redis
- [ ] Config TTL 2 giờ
- [ ] Cho phép scale horizontal analytics-service

#### 4.6 Security & Production

- [ ] Chuyển API keys (Gemini, Pinecone) ra environment variables trong docker-compose.yml (đã phân tích trong PLAN-chatbot-deploy-2026-05-18.md)
- [ ] Rate limiting cho `/api/chatbot/chat` (VD: max 60 req/phút/IP)
- [ ] Input sanitization — strip HTML/script trong user message
- [ ] Log conversation với masking PII (ẩn SĐT, email trong logs)

---

## PHẦN 5: BẢNG ƯU TIÊN TỔNG HỢP

| Priority | Task | Phase | Effort | Impact |
|---|---|---|---|---|
| 🔴 P0 | Bật tour cards (uncomment) | 1 | 0.5 ngày | Cao |
| 🔴 P0 | Bật quick actions (uncomment) | 1 | 0.5 ngày | Cao |
| 🔴 P0 | Chuyển API keys ra env vars | 1 | 1 ngày | Bảo mật |
| 🟠 P1 | Multi-turn context (conversation history) | 2 | 3 ngày | Rất cao |
| 🟠 P1 | Slot-filling prompt engineering | 2 | 2 ngày | Cao |
| 🟠 P1 | Tour card với nhiều ngày khởi hành | 2 | 2 ngày | Cao |
| 🟡 P2 | Welcome chips / gợi ý câu hỏi ban đầu | 1 | 1 ngày | Trung bình |
| 🟡 P2 | Like/Dislike feedback | 1 | 1 ngày | Trung bình |
| 🟡 P2 | Booking flow trong chat | 3 | 10 ngày | Rất cao |
| 🟢 P3 | Tra cứu đơn hàng | 4 | 3 ngày | Trung bình |
| 🟢 P3 | Lead capture | 4 | 2 ngày | Trung bình |
| 🟢 P3 | User auth integration | 4 | 2 ngày | Trung bình |
| 🟢 P3 | Redis session store | 4 | 2 ngày | Thấp |
| 🟢 P3 | Real-time data sync | 4 | 3 ngày | Trung bình |

---

## PHẦN 6: KIẾN TRÚC MỤC TIÊU (End State)

```
┌──────────────────────────────────────────────────────────────────────┐
│              FUTURE TRAVEL CHATBOT — TARGET ARCHITECTURE             │
│                    (Sau khi hoàn thành Phase 4)                      │
└──────────────────────────────────────────────────────────────────────┘

[React Frontend — ChatbotWidget v2]
  ├── MascotVideo (ChromaKey)
  ├── SpeechBubble (rotating greetings)
  ├── ChatWindow
  │     ├── WelcomeChips (quick start suggestions)
  │     ├── MessageList
  │     │     ├── TextMessage (Markdown)
  │     │     ├── TourCardCarousel (image + dates + price + CTA)
  │     │     ├── BookingConfirmCard
  │     │     └── FeedbackButtons (👍 👎)
  │     ├── SuggestedQuestions panel (collapsible)
  │     └── MessageInput
  │
  │  POST /api/chatbot/chat
  │  { message, sessionId, userId? }
  │
[API Gateway → analytics-service]
  │
[ChatbotController]
  │
[ChatbotService v2]
  ├── ConversationStateService (Redis)
  │     └── ConversationState { history, slots, stage, lastTours, userInfo }
  ├── IntentClassifier
  │     ├── TOUR_SEARCH
  │     ├── BOOKING_REQUEST
  │     ├── ORDER_LOOKUP
  │     ├── DISCOUNT_QUERY
  │     ├── COUPON_QUERY
  │     └── GENERAL_QA
  ├── SlotFillingEngine
  │     └── Extract & fill slots từ user input
  ├── RAG Pipeline (giữ nguyên)
  │     ├── VectorService (Pinecone + llama-text-embed-v2)
  │     └── Gemini 2.0 Flash (generation)
  ├── BookingService (Feign → booking-service)
  └── LeadCaptureService (lưu DB)
  │
[Responses]
  ├── reply (text / markdown)
  ├── tourSuggestions (cards với images + departureDates)
  ├── bookingConfirmation
  ├── quickActions
  └── suggestedQuestions
```

---

## PHẦN 7: ĐỊNH NGHĨA "DONE" CHO TỪNG PHASE

### Phase 1 Done:
- [ ] Tour cards hiển thị trong chat với ảnh, tên tour, giá, link chi tiết
- [ ] Quick action buttons hoạt động
- [ ] Welcome chips hiện khi mở chat lần đầu
- [ ] API keys không còn hardcode trong source code

### Phase 2 Done:
- [ ] Chatbot nhớ "tao ở HCM" từ câu trước, không hỏi lại điểm đi
- [ ] Khi user hỏi "đi biển", bot hỏi thêm tháng + số người trước khi trả kết quả
- [ ] Tour card hiển thị 3-4 ngày khởi hành dưới dạng clickable buttons

### Phase 3 Done:
- [ ] User có thể đặt tour hoàn chỉnh trong chat (tên, SĐT, email, chọn ngày, xác nhận)
- [ ] Nhận booking code + link thanh toán ngay trong chat

### Phase 4 Done:
- [ ] User tra cứu được đơn hàng bằng mã booking + họ tên
- [ ] User đăng nhập → bot pre-fill thông tin khi đặt tour
- [ ] Conversation state persist qua Redis

---

## PHỤ LỤC: FILES LIÊN QUAN TRONG PROJECT

| File | Mục đích |
|---|---|
| `analytics-service/.../ChatbotService.java` | Core RAG pipeline, prompt builder |
| `analytics-service/.../ChatbotController.java` | HTTP endpoints |
| `analytics-service/.../VectorService.java` | Pinecone embed + search |
| `analytics-service/.../VectorSyncService.java` | Data sync pipeline |
| `analytics-service/.../ChatbotConfig.java` | Cấu hình Gemini, Pinecone |
| `tourism_frontend/.../ChatbotWidget.jsx` | UI chatbot React |
| `tourism_frontend/.../ChatbotWidget.module.scss` | CSS chatbot |
| `doc/chatbot/CHATBOT_PHAN_TICH_VA_NANG_CAP.md` | Phân tích kiến trúc chi tiết |
| `doc/chatbot-update/PLAN-chatbot-deploy-2026-05-18.md` | Kế hoạch deploy + bảo mật |
| `docker-compose.yml` | Cần thêm env vars cho API keys |

---

*Tài liệu này được tạo dựa trên phân tích source code thực tế của project Future Travel và quan sát trực tiếp chatbot Tripi của Vietravel (travel.com.vn) ngày 2026-05-17.*
