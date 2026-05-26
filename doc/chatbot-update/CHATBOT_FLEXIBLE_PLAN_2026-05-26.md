# CHATBOT FLEXIBLE PLAN — Nâng cấp luồng linh hoạt
**Ngày:** 26/05/2026 (cập nhật lần 2)
**Người phân tích:** GitHub Copilot  
**Status:** REVISED — Plan cũ có 5 lỗi kiến trúc nghiêm trọng, xem §0

---

## 0. Những điểm SAI / THIẾU trong plan cũ (§0 — phải đọc trước)

Plan lần 1 bắt đúng các bug gốc nhưng **kiến trúc fix sai** ở 5 điểm. Nếu implement theo plan cũ bot vẫn sẽ:
- Trả lời sai số slot (Gemini hallucinate vì không có số thật)  
- Không nhận diện "còn mấy slot" / "giá tour 2 bao nhiêu" tại SHOWING_SEARCH_RESULTS  
- IntentRouter vẫn không chạy khi stage ≠ IDLE → toàn bộ intent routing bị bỏ qua  
- Fallback tour rác vẫn còn (plan cũ fix filter nhưng không xóa fallback)

| # | Vấn đề trong plan cũ | Hậu quả nếu giữ | Fix trong plan mới |
|---|---|---|---|
| A | `shouldSmartEscape` trong BookingConversationService dùng regex đơn giản để escape | Miss nhiều pattern; logic lặp lại với IntentRouter | Xóa `shouldSmartEscape`; IntentRouter chạy TRƯỚC trong ChatbotService |
| B | Contextual question (slot/giá) → `return null` → RAG → Gemini đoán | Gemini không biết số slot thật → hallucinate | Thêm `ContextQuestionHandler` đọc từ `state` (deterministic) |
| C | IntentRouter chỉ chạy khi `stage == IDLE` (ChatbotService code hiện tại) | Khi đang trong booking flow, IntentRouter không bao giờ được gọi | Chuyển IntentRouter lên **bước 1** — trước mọi delegate |
| D | Fallback tour rác: khi filter empty → `departureDocs = docs.stream().filter(TOUR_DEPARTURE)` | Tour ngẫu nhiên vẫn hiện | Xóa fallback; trả message "không tìm thấy + gợi ý thay destination" |
| E | P2 `searchStartLocation` chỉ là pseudo-code với "TODO" | Không filter được departure city | Thêm field vào ConversationState + filter Java-side hoàn chỉnh |

---

## 1. Tổng quan vấn đề (từ screenshots thực tế)

### 1.1 Bảng lỗi đã quan sát

| # | User nói gì | Stage hiện tại | Bot trả lời hiện tại | Kỳ vọng đúng |
|---|---|---|---|---|
| 1 | `tôi muốn đi đà lạt` | IDLE | 3 tour: Đà Nẵng, Sa Pa, Vũng Tàu ❌ | Tour Đà Lạt |
| 2 | `xem chi tiết tour sapa đi` | SHOWING_SEARCH_RESULTS | Tìm lại từ đầu (reset) | Xem chi tiết Sa Pa trong kết quả |
| 3 | `có tour khởi hành hcm ko` | SHOWING_SEARCH_RESULTS | 3 tour Sa Pa/Hạ Long/Vũng Tàu lẫn lộn | Lọc tour có điểm khởi hành HCM |
| 4 | `còn mấy slot` | SHOWING_SEARCH_RESULTS | "Bạn muốn chọn tour nào? Nhập 1,2,3" ❌ | Trả lời số slot của 3 tour đang hiển thị |
| 5 | `ùa sao ko xem được booking vậy nguu à` | SELECTING_DEPARTURE | "Tôi chưa tìm thấy ngày đó..." ❌ | Giải thích cách xem booking + cho thoát |
| 6 | `tôi muốn xem 1 booking thì sao` | SELECTING_DEPARTURE | "Tôi chưa tìm thấy ngày đó..." ❌ | Hướng dẫn xem booking hoặc nhận mã |
| 7 | Lặp lại 3 lần ở SELECTING_DEPARTURE | SELECTING_DEPARTURE | Loop vô hạn "nhập lại ngày" ❌ | Cho thoát sau 2 lần fail |

### 1.2 Mô tả ngắn gọn

Bot hiện tại hoạt động như **form wizard cứng**: một khi vào stage nào là bị nhốt trong đó, không thoát được dù hỏi bất cứ gì. Không giống chatbot AI thật — không giống nhân viên tư vấn du lịch thật.

---

## 2. Chẩn đoán kỹ thuật (root cause analysis)

### Bug #1 — `normalizeLocation` filter sai vế so sánh

**File:** `BookingConversationService.java` → method `doSearch()` — dòng ~140

```java
// CODE HIỆN TẠI (SAI):
String endLoc = String.valueOf(m.getOrDefault("endLocationName", "")).toLowerCase();
// endLoc = "đà lạt" (lowercase nhưng VẪN còn dấu tiếng Việt)

return endLoc.contains(normalizeLocation(destFilter));
// normalizeLocation("đà lạt") = "da lat" (đã bỏ dấu)
// "đà lạt".contains("da lat") = FALSE !!! → filter luôn fail
```

**Hậu quả:** Filter destination luôn trả về `false` → `departureDocs.isEmpty()` = true → fallback hiển thị **3 tour ngẫu nhiên** từ Pinecone không liên quan đến điểm đến yêu cầu.

**Fix:** Normalize cả 2 vế:
```java
// CODE ĐÚNG:
String normalizedDest = normalizeLocation(destFilter);
String endLoc = normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
String tourName = normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
String content = normalizeLocation(d.getContent() != null ? d.getContent() : "");
return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest) || content.contains(normalizedDest);
```

---

### Bug #2 — `handle()` không có đường thoát khi ở mid-flow stage

**File:** `BookingConversationService.java` → method `handle()` — dòng ~50

```java
// CODE HIỆN TẠI:
return switch (state.getStage()) {
    case SELECTING_DEPARTURE -> handleDepartureSelection(msg, ...); // LUÔN trả non-null
    case COLLECTING_PASSENGERS -> handlePassengerInfo(msg, ...);   // LUÔN trả non-null
    // ...
};
```

**Hậu quả:** `handle()` KHÔNG BAO GIỜ trả về `null` khi đang ở active stage → `ChatbotService` KHÔNG BAO GIỜ dùng RAG → mọi câu hỏi lệch đều bị stage handler xử lý sai.

**Cơ chế đúng** (đã có sẵn nhưng không được dùng):
```
ChatbotService:
  bookingService.handle() → null   ← đây là tín hiệu "tôi không xử lý được"
        ↓
  handleWithRAG() được gọi         ← RAG trả lời contextual
```

**Fix:** Thêm escape valve TRƯỚC switch statement trả về `null` cho off-topic input.

---

### Bug #3 — `handleTourSelection()` trap không clear old destination

**File:** `BookingConversationService.java` → method `handleTourSelection()` — dòng ~262

```java
// CODE HIỆN TẠI:
if (lower.length() > 8 && (lower.contains("tour") || lower.contains("đi") || ...)) {
    state.setStage(Stage.COLLECTING_SEARCH_INFO);
    parseAndFillSearchParams(msg, state); // không clear state.searchDestination trước!
    if (hasEnoughSearchParams(state)) return doSearch(sessionId, state);
}
```

**Hậu quả:** `state.searchDestination` vẫn giữ destination cũ (ví dụ "đà nẵng") → `parseAndFillSearchParams` không overwrite → `doSearch` search với destination cũ → trả cùng kết quả cũ.

---

### Bug #4 — `handleTourSelection()` không trả lời câu hỏi về kết quả

**File:** `BookingConversationService.java` → method `handleTourSelection()` — dòng ~262

```java
// Khi idx < 0 (không chọn được tour):
if (lower.length() > 8 && contains("tour","đi","tháng","thang")) {
    // ... re-search
}
return text("Bạn muốn chọn tour nào? Nhập 1,2,3"); // bắt chọn số ngay, không trả lời gì
```

**Hậu quả:** "còn mấy slot" → idx = -1 → không match trap → bot trả "Nhập 1,2,3" → **bỏ qua hoàn toàn câu hỏi về slot**.

---

### Bug #5 — `handleDepartureSelection()` không có lối thoát

**File:** `BookingConversationService.java` → method `handleDepartureSelection()` — dòng ~308

```java
// CODE HIỆN TẠI: chỉ check ngày, nếu không match:
if (matched == null) {
    return text("Tôi chưa tìm thấy ngày đó trong danh sách. Bạn nhập lại ngày..."); 
    // Loop vô hạn — không bao giờ trả null để RAG xử lý
}
```

---

### Bug #6 — `isBookingIntent()` miss nhiều pattern dấu tiếng Việt

**File:** `BookingConversationService.java` → method `isBookingIntent()` — dòng ~650

```java
// Regex dùng lower (lowercase nhưng còn dấu):
return lower.matches(".*(muốn\\s*đi|tim\\s*tour|...).*");
// "tìm tour" (có dấu) KHÔNG match "tim\\s*tour" → isBookingIntent = false!
// "muốn đi" = đúng nhưng thiếu nhiều pattern: "có tour", "cho xem tour", "tìm chuyến"
```

---

### Bug #7 — Không có xử lý "khởi hành từ X" (start location)

`parseAndFillSearchParams()` chỉ extract điểm ĐẾN (destination), không extract điểm KHỞI HÀNH.  
Khi user nói "có tour khởi hành hcm" → `searchDestination` = null → `hasEnoughSearchParams` = false → hỏi lại.

---

## 3. Kiến trúc sửa đổi (target architecture)

### 3.1 Nguyên tắc thiết kế

```
NGUYÊN TẮC MỚI (khác plan cũ):
  1. IntentRouter chạy LUÔN LUÔN — kể cả khi stage != IDLE
  2. ContextQuestionHandler đọc dữ liệu thật từ state — KHÔNG đưa cho Gemini
  3. Booking lookup = deterministic: extractBookingCode → performLookup (không Gemini)
  4. ReferenceResolver wire vào ChatbotService: "tour đó" → resolve → deterministic answer
  5. BookingConversationService.handle() chỉ xử lý stage-specific input (date, 1/2/3, name/phone/email)
     → trả null khi input không phải stage input → ChatbotService fallback RAG
  6. Stage trong Redis được GIỮ NGUYÊN khi RAG trả lời (user tiếp tục booking sau off-topic)
```

### 3.2 Luồng mới — ChatbotService.handleUserMessage()

```
┌───────────────────────────────────────────────────────┐
│  ChatbotService.handleUserMessage()                    │
│                                                        │
│  1. Load state (Redis)                                 │
│  2. addTurn(user, msg)                                 │
│                                                        │
│  3. IntentRouter.route(msg, state)  ← LUÔN chạy       │
│     → intent, resolvedTourId, resolvedDepId            │
│                                                        │
│  4. handleDeterministic(intent, state)                 │
│     ├── BOOKING_LOOKUP  → performLookup() [code exact] │
│     ├── ASK_SLOT        → buildSlotAnswer(state)       │
│     ├── ASK_PRICE       → buildPriceAnswer(state)      │
│     ├── ASK_DEPARTURE_DATE → buildDepDateAnswer(state) │
│     └── other           → null                         │
│                                                        │
│  5. if null: handleBookingFlow(intent, state)          │
│     ├── BOOKING_FLOW / TOUR_SEARCH / CHANGE_SEARCH     │
│     ├── CONFIRM / CANCEL (stage-aware)                 │
│     ├── stage != IDLE   → BookingConvService.handle()  │
│     └── null if can't handle                           │
│                                                        │
│  6. if null: handleWithRAG()  ← inject bookingContext  │
│     + append quickActions tiếp tục/hủy nếu stage!=IDLE│
│                                                        │
│  7. addTurn(assistant, reply); save state              │
└───────────────────────────────────────────────────────┘
```

### 3.3 Ví dụ từng case sau khi fix

**Case A: "còn mấy slot" tại SHOWING_SEARCH_RESULTS**
```
IntentRouter: isContextualQuestion → ASK_SLOT (via ReferenceResolver hoặc regex)
handleDeterministic: ASK_SLOT → buildSlotAnswer(state)
  state.getLastSearchResults() → đọc availableSlots thật
  → "Tour 1 Đà Lạt còn 15 chỗ, Tour 2 Hạ Long còn 8 chỗ, Tour 3 Nha Trang còn 20 chỗ"
stage KHÔNG thay đổi ✓, không gọi Gemini ✓
```

**Case B: "ùa sao ko xem được booking vậy" tại SELECTING_DEPARTURE**
```
IntentRouter: không phải date → UNKNOWN (không khớp booking/tour pattern)
handleDeterministic: UNKNOWN → null
handleBookingFlow: stage=SELECTING_DEPARTURE + msg không phải date-like → null  
handleWithRAG: gọi Gemini + inject bookingContext
  → "Để xem booking nhập mã BKxxxxxxxx vào chat.
     Nhân tiện, bạn đang chọn ngày tour [X]. Muốn tiếp tục không?"
quickActions: ["▶️ Tiếp tục", "❌ Hủy"]
stage vẫn = SELECTING_DEPARTURE ✓
```

**Case C: "tôi muốn đi đà lạt" tại COLLECTING_PASSENGERS**
```
IntentRouter: extractDestination → "đà lạt" → TOUR_SEARCH
handleDeterministic: TOUR_SEARCH → null (không phải slot/price)
handleBookingFlow: TOUR_SEARCH → clear state → BookingConvService → doSearch("đà lạt")
  doSearch: normalizeLocation(endLoc).contains("da lat") ✓ → kết quả Đà Lạt thật
```

**Case D: "BK3f7a9c12" tại bất kỳ stage**
```
IntentRouter: BK_PATTERN match → BOOKING_LOOKUP, bookingCode="BK3F7A9C12"
handleDeterministic: BOOKING_LOOKUP + code != null → performLookup("BK3F7A9C12")
  → show booking detail
state.stage = IDLE sau lookup ✓, không gọi Gemini ✓
```

**Case E: "giá tour 2 bao nhiêu" tại SHOWING_SEARCH_RESULTS**
```
IntentRouter: ASK_PRICE (regex match "giá") + "tour 2" → resolvedIndex=1
handleDeterministic: ASK_PRICE → buildPriceAnswer(state, resolvedIndex=1)
  state.getLastSearchResults().get(1).getAdultSalePrice() → "Tour 2 Hạ Long: 3.500.000đ/người lớn"
Không gọi Gemini ✓
```

---

## 4. Danh sách thay đổi cụ thể (REVISED)

---

### FILE 0: `ConversationState.java` — Thêm field `searchStartLocation`

**Thêm field mới** (P2):
```java
// Thêm sau searchDestination:
private String searchStartLocation; // điểm khởi hành (ví dụ: "hcm", "hà nội")
```

---

### FILE 1: `BookingConversationService.java`

#### Thay đổi 1 — Fix `doSearch()`: normalizeLocation 2 vế + XÓA fallback tour rác

**Vị trí:** Method `doSearch()`, block `.filter()` và block `if (departureDocs.isEmpty())`

```java
// ── THAY filter destination ──
// XÓA:
String endLoc = String.valueOf(m.getOrDefault("endLocationName", "")).toLowerCase();
String content = (d.getContent() != null ? d.getContent() : "").toLowerCase();
return endLoc.contains(normalizeLocation(destFilter)) ||
       content.contains(normalizeLocation(destFilter));

// THAY BẰNG (normalize CẢ 2 vế):
String normalizedDest = normalizeLocation(destFilter);
String endLoc   = normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
String tourName = normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
```

```java
// ── THAY fallback tour rác ──
// XÓA HOÀN TOÀN block này:
if (departureDocs.isEmpty()) {
    // Fallback: dùng kết quả không filter destination (vẫn hữu ích)
    departureDocs = docs.stream().filter(d -> "TOUR_DEPARTURE".equals(d.getType())).collect(Collectors.toList());
}

// THAY BẰNG: báo không tìm thấy + gợi ý
if (departureDocs.isEmpty()) {
    state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
    state.setSearchDestination(null);
    sessionService.save(sessionId, state);
    String destMsg = destFilter != null ? " đến **" + destFilter + "**" : "";
    return text("Mình chưa tìm được tour nào" + destMsg + " phù hợp 😅\n\n"
              + "Bạn thử:\n"
              + "• Đổi điểm đến (ví dụ: **Đà Nẵng**, **Nha Trang**, **Phú Quốc**)\n"
              + "• Thay đổi thời gian\n"
              + "• Hoặc mô tả lại tour bạn muốn", sessionId, "COLLECTING_SEARCH_INFO");
}
```

> **Lý do xóa fallback**: khi filter fail (ví dụ destination "đà lạt" không khớp), fallback hiện 3 tour ngẫu nhiên làm user bối rối. Sau khi fix normalizeLocation, filter sẽ hoạt động đúng. Nếu DB thật sự không có tour đến X → trả message rõ ràng, không show tour khác.

---

#### Thay đổi 2 — Thêm `searchStartLocation` vào `parseAndFillSearchParams()` + `buildSearchQuery()`

**Vị trí:** Method `parseAndFillSearchParams()`, thêm sau block extract destination:

```java
// THÊM: Extract start location (điểm khởi hành)
// normalize lower để match cả dạng có/không dấu
String normLower = normalizeLocation(lower);
String[][] startCityMap = {
    {"hcm","sai gon","ho chi minh","sài gòn","hồ chí minh"},
    {"ha noi","hà nội","hanoi"},
    {"da nang","đà nẵng","danang"},
    {"can tho","cần thơ"},
};
String[] startCityCanonical = {"hcm","hà nội","đà nẵng","cần thơ"};
boolean hasStartKeyword = normLower.matches(".*(khoi hanh|xuat phat|bay tu|di tu|tu.*di|departing).*");
if (hasStartKeyword) {
    for (int i = 0; i < startCityMap.length; i++) {
        for (String alias : startCityMap[i]) {
            if (normLower.contains(normalizeLocation(alias))) {
                state.setSearchStartLocation(startCityCanonical[i]);
                break;
            }
        }
    }
}
```

**Vị trí:** Method `buildSearchQuery()`:
```java
private String buildSearchQuery(ConversationState state) {
    StringBuilder q = new StringBuilder("tour du lịch");
    if (state.getSearchDestination() != null) q.append(" ").append(state.getSearchDestination());
    if (state.getSearchStartLocation() != null) q.append(" khởi hành ").append(state.getSearchStartLocation());
    if (state.getSearchDateRange() != null) q.append(" ").append(state.getSearchDateRange());
    q.append(" ").append(state.getSearchAdults()).append(" người lớn");
    if (state.getSearchChildren() > 0) q.append(" ").append(state.getSearchChildren()).append(" trẻ em");
    return q.toString();
}
```

**Vị trí:** `doSearch()`, trong filter block — thêm filter startLocation:
```java
// Sau filter endLoc, thêm filter startLocation (nếu có)
String startFilter = state.getSearchStartLocation();
List<VectorDocumentDTO> departureDocs = docs.stream()
    .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
    .filter(d -> {
        if (destFilter == null || destFilter.isEmpty()) return true;
        try {
            Map<String, Object> m = gson.fromJson(d.getMetadata(), Map.class);
            String normalizedDest = normalizeLocation(destFilter);
            String endLoc   = normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
            String tourName = normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
            return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
        } catch (Exception e) { return true; }
    })
    .filter(d -> {
        if (startFilter == null || startFilter.isEmpty()) return true;
        try {
            Map<String, Object> m = gson.fromJson(d.getMetadata(), Map.class);
            String normStart = normalizeLocation(startFilter);
            String startLoc  = normalizeLocation(String.valueOf(m.getOrDefault("startLocationName", "")));
            return startLoc.contains(normStart);
        } catch (Exception e) { return true; }
    })
    .collect(Collectors.toList());
```

> **Tăng topK lên 50**: `vectorService.searchSimilar(query, 50)` để Java filter có đủ candidate sau khi lọc 2 tầng.

---

#### Thay đổi 3 — Fix `handleDepartureSelection()`: thoát thông minh thay vì loop

**Vị trí:** Method `handleDepartureSelection()`, block `if (matched == null)`

```java
// XÓA:
if (matched == null) {
    return text("Tôi chưa tìm thấy ngày đó trong danh sách. Bạn nhập lại ngày...", sessionId, "SELECTING_DEPARTURE");
}

// THAY BẰNG:
if (matched == null) {
    // Kiểm tra xem input có "vẻ như" là ngày không
    boolean looksLikeDate = msg.replaceAll("[^\\d/\\-]", "").length() >= 3;
    if (looksLikeDate) {
        // User đang cố nhập ngày nhưng sai → re-show danh sách
        StringBuilder reSb = new StringBuilder("Không tìm thấy ngày **" + msg.trim() + "** 😅\n\n");
        reSb.append("Các ngày khởi hành còn chỗ:\n");
        if (selectedTour != null) {
            for (ConversationState.DepartureMeta dep : selectedTour.getDepartures()) {
                reSb.append("  • **").append(formatDate(dep.getDepartureDate())).append("**");
                if (dep.getAvailableSlots() != null && dep.getAvailableSlots() > 0)
                    reSb.append(" — còn ").append(dep.getAvailableSlots()).append(" chỗ");
                reSb.append("\n");
            }
        }
        reSb.append("\nNhập ngày theo dạng **DD/MM** (ví dụ: **10/06**).");
        reSb.append("\nGõ **Hủy** để chọn tour khác.");
        return text(reSb.toString(), sessionId, "SELECTING_DEPARTURE");
    } else {
        // Input không phải ngày → return null → ChatbotService xử lý bằng IntentRouter/RAG
        return null;
    }
}
```

---

#### Thay đổi 4 — Fix `handleTourSelection()`: clear destination cũ + hint rõ hơn

**Vị trí:** Method `handleTourSelection()`, block `if (idx < 0)`

```java
// XÓA:
if (lower.length() > 8 && (lower.contains("tour") || lower.contains("đi") || lower.contains("tháng") || lower.contains("thang"))) {
    state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
    parseAndFillSearchParams(msg, state); // BUG: không clear destination cũ
    if (hasEnoughSearchParams(state)) return doSearch(sessionId, state);
    sessionService.save(sessionId, state);
    return text("Bạn muốn tìm tour đến đâu? 🗺️", sessionId, "COLLECTING_SEARCH_INFO");
}
return text("Bạn muốn chọn tour nào? Nhập 1,2,3...", sessionId, "SHOWING_SEARCH_RESULTS");

// THAY BẰNG:
// Các trường hợp re-search rõ ràng
String normLower = normalizeLocation(lower);
boolean wantsNewSearch = normLower.matches(".*(tim|tim lai|tour khac|doi|muon di|di bien|di nui|tìm lại).*")
        || lower.contains("tìm lại") || lower.contains("tour khác") || lower.contains("đổi");
if (wantsNewSearch) {
    state.setSearchDestination(null);   // PHẢI clear trước
    state.setSearchStartLocation(null); // clear cả start location
    state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
    parseAndFillSearchParams(msg, state);
    if (hasEnoughSearchParams(state)) return doSearch(sessionId, state);
    sessionService.save(sessionId, state);
    return text("Bạn muốn tìm tour đến **đâu**? 🗺️", sessionId, "COLLECTING_SEARCH_INFO");
}
// Không nhận dạng → return null → ChatbotService xử lý (có thể là ASK_SLOT, ASK_PRICE, etc.)
return null;
```

> **Tại sao return null thay vì text "Nhập 1,2,3"?**  
> Vì "còn mấy slot" / "giá bao nhiêu" cũng vào đây với idx=-1 và không match wantsNewSearch.  
> return null → ChatbotService → IntentRouter → ASK_SLOT/ASK_PRICE → ContextQuestionHandler trả lời đúng.

---

#### Thay đổi 5 — Fix `isBookingIntent()` miss dấu tiếng Việt

```java
// THAY:
public boolean isBookingIntent(String msg) {
    String norm = normalizeLocation(msg); // strip diacritics
    return norm.matches(".*(dat\\s*tour|book\\s*tour|mua\\s*tour|"
            + "muon\\s*(di|dat)|toi\\s*(can|muon)\\s*dat|"
            + "tim\\s*tour|tim\\s*chuyen|co\\s*tour|"
            + "dat\\s*(cho|ngay)|cho.*xem.*tour|"
            + "xem\\s*tour|muon.*di\\s+[a-z]).*");
}
```

---

#### Thay đổi 6 — Expand `isLookupIntent()`

```java
// THAY:
public boolean isLookupIntent(String msg) {
    String lower = msg.toLowerCase();
    String norm  = normalizeLocation(lower);
    return norm.matches(".*(tra\\s*cuu|kiem\\s*tra\\s*don|xem\\s*don|tinh\\s*trang|"
            + "xem\\s*booking|tra\\s*booking|ma\\s*booking|"
            + "don\\s*cua\\s*toi|dat\\s*cua\\s*toi).*")
            || extractBookingCode(msg) != null;
}
```

---

### FILE 2: `ChatbotService.java` — Kiến trúc mới (quan trọng nhất)

#### Thay đổi 7 — IntentRouter luôn chạy TRƯỚC, thêm `handleDeterministic()` + `handleBookingFlow()`

**Thay toàn bộ block step 3→4 trong `handleUserMessage()`:**

```java
// XÓA TOÀN BỘ đoạn này:
// 3. If currently in booking flow — delegate to BookingConversationService
if (state.getStage() != ConversationState.Stage.IDLE) {
    log.info("📋 Session {} is in stage {}", sessionId, state.getStage());
    resp = bookingService.handle(finalRequest, state);
}
// 4. If still null, use IntentRouter to classify
if (resp == null) {
    IntentResult intent = intentRouter.route(userMessage, state);
    ...
    resp = switch (intent.getIntent()) { ... };
}

// THAY BẰNG:
// 3. IntentRouter LUÔN chạy — bất kể stage
IntentResult intent = intentRouter.route(userMessage, state);
log.info("🎯 Intent: {} (stage={}, source={}, conf={})",
         intent.getIntent(), state.getStage(), intent.getRawSource(), intent.getConfidence());

// 4a. Xử lý deterministic (đọc từ state, không cần Gemini)
resp = handleDeterministic(intent, userMessage, sessionId, state, finalRequest);

// 4b. Booking flow (state machine)
if (resp == null) {
    resp = handleBookingFlow(intent, finalRequest, state);
}
```

**Thêm 2 method mới vào ChatbotService:**

```java
/**
 * Xử lý deterministic: các câu hỏi có thể trả lời từ state hiện tại, không cần Gemini.
 * Returns null nếu không xử lý được → caller sẽ fallback sang booking flow hoặc RAG.
 */
private ChatMessageResponse handleDeterministic(IntentResult intent, String userMessage,
        String sessionId, ConversationState state, ChatMessageRequest request) {
    return switch (intent.getIntent()) {

        case BOOKING_LOOKUP -> {
            // Code đã extract bởi IntentRouter hoặc extract lại từ message
            String code = intent.getBookingCode() != null
                    ? intent.getBookingCode()
                    : bookingService.extractBookingCodePublic(userMessage);
            if (code != null) {
                log.info("🔍 Deterministic lookup: {}", code);
                yield bookingService.performLookupPublic(code, sessionId, state);
            }
            // Không có code trong message → hỏi user
            state.setStage(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
            sessionService.save(sessionId, state);
            yield ChatMessageResponse.builder()
                    .reply("Vui lòng cho tôi biết **mã đặt tour** (ví dụ: **BK3f7a9c12**):")
                    .sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                    .messageType("TEXT").conversationStage("COLLECTING_LOOKUP_CODE").build();
        }

        case ASK_SLOT -> {
            List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
            if (results == null || results.isEmpty()) yield null; // không có data → RAG
            
            // Determine which tour user is asking about
            Integer tourIdx = intent.getResolvedTourIdx(); // có thể null = hỏi tất cả
            StringBuilder sb = new StringBuilder("📊 **Số chỗ còn trống:**\n\n");
            for (int i = 0; i < results.size(); i++) {
                if (tourIdx != null && tourIdx != i) continue;
                ConversationState.TourGroupDisplay g = results.get(i);
                sb.append("**Tour ").append(i + 1).append(" — ").append(g.getTourName()).append(":**\n");
                for (ConversationState.DepartureMeta dep : g.getDepartures()) {
                    sb.append("  • ").append(formatDate(dep.getDepartureDate())).append(": ");
                    if (dep.getAvailableSlots() != null && dep.getAvailableSlots() > 0)
                        sb.append("còn **").append(dep.getAvailableSlots()).append(" chỗ**");
                    else
                        sb.append("⚠️ hết chỗ");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            sb.append("Bạn muốn chọn tour nào? Nhập **1**, **2** hoặc **3** 😊");
            yield ChatMessageResponse.builder()
                    .reply(sb.toString()).sessionId(sessionId)
                    .timestamp(java.time.LocalDateTime.now())
                    .messageType("TEXT").conversationStage("SHOWING_SEARCH_RESULTS").build();
        }

        case ASK_PRICE -> {
            List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
            if (results == null || results.isEmpty()) yield null;
            
            Integer tourIdx = intent.getResolvedTourIdx();
            StringBuilder sb = new StringBuilder("💰 **Giá tour:**\n\n");
            for (int i = 0; i < results.size(); i++) {
                if (tourIdx != null && tourIdx != i) continue;
                ConversationState.TourGroupDisplay g = results.get(i);
                sb.append("**Tour ").append(i + 1).append(" — ").append(g.getTourName()).append(":**\n");
                sb.append("  💵 Từ **")
                  .append(String.format("%,.0f", (double) g.getAdultSalePrice()))
                  .append("đ**/người lớn\n\n");
            }
            yield ChatMessageResponse.builder()
                    .reply(sb.toString()).sessionId(sessionId)
                    .timestamp(java.time.LocalDateTime.now())
                    .messageType("TEXT").conversationStage(state.getStage().name()).build();
        }

        case ASK_DEPARTURE_DATE -> {
            List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
            if (results == null || results.isEmpty()) yield null;
            
            Integer tourIdx = intent.getResolvedTourIdx();
            StringBuilder sb = new StringBuilder("📅 **Ngày khởi hành:**\n\n");
            for (int i = 0; i < results.size(); i++) {
                if (tourIdx != null && tourIdx != i) continue;
                ConversationState.TourGroupDisplay g = results.get(i);
                sb.append("**Tour ").append(i + 1).append(" — ").append(g.getTourName()).append(":**\n");
                for (ConversationState.DepartureMeta dep : g.getDepartures()) {
                    sb.append("  • **").append(formatDate(dep.getDepartureDate())).append("**");
                    if (dep.getAvailableSlots() != null)
                        sb.append(" — còn ").append(dep.getAvailableSlots()).append(" chỗ");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            yield ChatMessageResponse.builder()
                    .reply(sb.toString()).sessionId(sessionId)
                    .timestamp(java.time.LocalDateTime.now())
                    .messageType("TEXT").conversationStage(state.getStage().name()).build();
        }

        default -> null; // không xử lý deterministic → thử booking flow rồi RAG
    };
}

/**
 * Delegate sang BookingConversationService khi intent là booking/search/stage-active.
 */
private ChatMessageResponse handleBookingFlow(IntentResult intent,
        ChatMessageRequest request, ConversationState state) {
    boolean isBookingRelated =
            intent.getIntent() == IntentResult.Intent.BOOKING_FLOW  ||
            intent.getIntent() == IntentResult.Intent.TOUR_SEARCH    ||
            intent.getIntent() == IntentResult.Intent.CHANGE_SEARCH  ||
            state.getStage() != ConversationState.Stage.IDLE;

    if (!isBookingRelated) return null;

    // Pre-fill search params từ intent entities (TOUR_SEARCH từ IntentRouter)
    if (intent.getIntent() == IntentResult.Intent.TOUR_SEARCH
            || intent.getIntent() == IntentResult.Intent.CHANGE_SEARCH) {
        if (intent.getDestination() != null) {
            state.setSearchDestination(null); // clear cũ khi CHANGE_SEARCH
            state.setSearchStartLocation(null);
            state.setSearchDestination(intent.getDestination());
        }
        if (intent.getTravelMonth() != null) state.setSearchDateRange(intent.getTravelMonth());
        if (intent.getAdultCount()   != null && intent.getAdultCount() > 0)
            state.setSearchAdults(intent.getAdultCount());
    }

    return bookingService.handle(request, state); // may return null → falls to RAG
}
```

---

#### Thay đổi 8 — Inject booking context vào `handleWithRAG()` + quickActions resume

**Thêm method `buildBookingContextBlock()` + gọi trong `handleWithRAG()`:**

```java
private String buildBookingContextBlock(ConversationState state) {
    if (state.getStage() == ConversationState.Stage.IDLE) return "";
    
    StringBuilder ctx = new StringBuilder("\n\n=== BOOKING CONTEXT ===\n");
    ctx.append("User đang trong luồng đặt tour. Stage: ").append(state.getStage()).append("\n");
    if (state.getSelectedTourName() != null)
        ctx.append("Tour đang đặt: ").append(state.getSelectedTourName()).append("\n");
    if (state.getDepartureDateDisplay() != null)
        ctx.append("Ngày đã chọn: ").append(state.getDepartureDateDisplay()).append("\n");
    if (state.getSearchDestination() != null)
        ctx.append("Điểm đến đang tìm: ").append(state.getSearchDestination()).append("\n");
    ctx.append("\nSau khi trả lời, hãy nhắc nhẹ user tiếp tục hoặc hủy booking.\n");
    ctx.append("======================\n");
    return ctx.toString();
}

// TRONG handleWithRAG(), thay dòng build prompt:
// XÓA:
String prompt = buildEnhancedPromptWithHistory(userMessage, context, contextWindow);
// THAY BẰNG:
String bookingCtx = buildBookingContextBlock(state);
String prompt = buildEnhancedPromptWithHistory(userMessage, context + bookingCtx, contextWindow);

// THÊM quick actions nếu đang trong booking flow:
List<ChatMessageResponse.QuickAction> quickActions = buildQuickActions(request);
ConversationState.Stage stg = state.getStage();
if (stg != ConversationState.Stage.IDLE && stg != ConversationState.Stage.BOOKING_SUCCESS) {
    List<ChatMessageResponse.QuickAction> qa = new ArrayList<>(quickActions);
    qa.add(0, ChatMessageResponse.QuickAction.builder()
            .label("▶️ Tiếp tục đặt tour").action("RESUME_BOOKING").build());
    qa.add(ChatMessageResponse.QuickAction.builder()
            .label("❌ Hủy đặt tour").action("CANCEL").build());
    quickActions = qa;
}
```

---

#### Thay đổi 9 — `handleWithRAG()` trả stage hiện tại (không hardcode "IDLE")

```java
// XÓA:
.conversationStage("IDLE")

// THAY BẰNG:
.conversationStage(state.getStage().name())
```

---

### FILE 3: `IntentRouter.java` — Thêm ASK_SLOT, ASK_PRICE, ASK_DEPARTURE_DATE + resolvedTourIdx

#### Thay đổi 10 — Thêm detector cho ASK_SLOT/ASK_PRICE/ASK_DEPARTURE_DATE

**Thêm vào step 3 (Regex-based intent detection):**
```java
// THÊM sau isChangeSearch():
if (isAskSlot(lower))          return buildAskResult(Intent.ASK_SLOT,          lower, state);
if (isAskPrice(lower))         return buildAskResult(Intent.ASK_PRICE,         lower, state);
if (isAskDepartureDate(lower)) return buildAskResult(Intent.ASK_DEPARTURE_DATE,lower, state);

// ── Detectors mới ──
private boolean isAskSlot(String lower) {
    return lower.matches(".*(còn\\s*mấy\\s*slot|còn\\s*chỗ\\s*không|hết\\s*chỗ\\s*chưa|"
            + "slot|chỗ\\s*trống|bao\\s*nhiêu\\s*chỗ|con\\s*may\\s*slot).*");
}
private boolean isAskPrice(String lower) {
    return lower.matches(".*(giá\\s*(tour|chuyến|đó|này|bao|của)|bao\\s*nhiêu\\s*tiền|"
            + "mấy\\s*tiền|giá\\s*bao|chi\\s*phí|gia\\s*tour).*");
}
private boolean isAskDepartureDate(String lower) {
    return lower.matches(".*(ngày\\s*khởi\\s*hành|lịch\\s*khởi\\s*hành|ngày\\s*đi|"
            + "khi\\s*nào\\s*khởi\\s*hành|có\\s*chuyến\\s*ngày|ngay\\s*khoi\\s*hanh).*");
}

// ── Extract tour index từ "tour 1", "tour 2", "cái 1", "cái 2" ──
private IntentResult buildAskResult(Intent intent, String lower, ConversationState state) {
    Integer tourIdx = null;
    if (lower.contains("tour 1") || lower.contains("cái 1") || lower.contains("số 1")) tourIdx = 0;
    else if (lower.contains("tour 2") || lower.contains("cái 2") || lower.contains("số 2")) tourIdx = 1;
    else if (lower.contains("tour 3") || lower.contains("cái 3") || lower.contains("số 3")) tourIdx = 2;
    // "tour đó" / "cái này" → resolvedTourId từ state
    else if (lower.matches(".*(tour\\s*đó|cái\\s*(này|đó)|tour\\s*(này|vừa|kia)).*")) {
        if (state.getLastMentionedTourId() != null && state.getLastSearchResults() != null) {
            for (int i = 0; i < state.getLastSearchResults().size(); i++) {
                if (Objects.equals(state.getLastSearchResults().get(i).getTourId(), state.getLastMentionedTourId())) {
                    tourIdx = i; break;
                }
            }
        }
    }
    return IntentResult.builder()
            .intent(intent).resolvedTourIdx(tourIdx)
            .rawSource("fast-path").confidence(0.9).build();
}
```

---

### FILE 4: `IntentResult.java` — Thêm field `resolvedTourIdx`

```java
// Thêm field mới:
private Integer resolvedTourIdx; // 0-based index trong lastSearchResults (null = tất cả)
```

---

### FILE 5: `BookingConversationService.java` — Expose `extractBookingCode` và `performLookup` (public)

```java
// Đổi access modifier:
public String extractBookingCodePublic(String msg) {
    return extractBookingCode(msg); // gọi private method nội bộ
}

public ChatMessageResponse performLookupPublic(String code, String sessionId, ConversationState state) {
    return performLookup(code, sessionId, state); // gọi private method nội bộ
}
```

> **Tại sao expose?** ChatbotService.handleDeterministic() cần gọi các method này trực tiếp.  
> Alternative: di chuyển performLookup sang ChatbotService (nhưng sẽ cần inject nhiều Feign client hơn). Expose public là cách ít thay đổi nhất.

---

### FILE 6: Frontend — `client-side/src` xử lý quickAction RESUME/CANCEL/LOOKUP

**Vị trí:** Component xử lý quickAction (tìm `action` handler trong chatbot component)

```typescript
// Khi user click quickAction:
const handleQuickAction = (action: string) => {
  switch (action) {
    case 'RESUME_BOOKING':
      // Gửi tin nhắn "tiếp tục" để BookingConvService tiếp tục từ stage hiện tại
      sendMessage('tiếp tục đặt tour');
      break;
    case 'CANCEL':
      sendMessage('hủy');
      break;
    case 'RESET_SEARCH':
      sendMessage('tìm lại');
      break;
    case 'CONFIRM_BOOKING':
      sendMessage('xác nhận');
      break;
    case 'LOOKUP':
      // Mở input và focus để user nhập mã BK
      setInputPlaceholder('Nhập mã đặt tour (BKxxxxxxxx)...');
      focusInput();
      break;
    default:
      // Gửi label của button như text thường
      sendMessage(label);
  }
};
```

---

## 5. Sơ đồ luồng sau khi sửa

### 5.1 "tôi muốn đi đà lạt" — destination filter đúng

```
User: "tôi muốn đi đà lạt"
  IntentRouter: TOUR_SEARCH, destination="đà lạt"
  handleDeterministic: TOUR_SEARCH → null
  handleBookingFlow: TOUR_SEARCH → state.searchDestination="đà lạt"
    → BookingConvService → doSearch()
    → Pinecone query: "tour du lịch đà lạt 1 người lớn"
    → filter: normalizeLocation("Đà Lạt") = "da lat"
              normalizeLocation("đà lạt") = "da lat"
              "da lat".contains("da lat") = TRUE ✓
    → 3 tour Đà Lạt thật, không có Đà Nẵng/Sa Pa/Vũng Tàu ✓
```

### 5.2 "còn mấy slot" tại SHOWING_SEARCH_RESULTS — deterministic

```
User: "còn mấy slot"  (stage = SHOWING_SEARCH_RESULTS)
  IntentRouter: isAskSlot() = true → ASK_SLOT (resolvedTourIdx=null = hỏi tất cả)
  handleDeterministic: ASK_SLOT
    → state.getLastSearchResults() → 3 tour với slots thật trong state
    → "Tour 1 Đà Lạt còn 15 chỗ, Tour 2 Hạ Long còn 8 chỗ..."
  Gemini KHÔNG được gọi ✓, stage vẫn SHOWING_SEARCH_RESULTS ✓
```

### 5.3 "giá tour 2 bao nhiêu" — deterministic

```
User: "giá tour 2 bao nhiêu"  (stage = SHOWING_SEARCH_RESULTS)
  IntentRouter: isAskPrice() = true → ASK_PRICE, "tour 2" → resolvedTourIdx=1
  handleDeterministic: ASK_PRICE, idx=1
    → state.getLastSearchResults().get(1).getAdultSalePrice() = 3_500_000L
    → "Tour 2 Hạ Long: 3.500.000đ/người lớn"
  Không gọi Gemini ✓
```

### 5.4 "ùa sao ko xem được booking vậy" tại SELECTING_DEPARTURE — RAG với context

```
User: "ùa sao ko xem được booking vậy"  (stage = SELECTING_DEPARTURE)
  IntentRouter: không match booking/tour/date/price → UNKNOWN
  handleDeterministic: UNKNOWN → null
  handleBookingFlow: stage=SELECTING_DEPARTURE → BookingConvService.handle()
    handleDepartureSelection(): looksLikeDate("ùa sao...") = false → return null
  handleWithRAG():
    buildBookingContextBlock: "Stage SELECTING_DEPARTURE, tour Nha Trang, ..."
    Gemini: "Để xem booking, nhập mã BKxxxxxxxx vào chat.
             Nhân tiện bạn đang chọn ngày tour Nha Trang, muốn tiếp tục không?"
    quickActions: ["▶️ Tiếp tục đặt tour", "❌ Hủy"]
    stage vẫn = SELECTING_DEPARTURE ✓
```

### 5.5 "BK3f7a9c12" tại bất kỳ stage — deterministic lookup

```
User: "BK3f7a9c12"  (bất kỳ stage)
  IntentRouter: BK_PATTERN match → BOOKING_LOOKUP, bookingCode="BK3F7A9C12"
  handleDeterministic: BOOKING_LOOKUP + code != null
    → bookingService.performLookupPublic("BK3F7A9C12", ...)
    → show chi tiết đơn hàng
  Gemini KHÔNG được gọi ✓, lookup 100% chính xác ✓
```

### 5.6 "thôi đổi sang đi nha trang đi" tại COLLECTING_PASSENGERS — restart search

```
User: "thôi đổi sang đi nha trang đi"  (stage = COLLECTING_PASSENGERS)
  IntentRouter: extractDestination("nha trang") → TOUR_SEARCH, destination="nha trang"
  handleDeterministic: TOUR_SEARCH → null
  handleBookingFlow: TOUR_SEARCH → clear state → searchDestination="nha trang"
    → BookingConvService → doSearch("nha trang")
    → 3 tour Nha Trang ✓, booking cũ bị reset ✓
```

---

## 6. File cần chỉnh sửa và thứ tự ưu tiên (REVISED)

| Ưu tiên | File | Thay đổi | Tại sao |
|---------|------|----------|---------|
| 🔴 P0 | `BookingConversationService` | TĐ1: Fix normalizeLocation 2 vế | Sửa 100% bug sai kết quả destination |
| 🔴 P0 | `BookingConversationService` | TĐ1: Xóa fallback tour rác | Dừng hiển thị tour không liên quan |
| 🔴 P0 | `BookingConversationService` | TĐ3: handleDeparture trả null khi off-topic | Phá vòng lặp vô hạn |
| 🔴 P0 | `BookingConversationService` | TĐ4: handleTourSelection trả null khi không nhận dạng | "còn mấy slot" không còn bị bỏ qua |
| 🟠 P1 | `IntentResult.java` | TĐ file 4: Thêm `resolvedTourIdx` | Prerequisite cho ContextQuestionHandler |
| 🟠 P1 | `IntentRouter.java` | TĐ10: Thêm ASK_SLOT/ASK_PRICE/ASK_DEPARTURE_DATE | Phân loại đúng câu hỏi contextual |
| 🟠 P1 | `ChatbotService.java` | TĐ7: IntentRouter chạy TRƯỚC, thêm handleDeterministic + handleBookingFlow | Kiến trúc mới — central fix |
| 🟠 P1 | `ChatbotService.java` | TĐ8: Inject booking context vào RAG + quickActions | Bot trả lời đúng ngữ cảnh + nhắc resume |
| 🟠 P1 | `ChatbotService.java` | TĐ9: conversationStage = state.getStage() thay vì hardcode IDLE | Frontend biết đúng stage |
| 🟠 P1 | `BookingConversationService` | TĐ5/6: Fix isBookingIntent/isLookupIntent | Nhận pattern tiếng Việt có dấu |
| 🟠 P1 | `BookingConversationService` | TĐ file5: Expose extractBookingCodePublic/performLookupPublic | Cho ChatbotService gọi trực tiếp |
| 🟡 P2 | `ConversationState.java` | TĐ file0: Thêm `searchStartLocation` | Prerequisite cho P2 filter |
| 🟡 P2 | `BookingConversationService` | TĐ2: parseAndFillSearchParams + buildSearchQuery + doSearch filter start | Filter departure city |
| 🟢 P3 | Frontend chatbot component | TĐ file6: handleQuickAction RESUME/CANCEL/LOOKUP | UX nút tiếp tục/hủy hoạt động đúng |

---

## 7. Test cases cần verify sau khi fix

### Test 1: Destination filter — P0 (must pass)
```
Input:  "tôi muốn đi đà lạt"  (từ IDLE)
Expect: - Kết quả có "đà lạt" / "Đà Lạt" trong tourName/endLocationName
        - KHÔNG thấy tour Đà Nẵng, Sa Pa, Vũng Tàu trong kết quả
        - Nếu DB không có tour Đà Lạt → message "Không tìm thấy tour đến Đà Lạt..." thay vì random tours
```

### Test 2: ASK_SLOT deterministic — P1 (must pass)
```
Setup:  Session ở SHOWING_SEARCH_RESULTS, state có 3 tour với slots: [15, 8, 20]
Input:  "còn mấy slot"
Expect: - Bot show slots thật: "Tour 1... còn 15 chỗ, Tour 2... còn 8 chỗ, Tour 3... còn 20 chỗ"
        - KHÔNG phải "Bạn muốn chọn tour nào?"
        - Gemini KHÔNG được gọi
        - stage vẫn = SHOWING_SEARCH_RESULTS
```

### Test 3: ASK_PRICE deterministic — P1 (must pass)
```
Setup:  Session ở SHOWING_SEARCH_RESULTS
Input:  "giá tour 2 bao nhiêu"
Expect: - Giá đúng từ state.getLastSearchResults().get(1).getAdultSalePrice()
        - Gemini KHÔNG được gọi
```

### Test 4: Off-topic tại SELECTING_DEPARTURE — P0 (must pass)
```
Setup:  Session ở SELECTING_DEPARTURE
Input:  "ùa sao ko xem được booking vậy nguu à"
Expect: - Bot giải thích cách xem booking
        - Nhắc "đang đặt tour X, tiếp tục không?"
        - stage vẫn = SELECTING_DEPARTURE trong Redis
        - quickAction "▶️ Tiếp tục đặt tour" xuất hiện
```

### Test 5: BK lookup tại bất kỳ stage — P1 (must pass)
```
Setup:  Session ở COLLECTING_PASSENGERS
Input:  "BK3f7a9c12"
Expect: - show chi tiết đơn hàng BK3F7A9C12
        - stage = IDLE sau khi show
        - Không gọi Gemini
```

### Test 6: Re-search với destination mới — P1 (must pass)
```
Setup:  Session ở SHOWING_SEARCH_RESULTS, đang xem Sa Pa
Input:  "thôi đổi sang đi nha trang đi"
Expect: - Bot tìm tour Nha Trang (không phải Sa Pa)
        - state.searchDestination = "nha trang" (không phải "sa pa")
```

### Test 7: Số 1/2/3 vẫn chọn tour bình thường — regression
```
Setup:  Session ở SHOWING_SEARCH_RESULTS, có 3 tour
Input:  "1"
Expect: - Chọn tour 1 thành công, chuyển SELECTING_DEPARTURE
        - IntentRouter không hijack "1" → BookingConvService xử lý đúng
```

### Test 8: Filter start location — P2
```
Input:  "có tour khởi hành hcm không" (từ IDLE)
Expect: - searchStartLocation = "hcm"
        - doSearch filter startLocationName.contains("hcm")
        - Kết quả chỉ có tour khởi hành từ HCM
```

---

## 8. Kế hoạch build & deploy

```bash
# 1. Sau khi sửa code:
cd D:\HK8\tourism-microservices-backend
mvn -pl analytics-service package -DskipTests

# 2. Rebuild Docker image:
docker-compose build analytics-service

# 3. Restart container:
docker-compose up -d analytics-service

# 4. Verify container healthy:
docker-compose ps analytics-service

# 5. Smoke test:
$body = '{"message":"tôi muốn đi đà lạt","sessionId":"test_flex_1","userId":null}'
# Gửi request → verify kết quả có đà lạt

# 6. Test escape từ stage:
# Gửi chuỗi request để simulate mid-flow và hỏi lệch
```

---

## 9. Quyết định thiết kế (REVISED)

| Quyết định | Lý do | So với plan cũ |
|-----------|-------|----------------|
| IntentRouter chạy LUÔN trước state machine | Đây là điểm vào duy nhất để phân loại; state machine chỉ xử lý stage-specific input | **Khác** — plan cũ IntentRouter chỉ chạy khi IDLE |
| ContextQuestionHandler đọc từ state (deterministic) | Số slot/giá là data thật trong state; Gemini sẽ hallucinate hoặc outdated | **Khác** — plan cũ route qua RAG |
| performLookup expose public | ChatbotService cần gọi trực tiếp; tránh inject thêm Feign clients vào ChatbotService | Mới |
| handleDepartureSelection trả null khi off-topic | Input không phải ngày → let IntentRouter/RAG handle, không loop | **Khác** — plan cũ dùng `shouldSmartEscape` + `isGeneralQuestion` regex |
| Xóa fallback tour rác | User bị lừa khi thấy tour không liên quan; thông báo rõ ràng tốt hơn | **Khác** — plan cũ chỉ fix filter nhưng giữ fallback |
| handleTourSelection trả null (không phải "Nhập 1,2,3") | ASK_SLOT/ASK_PRICE với idx=-1 → IntentRouter phải được xử lý, không phải bị kẹt ở đây | **Khác** — plan cũ vẫn trả "Nhập 1,2,3" |
| searchStartLocation field riêng trong ConversationState | Tách biệt với destination để filter 2 tầng độc lập | **Khác** — plan cũ là TODO |
| Stage được GIỮ khi RAG trả lời | User hỏi xong vẫn tiếp tục booking, không mất progress | Giữ nguyên |
| conversationStage = state.getStage() thay vì hardcode | Frontend cần biết stage thật để show đúng UI | Mới |

---

## 10. DEPRECATED — Items từ plan cũ KHÔNG implement

Các mục sau từ plan lần 1 bị **thay thế** bởi kiến trúc mới:

| Mục cũ | Lý do deprecated | Thay bằng |
|--------|-----------------|-----------|
| `shouldSmartEscape()` + `performSmartEscape()` trong BookingConvService | Logic duplicate với IntentRouter; regex không đủ mạnh | IntentRouter chạy trước, handleDeterministic |
| `isGeneralQuestion()` + `isContextualQuestion()` helpers | Dùng để quyết định escape; logic này thuộc IntentRouter | `isAskSlot/isAskPrice/isAskDepartureDate` trong IntentRouter |
| `extractDestination()` helper trong BookingConvService | Đã có trong IntentRouter.extractSearchEntities() | IntentRouter |
| Thêm escape valve `if (shouldSmartEscape)` vào `handle()` | Logic escape bây giờ ở ChatbotService, không phải BookingConvService | `handleBookingFlow()` return null → RAG |


