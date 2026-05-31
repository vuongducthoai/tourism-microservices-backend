# CHATBOT PRO ASSISTANT PLAN - Chuan hoa chatbot tu van du lich

**Ngay:** 26/05/2026  
**Pham vi:** Plan cai thien chatbot thanh tro ly tu van gan voi nhan vien that  
**Trang thai:** PLAN - Chua sua code trong tai lieu nay  
**Can cu:** Ket qua test API ngay 26/05/2026, transcript UI, plan `CHATBOT_FLEXIBLE_PLAN_2026-05-26.md`

---

## 1. Ket luan ngan

Chatbot hien tai da co mot so diem dung: API chay, booking code lookup da bot bi ket, search co state voi mot so cau nhu `toi muon di sa pa`, va cau `con may slot` da tra loi duoc bang du lieu that neu `lastSearchResults` ton tai.

Nhung bot van chua "pro" vi van bi 4 loi kien truc:

1. **Stage dang co quyen qua lon.** Khi dang o `SELECTING_DEPARTURE`, bot cu ep moi cau thanh ngay khoi hanh, nen hoi giam gia/booking/loi gi cung bi tra "nhap lai ngay".
2. **RAG/Gemini dang tra loi tour qua nhieu.** Neu user chao hoi, hoi booking, hoi loi, hoac khong tim thay tour, bot van chen tour giam gia/coupon lam cam giac khong nghe user.
3. **Ket qua tour khong duoc luu nhat quan vao state.** Mot so cau search vao `SHOWING_SEARCH_RESULTS`, mot so cau lai di RAG va stage van `IDLE`, nen follow-up `tour do`, `gia tour 1`, `con may slot` bi troi sang tour khac.
4. **Entity extraction con yeu.** `sapa` khong bang `sa pa`, `toi o HCM` bi hieu la diem den HCM thay vi diem khoi hanh, `xem chi tiet tour` khong resolve duoc tour dang hien.

Muc tieu cua plan nay: bot phai hanh xu nhu nhan vien tu van:

- Cau nao co du lieu that thi lay du lieu that, khong cho AI doan.
- Cau nao dang lam dang do thi van cho user hoi ngang va quay lai duoc.
- Cau nao mo ho thi hoi lai ngan gon, khong doan bua.
- Khong bao gio hien tour rac khi user yeu cau mot diem den cu the ma khong co ket qua.

---

## 2. Vai tro dung cua AI, Pinecone va database

### 2.1 Gemini/AI dung de lam gi

AI chi nen dung cho:

- Hieu y dinh cau tu nhien khi regex khong bat duoc.
- Dien dat cau tra loi mem mai, giong nhan vien tu van.
- Tu van chung: nen di bien nao, nen di thang may, gia dinh co tre nho nen chon tour nao.
- Tom tat thong tin tour da lay duoc tu du lieu that.
- Hoi lai khi thieu thong tin.

AI **khong duoc** tu nghi ra:

- So slot con lai.
- Gia tour.
- Ma booking.
- Tinh trang thanh toan.
- Link thanh toan.
- Ngay khoi hanh.
- Chinh sach huy/hoan tien neu database co record rieng.

### 2.2 Pinecone dung de lam gi

Pinecone/vector database dung cho:

- Tim ung vien tour theo ngu nghia: "di bien", "nghi duong", "gia dinh", "gan Sai Gon".
- Tim noi dung mo ta/llich trinh/review/coupon lien quan de dua vao context.
- Ho tro search khi user go khong dung ten tour chinh xac.

Pinecone **khong phai source of truth** cho:

- Slot con lai.
- Gia hien tai.
- Trang thai booking/payment.
- Coupon con hieu luc hay da het.

Sau khi Pinecone tra ve candidate, backend phai doi chieu voi database/API that truoc khi tra user.

### 2.3 Database/API service dung de lam gi

Day la source of truth:

- Tour/departure: ten tour, diem di, diem den, ngay khoi hanh, slot, gia.
- Booking: thong tin don, userId co the null neu khach chua dang nhap.
- Payment: trang thai thanh toan, payment URL, lich su giao dich.
- Coupon/promotion: ma giam gia, han su dung, dieu kien ap dung.

---

## 3. Kien truc muc tieu

### 3.1 Luong tong

```text
User message
  |
  v
ChatbotService
  1. Load session state tu Redis
  2. Luu turn user vao history
  3. GlobalIntentRouter chay truoc moi stage
  4. GlobalDeterministicHandler xu ly cau co du lieu that
  5. TourSearchOrBookingFlowHandler neu la search/dat tour
  6. BookingConversationService chi xu ly input dung stage
  7. RAG/AI fallback cho cau tu van chung
  8. Luu assistant turn + state
```

Nguyen tac quan trong: **IntentRouter phai chay truoc `BookingConversationService.handle()` trong moi truong hop.**

Neu dang o `SELECTING_DEPARTURE` ma user hoi `tour nao dang giam gia ko`, message nay phai duoc route la `ASK_DISCOUNT`, khong duoc dua vao `handleDepartureSelection()`.

---

## 4. Phan chia module de bot bot cung

### 4.1 `ChatbotService` - bo dieu phoi duy nhat

Trach nhiem:

- Load/save Redis state.
- Goi `IntentRouter` dau tien.
- Quyet dinh handler nao xu ly.
- Khong tu viet logic chi tiet cua booking/search trong ham lon.

Thu tu xu ly bat buoc:

1. `BOOKING_CODE_EXACT`
2. `CANCEL`
3. `RESUME_BOOKING`
4. `BOOKING_LOOKUP_HELP`
5. `ASK_SLOT`, `ASK_PRICE`, `ASK_DEPARTURE_DATE`, `ASK_DETAIL`, `ASK_ITINERARY`
6. `ASK_DISCOUNT`, `ASK_COUPON`, `PAYMENT_HELP`
7. `TOUR_SEARCH`, `CHANGE_SEARCH`, `START_LOCATION_SEARCH`, `CATEGORY_SEARCH`
8. Stage-specific input cua `BookingConversationService`
9. RAG/AI fallback

### 4.2 `IntentRouter` - hieu user dang muon gi

Can bo sung intent:

| Intent | Vi du |
|---|---|
| `GREETING` | `xin chao`, `hello` |
| `TOUR_SEARCH_DESTINATION` | `toi muon di nha trang`, `co tour ha long ko` |
| `TOUR_SEARCH_CATEGORY` | `toi muon di bien`, `tour nghi duong`, `tour gia dinh` |
| `START_LOCATION_SEARCH` | `toi o hcm thi di dau`, `tour khoi hanh hcm` |
| `CHANGE_SEARCH` | `thoi doi sang nha trang`, `tim tour khac` |
| `ASK_DETAIL` | `xem chi tiet tour`, `tour nay co gi`, `lich trinh sao` |
| `ASK_ITINERARY` | `lich trinh tour do`, `ngay 1 di dau` |
| `ASK_SLOT` | `con may slot`, `con cho khong` |
| `ASK_PRICE` | `gia tour 2`, `bao nhieu tien` |
| `ASK_DEPARTURE_DATE` | `ngay khoi hanh nao`, `co chuyen ngay nao` |
| `ASK_DISCOUNT` | `tour nao dang giam gia`, `co coupon khong` |
| `BOOKING_LOOKUP_CODE` | `BK3f7a9c12` |
| `BOOKING_LOOKUP_HELP` | `xem booking sao`, `don hang cua toi dau` |
| `PAYMENT_HELP` | `thanh toan sao`, `tao link thanh toan` |
| `RESUME_BOOKING` | `tiep tuc`, click nut `Tiep tuc dat tour` |
| `CANCEL` | `huy`, `bo qua`, click nut `Huy` |
| `UNKNOWN` | khong ro y |

### 4.3 `BookingConversationService` - chi xu ly form dat tour

Service nay khong nen la "bo nao" cua chatbot. No chi nen xu ly cac input dung stage:

| Stage | Chi xu ly |
|---|---|
| `SHOWING_SEARCH_RESULTS` | so `1/2/3`, hoac ten tour trong danh sach |
| `SELECTING_DEPARTURE` | ngay dang `10/04`, `10/04/2027`, hoac chon departure ro rang |
| `COLLECTING_PASSENGERS` | so nguoi lon/tre em/em be |
| `COLLECTING_CONTACT_NAME_PHONE` | ten + phone |
| `COLLECTING_CONTACT_EMAIL` | email |
| `CONFIRMING_BOOKING` | xac nhan/huy |

Neu input khong dung stage, service phai `return null` de `ChatbotService` xu ly tiep.

Vi du:

- Stage `SELECTING_DEPARTURE`, user noi `tour nao dang giam gia ko` -> `return null`
- Stage `SELECTING_DEPARTURE`, user noi `xem booking sao` -> `return null`
- Stage `SELECTING_DEPARTURE`, user noi `10/04` -> xu ly chon ngay

### 4.4 `ContextQuestionHandler` - tra loi bang state/API that

Dung cho cac cau hoi lien quan ket qua dang hien:

- `con may slot`
- `gia tour 2 bao nhieu`
- `ngay khoi hanh tour do`
- `xem chi tiet tour nay`
- `lich trinh tour do`

Nguon du lieu uu tien:

1. `ConversationState.lastSearchResults`
2. `lastMentionedTourId`, `lastMentionedDepartureId`
3. Tour/departure API neu state thieu field
4. Pinecone chi bo sung mo ta/llich trinh, khong thay the gia/slot

Neu khong resolve duoc tour:

- Mot tour dang hien -> tu dong lay tour do.
- Nhieu tour dang hien -> hoi lai: `Ban muon xem chi tiet tour 1, 2 hay 3?`
- Khong co tour trong state -> hoi diem den hoac ma tour, khong doan.

### 4.5 `TourSearchService` - tim tour nghiem tuc

Can tach logic search khoi `BookingConversationService` neu co the.

Bat buoc co:

- Alias dictionary: `sapa` = `sa pa`, `ha long` = `ha long`, `hcm` = `tp ho chi minh` = `sai gon`.
- Tach `destination` va `startLocation`.
- Category mapping:
  - `di bien` -> Nha Trang, Da Nang, Phu Quoc, Vung Tau, Ha Long, Mui Ne
  - `di nui` -> Sa Pa, Da Lat, Ha Giang
  - `gan hcm` -> Vung Tau, Can Tho, Phan Thiet, Da Lat neu co
- No fallback rac: neu user hoi `Da Lat` ma DB khong co tour Da Lat, khong hien Da Nang/Sa Pa/Vung Tau nhu tour phu hop.
- Candidate tu Pinecone phai filter lai bang metadata/API:
  - `endLocationName` cho destination
  - `startLocationName` cho khoi hanh
  - `availableSlots > 0` neu user hoi con cho
  - ngay/thang neu user co yeu cau thoi gian

---

## 5. Chinh sach tra loi de bot bot "may moc"

### 5.1 Greeting

User: `xin chao`

Sai hien tai: bot lap tuc quang cao tour giam gia.

Dung:

```text
Chao ban, minh co the giup ban tim tour, xem booking, kiem tra thanh toan hoac tu van lich trinh. Ban dang can ho tro phan nao?
```

Co quick actions:

- Tim tour
- Xem booking
- Tour giam gia
- Tu van diem den

### 5.2 Hoi tour theo category

User: `toi muon di bien co tour nao ko`

Dung:

```text
Co. Minh co the tim tour bien theo diem den nhu Nha Trang, Da Nang, Phu Quoc, Vung Tau hoac Ha Long.
Ban muon di tu dau va khoang thoi gian nao?
```

Neu DB co du lieu va user khong yeu cau them thong tin, co the hien 3 tour bien tot nhat, nhung phai ghi ro: `Minh goi y truoc mot vai tour bien dang co`.

### 5.3 Khong tim thay diem den

User: `toi di da lat`

Neu DB khong co tour Da Lat:

```text
Hien minh chua thay tour Da Lat dang mo ban trong he thong.
Ban muon minh goi y tour gan tinh chat Da Lat nhu Sa Pa/Ha Giang, hay doi sang diem den khac?
```

Khong duoc hien 3 tour khac va goi la `phu hop`.

### 5.4 Xem chi tiet tour

User: `xem chi tiet tour`

Dung:

- Neu chi co 1 tour dang hien -> xem chi tiet tour do.
- Neu co 3 tour -> hoi `Ban muon xem chi tiet tour 1, 2 hay 3?`
- Neu user noi `tour nha trang tren` -> resolve tour Nha Trang trong `lastSearchResults`.

Khong duoc reset search ve `Ban muon tim tour den dau?`.

### 5.5 Hoi slot/gia/ngay

User: `con may slot`

Dung:

- Neu dang co 1 tour: tra slot theo tung ngay.
- Neu dang co nhieu tour: tra slot tom tat moi tour, hoac hoi tour nao neu qua dai.
- Khong goi Gemini de doan.

### 5.6 Hoi booking khi dang o luong dat tour

User dang chon ngay, nhung hoi: `toi muon xem 1 booking thi sao`

Dung:

```text
Duoc. Ban nhap ma booking dang BK... de minh kiem tra.
Ban dang chon ngay cho tour Ha Noi - Nha Trang. Sau khi xem booking, ban co the bam Tiep tuc de quay lai buoc nay.
```

Quick actions:

- Nhap ma booking
- Tiep tuc dat tour
- Huy dat tour

Khong duoc tra `Tôi chưa tìm thấy ngày đó trong danh sách`.

### 5.7 Hoi giam gia khi dang chon ngay

User: `tour nao dang giam gia ko`

Dung:

- Route `ASK_DISCOUNT`.
- Tra tour/coupon giam gia that.
- Sau do nhac nhe: `Ban van dang chon ngay cho tour X, co muon tiep tuc khong?`

Khong duoc dua vao date parser.

### 5.8 Tiep tuc dat tour

User bam quick action `Tiep tuc dat tour` hoac go `tiep tuc dat tour`.

Dung:

- Neu stage `SHOWING_SEARCH_RESULTS` -> hien lai danh sach tour dang co va hoi chon tour.
- Neu stage `SELECTING_DEPARTURE` -> hien lai cac ngay khoi hanh cua tour dang chon.
- Neu stage `COLLECTING_PASSENGERS` -> hoi lai so khach.
- Neu khong co state -> hoi user muon tim tour den dau.

Khong duoc mac dinh hoi `Ban muon tim tour den dau?` neu state van con.

---

## 6. Session, nho ngu canh va F5

### 6.1 Khach chua dang nhap

Frontend tao `anonymousSessionId` va luu vao `localStorage`.

- F5 lai trang -> van dung sessionId cu.
- Backend load Redis state theo sessionId -> hoi thoai/flow con tiep.
- Dat tour khi chua dang nhap -> `booking.userId = null`, luu contact info khach nhap.

### 6.2 Khach da dang nhap

Session key nen gom:

```text
chat:{userId}:{sessionId}
```

Booking tao ra gan `userId`.

### 6.3 Chat history

Can luu 2 lop:

1. **Redis state ngan han**: stage, selectedTour, lastSearchResults, pending booking info. TTL 1-7 ngay.
2. **Database chat history**: message user/assistant de reload UI va audit. Co the luu theo `sessionId`, `userId nullable`.

Neu chua lam DB history ngay, toi thieu frontend phai luu transcript vao localStorage de F5 khong mat UI.

---

## 7. Dong bo Pinecone khi tour/review/booking thay doi

### 7.1 Co can RabbitMQ khong?

Co, neu he thong co nhieu service va du lieu tour/review/coupon thay doi thuong xuyen.

Khong nen moi lan update tour la sync Pinecone ngay lap tuc. Nen dung event queue + debounce/batch.

### 7.2 Luong de xuat

```text
Tour/Booking/Review/Coupon service
  -> publish event vao RabbitMQ
  -> chatbot-indexer/analytics consumer doc event
  -> gom event vao Redis set/buffer
  -> moi 30-120 giay flush mot lan
  -> lay du lieu moi nhat tu DB
  -> upsert/delete Pinecone
```

Event nen co:

```json
{
  "eventType": "TOUR_UPDATED",
  "entityType": "TOUR",
  "entityId": 123,
  "changedFields": ["price", "availableSlots"],
  "occurredAt": "2026-05-26T13:55:00"
}
```

### 7.3 Cai gi nen sync vao Pinecone

Nen sync:

- Mo ta tour.
- Lich trinh.
- Diem den/diem di metadata.
- Review/tom tat review.
- Chinh sach, FAQ, huong dan thanh toan.

Khong nen sync nhu source chinh:

- Slot realtime.
- Payment status.
- Booking status.

Voi slot/gia, Pinecone co the giu metadata de search, nhung truoc khi tra user van phai query API/DB moi nhat.

---

## 8. Data/state can bo sung

### 8.1 `ConversationState`

Can co hoac chuan hoa cac field:

```text
stage
previousStage
searchDestination
searchStartLocation
searchCategory
lastSearchResults
selectedTourId
selectedTourName
selectedDepartureId
lastMentionedTourId
lastMentionedDepartureId
pendingLookupMode
recentTurns
```

### 8.2 `IntentResult`

Can co:

```text
intent
confidence
destination
startLocation
category
resolvedTourIdx
resolvedTourId
resolvedDepartureId
bookingCode
needsClarification
rawSource
```

---

## 9. Ke hoach implement theo pha

### P0 - Stop bleeding: dung cac loi lam user tuc

Muc tieu: khong con bi ket stage vo ly.

Viec can lam:

1. `IntentRouter` chay truoc `BookingConversationService` trong `ChatbotService`.
2. `handleDepartureSelection()` chi xu ly input giong ngay; input khac `return null`.
3. `handleTourSelection()` chi xu ly `1/2/3`; `xem chi tiet`, `con slot`, `gia` phai `return null`.
4. Them global deterministic cho:
   - `GREETING`
   - `BOOKING_LOOKUP_HELP`
   - `ASK_DISCOUNT`
   - `RESUME_BOOKING`
   - `CANCEL`
5. Tat promotion tu dong trong cau tra loi booking/help/error/no-result.

Acceptance:

- Dang o `SELECTING_DEPARTURE`, hoi `xem booking sao` -> khong bao sai ngay.
- Dang o `SELECTING_DEPARTURE`, hoi `tour nao giam gia` -> tra giam gia, khong bao sai ngay.
- `xin chao` -> khong tu dong show tour giam gia.

### P1 - Context answer: tra loi dung tour dang hien

Muc tieu: user hoi tiep thi bot nho duoc `tour do`, `tour 1`, `nha trang tren`.

Viec can lam:

1. Them `ASK_DETAIL`, `ASK_ITINERARY`, `ASK_SLOT`, `ASK_PRICE`, `ASK_DEPARTURE_DATE`.
2. Dung `ReferenceResolverService` resolve:
   - `tour 1/2/3`
   - `tour do`
   - `tour tren`
   - ten tour/destination trong ket qua dang hien
3. Neu RAG tra tour cards thi phai luu chung vao `lastSearchResults`; tot hon la khong de RAG tao tour cards cho intent search.
4. Detail tour lay tu API/Pinecone content nhung gia/slot/ngay lay tu API/state.

Acceptance:

- Sau `nha trang`, go `xem chi tiet tour` -> hien chi tiet Nha Trang.
- Sau `nha trang`, go `con may slot` -> hien 20/22 cho.
- Sau 3 tour, go `gia tour 2` -> gia tour 2, khong nhay tour khac.

### P2 - Search tu nhien va loc dung

Muc tieu: search giong nguoi noi that.

Viec can lam:

1. Alias dictionary:
   - `sapa`, `sa pa`
   - `hcm`, `sai gon`, `tp ho chi minh`
   - `ha long`, `halong`
   - `nha trang`, `nhatrang`
2. Phan biet `destination` va `startLocation`.
3. Category search:
   - `di bien`
   - `di nui`
   - `gan hcm`
   - `gia re`
   - `gia dinh`
4. Xoa fallback tour rac.
5. No-result response ngan gon, co hoi muon xem goi y gan giong khong.

Acceptance:

- `toi muon di sapa` -> ra Sa Pa.
- `toi o hcm thi di dau` -> goi y tour khoi hanh HCM hoac gan HCM.
- `co tour khoi hanh hcm ko` -> chi tour co `startLocationName = HCM/TP.HCM/Sai Gon`.
- `toi di da lat` neu DB khong co -> no-result, khong show Da Nang/Sa Pa/Vung Tau la phu hop.

### P3 - Booking/payment pro

Muc tieu: chatbot ho tro duoc dat tour va xem don hang nhu nhan vien.

Viec can lam:

1. Booking lookup:
   - Co ma BK -> tra chi tiet don.
   - Khong co ma -> hoi ma BK.
   - Neu user da dang nhap -> co the goi API lay don gan day.
2. Booking create:
   - Chua dang nhap -> `userId = null`.
   - Da dang nhap -> luu `userId`.
3. Payment:
   - Neu booking chua thanh toan -> co quick action tao link thanh toan.
   - Neu da thanh toan -> tra status.
4. Refund/cancel:
   - Neu booking cho phep huy -> huong dan/tao request.
   - Neu khong -> noi ly do dua tren policy.

Acceptance:

- `toi muon xem don hang toi da dat` -> hoi ma BK hoac show don gan day neu da login.
- `BK...` -> show booking.
- `thanh toan don nay sao` -> tao link neu du dieu kien.

### P4 - Test, observability va guardrail

Muc tieu: moi bug trong transcript phai thanh regression test.

Can co:

- API test scripted theo session.
- Unit test cho `IntentRouter`.
- Unit test cho `ReferenceResolverService`.
- Integration test cho `ChatbotService` voi fake state.
- Log structured:
  - `sessionId`
  - `stageBefore`
  - `intent`
  - `handler`
  - `stageAfter`
  - `usedGemini`
  - `usedPinecone`
  - `resolvedTourId`

---

## 10. Bo test bat buoc tu transcript cua user

| # | Input | Trang thai | Expected |
|---|---|---|---|
| 1 | `xin chao` | IDLE | Chao ngan gon, khong show tour giam gia neu user chua hoi |
| 2 | `toi muon di bien co tour nao ko` | IDLE | Hoi them diem den/khoi hanh hoac goi y tour bien that |
| 3 | `toi di da lat` | Search | Neu khong co DB -> no-result, khong show tour rac |
| 4 | `nha trang` | Search | Hien tour Nha Trang, stage `SHOWING_SEARCH_RESULTS` |
| 5 | `xem chi tiet tour` | Sau Nha Trang | Hien chi tiet Nha Trang |
| 6 | `con may slot vay` | Sau Nha Trang | Hien slot that theo ngay |
| 7 | `tiep tuc dat tour` | Sau slot | Quay lai dung stage/result, khong hoi lai destination |
| 8 | `toi o hcm thi di dau` | IDLE/Search | Hieu HCM la startLocation/context, khong coi la destination |
| 9 | `co tour khoi hanh o hcm ko` | IDLE/Search | Chi hien tour khoi hanh HCM |
| 10 | `1` | SHOWING_SEARCH_RESULTS | Chon tour 1, hien ngay khoi hanh |
| 11 | `tour nao dang giam gia ko` | SELECTING_DEPARTURE | Tra discount/coupon, khong bao sai ngay |
| 12 | `ko giam gia a` | SELECTING_DEPARTURE | Tra loi discount context, khong bao sai ngay |
| 13 | `xem ma booking ko` | SELECTING_DEPARTURE | Hoi ma BK, khong bao sai ngay |
| 14 | `ua sao ko xem duoc booking vay` | SELECTING_DEPARTURE | Huong dan xem booking + nut tiep tuc/huy |
| 15 | `toi muon xem 1 booking thi sao` | SELECTING_DEPARTURE | Hoi ma BK, preserve booking state |
| 16 | `BK3f7a9c12` | Bat ky | Lookup booking deterministic |
| 17 | `huy` | Bat ky | Reset state, xac nhan ngan gon |

---

## 11. Dinh nghia "chatbot pro" cho he thong nay

Chatbot duoc xem la dat khi:

1. **Khong bi ket luong.** Dang dat tour van hoi booking/giam gia/thanh toan duoc.
2. **Khong noi bua.** Gia, slot, booking, payment deu tu data that.
3. **Nho ngu canh hien tai.** `tour do`, `tour tren`, `tour 1` resolve dung.
4. **Search linh hoat.** Hieu alias, category, diem khoi hanh, diem den.
5. **Khong spam promotion.** Chi show coupon/tour giam gia khi user hoi hoac trong man hinh goi y ro rang.
6. **F5 khong mat phien.** SessionId va transcript duoc restore.
7. **No-result thanh that.** Khong co tour thi noi khong co, khong lay tour khac gan nhan "phu hop".
8. **Co regression test.** Cac case tren phai chay lai duoc sau moi lan deploy.

---

## 12. Thu tu uu tien de lam tiep

1. P0: Sua orchestration de global intent chay truoc stage, va stage handler `return null` khi input khong dung.
2. P1: Them deterministic handlers cho detail/slot/price/date/discount/booking help.
3. P2: Chuan hoa search alias + startLocation/destination + no fallback rac.
4. P3: Session persistence + frontend quick actions + chat history.
5. P4: RabbitMQ/Pinecone sync theo batch va test tu dong.

Neu chi lam P0 + P1, bot se bot "ngu" thay ro ngay.  
Neu lam P0-P3, bot moi gan muc Vietravel-style: noi chuyen tu nhien, nho ngu canh, ho tro dat/xem booking.  
P4 giup he thong ben vung khi du lieu tour/review/coupon thay doi lien tuc.

