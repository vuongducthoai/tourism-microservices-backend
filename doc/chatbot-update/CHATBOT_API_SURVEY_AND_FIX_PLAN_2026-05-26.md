# CHATBOT API SURVEY AND FIX PLAN - 2026-05-26

**Pham vi:** Khao sat API chatbot dang chay tren Docker, chua sua code trong buoc nay.  
**Endpoint test:** `POST http://localhost:8087/api/chatbot/chat`  
**Session test:** `codex_api_survey_20260526_221554_*`  
**Ket luan ngan:** Bot da co mot so diem tot trong booking flow, nhung van sai lon o dieu phoi intent/search/RAG. Nhieu cau dang ra phai vao search state hoac deterministic handler lai roi xuong RAG/Gemini, dan toi mat ngu canh, tra tour HCM/khuyen mai sai cho, va khong giong nhan vien tu van.

---

## 1. Ket qua test API that

### 1.1 Case chao hoi / dat tour / booking help

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| `xin chao` | Chao ngan gon, co quick actions | Tot |
| `toi dat tour` | `stage=IDLE`, bot chen tour uu dai HCM/Ha Long/Vung Tau | Sai |
| `xem booking sao` | Hoi ma booking `BK...` | Tot |
| `toi muon xem don hang da dat` | Hoi ma booking nhung van chen tour moi/uu dai | Sai nhe |
| `thanh toan sao` | Hoi ma booking de xem/thanh toan | Tot |

**Loi chinh:** `toi dat tour` khong duoc coi la bat dau luong dat tour. Bot roi xuong RAG va RAG tu dong quang cao tour.

---

### 1.2 Case Nha Trang va hoi tiep theo ngu canh

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| `toi muon di nha trang` | `stage=IDLE`, `TEXT`, co 5 suggestions, khong vao `SHOWING_SEARCH_RESULTS` | Sai |
| `xem chi tiet tour` | Mat ngu canh Nha Trang, bot hien Phu Quoc/uu dai | Sai |
| `con may slot` | Tra slot nhieu tour khac nhau, khong gan voi Nha Trang | Sai |
| `gia tour bao nhieu` | Tra danh sach uu dai rong, khong gan voi tour dang hoi | Sai |

**Loi chinh:** Search theo destination dang bi RAG xu ly thay vi `BookingConversationService.doSearch()`, nen `lastSearchResults` khong duoc luu. Khi user hoi tiep, bot khong co context de resolve `tour do`, `tour nay`, `slot`, `gia`.

---

### 1.3 Case khong co tour

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| `co tour di phu yen ko` | Noi khong co Phu Yen nhung chen tour Phu Quoc/Hoi An/Hue | Sai |
| `co tour di da lat ko` | Noi khong co Da Lat nhung chen tour Da Nang/HCM/khuyen mai | Sai |

**Loi chinh:** No-result dang roi vao RAG promotion. Neu khong co tour diem den X thi phai noi that, hoi user co muon xem diem gan giong khong. Khong duoc gan tour khac la "phu hop".

---

### 1.4 Case khoi hanh HCM va xem chi tiet bang ten tour

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| `tour khoi hanh hcm ko` | Tim duoc 3 tour HCM, vao `SHOWING_SEARCH_RESULTS` | Tot |
| `Ha Noi - Hai Phong - Cat Ba 2 Ngay 1 Dem xem chi tiet` | Dang co context HCM, bot tra chi tiet tour 1 HCM - Vung Tau | Sai nghiem trong |

**Loi chinh:** `ASK_DETAIL` chi resolve theo index/lastMentioned. Khi user go ten tour khong nam trong `lastSearchResults`, handler fallback ve tour dang mention cu thay vi search/resolve theo ten user vua nhap.

---

### 1.5 Case failed search lam ro state cu

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| `tour khoi hanh hcm ko` | Co 3 result HCM | Tot |
| `tour khoi hanh ha noi di bien` | No-result, stage `COLLECTING_SEARCH_INFO` | Tam duoc |
| `con may slot` | Van tra slot cua 3 tour HCM cu | Sai |
| `1` | Lai hoi destination/time/adults | State bi lech |

**Loi chinh:** Khi search fail/no-result, `lastSearchResults`, `lastMentionedTourId`, `selectedTourId` chua duoc clear dong bo. Bot dang o stage moi nhung van cam context tour cu.

---

### 1.6 Case booking flow day du hanh khach

| Buoc | Ket qua hien tai | Danh gia |
|---|---|---|
| Search HCM -> chon `1` -> chon `20/03` | Vao chon hanh khach | Tot |
| `2 nguoi` | Ghi nhan 2 nguoi lon, hoi hanh khach 1 | Tot |
| Nhap ten/gioi tinh/ngay sinh tung hanh khach | Hoi du 2 hanh khach | Tot |
| Nhap lien he/email | Tao card xac nhan | Tot |
| Tong tien | `2 x 1,600,000d = 3,200,000d` | Tot |

**Nhan xet:** Day la phan dang tot nhat sau cac sua doi gan day. Can giu, chi them guardrail de user hoi ngang khong pha flow.

---

### 1.7 Case hoi ngang khi dang chon ngay

| Input | Ket qua hien tai | Danh gia |
|---|---|---|
| Dang `SELECTING_DEPARTURE`, hoi `tour nao dang giam gia ko` | Tra duoc discount, co `RESUME_BOOKING` | Tam duoc nhung qua dai |
| Dang `SELECTING_DEPARTURE`, hoi `xem booking sao` | Hoi ma BK, co resume/cancel | Tot |

**Nhan xet:** Da het loi cu "cau nao cung ep thanh ngay", nhung discount dang spam 11 tour va 6 cards. Can gioi han va nhac nhe quay lai flow.

---

## 2. Nguyen nhan ky thuat theo file

### 2.1 `IntentRouter.java`

Vi tri lien quan:

- `route()` quanh dong 32
- `isTourSearch()` quanh dong 160
- `isBookingIntent()` quanh dong 166
- `isAskDetail()` quanh dong 140
- `extractSearchEntities()` quanh dong 231

Van de:

1. `toi muon di nha trang` trong API dang khong vao deterministic search, the hien bang response `stage=IDLE`. Nghia la intent/entity search dang chua du manh hoac route xuong RAG.
2. `toi dat tour` bi coi la RAG/promotion thay vi `BOOKING_FLOW`.
3. `ASK_DETAIL` khong extract ten tour trong message. Cau `Ha Noi - Hai Phong - Cat Ba ... xem chi tiet` khong duoc resolve theo ten vua nhap.
4. Gemini intent fallback bi loi/rate limit trong log, nen khong duoc dung lam tru chong cho business intent.

### 2.2 `ChatbotService.java`

Vi tri lien quan:

- Pipeline `handleUserMessage()` quanh dong 82-91
- `handleWithRAG()` quanh dong 119
- `ASK_DISCOUNT/ASK_COUPON -> handleWithRAG()` quanh dong 207
- `buildTourDetailAnswer()` quanh dong 390
- `resolveTargetTours()` quanh dong 420
- `isTourLikeQuery()` quanh dong 976

Van de:

1. `handleWithRAG()` vua goi Pinecone/Gemini vua build tour cards. Khi user hoi help/booking/no-result, RAG van co the chen tour.
2. `ASK_DISCOUNT` dang dua thang sang RAG, nen tra qua dai va co the lay cac tour khong phu hop voi context.
3. Detail resolve fallback ve `lastMentionedTourId` qua de dang. Khi user nhap ten tour moi, bot van tra tour cu.
4. RAG khong nen la noi tao business answer cho booking/search/price/slot. RAG chi nen dung de dien dat/tu van sau khi da co data that.

### 2.3 `BookingConversationService.java`

Vi tri lien quan:

- `askForMissingSearchInfoIfNeededV3()` quanh dong 132
- `destinationHasAnyTour()` quanh dong 156
- `doSearch()` quanh dong 177
- no-result `departureDocs.isEmpty()` quanh dong 209
- save `lastSearchResults` quanh dong 272
- `handleTourSelection()` quanh dong 317
- `handleDepartureSelection()` quanh dong 375

Van de:

1. `doSearch()` dung hon truoc, nhung no-result chua clear het context cu.
2. Search flow chi tot neu message thuc su duoc route vao booking/search handler. Neu roi vao RAG, state khong duoc luu.
3. `destinationHasAnyTour()` dang goi Pinecone de precheck, nhung neu intent khong vao service nay thi khong co tac dung.
4. Search ask-more-info nen theo kieu Vietravel: khi co destination, hoi thieu start/time/adults truoc khi list tour, tru khi user yeu cau "xem ngay tour".

---

## 3. Nguyen tac sua moi

1. **Business intent khong duoc roi tu do xuong RAG.** Search, booking, payment, booking lookup, price, slot, departure date, detail, discount phai co handler rieng.
2. **RAG/Pinecone khong phai state machine.** Pinecone dung lay candidate/noi dung. DB/API/state moi la source of truth cho gia, slot, booking, payment.
3. **Khong fallback tour rac.** Khong co Da Lat/Phu Yen thi noi khong co, hoi co muon xem goi y gan giong khong.
4. **State phai dong bo.** No-result thi clear old result, selected tour, mentioned tour. Search success thi luu `lastSearchResults` bat buoc.
5. **Detail theo ten user vua nhap uu tien hon lastMentioned.** Neu user go ten tour khac, phai resolve theo ten hoac bao "tour do khong nam trong ket qua hien tai".

---

## 4. Plan sua theo uu tien

### P0 - Chan RAG tra loi sai business intent

Can lam:

1. Sua `IntentRouter.isBookingIntent()` de bat:
   - `toi dat tour`
   - `toi muon dat tour`
   - `dat tour nay`
   - `muon dat dat tour nay ...`
2. Sua `IntentRouter.isTourSearch()`/entity extraction de `toi muon di nha trang`, `co tour di phu yen ko`, `co tour di da lat ko` bat buoc vao `TOUR_SEARCH`.
3. Trong `ChatbotService`, neu intent la `TOUR_SEARCH` nhung chua du entity thi hoi lai co cau truc, khong cho RAG tu tra.
4. Trong `ChatbotService`, neu intent la `BOOKING_LOOKUP`/`PAYMENT_HELP`/`SYSTEM_HELP`, response khong duoc chen promotion.

Acceptance:

- `toi dat tour` -> hoi diem den/khoi hanh/thoi gian/so khach, khong show tour uu dai.
- `toi muon di nha trang` -> vao search flow/state, khong `IDLE`.
- `toi muon xem don hang da dat` -> chi hoi ma BK, khong quang cao tour.

### P1 - Sua search state va no-result

Can lam:

1. Moi search thanh cong phai set:
   - `stage=SHOWING_SEARCH_RESULTS`
   - `lastSearchResults`
   - `lastMentionedTourId`
2. No-result phai clear:
   - `lastSearchResults`
   - `lastDepartures`
   - `lastMentionedTourId`
   - `lastMentionedDepartureId`
   - `selectedTourId`
   - `selectedDepartureId`
3. No-result response:
   - Neu user hoi diem den cu the: "Hien chua co tour X dang mo ban."
   - Them quick actions: `Doi diem den`, `Goi y tuong tu`, `Huy`
   - Khong tu dong show tour khac.

Acceptance:

- `co tour di phu yen ko` -> no-result sach, `SUG=0`.
- Sau failed search, `con may slot` -> khong tra slot HCM cu.

### P2 - Detail/slot/price/date phai resolve theo context that

Can lam:

1. Them `resolvedTourName` hoac `queryTourName` vao `IntentResult`.
2. `isAskDetail()` khi message co ten tour thi extract ten tour, khong chi set intent.
3. `resolveTargetTours()` thu tu moi:
   - exact/normalized name trong `lastSearchResults`
   - index `tour 1/2/3`
   - `lastMentionedTourId`
   - neu khong tim thay trong state thi search Pinecone/DB theo ten tour user vua go
   - neu van khong co: hoi lai, khong fallback ve tour 1 cu
4. `ASK_SLOT/ASK_PRICE/ASK_DEPARTURE_DATE` neu khong co state thi hoi "Ban muon hoi tour nao?", khong lay random Pinecone list.

Acceptance:

- Sau `toi muon di nha trang`, `xem chi tiet tour` -> Nha Trang detail.
- Sau HCM results, go `Ha Noi - Hai Phong - Cat Ba ... xem chi tiet` -> khong tra Vung Tau.
- `con may slot` khi khong co context -> hoi tour nao, khong list lung tung.

### P3 - Discount/coupon deterministic, khong spam

Can lam:

1. Tao `DiscountHandler`/method deterministic rieng, khong goi `handleWithRAG()` truc tiep.
2. Lay top 3-5 tour discount tu Pinecone/DB, filter ngay con hieu luc/con slot.
3. Neu dang trong booking flow, append cau ngan:
   - "Ban van dang chon ngay cho tour X. Muon tiep tuc thi bam Tiep tuc dat tour."
4. Khong tra 11 tour trong mot bong chat.

Acceptance:

- Dang `SELECTING_DEPARTURE`, hoi `tour nao dang giam gia ko` -> toi da 3-5 tour, co resume/cancel.

### P4 - RAG chi lam tu van mem, khong lam core booking/search

Can lam:

1. RAG chi duoc dung cho:
   - tu van diem den chung
   - giai thich lich trinh/chinh sach neu da co context
   - dien dat cau tra loi tu data that
2. Neu Gemini intent bi 429 hoac parse fail, fallback phai la deterministic clarification, khong promotion.
3. Log bat buoc co:
   - `sessionId`
   - `stageBefore`
   - `intent`
   - `handler`
   - `stageAfter`
   - `usedRag`
   - `usedGemini`
   - `usedVector`
   - `resolvedTourId`

Acceptance:

- Khi Gemini 429, bot van hoi lai ngan gon, khong noi xang.

---

## 5. Bo regression test bat buoc sau khi sua

| # | Session flow | Expected |
|---|---|---|
| 1 | `xin chao` | Chao ngan, co quick actions, khong promotion |
| 2 | `toi dat tour` | Hoi diem den/khoi hanh/thoi gian/so khach |
| 3 | `toi muon di nha trang` -> `xem chi tiet tour` | Detail Nha Trang |
| 4 | `toi muon di nha trang` -> `con may slot` | Slot Nha Trang |
| 5 | `co tour di phu yen ko` | No-result, khong suggestions tour khac |
| 6 | `tour khoi hanh hcm ko` -> `Ha Noi - Hai Phong - Cat Ba ... xem chi tiet` | Khong fallback Vung Tau; resolve ten tour hoac hoi lai |
| 7 | `tour khoi hanh hcm ko` -> failed search -> `con may slot` | Khong dung old HCM results |
| 8 | Booking 2 nguoi | Hoi du 2 hanh khach + DOB + lien he + tong tien dung |
| 9 | Dang chon ngay -> `xem booking sao` | Hoi ma BK, preserve state, co resume |
| 10 | Dang chon ngay -> `tour nao dang giam gia ko` | Tra discount ngan gon, co resume |

---

## 6. Ket luan

Bot khong can "Pinecone truoc tat ca". Cai can la **router dung + state dung + RAG dung vai tro**.

Hien tai loi lon nhat khong phai la thieu AI, ma la:

- Intent search/booking chua bat du cau nguoi dung.
- RAG dang duoc dung de tra loi thay cho business flow.
- State cu khong duoc clear khi search fail.
- Detail resolver fallback qua manh ve tour cu.

Neu sua theo P0-P2, bot se het phan lon cam giac "hoi gi cung tour HCM". P3-P4 se lam bot gan phong cach nhan vien tu van hon: hoi dung thong tin, khong noi bua, va biet quay lai luong dang lam.
