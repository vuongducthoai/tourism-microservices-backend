# CHATBOT CONTEXT ROUTING FIX PLAN - Khao sat API va huong sua

**Ngay:** 26/05/2026  
**Trang thai:** PLAN - Chua sua code theo plan nay  
**Can cu:** Khao sat API tren container analytics-service dang chay cong 8087, doi chieu `IntentRouter`, `ChatbotService`, `BookingConversationService`

---

## 1. Ket luan ngan

Chatbot dang tra loi "xang bay" khong phai vi thieu Pinecone/RAG, ma vi **RAG dang duoc goi sai thoi diem** va **state dang giu ket qua HCM qua manh**.

Loi lon nhat:

1. Nhieu cau co nghia nghiep vu ro nhu `toi dat tour`, `toi muon di nha trang`, `co tour di da lat ko` lai bi route thanh `UNKNOWN` roi roi xuong `handleWithRAG()`.
2. `handleWithRAG()` lay vector docs theo semantic, sau do Gemini tu dien dat thanh tour khuyen mai, nen bot hay chen tour HCM/Phu Quoc/Vung Tau du khach khong hoi.
3. `ASK_DETAIL` khi co `lastSearchResults` nhung user go ten tour ngoai danh sach thi van fallback ve tour dau tien trong state. Vi state dang co 3 tour khoi hanh HCM, bot tra chi tiet `TP. Ho Chi Minh - Vung Tau` du user hoi `Ha Noi - Hai Phong - Cat Ba`.
4. Sau khi search fail, `lastSearchResults` cu khong duoc clear nen cau `con may slot` van tra slot cua bo ket qua cu.
5. Gemini intent dang bi quota 429 lien tuc, nen khong the dua vao AI de sua routing. Routing P0 phai deterministic truoc.

---

## 2. Bang khao sat API thuc te

Endpoint test:

```text
POST http://localhost:8087/api/chatbot/chat
```

### Session 1 - dat tour/chon tour

| Input | Stage | Ket qua hien tai | Loi |
|---|---|---|---|
| `toi dat tour` | `IDLE` | Tra tour uu dai HCM/Ha Long/Vung Tau | Sai. Phai hoi muon dat tour nao/di dau, khong show promotion |
| `Ha Noi - Hai Phong - Cat Ba 2 Ngay 1 Dem xem chi tiet` | `IDLE` | RAG tra dung tour nhung khong luu state | Chua vao luong dat tour, follow-up se mat ngu canh |
| `muon dat dat tour nay TP. Ho Chi Minh - can tho 2 Ngay 1 Dem` | `IDLE` | RAG tu sua thanh Can Tho 3N2D | Sai. Neu ten/so ngay khong khop phai hoi xac nhan, khong tu doi tour |
| `tour khoi hanh ha noi di bien` | `COLLECTING_SEARCH_INFO` | Khong tim thay, xoa search params | Co the dung neu DB khong co, nhung can hoi co muon xem diem bien khac tu Ha Noi khong |

### Session 2 - ket qua HCM bi giu qua lau

| Input | Stage | Ket qua hien tai | Loi |
|---|---|---|---|
| `tour khoi hanh hcm ko` | `SHOWING_SEARCH_RESULTS` | Tra 3 tour HCM | Dung |
| `Ha Noi - Hai Phong - Cat Ba 2 Ngay 1 Dem xem chi tiet` | `SHOWING_SEARCH_RESULTS` | Tra chi tiet `TP. Ho Chi Minh - Vung Tau` | Sai nghiem trong. `ASK_DETAIL` khong resolve ten tour trong message, nen lay tour dau tien |
| `tour khoi hanh ha noi di bien` | `COLLECTING_SEARCH_INFO` | Khong tim thay | Tam chap nhan, nhung phai clear old results |
| `con may slot` | `COLLECTING_SEARCH_INFO` | Van tra slot 3 tour HCM cu | Sai. Search fail nhung `lastSearchResults` cu chua clear |
| `1` | `COLLECTING_SEARCH_INFO` | Hoi lai diem den/thoi gian | Do stage da bi doi, mat kha nang chon tour cu |

### Session 3 - no-result va RAG

| Input | Stage | Ket qua hien tai | Loi |
|---|---|---|---|
| `co tour di phu yen ko` | `IDLE` | Bao khong co Phu Yen nhung chen tour Phu Quoc/HCM | Sai. No-result khong duoc quang cao tour khac neu user chua dong y |
| `co tour di da lat ko` | `IDLE` | Bao khong co Da Lat nhung chen Da Nang/Sa Pa/HCM | Sai tuong tu |
| `toi muon di nha trang` | `IDLE` | RAG tra tour Nha Trang uu dai, stage van IDLE | Sai. Phai vao search flow va luu `lastSearchResults` |
| `xem chi tiet tour` | `IDLE` | RAG tra Phu Quoc | Sai. Bot khong nho Nha Trang vi lan truoc RAG khong luu state |

---

## 3. Root cause theo code

### 3.1 `IntentRouter` con qua hep

`IntentRouter.isTourSearch()` khong bat duoc nhieu cau tu nhien:

```text
toi muon di nha trang
co tour di phu yen ko
co tour di da lat ko
toi dat tour
dat tour nay + ten tour
```

Khi khong bat duoc, log cho thay intent thanh:

```text
Intent: UNKNOWN (source=fast-path, confidence=0.3)
```

Sau do `ChatbotService` roi xuong RAG/Gemini.

### 3.2 `handleWithRAG()` dang co quyen tra tour qua lon

`handleWithRAG()` goi:

```java
vectorService.searchSimilar(userMessage, topK)
buildEnhancedContext(docs, userMessage)
callGeminiAPI(prompt)
buildTourSuggestions(docs)
```

Voi cau search tour/no-result, Pinecone van tra candidate gan nghia. Gemini thay context co tour uu dai thi tra tour HCM/Phu Quoc. Day la ly do user hoi gi cung bi keo ve tour uu dai.

### 3.3 `ASK_DETAIL` resolve sai tour

`buildTourDetailAnswer()` goi `resolveTargetTours()`. Neu khong co `resolvedTourIdx`/`resolvedTourId`, code dang fallback:

```java
if (state.getLastMentionedTourId() != null) return mentioned;
if (results.size() == 1) return results.get(0);
return results;
```

Nhung `IntentRouter.buildAskResult()` khong extract ten tour trong message. Vi vay cau:

```text
Ha Noi - Hai Phong - Cat Ba 2 Ngay 1 Dem xem chi tiet
```

dang o state HCM se bi coi la `ASK_DETAIL` chung chung, roi fallback ve tour HCM dau tien.

### 3.4 Search fail khong clear context cu

Khi `doSearch()` khong tim thay, code xoa destination/startLocation nhung chua clear:

```text
lastSearchResults
lastMentionedTourId
selectedTourId
selectedDepartureId
```

Nen sau no-result, user hoi `con may slot`, bot van doc slot cua bo ket qua cu.

### 3.5 Prompt RAG bi thien ve promotion

`buildEnhancedPrompt()` co nhieu rule ve giam gia/coupon va format tour uu dai, ke ca khi user khong hoi giam gia. Dieu nay lam Gemini co xu huong chot bang tour promotion.

### 3.6 Gemini intent dang bi quota 429

Log container co nhieu dong:

```text
GeminiIntentService error: 429 Too Many Requests
Intent: UNKNOWN
```

Nen P0 khong duoc phu thuoc vao Gemini classify. Phai sua deterministic routing truoc.

---

## 4. Plan sua de chatbot linh hoat va dung ngu canh

### P0 - Chan RAG tra tour sai

1. Them lop `ChatbotOrchestrator` logic ro rang trong `ChatbotService`:

```text
IntentRouter
  -> deterministic business handlers
  -> tour search handler
  -> booking state machine
  -> RAG advice only
```

2. `handleWithRAG()` khong duoc tra tour cards khi:

```text
intent = UNKNOWN
user hoi booking/payment/system help
user hoi detail nhung khong resolve duoc tour
user hoi destination cu the ma search khong co ket qua
```

3. Neu user hoi destination cu the va khong co tour, tra no-result trung thuc:

```text
Hien minh chua thay tour Phu Yen dang mo ban.
Ban muon xem goi y diem bien khac nhu Nha Trang/Da Nang/Phu Quoc khong?
```

Khong tu chen tour khac.

### P1 - Sua intent va entity extraction

1. `IntentRouter` phai bat duoc cac mau:

```text
toi dat tour
toi muon dat tour
dat tour nay
dat tour + ten tour
toi muon di + destination
co tour di + destination
co tour den + destination
destination + xem chi tiet
```

2. Tach intent:

```text
BOOKING_START
BOOKING_SELECT_BY_NAME
TOUR_SEARCH_DESTINATION
TOUR_SEARCH_START_LOCATION
ASK_DETAIL_BY_NAME
ASK_DETAIL_CURRENT
NO_RESULT_CLARIFY
```

3. `LocationResolverService` van dung DB/Pinecone, nhung ket qua location phai kem role:

```text
destination
startLocation
unknownMention
```

4. Neu text co ten tour ro, can resolve theo:

```text
lastSearchResults exact/contains
Pinecone TOUR_DEPARTURE direct metadata
tour-catalog API neu co
```

Khong resolve duoc thi hoi lai, khong fallback tour dau tien.

### P2 - Search flow phai luu state nhat quan

1. Moi ket qua tour hien ra cho user, du tu search hay RAG candidate, phai di qua mot `TourSearchService` chung va luu:

```text
lastSearchResults
lastMentionedTourId
stage = SHOWING_SEARCH_RESULTS
```

2. Khong de Gemini tao danh sach tour rieng. Gemini chi duoc tom tat sau khi backend da chon tour hop le.

3. Search fail phai clear context cu:

```text
lastSearchResults = []
lastMentionedTourId = null
selectedTourId = null
selectedDepartureId = null
```

4. Neu user dang trong `SHOWING_SEARCH_RESULTS` ma hoi tour ngoai danh sach, bot phai:

```text
Tim tour do trong he thong
Neu co -> hien detail/search result moi
Neu khong -> hoi lai
```

Khong lay tour cu.

### P3 - Context answer dung tour dang hoi

1. `ReferenceResolverService` can xu ly:

```text
tour nay
tour do
tour tren
tour vua roi
tour + ten rieng
ten destination trong lastSearchResults
```

2. Neu user hoi `xem chi tiet tour`:

```text
lastSearchResults size = 1 -> detail tour do
size > 1 -> hoi tour 1/2/3
khong co state -> hoi user ten tour hoac diem den
```

3. Neu user hoi `con may slot` sau no-result:

```text
Neu lastSearchResults empty -> "Ban dang muon kiem tra slot tour nao?"
```

Khong dung ket qua cu.

### P4 - RAG dung vi tri

RAG/Pinecone/Gemini chi nen dung cho:

```text
tu van chung
tom tat lich trinh
mo ta tour da resolve duoc
review/chinh sach/FAQ
goi y thay the khi user dong y
```

RAG khong duoc quyet dinh:

```text
gia/slot/ngay khoi hanh
booking/payment status
tour nao la selectedTour
co hay khong co destination cu the
```

### P5 - Giam phu thuoc Gemini

1. Gemini intent classify chi la fallback sau deterministic, khong phai phao cuu bat buoc.
2. Neu Gemini 429, chatbot van phai tra loi duoc cac intent nghiep vu co ban.
3. Log moi response can co:

```text
sessionId
stageBefore
intent
handler
stageAfter
usedRag
usedGemini
resolvedTourId
clearedContext
```

---

## 5. Acceptance test sau khi sua

### Search/no-result

| Case | Expected |
|---|---|
| `co tour di phu yen ko` | Neu khong co Phu Yen, bao khong co, khong show Phu Quoc/HCM |
| `co tour di da lat ko` | Neu khong co Da Lat, bao khong co, khong show Da Nang/Sa Pa |
| `toi muon di nha trang` | Vao search flow, stage `SHOWING_SEARCH_RESULTS`, luu `lastSearchResults` |
| `xem chi tiet tour` sau Nha Trang | Hien chi tiet Nha Trang, khong nhay Phu Quoc |

### Context/detail

| Case | Expected |
|---|---|
| Dang xem 3 tour HCM, go `Ha Noi - Hai Phong - Cat Ba xem chi tiet` | Khong tra Vung Tau. Hoac tim Cat Ba, hoac noi khong nam trong ket qua va hoi co muon tim tour Cat Ba |
| Dang xem 3 tour, go `xem chi tiet tour 2` | Hien dung tour 2 |
| Dang xem 1 tour, go `tour nay co gi` | Hien dung tour do |

### Booking flow

| Case | Expected |
|---|---|
| `toi dat tour` | Hoi user muon dat tour nao/di dau, khong show deal |
| `dat tour nay` sau khi co selected tour | Chuyen sang chon ngay khoi hanh |
| `dat tour + ten tour` | Resolve ten tour, hoi ngay khoi hanh |
| `huy` o moi stage | Reset state sach |

### Old context cleanup

| Case | Expected |
|---|---|
| Search HCM thanh cong, sau do search Ha Noi di bien fail, roi hoi `con may slot` | Khong tra slot HCM cu |
| No-result xong nhap `1` | Khong chon tour cu, hoi lai search context |

---

## 6. Thu tu lam de it pha he thong

1. Sua `IntentRouter` truoc: bo sung intent cho booking/search/detail by name.
2. Sua `ChatbotService`: khong de RAG tra tour khi intent nghiep vu chua xu ly xong.
3. Tao/tach `TourSearchService`: search Pinecone + filter metadata + luu state theo mot duong duy nhat.
4. Sua `ReferenceResolverService`: resolve ten tour trong message, khong fallback tour dau tien khi co ten tour khac.
5. Sua cleanup state khi no-result/cancel/change-search.
6. Viet regression tests theo bang acceptance.
7. Chay API scripted moi lan deploy va log intent/stage.

---

## 7. Mac dinh thiet ke de implement

- Pinecone dung de lay candidate/context, khong dung lam router dau tien.
- DB/API/state la source of truth cho tour selected, gia, slot, ngay, booking, payment.
- Gemini chi dien dat va tom tat, khong duoc tu chon tour thay backend.
- No-result phai thanh that. Goi y tour khac chi hien khi user dong y.
- Neu khong resolve duoc reference, bot hoi lai ngan gon thay vi doan.

