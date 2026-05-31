# CHATBOT — BÁO CÁO PHÂN TÍCH & PLAN SỬA CẢI TẠO

**Ngày:** 2026-05-28  
**Dự án:** `tourism-microservices-backend / analytics-service`  
**Căn cứ plan triển khai:** `doc/chatbot-update/CHATBOT_TRANSACTION_RAG_ORCHESTRATION_PLAN_2026-05-27.md`

---

## 1. KIỂM TRA SYNTAX / COMPILATION

### Kết quả

```
mvn -pl analytics-service clean compile -Dmaven.test.skip=true
→ BUILD SUCCESS (13.9s)
→ 55 source files compiled, 0 errors
```

### Chi tiết warnings (không phải lỗi, không cần sửa khẩn)

| File | Warning | Mức độ |
|------|---------|--------|
| `DailyRevenueStat.java` | Lombok @EqualsAndHashCode thiếu callSuper=false | Minor |
| `TourPerformanceStat.java` | Lombok @EqualsAndHashCode thiếu callSuper=false | Minor |
| `UserGrowthStat.java` | Lombok @EqualsAndHashCode thiếu callSuper=false | Minor |
| `VectorService.java` | Unchecked/unsafe raw type cast | Minor |

**Kết luận:** Không có lỗi syntax/compilation. Hai file được yêu cầu kiểm tra:
- `GeminiIntentService.java` ✅ sạch
- `BookingConversationService.java` ✅ sạch

---

## 2. KẾT QUẢ TEST API — 1 SESSION DÀI

### Thông tin test

- **Script:** `D:\HK8\test_long_session.js`
- **Session ID:** `long-session-{timestamp}` (1 session duy nhất xuyên suốt)
- **Số lượt hội thoại:** 39 turns
- **Kết quả:** ✅ **37 PASS / ❌ 2 FAIL**

---

### 2.1 Kịch bản test và kết quả chi tiết

#### PHASE 1: Greeting & General RAG

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 1.1 | `xin chao` | IDLE | ✅ PASS | Chào đúng, stage IDLE |
| 1.2 | `chinh sach huy tour nhu the nao?` | IDLE | ❌ **FAIL** | Bot trả lời "Đã hủy luồng" — **FALSE CANCEL BUG** |
| 1.3 | `hanh ly toi da duoc mang bao nhieu kg?` | IDLE | ✅ PASS | RAG trả lời hành lý đúng |
| 1.4 | `tour nao dang giam gia nhat?` | IDLE | ✅ PASS | RAG trả lời tour giảm giá |

#### PHASE 2: Tour Search

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 2.1 | `toi muon tim tour di Da Nang` | COLLECTING_SEARCH_INFO | ✅ PASS | Vào luồng tìm tour, hỏi thêm thông tin |
| 2.2 | `huy bo bao hiem co can thiet khong?` | COLLECTING_SEARCH_INFO | ✅ PASS | KHÔNG cancel, RAG trả lời về bảo hiểm |
| 2.3 | `lich trinh cua tour 1 nhu the nao?` | COLLECTING_SEARCH_INFO | ✅ PASS | Trả lời lịch trình từ RAG/context |
| 2.4 | `slot con bao nhieu cho?` | COLLECTING_SEARCH_INFO | ✅ PASS | Trả lời slot từ context |

#### PHASE 3: Booking Flow (⚠️ Stuck in COLLECTING_SEARCH_INFO)

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 3.1 | `dat tour 1` | COLLECTING_SEARCH_INFO | ✅ PASS* | Bot hỏi tiếp thông tin — **DESIGN ISSUE** |
| 3.2 | `1` | COLLECTING_SEARCH_INFO | ✅ PASS* | Bot hiểu là discount query — **DESIGN ISSUE** |
| 3.3 | `thoi tiet Da Nang thang sau nhu the nao?` | COLLECTING_SEARCH_INFO | ✅ PASS | RAG trả lời thời tiết, stage không đổi |
| 3.4 | `gia tour nay la bao nhieu?` | COLLECTING_SEARCH_INFO | ✅ PASS | Trả lời giá từ context |
| 3.5 | `2 nguoi lon khong co tre em` | COLLECTING_SEARCH_INFO | ✅ PASS* | Bot đang hỏi thông tin tìm tour — **DESIGN ISSUE** |
| 3.6-3.12 | Passenger/Contact/Confirm | COLLECTING_SEARCH_INFO | ✅ PASS* | Bot đang ở wrong stage — **DESIGN ISSUE** |

> *PASS vì test chỉ kiểm tra "không crash" và "không cancel", nhưng luồng **không advance** đúng.

#### PHASE 4: Cancel Tests

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 4.1 | `toi muon dat tour Ha Noi` | COLLECTING_SEARCH_INFO | ✅ PASS | Vào tìm tour |
| 4.2 | `thoi khong dat nua` | IDLE | ✅ PASS | Cancel đúng, stage về IDLE |
| 4.3 | `dat tour Nha Trang di` | COLLECTING_SEARCH_INFO | ✅ PASS | Vào tìm tour |
| 4.4 | `huy` | IDLE | ✅ PASS | Single-word cancel đúng |

#### PHASE 5: Booking Lookup

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 5.1 | `toi muon tra cuu don hang cua toi` | COLLECTING_LOOKUP_CODE | ✅ PASS | Hỏi mã BK đúng |
| 5.2 | `BK12345678` | IDLE | ✅ PASS | Báo không tìm thấy đúng format |
| 5.3 | `kiem tra booking BK98765432` | IDLE | ✅ PASS | Inline BK lookup hoạt động |

#### PHASE 6: Edge Cases

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 6.1 | `toi dat tour do luon di` | IDLE | ✅ PASS | Trả lời context tour |
| 6.2 | `HCM di Ha Noi co tour khong?` | COLLECTING_SEARCH_INFO | ✅ PASS | Bắt đầu tìm tour HCM→HN |
| 6.3 | `tiep tuc dat tour` | COLLECTING_SEARCH_INFO | ✅ PASS | Resume booking hoạt động |
| 6.4 | `1` ở IDLE | COLLECTING_SEARCH_INFO | ✅ PASS | Không crash |
| 6.5 | `link thanh toan o dau?` | COLLECTING_LOOKUP_CODE | ✅ PASS | Hỏi mã BK |
| 6.6 | `moi nguoi danh gia tour Da Nang nhu the nao?` | COLLECTING_LOOKUP_CODE | ❌ **FAIL** | Stage không về IDLE, hiện giá tour thay vì đánh giá |
| 6.7 | `di da lat thoi tiet nhu the nao?` | IDLE | ✅ PASS* | Không cancel, nhưng bot lookup "DI DA LAT..." như BK code — **SUB-BUG** |

#### PHASE 7: Search + Interrupted Questions

| # | Tin nhắn | Stage | Kết quả | Ghi chú |
|---|----------|-------|---------|---------|
| 7.1 | `tim tour da lat 3 ngay 2 nguoi` | COLLECTING_SEARCH_INFO | ✅ PASS | Vào tìm tour |
| 7.2 | `co nhung tour nao di nhieu ngay khong?` | COLLECTING_SEARCH_INFO | ✅ PASS | RAG trả lời |
| 7.3 | `gia tour 2 la bao nhieu?` | COLLECTING_SEARCH_INFO | ✅ PASS | Context answer |
| 7.4 | `tour 2 khoi hanh ngay nao?` | **SHOWING_SEARCH_RESULTS** | ✅ PASS | **Search triggered!** 3 tour kết quả |
| 7.5 | `toi muon dat tour 2` | **SELECTING_DEPARTURE** | ✅ PASS | Tour 2 được chọn, hiện departure options |

---

### 2.2 Tóm tắt vấn đề phát hiện qua test

```
37/39 PASS — 2 FAIL trực tiếp + 3 DESIGN ISSUES ảnh hưởng flow Phase 2-3
```

---

## 3. BUGS ĐÃ XÁC NHẬN

### BUG-1 (CRITICAL): IntentRouter.isCancel — false positive "huy tour" trong câu hỏi chính sách

**File:** `IntentRouter.java` dòng 338  
**Pattern lỗi:**
```java
return s.matches(".*(^|\\s)(cancel|bo\\s*qua|...|huy\\s*tour|huy\\s*dat)(...)*");
```

**Ví dụ lỗi:**
- `"chinh sach huy tour nhu the nao?"` → regex khớp `huy tour` → CANCEL → bot trả "Đã hủy luồng"
- `"co the huy tour truoc may ngay?"` → tương tự

**Lý do:** `huy\\s*tour` và `huy\\s*dat` là cụm từ cũng xuất hiện trong **câu hỏi chính sách**, không chỉ là lệnh hủy booking.

**Fix đề xuất:** Xóa `huy\\s*tour` và `huy\\s*dat` khỏi multi-word cancel regex. Chỉ giữ:
```java
// Single-word cancel (exact match):
s.equals("huy") || s.equals("thoi") || s.equals("cancel") || s.equals("thoat")
// Multi-word, rõ ràng là lệnh hủy:
.*(^|\\s)(thoi\\s*di|huy\\s*di|huy\\s*thoi|thoi\\s+khong|bo\\s*qua|khong\\s*can\\s*nua|khong\\s*dat)(...)
```

---

### BUG-2 (MEDIUM): COLLECTING_LOOKUP_CODE — xử lý mọi tin nhắn như BK code

**File:** `BookingConversationService.java` — handleLookup()

**Vấn đề:**
- Khi stage = COLLECTING_LOOKUP_CODE, bot truyền TOÀN BỘ message vào extractBookingCode/performLookup
- `"di da lat thoi tiet nhu the nao?"` → bị normalize thành `"DI DA LAT THOI TIET NHU THE NAO?"` → lookup → "Không tìm thấy đơn hàng"
- `"moi nguoi danh gia tour Da Nang"` → bị hiểu là BK code

**Fix đề xuất:** Trong `handleLookup()`:
```java
// Nếu tin nhắn không khớp BK pattern → return null → fall through RAG
if (!msg.matches("(?i)BK[A-Za-z0-9]{8,}")) {
    return null; // ChatbotService sẽ xử lý bằng RAG
}
```

---

### BUG-3 (DESIGN): B4 "search immediately" là dead code

**File:** `BookingConversationService.java` — handleIdle() và handleSearchInfo()

**Vấn đề:** Code có ghi chú `// B4: if destination is present, search immediately`:
```java
// B4: if destination is present, search immediately even without all params
if (state.getSearchDestination() != null && !state.getSearchDestination().isBlank()) {
    return doSearch(sessionId, state);
}
```
Nhưng **không bao giờ được thực thi** vì ngay trước đó:
```java
ChatMessageResponse clarify = askForMissingSearchInfoIfNeeded(sessionId, state);
if (clarify != null) {
    return clarify; // ← luôn return ở đây nếu start location chưa có
}
```
`askForMissingSearchInfoIfNeeded` trả về tin nhắn hỏi thêm khi start location = null → B4 bị block.

**Hậu quả:** Bot **luôn hỏi điểm khởi hành** trước khi tìm tour, kể cả khi user chỉ muốn "xem có tour Đà Nẵng không".

---

## 4. DESIGN ISSUES (theo vision của user)

### 4.1 Luồng tìm tour: hỏi quá nhiều trước khi hiện kết quả

**Tình huống thực tế (trong test Phase 2-3):**
```
User: tôi muốn tìm tour đi Đà Nẵng
Bot:  Dạ tuyệt vời, Đà Nẵng là lựa chọn rất thú vị! Để mình tìm tour phù hợp nhất,
      bạn cho mình biết thêm:
      • Khởi hành từ đâu?
      • Dự kiến đi tháng mấy?
      • Đi bao nhiêu người lớn?

User: dat tour 1
Bot:  Dạ tuyệt vời, Đà Nẵng là lựa chọn rất thú vị! [hỏi lại y chang...]

User: 2 nguoi lon
Bot:  Dạ tuyệt vời, Đà Nẵng là lựa chọn rất thú vị! [hỏi lại y chang...]
```

Bot **bị kẹt** vì đang chờ đủ 3 params: điểm khởi hành + thời gian + số người.

**Vision của user:** Khi biết điểm đến → **tìm ngay**, hiện kết quả. Người dùng sẽ filter sau bằng cách chọn tour hoặc hỏi thêm.

---

### 4.2 Intent system quá nhiều cờ → khó maintain

**Hiện tại** có quá nhiều intent con: `ASK_SLOT`, `ASK_PRICE`, `ASK_CHILD_PRICE`, `ASK_DEPARTURE_DATE`, `ASK_DETAIL`, `ASK_ITINERARY`, `ASK_POLICY`, `ASK_DISCOUNT`, `ASK_COUPON`, `ASK_ADVICE`, `GENERAL_RAG`, `TOUR_RETRIEVAL`...

**Vision của user:** Chỉ cần **3-4 route groups**:

| Group | Mục đích | Xử lý |
|-------|----------|-------|
| `TRANSACTION_FLOW` | Đặt tour (state machine) | BookingConversationService |
| `BOOKING_LOOKUP_PAYMENT` | Tra cứu BK, thanh toán | BookingConversationService |
| `TOUR_RETRIEVAL` | Hỏi tour/giá/slot/lịch trình/chi tiết | → Thẳng RAG/Pinecone |
| `GENERAL_RAG` | Mọi câu hỏi khác | → Thẳng RAG/Pinecone |
| `UNKNOWN` | Fallback | → GENERAL_RAG |

Xóa các fast-path riêng cho SLOT/PRICE/DATE/DETAIL → tất cả vào Pinecone.

---

### 4.3 User đặt "tour đó" sau khi xem kết quả — bot không hiểu

**Tình huống:**
```
Bot hiển thị: Tour 1, Tour 2, Tour 3
User: tôi đặt tour đó luôn đi
Bot: ??? (không rõ tour nào)
```

**Cần:** Khi ở stage SHOWING_SEARCH_RESULTS:
- Nếu 1 tour → auto chọn
- Nếu nhiều tour + user nói mơ hồ → hỏi lại: "Bạn muốn đặt **tour nào**? Nhập **1**, **2** hoặc **3**."
- Quick actions: [Đặt Tour 1] [Đặt Tour 2] [Đặt Tour 3]

---

### 4.4 Departure dates — lặp lại, thiếu dedup

**Hiện tại:** Pinecone trả về nhiều doc cho cùng 1 tour, mỗi doc có thể trùng departure date.

**Cần:** Nhóm theo tourId + dedup departureDate. Chỉ hiện mỗi ngày 1 lần.

---

## 5. PLAN SỬA — THEO THỨ TỰ ƯU TIÊN

### Sprint 1 — Bug fixes (nhỏ, dứt điểm)

| # | Thay đổi | File | Độ khó |
|---|----------|------|--------|
| S1-1 | Xóa `huy\\s*tour` và `huy\\s*dat` khỏi isCancel | `IntentRouter.java:338` | Nhỏ |
| S1-2 | handleLookup: return null nếu msg không match BK pattern | `BookingConversationService.java` | Nhỏ |

### Sprint 2 — Search flow refactor (quan trọng nhất)

| # | Thay đổi | File | Độ khó |
|---|----------|------|--------|
| S2-1 | Xóa `askForMissingSearchInfoIfNeeded` | `BookingConversationService.java` | Vừa |
| S2-2 | Nếu có destination → `doSearch()` ngay | `BookingConversationService.java` | Nhỏ |
| S2-3 | Nếu không có destination → hỏi 1 câu ngắn gọn: "Bạn muốn đi đâu?" | `BookingConversationService.java` | Nhỏ |
| S2-4 | doSearch: dedup departure dates per tourId | `BookingConversationService.java` | Vừa |

### Sprint 3 — Tour selection UX

| # | Thay đổi | File | Độ khó |
|---|----------|------|--------|
| S3-1 | Khi user nói "đặt tour đó" ở SHOWING_SEARCH_RESULTS → hỏi chọn 1/2/3 nếu nhiều tour | `BookingConversationService.java` | Vừa |
| S3-2 | Quick actions sau khi hiện kết quả: [Đặt Tour 1] [Đặt Tour 2] [Đặt Tour 3] | `BookingConversationService.java` | Nhỏ |
| S3-3 | Nhận diện "tour 1/2/3", "cái đầu tiên", "cái thứ 2"... | `BookingConversationService.java` | Vừa |

### Sprint 4 — Intent simplification (dài hơi hơn)

| # | Thay đổi | File | Độ khó |
|---|----------|------|--------|
| S4-1 | Xóa fast-path riêng cho ASK_SLOT/PRICE/DATE/DETAIL → merge vào TOUR_RETRIEVAL | `IntentRouter.java` | Vừa |
| S4-2 | TOUR_RETRIEVAL → không xử lý riêng trong ChatbotService → fall qua RAG | `ChatbotService.java` | Vừa |
| S4-3 | UNKNOWN → GENERAL_RAG (không còn case UNKNOWN riêng) | `ChatbotService.java` | Nhỏ |
| S4-4 | Xóa buildContextAnswer, buildDiscountAnswer (merge vào RAG pipeline) | `ChatbotService.java` | Lớn |

---

## 6. FLOW MỚI ĐỀ XUẤT — AFTER FIX

### 6.1 Tìm tour đơn giản
```
User: tôi muốn đi Đà Nẵng
Bot:  Mình tìm được 3 tour đến Đà Nẵng:
      [Tour 1] Hà Nội - Đà Nẵng 4N3Đ | 8.500.000đ/người | KH: [15/07] [22/07]
      [Tour 2] HCM - Đà Nẵng 5N4Đ | 9.200.000đ/người | KH: [18/07] [25/07]
      [Tour 3] Đà Nẵng - Hội An 3N2Đ | 5.800.000đ/người | KH: [12/07]
      Bạn thích tour nào? (nhập 1, 2 hoặc 3)
      [Đặt Tour 1] [Đặt Tour 2] [Đặt Tour 3] [🔄 Tìm lại]

User: cái nào rẻ nhất?
Bot:  [RAG trả lời dựa trên results + Pinecone context]
      Sau đó: Bạn muốn đặt tour nào?

User: đặt tour 2
Bot:  Bạn đã chọn: HCM - Đà Nẵng 5N4Đ
      📅 Chọn ngày khởi hành:
        • [18/07/2026] — còn 15 chỗ
        • [25/07/2026] — còn 22 chỗ
```

### 6.2 Hỏi ngoài luồng giữa chừng booking
```
[Stage: COLLECTING_PASSENGERS]
User: ơ mà chính sách hủy tour như thế nào?
Bot:  [RAG trả lời chính sách hủy]
      ─────────────────────────────
      Bạn đang điền thông tin đặt tour **HCM - Đà Nẵng 5N4Đ**.
      [▶ Tiếp tục đặt tour] [✖ Hủy luồng]

User: tiếp tục đặt tour
Bot:  Vào lại bước điền hành khách 2/2:
      Hành khách 2 — Họ tên? Giới tính? Ngày sinh?
```

### 6.3 User nói câu dễ nhầm lẫn
```
User: chính sách hủy tour như thế nào?
Bot:  [RAG trả lời chính sách] — KHÔNG phải "Đã hủy luồng"

User: hủy bảo hiểm có ảnh hưởng không?
Bot:  [RAG trả lời về bảo hiểm] — KHÔNG phải "Đã hủy luồng"

User: huy (đứng một mình)
Bot:  Đã hủy. Bạn cần tư vấn hay đặt tour gì khác không? ✅
```

---

## 7. SƠ ĐỒ INTENT ĐỀ XUẤT SAU REFACTOR

```
Tin nhắn user
     │
     ▼
IntentRouter.route()
     │
     ├─► BK_PATTERN? → BOOKING_LOOKUP_PAYMENT
     ├─► isCancel()  → CANCEL (chỉ: huy/thoi/cancel đứng riêng, hoặc "thoi di", "huy di")
     ├─► isResume()  → RESUME_BOOKING
     ├─► isGreeting() → GREETING
     ├─► Stage-based fast-path (CONFIRMING, SHOWING_RESULTS, SELECTING_DEPARTURE)
     ├─► isLookupIntent() → BOOKING_LOOKUP_PAYMENT
     ├─► isBookingIntent() → TRANSACTION_FLOW
     ├─► Gemini classify → TRANSACTION_FLOW | BOOKING_LOOKUP_PAYMENT | TOUR_RETRIEVAL | GENERAL_RAG
     └─► fallback: GENERAL_RAG (không còn UNKNOWN riêng)

TRANSACTION_FLOW → BookingConversationService state machine
BOOKING_LOOKUP_PAYMENT → BookingConversationService lookup/payment
TOUR_RETRIEVAL → RAG (Pinecone search, Gemini generate)
GENERAL_RAG → RAG (Pinecone search, Gemini generate)
```

---

## 8. CÁC FILE CẦN THAY ĐỔI

| File | Thay đổi | Sprint |
|------|----------|--------|
| `IntentRouter.java` | isCancel: xóa `huy\\s*tour`, `huy\\s*dat`; xóa fast-path SLOT/PRICE/DATE/DETAIL | S1-1, S4-1 |
| `BookingConversationService.java` | handleLookup: check BK pattern; xóa `askForMissingSearchInfoIfNeeded`; search-immediately; dedup departures; selection UX | S1-2, S2-1..4, S3-1..3 |
| `ChatbotService.java` | TOUR_RETRIEVAL → fall through RAG; UNKNOWN → GENERAL_RAG; xóa buildContextAnswer | S4-2..4 |
| `GeminiIntentService.java` | Đơn giản hóa prompt chỉ còn 4 route groups | S4-1 |

---

## 9. CÁC ĐIỂM HOẠT ĐỘNG ĐÚNG — GIỮ NGUYÊN

| Tính năng | Trạng thái | Test case |
|-----------|------------|-----------|
| Cancel với "huy" đứng riêng | ✅ OK | 4.4 |
| Cancel với "thoi khong dat nua" | ✅ OK | 4.2 |
| "huy bo bao hiem" KHÔNG cancel | ✅ OK | 2.2, 3.8 |
| Lookup BK code trực tiếp | ✅ OK | 5.2, 5.3 |
| Lookup intent → hỏi mã | ✅ OK | 5.1 |
| Resume booking | ✅ OK | 6.3 |
| Hỏi ngoài luồng giữa COLLECTING_SEARCH_INFO | ✅ OK | 2.2, 3.3, 3.4 |
| SHOWING_SEARCH_RESULTS → chọn tour 2 | ✅ OK | 7.4, 7.5 |
| Departure selection sau chọn tour | ✅ OK | 7.5 |
| Quick actions resume/cancel khi đang booking | ✅ OK | flow |
| Greeting reset session cũ | ✅ OK | 1.1 |
| RAG trả lời hành lý, giảm giá | ✅ OK | 1.3, 1.4 |

---

## 10. TỔNG KẾT

### Ưu tiên sửa ngay (Sprint 1)

1. **IntentRouter.isCancel** — xóa `huy\\s*tour` và `huy\\s*dat`  
   → Sửa 1 dòng regex, impact lớn, rủi ro thấp

2. **handleLookup** — check BK pattern trước khi lookup  
   → Sửa ~5 dòng, ngăn bot tra cứu câu hỏi thường như BK code

### Ưu tiên sửa tiếp (Sprint 2)

3. **Search-immediately** — xóa `askForMissingSearchInfoIfNeeded`, search ngay khi có destination  
   → Đây là thay đổi UX lớn nhất, trực tiếp cải thiện trải nghiệm người dùng  
   → Sau khi sửa, bot sẽ không còn hỏi "Khởi hành từ đâu?" mà tìm ngay

4. **Dedup departure dates**  
   → Logic đơn giản, hiển thị gọn hơn

### Dài hơi hơn (Sprint 3-4)

5. **Tour selection UX** — quick actions đặt tour cụ thể  
6. **Intent simplification** — gộp SLOT/PRICE/DATE/DETAIL vào RAG  
   → Ít urgent hơn vì RAG hiện đã được gọi làm fallback
