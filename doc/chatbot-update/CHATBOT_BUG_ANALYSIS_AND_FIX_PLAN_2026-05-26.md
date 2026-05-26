# CHATBOT BUG ANALYSIS & FIX PLAN — 2026-05-26

Tài liệu này ghi lại kết quả test API thực tế (chạy session chatbot như người dùng), phân tích nguyên nhân gốc rễ từng lỗi, và kế hoạch sửa theo mức ưu tiên.

---

## 1. Kết quả test API (tóm tắt)

Môi trường: Docker container `tourism-analytics-service` đang chạy, gateway `localhost:8080`.

| Test | Kịch bản | Kết quả thực tế | Đúng/Sai |
|------|----------|-----------------|----------|
| A1 | `"cac tour nao duoc danh gia cao"` | `stage=COLLECTING_SEARCH_INFO` — bot hỏi "Bạn muốn đến đâu?" | ❌ Sai — nên vào RAG |
| B2 | Sau khi đã nói "ha long", gửi `"bay gio di da lat"` | Stage vẫn `COLLECTING_SEARCH_INFO`, bot vẫn nhắc Hạ Long | ❌ Sai — không đổi được điểm đến |
| B3 | Tiếp theo hỏi `"co tour nao ko"` | `still_halong=True` | ❌ Bị stuck |
| C1 | Sau session "ha long → thang 6", gửi `"xin chao"` | `stage=COLLECTING_SEARCH_INFO`, không về IDLE | ❌ Sai — greeting không reset stage |
| D1 | `"tour khac di"` (fresh session) | `stage=IDLE`, trả danh sách giảm giá | ✅ Chấp nhận được |
| E3 | `"2 nguoi lon"` trong luồng tìm Phú Quốc + HCM | `stage=SELECTING_DEPARTURE` — chọn "Cần Thơ" (sai tour!) | ❌ Sai — lọc sai, chọn nhầm tour |
| F2 | Sau `"co tour di nha trang khong"`, hỏi `"con may cho"` | `stage=COLLECTING_SEARCH_INFO`, reply "Mình chưa có tour cụ thể" | ❌ Sai — search chưa chạy, không có context |
| G3 | Off-topic `"thoi tiet ha noi hom nay"` khi đang trong booking flow | `stage=IDLE`, không có quickAction `RESUME_BOOKING` | ❌ Sai — mất luồng booking |
| H1 | `"xem don hang cua toi"` | `stage=IDLE`, reply hỏi mã BK (không vào COLLECTING_LOOKUP_CODE) | ⚠️ Hành vi OK nhưng stage không đúng |
| I1 | `"huy"` giữa chừng | `stage=IDLE`, reply "Đã hủy..." | ✅ OK |
| I2 | `"xin chao"` sau khi hủy | `stage=IDLE` | ✅ OK |

---

## 2. Log container — Vấn đề cốt lõi phát hiện

```
WARN GeminiIntentService error: 429 Too Many Requests
"Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests
limit: 20, model: gemini-3.5-flash"
→ Intent: UNKNOWN (source=fast-path, confidence=0.3)
```

**Gemini free tier chỉ cho 20 request/ngày**. Khi hết quota, mọi message không match fast-path regex đều bị phân loại `UNKNOWN` → đưa vào `handleWithRAG` → hành vi sai.

---

## 3. Phân tích lỗi chi tiết

---

### BUG 0 (P0) — Gemini 429: Quota free tier kiệt, nhiều intent bị UNKNOWN

**Triệu chứng:**
- Bất kỳ câu nào không match fast-path regex → Gemini được gọi → 429 → `UNKNOWN`
- Các câu bị ảnh hưởng: `"bay gio di da lat"`, `"thang 6"`, `"tour khac di"`, v.v.
- Hệ quả: nhiều bug phụ xuất hiện chỉ do UNKNOWN cascades

**Root cause:**
```java
// IntentRouter.java ~line 120
if (state.getRecentTurns() != null && !state.getRecentTurns().isEmpty()) {
    IntentResult geminiResult = geminiIntentService.classify(msg, state);
    // Gọi Gemini cho MỌI message không match fast-path
}
```
Gemini được gọi ngay cả với câu đơn giản như `"thang 6"`, `"2 nguoi"`, `"bay gio di da lat"`.

**Fix cần làm:**

a. **Mở rộng fast-path regex** để bao phủ thêm trường hợp:
```java
// Thêm vào isChangeSearch():
"bay\\s*gio\\s*(di|den)\\s+[a-z]" |  "doi\\s*(sang|qua|diem)" | "muon\\s*di\\s+[a-z]" 

// Thêm isMonthInput(): "thang\\s*[1-9]|tháng\\s*[1-9]"
// Thêm isPeopleInput(): "\\d+\\s*nguoi\\s*(lon|adult)"
// Thêm isSimpleNumber(): "^[1-9]$" (chọn tour 1/2/3)
```

b. **Cache kết quả Gemini** theo hash(message) trong Redis TTL 1h → không gọi lại câu giống nhau

c. **Khi Gemini 429, không trả UNKNOWN** — thay vào đó kiểm tra stage hiện tại để đoán intent:
```java
// Khi 429 và stage=COLLECTING_SEARCH_INFO → Intent=TOUR_SEARCH_PARTIAL
// Khi 429 và stage=SHOWING_SEARCH_RESULTS → Intent=TOUR_SELECTION
// Khi 429 và stage=IDLE → Intent=UNKNOWN (ok)
```

---

### BUG 1 (P0) — "tour nào được đánh giá cao" → COLLECTING_SEARCH_INFO (false booking intent)

**Triệu chứng:**
```
User: "cac tour nao duoc danh gia cao"  
stage = COLLECTING_SEARCH_INFO
Reply = "Bạn muốn đến đâu..."
```

**Root cause:**
```java
// IntentRouter.java ~line 166
private boolean isTourSearch(String s) {
    return s.matches(".*(tour\\s*(nao|den|di|o|tai|co|gia)|tim\\s*tour|...).*");
    //                         ^^^^^^^^^
    // "tour nào được đánh giá cao" → khớp "tour nao" → TOUR_SEARCH → booking flow!
}
```

**Fix:**
```java
// Thêm guard TRƯỚC khi check isTourSearch:
if (isRatingQuery(norm)) return buildResult(Intent.UNKNOWN, "fast-path-rag", 0.95);

private boolean isRatingQuery(String s) {
    return s.matches(".*(duoc\\s*danh\\s*gia|xep\\s*hang|noi\\s*tieng|pho\\s*bien|"
                   + "duoc\\s*yeu|tot\\s*nhat|uy\\s*tin|review|rating|danh\\s*gia\\s*cao).*");
}
```

---

### BUG 2 (P0) — Destination bị stuck, không thể đổi điểm đến

**Triệu chứng:**
```
User: "ha long"          → destination = "ha long", stage = COLLECTING_SEARCH_INFO
User: "bay gio di da lat" → stage = COLLECTING_SEARCH_INFO, bot vẫn nói "Hạ Long là lựa chọn..."
User: "co tour nao ko"   → still_halong = True (destination không đổi)
```

**Root cause — 2 tầng:**

**Tầng 1:** `"bay gio di da lat"` không match `isChangeSearch`:
```java
private boolean isChangeSearch(String s) {
    return s.matches(".*(tim\\s*lai|tim\\s*tour\\s*khac|doi\\s*diem|doi\\s*ngay|...).*");
    // "bay gio di da lat" KHÔNG khớp pattern nào → rơi vào Gemini
}
```

**Tầng 2:** Gemini 429 → `UNKNOWN` → `handleWithRAG` thay vì `handleBookingFlow` → `parseAndFillSearchParamsV3` không được gọi → destination không cập nhật.

**Fix tầng 1** — Mở rộng `isChangeSearch`:
```java
private boolean isChangeSearch(String s) {
    return s.matches(".*(tim\\s*lai|tim\\s*tour\\s*khac|doi\\s*diem|doi\\s*ngay|thay\\s*doi|"
                   + "bay\\s*gio\\s*(di|den)\\s+[a-z]|muon\\s*(di|den)\\s+[a-z]|"
                   + "di\\s+[a-z]+\\s*(di|thoi|luon)|thay|khac\\s*di).*");
}
```

**Fix tầng 2** — Trong `BookingConversationService.handleSearchInfo()`, khi gặp location mới **khác destination hiện tại**, override destination:
```java
private ChatMessageResponse handleSearchInfo(String msg, String sessionId, ConversationState state) {
    String oldDest = state.getSearchDestination();
    parseAndFillSearchParamsV3(msg, state);
    String newDest = state.getSearchDestination();
    // Nếu destination thay đổi → clear cached results
    if (oldDest != null && newDest != null && !oldDest.equals(newDest)) {
        state.setLastSearchResults(null);
        state.setLastDepartures(null);
        state.setLastMentionedTourId(null);
    }
    // ... existing logic
}
```

---

### BUG 3 (P1) — "xin chào" không reset stage về IDLE

**Triệu chứng:**
```
User: (đang COLLECTING_SEARCH_INFO về Hạ Long)
User: "xin chao"
stage = COLLECTING_SEARCH_INFO  ← vẫn còn Hạ Long session
```

**Root cause:**
```java
// ChatbotService.handleDeterministic()
case GREETING -> {
    return buildResponse("Chào bạn...", sessionId, state, List.of(...));
    // KHÔNG có: state.setStage(IDLE); sessionService.save(sessionId, state);
}
```

**Fix:**
```java
case GREETING -> {
    boolean hadActiveSession = state.getStage() != ConversationState.Stage.IDLE;
    // Reset toàn bộ state về IDLE khi chào
    state.setStage(ConversationState.Stage.IDLE);
    state.setSearchDestination(null);
    state.setSearchStartLocation(null);
    state.setSearchDateRange(null);
    state.setLastSearchResults(null);
    sessionService.save(sessionId, state);
    
    String reply = hadActiveSession
        ? "Chào lại bạn! Mình đã reset phiên cũ.\nBạn cần hỗ trợ gì?"
        : "Chào bạn, mình có thể hỗ trợ tìm tour, xem booking...";
    return buildResponse(reply, sessionId, state, List.of(...));
}
```

---

### BUG 4 (P1) — "co tour di Nha Trang không" → Collecting mode, không search ngay

**Triệu chứng:**
```
User: "co tour di nha trang khong"
stage = COLLECTING_SEARCH_INFO   ← bot hỏi thêm thay vì search ngay

User: "con may cho" (ASK_SLOT)
reply = "Mình chưa có tour cụ thể trong phiên này"  ← không có search results
```

**Root cause:**
`handleIdle` trong `BookingConversationService` gọi `parseAndFillSearchParamsV3` → set destination → trả về prompt hỏi thêm (thời gian, số người), chưa thực hiện search.

Nhưng user kỳ vọng: "có tour đi X không?" → **trả kết quả luôn**, không phải hỏi thêm.

**Fix:**
```java
// BookingConversationService.handleIdle()
// Sau parseAndFillSearchParamsV3, nếu chỉ có destination (không có ngày/người),
// vẫn thực hiện doSearch() với topK = 50 và hiển thị kết quả ngay,
// đồng thời trong reply nhắc nhở có thể lọc thêm theo thời gian/số người.

if (state.getSearchDestination() != null) {
    // Search ngay với destination có sẵn
    ChatMessageResponse searchResult = doSearch(sessionId, state);
    if (searchResult != null) return searchResult;
}
// Nếu search không ra → mới hỏi thêm thông tin
```

---

### BUG 5 (P1) — Off-topic giữa booking → stage reset IDLE, mất RESUME_BOOKING

**Triệu chứng:**
```
User: "dat tour" → stage = COLLECTING_SEARCH_INFO
User: "nha trang" → cập nhật destination
User: "thoi tiet ha noi hom nay"  ← off-topic
stage = IDLE, quickActions = [] (không có RESUME_BOOKING!)
```

**Root cause — 2 tầng:**

**Tầng 1:** `parseAndFillSearchParamsV3` extracts `"ha noi"` từ câu "thoi tiet ha noi" → `state.setSearchDestination("ha noi")` → overwrites "nha trang"!
```java
// parseAndFillSearchParamsV3 chạy extractKnownLocation("thoi tiet ha noi")
// → tìm thấy "ha noi" trong aliases → setSearchDestination("ha noi")
// Sai: chỉ cần extract destination khi có booking intent
```

**Tầng 2:** Sau khi overwrite destination thành "ha noi", doSearch chạy, không tìm được tour, state.setStage(IDLE) → handleWithRAG nhận stage=IDLE → không thêm RESUME_BOOKING

**Fix tầng 1** — `handleBookingFlow` phải kiểm tra intent trước khi gọi booking service:
```java
private ChatMessageResponse handleBookingFlow(IntentResult intent, ChatMessageRequest request, ConversationState state) {
    // Chỉ delegate sang booking service cho booking-related intents
    if (intent.getIntent() == Intent.UNKNOWN 
        || intent.getIntent() == Intent.GREETING
        || intent.getIntent() == Intent.ASK_DISCOUNT) {
        return null;  // Để handleWithRAG xử lý
    }
    // ... existing logic
}
```

**Fix tầng 2** — Trong `handleWithRAG`, khi stage != IDLE, **lưu lại stage trước khi return** (đã có code nhưng cần đảm bảo không bị override):
```java
// handleWithRAG luôn giữ stage không đổi khi stage != IDLE
// KHÔNG gọi state.setStage() trong RAG path
```

---

### BUG 6 (P2) — E3: Lọc tour sai sau khi có Phú Quốc + HCM

**Triệu chứng:**
```
User: "tim tour phu quoc khoi hanh hcm"
User: "2 nguoi lon"
stage = SELECTING_DEPARTURE — nhưng tour được chọn là "Cần Thơ", không phải Phú Quốc!
```

**Root cause:**
- `"2 nguoi lon"` không match fast-path → Gemini 429 → UNKNOWN → không vào booking flow
- Hoặc doSearch lọc sai, không tìm được tour Phú Quốc khởi hành HCM → fallback tour ngẫu nhiên

**Fix:**
- Mở rộng fast-path để `"2 nguoi lon"` → `isPeopleInput()` → vào booking flow
- Đảm bảo `doSearch` filter đúng `endLocation=phu quoc AND startLocation=hcm`

---

### BUG 7 (P2) — H1: "xem đơn hàng" không vào COLLECTING_LOOKUP_CODE

**Triệu chứng:**
```
User: "xem don hang cua toi"
stage = IDLE   ← đúng là IDLE nhưng không chuyển sang COLLECTING_LOOKUP_CODE
reply = "Bạn gửi mình mã booking dạng BK..."
```

**Root cause:**
```java
// ChatbotService.handleDeterministic() — BOOKING_LOOKUP case
case BOOKING_LOOKUP -> {
    String code = intent.getBookingCode();
    if (code != null) { return performLookup(code, ...); }
    // Không có BK code → hỏi user cung cấp mã
    // NHƯNG không set state.setStage(COLLECTING_LOOKUP_CODE)!
}
```

**Fix:**
```java
case BOOKING_LOOKUP -> {
    String code = intent.getBookingCode();
    if (code != null) { return bookingService.performLookupPublic(code, sessionId, state); }
    // Chuyển sang COLLECTING_LOOKUP_CODE để nhận BK ở turn tiếp theo
    state.setStage(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
    sessionService.save(sessionId, state);
    return buildResponse("Được. Bạn gửi mình mã booking dạng **BK...** nhé.", 
                         sessionId, state, List.of(...));
}
```

---

## 4. Kế hoạch sửa (theo ưu tiên)

### Phase 1 — P0: Sửa fast-path & Gemini quota (1-2 giờ)

**File:** `IntentRouter.java`

1. **Thêm `isRatingQuery()`** trước `isTourSearch()` → câu hỏi đánh giá tour đi vào RAG
2. **Mở rộng `isChangeSearch()`** bao phủ `"bay gio di X"`, `"muon di X"`, `"di X di"`
3. **Thêm `isMonthInput()`**: `"thang [1-9]"` → khi stage=COLLECTING_SEARCH_INFO → vào booking flow thay Gemini
4. **Thêm `isPeopleCountInput()`**: `"\\d+ nguoi lon"`, `"\\d+ adults"` → vào booking flow
5. **Khi Gemini 429**: fallback intent dựa trên `state.getStage()` thay vì UNKNOWN

---

### Phase 2 — P0: Sửa booking flow integrity (2-3 giờ)

**File:** `ChatbotService.java`

6. **GREETING case**: thêm `state.setStage(IDLE)` + `state.clearSearchContext()` + `sessionService.save()`
7. **BOOKING_LOOKUP case**: thêm `state.setStage(COLLECTING_LOOKUP_CODE)` khi không có BK code
8. **`handleBookingFlow()`**: kiểm tra intent != UNKNOWN trước khi delegate booking service → tránh off-topic làm hỏng state

**File:** `BookingConversationService.java`

9. **`handleIdle()`**: khi destination đã có, thực hiện doSearch ngay thay vì hỏi thêm
10. **`handleSearchInfo()`**: khi destination thay đổi → clear lastSearchResults, lastDepartures, lastMentionedTourId

---

### Phase 3 — P1: Cải thiện UX (1-2 giờ)

11. **Destination change UX**: khi detect destination mới, reply confirm "Oke, đổi sang **Đà Lạt** nhé. Tìm ngay..." 
12. **`handleWithRAG()`**: đảm bảo **không gọi `state.setStage()`** → stage luôn được preserve qua RAG
13. **`parseAndFillSearchParamsV3()`**: chỉ extract location khi có booking/search signal, không extract từ câu off-topic

---

### Phase 4 — P2: Nâng cao chất lượng (tuỳ chọn)

14. **Gemini caching**: cache intent result theo hash(message) trong Redis TTL 60 phút
15. **Tour filter debug**: log filter params + kết quả count để dễ debug lọc sai
16. **Test coverage**: unit test cho từng fast-path method trong IntentRouter

---

## 5. Tóm tắt lỗi

| # | Lỗi | File sửa | Ưu tiên |
|---|-----|----------|---------|
| B0 | Gemini 429 → UNKNOWN cascade | `IntentRouter.java` | P0 |
| B1 | Rating query → booking flow | `IntentRouter.java` | P0 |
| B2 | Destination stuck khi đổi | `IntentRouter.java`, `BookingConversationService.java` | P0 |
| B3 | Greeting không reset stage | `ChatbotService.java` | P1 |
| B4 | "có tour đi X không" → hỏi thêm, không search ngay | `BookingConversationService.java` | P1 |
| B5 | Off-topic → stage IDLE, mất RESUME_BOOKING | `ChatbotService.java`, `BookingConversationService.java` | P1 |
| B6 | Lọc tour sai (sai destination sau khi user cung cấp) | `BookingConversationService.java` | P2 |
| B7 | Lookup không vào COLLECTING_LOOKUP_CODE | `ChatbotService.java` | P2 |

---

## 6. Files cần sửa

```
analytics-service/src/main/java/com/tourism/analytics/service/
  ├── IntentRouter.java              # B0, B1, B2 (fast-path mở rộng, Gemini fallback)
  ├── ChatbotService.java            # B3, B5, B7 (GREETING reset, handleBookingFlow guard)
  └── BookingConversationService.java # B2, B4, B5, B6 (destination change, search ngay)
```

---

*Tạo bởi API test session 2026-05-26 23:45–00:10. Xem ảnh chụp màn hình frontend đính kèm để minh hoạ trực quan.*
