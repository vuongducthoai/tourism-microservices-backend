# CHATBOT LONG SESSION SURVEY + RAG-FIRST FIX PLAN - 2026-05-27

**Pham vi:** Khao sat API chatbot dang chay Docker, khong sua code.  
**Endpoint test:** `POST http://localhost:8080/api/chatbot/chat` va health `8080/8087`.  
**Session test:** `codex-survey-*`, `codex-detail-*`, `codex-long-*`.  
**Ket luan ngan:** Bot dang co Pinecone/RAG, nhung luong dieu phoi sai nen nhieu cau khong di qua retrieval/filter dung cach. Loi nang nhat hien tai la state machine bi dieu huong sai, entity resolver bi over-trust vector, va text backend bi mojibake.

---

## 1. Ket qua API thuc te

### 1.1 Nha Trang flow bi mat context

Flow test:

```text
xin chao
toi muon di nha trang
xem chi tiet tour
con may slot
gia tour nay bao nhieu
toi muon dat tour nay
```

Ket qua:

- `toi muon di nha trang` -> `COLLECTING_SEARCH_INFO`, bot hoi them start/time/adults, chua co `lastSearchResults`.
- `xem chi tiet tour` -> bot noi chua xac dinh duoc tour.
- `con may slot` -> bot noi chua co tour cu the.
- `gia tour nay bao nhieu` -> bot noi chua biet hoi gia tour nao.
- `toi muon dat tour nay` -> lap lai cau hoi thong tin, khong dat duoc.

Nhan xet:

- Neu da chon flow kieu Vietravel hoi them thong tin truoc khi show tour thi dung, nhung bot phai nho `pendingDestination=Nha Trang`.
- Follow-up ve detail/slot/gia khi chua co result nen duoc tra loi: "Minh can khoi hanh/thoi gian/so nguoi de tim tour Nha Trang truoc", khong nen noi chung chung "chua co tour cu the".

---

### 1.2 Reply bo sung diem khoi hanh bi hieu sai thanh diem den

Flow test:

```text
toi muon di nha trang
ha noi
thang 6
2 nguoi lon
```

Ket qua:

- Sau `toi muon di nha trang`, state hoi them start/time/adults.
- User tra `ha noi`.
- Bot doi destination thanh **Hoi An** va tiep tuc hoi start/time/adults.

Day la loi nghiem trong. Cau `ha noi` trong ngu canh dang hoi "Khoi hanh tu dau?" phai duoc set vao `searchStartLocation=Ha Noi`, khong duoc resolve thanh destination moi, cang khong duoc thanh Hoi An.

Nguon nghi ngo trong code:

- `ChatbotService.handleBookingFlow()` quanh dong 342 co logic tu gan `intent.destination` thanh `searchStartLocation` khi dang collect destination, nhung sau do van delegate lai `BookingConversationService.handle()`.
- `BookingConversationService.parseAndFillSearchParamsV3()` quanh dong 871 lai tiep tuc resolve location tu message.
- `LocationResolverService.resolveFromVectors()` dung Pinecone fallback cho ca location extraction. Voi input ngan nhu `ha noi`, semantic vector co the tra document gan sai, dan den "Hoi An".

Ket luan: **location extraction khong duoc dung semantic fallback cho input ngan va cho cau tra loi stage-specific**. Short answer trong stage phai duoc parse theo slot dang hoi.

---

### 1.3 Chon tour `1` bi search lai, khong vao SELECTING_DEPARTURE

Flow test:

```text
tour khoi hanh hcm ko
1
20/03
```

Ket qua:

- `tour khoi hanh hcm ko` -> `SHOWING_SEARCH_RESULTS`, co 3 cards HCM.
- `1` -> van `SHOWING_SEARCH_RESULTS`, bot lap lai 3 tour, khong chon tour.
- `20/03` -> van `SHOWING_SEARCH_RESULTS`, bot lap lai 3 tour.

Nguon loi trong code:

- `IntentRouter.route()` neu stage `SHOWING_SEARCH_RESULTS` va msg `1` thi return `TOUR_SEARCH`.
- `ChatbotService.handleBookingFlow()` case `TOUR_SEARCH` lai set stage ve `COLLECTING_SEARCH_INFO` khi stage la `SHOWING_SEARCH_RESULTS`.
- Sau do delegate `bookingService.handle()`, nhung stage da bi doi nen khong vao `handleTourSelection()`.

Ket luan: day la bug dieu phoi P0. Trong `SHOWING_SEARCH_RESULTS`, input `1/2/3` phai la **stage input**, khong phai search intent.

---

### 1.4 No-result da tot hon, nhung state/UX chua dung

Flow test:

```text
co tour di phu yen ko
con may slot
xem chi tiet tour do
```

Ket qua:

- Bot noi chua co tour Phu Yen dang mo ban, khong show tour rac. Diem nay tot.
- Sau do `con may slot` va `xem chi tiet tour do` tra loi chua co tour cu the.

Can cai thien:

- Sau no-result, bot nen clear result context va dat `pendingNoResultDestination=Phu Yen`.
- Neu user hoi tiep `goi y tuong tu`, bot moi dung RAG/category de goi y tour bien gan tuong tu.
- Khong nen them nut `Tiep tuc dat tour` neu chua co booking flow thuc su.

---

### 1.5 Booking/help thoat luong con loi resume

Flow test:

```text
tour khoi hanh hcm ko
tour nao dang giam gia ko
xem booking sao
tiep tuc dat tour
```

Ket qua:

- Discount tra duoc, nhung qua dai va khong gioi han tot.
- `xem booking sao` -> stage thanh `COLLECTING_LOOKUP_CODE`.
- `tiep tuc dat tour` -> bot bao chua co luong dat tour dang cho.

Nguon loi:

- Khi vao lookup, state cu bi overwrite stage thanh `COLLECTING_LOOKUP_CODE`.
- Khong co `previousStage` / `suspendedBookingState` de quay lai flow truoc do.

Ket luan: can co co che **pause/resume flow**. Xem booking/payment la side quest, khong duoc pha booking/search context.

---

### 1.6 Text tieng Viet bi mojibake tu backend

API reply tra ve dang:

```text
Dáº¡ tuyá»t vá»i...
Äá» mÃ¬nh...
```

Day khong phai loi frontend don thuan. Source Java hien tai cung da chua chuoi mojibake trong `ChatbotService`, `BookingConversationService`, `VectorSyncService`, `application.yml`.

Ket luan:

- Can sua encoding source ve UTF-8 dung.
- Sau do build image lai.
- Neu Pinecone da duoc sync bang content mojibake, can clear/resync vector sau khi content builder duoc sua.

---

## 2. Vi sao hien tai "co RAG ma van ngao"

He thong dang co 2 duong tra loi tour:

1. **Booking/Search state path**
   - `IntentRouter` -> `BookingConversationService.doSearch()`
   - Co goi `VectorService.searchSimilar(query, 50)`
   - Co filter destination/startLocation bang metadata.
   - Co luu `lastSearchResults`.

2. **Generic RAG path**
   - `ChatbotService.handleWithRAG()`
   - Goi `VectorService.searchSimilar(userMessage, topK)`
   - Build context cho Gemini.
   - Co the build `tourSuggestions` truc tiep tu docs.
   - Khong dam bao strict filter theo destination/start.
   - Khong dam bao luu `lastSearchResults`.

Vi co 2 duong, bot de bi:

- Search luc thi vao state, luc thi vao RAG.
- RAG sinh/chen tour khong dung filter.
- Follow-up `tour nay`, `slot`, `gia` khong co state chuan.
- Chon `1` bi coi la search lai.

RAG khong sai. Sai la **RAG chua duoc dat vao mot retrieval pipeline co filter va state duy nhat**.

---

## 3. Kien truc de xuat: Retrieval-first cho tour, deterministic cho giao dich

Khong nen "Gemini/RAG truoc tat ca". Nen tach:

### 3.1 Giao dich khong can RAG truoc

Nhung intent sau phai deterministic/API truoc:

- `BOOKING_LOOKUP`
- `PAYMENT_HELP`
- `CONFIRM_BOOKING`
- `CANCEL`
- `RESUME_BOOKING`
- nhap hanh khach
- nhap contact
- nhap email
- chon tour `1/2/3`
- chon ngay khoi hanh

Ly do: day la thao tac chinh xac, khong duoc de AI doan.

### 3.2 Tour/advice/detail thi retrieval-first

Nhung intent sau nen qua retrieval pipeline:

- `TOUR_SEARCH`
- `START_LOCATION_SEARCH`
- `CHANGE_SEARCH`
- `GENERAL_TRAVEL_ADVICE`
- `ASK_DETAIL`
- `ASK_ITINERARY`
- `ASK_SLOT`
- `ASK_PRICE`
- `ASK_DEPARTURE_DATE`
- `ASK_DISCOUNT`
- `ASK_COUPON`

Luong:

```text
User message
  -> IntentRouter chi phan loai y dinh va entity co ban
  -> TourRetrievalService.retrieve(intent, message, state)
      -> Pinecone topK=50
      -> parse metadata
      -> strict filter theo destination/start/date/category
      -> optional API refresh cho gia/slot
      -> rerank
      -> return RetrievalResult
  -> ChatbotService quyet dinh:
      - exact destination co 1 tour -> tra 1 tour
      - exact destination co nhieu tour -> tra toi da 3 tour dung destination
      - no-result -> noi khong co, khong show tour rac
      - category/advice -> goi y ro rang la "goi y"
  -> save lastSearchResults/lastMentionedTourId
  -> Gemini chi viet cau tra loi dua tren evidence
```

---

## 4. Chunking/RAG nen lam lai the nao

### 4.1 Chunk types

Nen sync vao Pinecone cac chunk rieng:

- `TOUR_SUMMARY`: tong quan tour.
- `TOUR_DEPARTURE`: moi ngay khoi hanh, gia, slot, coupon metadata.
- `TOUR_ITINERARY_DAY`: tung ngay lich trinh.
- `TOUR_POLICY`: dieu kien, bao gom/khong bao gom, huy/hoan.
- `LOCATION`: diem den/diem khoi hanh.
- `REVIEW`: review visible.
- `COUPON`: coupon global/specific.
- `FAQ_PAYMENT`: huong dan thanh toan.
- `FAQ_BOOKING`: huong dan dat/tra cuu booking.

### 4.2 Metadata bat buoc

Moi chunk tour nen co:

```json
{
  "type": "TOUR_DEPARTURE",
  "tourId": 1,
  "tourCode": "HCM-VT-2N1D",
  "tourName": "TP. Ho Chi Minh - Vung Tau 2 Ngay 1 Dem",
  "startLocationName": "TP. Ho Chi Minh",
  "startLocationID": 1,
  "endLocationName": "Vung Tau",
  "endLocationID": 2,
  "departureID": 10,
  "departureDate": "2027-03-20",
  "availableSlots": 25,
  "salePrice": 1500000,
  "originalPrice": 1800000,
  "categoryTags": ["bien", "gan-hcm"],
  "isActive": true
}
```

### 4.3 Search rules

- Exact destination query: filter exact/normalized destination before return.
- Start location query: filter exact/normalized start.
- Category query: allow semantic/category tags.
- Detail/slot/price: prefer state, then exact tour name retrieval.
- No-result: never auto show unrelated tours.
- Gemini gets only selected evidence, not raw topK noisy docs.

---

## 5. RabbitMQ sync tu dong sau nay

Nen doi tu daily/full sync sang event-driven batch sync:

```text
tour-catalog / booking / review service
  -> publish RabbitMQ event
  -> analytics chatbot-indexer consumer
  -> gom event vao Redis buffer/set
  -> moi 30-120 giay flush 1 lan
  -> fetch latest DB/API state
  -> rebuild chunks
  -> upsert/delete Pinecone
```

Event mau:

```json
{
  "eventType": "TOUR_DEPARTURE_UPDATED",
  "entityType": "DEPARTURE",
  "entityId": 123,
  "tourId": 10,
  "changedFields": ["availableSlots", "adultSalePrice"],
  "occurredAt": "2026-05-27T03:30:00+07:00"
}
```

Quy tac:

- Update gia/slot: upsert lai `TOUR_DEPARTURE`.
- Update lich trinh: upsert lai `TOUR_ITINERARY_DAY`.
- Update coupon: upsert `COUPON` va departure metadata lien quan.
- Delete/disable tour: delete vectors theo `tourId` hoac set `isActive=false` va filter bo.

---

## 6. Plan sua theo uu tien

### P0 - Stop broken flow

1. Sua routing cho stage `SHOWING_SEARCH_RESULTS`:
   - input `1/2/3` phai vao `BookingConversationService.handleTourSelection()`.
   - khong set stage ve `COLLECTING_SEARCH_INFO`.
2. Sua stage `SELECTING_DEPARTURE`:
   - input ngay/so ngay moi vao date handler.
   - off-topic booking/payment/discount/detail duoc xu ly global va preserve state.
3. Them `previousStage` hoac `suspendedState` de `BOOKING_LOOKUP`/`PAYMENT_HELP` khong pha flow cu.
4. Sua mojibake UTF-8 trong source va rebuild Docker.

Acceptance:

- `tour khoi hanh hcm ko` -> `1` -> `SELECTING_DEPARTURE`.
- `20/03` -> `COLLECTING_PASSENGERS`.
- `xem booking sao` -> hoi ma BK, bam `tiep tuc dat tour` quay lai dung stage.
- UI khong con text `Dáº¡`, `Ä...`.

### P1 - Retrieval pipeline duy nhat cho tour

1. Tao `TourRetrievalService` dung chung cho search/detail/slot/price/discount.
2. Chuyen logic Pinecone + filter tu `BookingConversationService`/`ChatbotService` ve service nay.
3. Moi response co tour cards phai save `lastSearchResults`.
4. RAG/Gemini khong duoc build cards tu raw docs nua.

Acceptance:

- `toi muon di nha trang` + du start/time/adults -> chi tour Nha Trang.
- `co tour phu yen ko` -> no-result sach.
- `xem chi tiet tour` sau result 1 tour -> detail tour do.

### P2 - Slot filling theo stage, khong semantic sai

1. Khi bot dang hoi `startLocation`, cau ngan `ha noi` chi duoc parse thanh start location.
2. Khi bot dang hoi `travelDate`, cau `thang 6`, `gan nhat` chi duoc parse thanh date preference.
3. Khi bot dang hoi `adults`, cau `2`, `2 nguoi` chi duoc parse thanh passenger count.
4. Tat vector fallback cho location extraction voi input ngan duoi 3 token, tru khi co exact match trong catalog.

Acceptance:

- `toi muon di nha trang` -> `ha noi` khong doi destination thanh Hoi An.
- `toi muon di da lat` -> no-result; `hcm` sau do bot hoi "HCM la diem khoi hanh hay diem den?" hoac cho user chon goi y, khong tu hieu sai.

### P3 - Chunking/RAG hien dai

1. Sync chunk theo types o muc 4.
2. Them metadata filter/rerank:
   - exact destination/start/date/category first.
   - score threshold.
   - no-result fallback co dieu kien.
3. Prompt Gemini rut gon:
   - chi nhan selected evidence.
   - cam tu tao tour/gia/slot.
   - output theo action hien tai: search/detail/advice/help.

Acceptance:

- `di bien gan HCM` -> goi y tour bien/gan HCM co ly do.
- `lich trinh tour nay` -> lay `TOUR_ITINERARY_DAY`, khong chi gia/slot.
- `tour nao duoc danh gia cao` -> lay review/rating chunks.

### P4 - RabbitMQ sync

1. Them producer event tu tour/booking/review/coupon service.
2. Analytics consumer gom event batch 30-120s.
3. Rebuild/upsert/delete chunk theo entity.
4. Log sync:
   - eventId
   - entityType/entityId
   - chunkCount
   - pinecone operation
   - elapsedMs

---

## 7. Regression test can them

1. Long session Vietravel style:
   - `toi muon di nha trang`
   - `ha noi`
   - `thang 6`
   - `2 nguoi lon`
   - expect `SHOWING_SEARCH_RESULTS`, only Nha Trang.
2. Selection:
   - `tour khoi hanh hcm ko`
   - `1`
   - expect `SELECTING_DEPARTURE`.
3. Departure:
   - `20/03`
   - expect `COLLECTING_PASSENGERS`.
4. No result:
   - `co tour di phu yen ko`
   - expect no cards, no unrelated tour.
5. Resume:
   - while selecting departure -> `xem booking sao` -> `tiep tuc dat tour`
   - expect previous stage restored.
6. Detail by name:
   - HCM results -> ask detail of Hanoi/Cat Ba
   - expect exact retrieval by typed name, not fallback Vung Tau.
7. Encoding:
   - response must contain `Dạ`, `Để`, `khởi hành`, not mojibake.

---

## 8. Ket luan

Huong "RAG/vector la chinh" la dung cho search/tour/advice/detail, nhung khong nen de Gemini tra loi truoc business rules. Kien truc can la:

```text
Intent -> Deterministic guard -> TourRetrievalService(Pinecone + filter + DB/API refresh) -> State -> Gemini wording
```

Neu chi sua prompt Gemini thi bot van ngao. Phai sua orchestration, slot filling theo stage, retrieval pipeline duy nhat, va encoding truoc.

