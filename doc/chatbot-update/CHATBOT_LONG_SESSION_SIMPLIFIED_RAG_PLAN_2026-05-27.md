# CHATBOT LONG SESSION SURVEY + SIMPLIFIED RAG PLAN - 2026-05-27

**Pham vi:** Khao sat API chatbot dang chay Docker, chua sua code trong buoc nay.  
**Endpoint:** `POST http://localhost:8087/api/chatbot/chat`  
**Session test:** `codex_long_customer_1779891035176`  
**Muc tieu:** Kiem tra mot cuoc hoi thoai dai nhu user that: hoi tour, bo sung thong tin, hoi ngang, tra cuu booking, tiep tuc, dat tour, no-result, cau tu van ngoai booking.

---

## 1. Ket luan ngan

Huong cua ban la dung: **ngoai cac thao tac giao dich chinh xac nhu dat tour, tra cuu booking, thanh toan, cancel/resume, phan con lai nen di qua retrieval/RAG + filter tren Pinecone/vector DB**.

Nhung khong nen chi con 3 intent `UNKNOWN`, `FLOW_BOOKING`, `SEARCH_BOOKING` theo nghia qua don gian. Nen doi sang it intent hon, nhung co pipeline ro:

```text
1. TRANSACTION_FLOW
   - chon tour 1/2/3
   - chon ngay
   - nhap hanh khach/contact/email
   - confirm/cancel/resume
   - tra cuu booking/payment

2. TOUR_RETRIEVAL
   - hoi tour, hoi diem di/den, gia, slot, lich trinh, chi tiet, uu dai
   - di qua Pinecone/RAG, sau do strict filter/rerank

3. GENERAL_RAG
   - tu van chung: gia dinh co tre nho, chuan bi hanh ly, nen di dau
   - di qua vector/RAG FAQ/policy/review/tour context

4. UNKNOWN/CLARIFY
   - khong du du kien thuc hoac ambiguous thi hoi lai ngan gon
```

Neu ep moi thu ve 3 flag, bot de mat thong tin hanh dong. Nhung neu giu qua nhieu regex intent nhu hien tai, bot lai bi "cung" va bat nham. Vay diem can lam la **bot gom intent thanh nhom lon**, con chi tiet thi de retrieval + state resolver quyet dinh.

---

## 2. Ket qua test session dai

### 2.1 Greeting

Input:

```text
xin chao
```

Ket qua:

- `stage=IDLE`
- Tra loi chao ngan gon, co quick actions `Tim tour`, `Xem booking`, `Tour giam gia`

Danh gia: **Tot**.

---

### 2.2 Hoi tour Can Tho roi bo sung thong tin

Flow:

```text
tour di can tho ko
khoi hanh ho chi minh, ngay gan nhat, di 2 nguoi lon va 1 tre em
```

Ket qua:

- Bot hoi them thong tin cho Can Tho la hop ly o buoc dau.
- Buoc bo sung thong tin lai roi sang RAG/discount, tra:
  - tour HCM - Vung Tau
  - text prompt rac: `"Khoang cach giua cac doan..."`, `"In dam ten Tour..."`
- `stage` van `COLLECTING_SEARCH_INFO`
- Khong co `lastSearchResults`

Danh gia: **Sai nghiem trong**.

Nguyen nhan:

- Slot filling trong `COLLECTING_SEARCH_INFO` khong nhan duoc cau gom nhieu entity.
- RAG/Gemini duoc goi khi state search chua duoc dong lai.
- Context prompt noi bo bi leak ra user.

Expected:

```text
Bot phai hieu:
- destination = Can Tho
- startLocation = Ho Chi Minh
- datePreference = nearest
- adults = 2
- children = 1

Sau do search Pinecone/DB:
- Neu co tour HCM -> Can Tho: hien dung tour do, gom ngay khoi hanh khong lap.
- Neu khong co: noi khong co, goi y diem khoi hanh khac neu co data that.
```

---

### 2.3 Hoi slot/gia sau search fail

Input:

```text
con may slot
gia tour nay bao nhieu
```

Ket qua:

- Bot noi chua co tour cu the.

Danh gia: **Tam dung**, vi khong co `lastSearchResults`. Nhung loi goc la search truoc do da fail/mat context.

Expected:

- Neu vua co 1 tour Can Tho -> tra slot/gia tour Can Tho.
- Neu khong co tour -> noi "minh chua co tour nao de kiem tra slot/gia".

---

### 2.4 `toi muon dat tour do` khong dat tour

Input:

```text
toi muon dat tour do
```

Ket qua:

- Bot lap lai cau hoi search Can Tho, khong chuyen sang booking.

Danh gia: **Sai nghiem trong**.

Expected:

- Neu `lastSearchResults.size() == 1`: tu dong chon tour do va hoi ngay khoi hanh.
- Neu `lastSearchResults.size() > 1`: hoi lai "Ban muon dat tour 1, 2 hay 3?"
- Neu khong co `lastSearchResults`: hoi "Ban muon dat tour nao? Hay gui ten tour/diem den."

---

### 2.5 Hoi discount/booking khi dang search

Flow:

```text
tour nao dang giam gia
xem booking sao
tiep tuc dat tour
```

Ket qua:

- Discount tra duoc nhung qua dai, khong gan voi context Can Tho.
- `xem booking sao` vao `COLLECTING_LOOKUP_CODE`.
- `tiep tuc dat tour` noi "chua co luong dat tour dang cho".

Danh gia: **Chua tot**.

Expected:

- Discount la side question, khong pha pending search.
- `previousStage`/`pendingSearchState` phai giu duoc destination/start/date/adults/children cua Can Tho.
- Resume phai quay lai "Dang tim tour Can Tho khoi hanh Ho Chi Minh, ngay gan nhat, 2 nguoi lon 1 tre em".

---

### 2.6 User nhap thong tin hanh khach khi chua vao booking

Input:

```text
2 nguoi lon 1 tre em
Nguyen Van A nam 12/08/1990
...
```

Ket qua:

- Bot van lap lai cau hoi search Can Tho.

Danh gia: **Dung mot phan**, vi chua vao booking thi khong nen thu passenger. Nhung UX kem vi bot khong noi ro "minh chua chon tour/ngay".

Expected:

```text
Minh chua chon duoc tour/ngay khoi hanh nen chua the nhan thong tin hanh khach.
Minh dang can tim tour Can Tho truoc. Ban muon minh tim voi thong tin vua cung cap khong?
```

---

### 2.7 Route query Ha Noi -> Hai Phong

Input:

```text
tour ha noi den hai phong thang 4 2 nguoi lon
```

Ket qua:

- `stage=SHOWING_SEARCH_RESULTS`
- 1 suggestion: `Ha Noi - Hai Phong - Cat Ba 2 Ngay 1 Dem`

Danh gia: **Tot**.

Expected tiep theo:

- `xem chi tiet tour do` -> detail dung tour.
- `toi dat tour do` -> vi chi co 1 tour, phai chon tour va hoi ngay.

Thuc te:

- `xem chi tiet tour do` dung.
- `toi dat tour do` lai hien detail, khong vao booking.

Loi: `BOOKING_FLOW`/`ASK_DETAIL` resolve dang uu tien detail thay vi action "dat".

---

### 2.8 Query `co tour hcm di ha noi khong`

Input:

```text
co tour hcm di ha noi khong
```

Ket qua:

- Bot hieu sai: destination = TP.HCM, start = Ha Noi.
- Tra no-result nguoc chieu.

Danh gia: **Sai nghiem trong**.

Expected:

- `hcm di ha noi` = start HCM, destination Ha Noi.
- Neu co tour HCM -> Ha Noi: hien tour do.
- Neu khong co: noi khong co, co the goi y cac tour den Ha Noi tu diem khac hoac tour khoi hanh HCM den diem khac, nhung phai ghi ro la goi y thay the.

Can co parser route:

```text
[start] đi [destination]
[start] đến [destination]
từ [start] đến [destination]
khởi hành [start] đi/đến [destination]
```

---

### 2.9 No-result Phu Yen

Input:

```text
co tour di phu yen ko
```

Ket qua:

- No-result sach, khong chen tour rac.

Danh gia: **Tot hon truoc**.

Can cai thien:

- Them option "Goi y tour bien tuong tu" chi khi user bam/hoi.

---

### 2.10 Booking lookup/payment

Flow:

```text
toi muon xem don hang da dat
BK3f7a9c12
thanh toan sao
```

Ket qua:

- Hoi ma BK dung.
- Ma khong ton tai -> bao khong tim thay.
- Payment help -> hoi ma BK.

Danh gia: **Tot** cho luong deterministic.

---

### 2.11 Cau tu van chung bi bat nham tour search

Input:

```text
gia dinh co tre nho nen chuan bi gi khi di du lich dai ngay
```

Ket qua:

- Bot coi `dai ngay` la destination/tour, tra no-result.

Danh gia: **Sai nghiem trong**.

Expected:

- Day la `GENERAL_RAG`.
- Query vector DB FAQ/policy/tour advice/review.
- Neu co data lien quan thi tra loi tu van.
- Khong vao booking/search tour.

---

## 3. Co nen bo bot cac co intent khong?

Co. Nen bot bot intent regex chi tiet.

Hien tai qua nhieu co nhu:

- `ASK_SLOT`
- `ASK_PRICE`
- `ASK_DETAIL`
- `ASK_DEPARTURE_DATE`
- `ASK_DISCOUNT`
- `TOUR_SEARCH`
- `START_LOCATION_SEARCH`
- `CHANGE_SEARCH`

Van de khong phai ban than cac intent nay sai, ma la chung dang duoc detect bang regex/fast-path qua som, lam bot:

- bat nham cau tu van thanh search tour,
- bat nham diem di/diem den,
- bo qua RAG khi can RAG,
- roi vao state `COLLECTING_SEARCH_INFO` qua dai.

De xuat thay bang **routing 2 lop**:

### Lop 1: Guard deterministic

Chi bat cac thao tac khong duoc de AI doan:

| Group | Vi du |
|---|---|
| `BOOKING_CODE` | `BK...` |
| `BOOKING_LOOKUP_HELP` | `xem booking sao`, `don hang cua toi` |
| `PAYMENT_HELP` | `thanh toan sao` |
| `CANCEL` | `huy`, `bo qua` |
| `RESUME` | `tiep tuc dat tour` |
| `STAGE_INPUT` | `1`, `20/03`, email, phone, passenger name |

### Lop 2: Retrieval router

Moi cau con lai di qua `RetrievalRouter`:

```text
message + state
  -> classify coarse action:
       TOUR_RETRIEVAL | GENERAL_RAG | CLARIFY
  -> retrieve Pinecone topK
  -> parse metadata/evidence
  -> strict filter/rerank
  -> decide answer
```

Chi tiet nhu slot/gia/detail/discount khong can la intent top-level cung nua. No co the la `retrievalTask`:

```text
retrievalTask = DETAIL | PRICE | SLOT | DATE | DISCOUNT | ITINERARY | SEARCH | ADVICE
```

Task nay co the suy ra bang:

- state,
- tu khoa nhe,
- evidence tu vector,
- va Gemini JSON classifier neu regex khong chac.

---

## 4. Kien truc de xuat

### 4.1 Pipeline moi

```text
ChatbotService.handle()
  1. Load state
  2. DeterministicGuard.tryHandle()
       - BK code
       - payment/lookup/cancel/resume
       - stage input: 1/2/3, date, passenger, contact, email
  3. If not handled:
       RetrievalConversationService.handle()
          - extract route entities by pattern + vector evidence
          - query Pinecone
          - filter by metadata
          - rerank
          - produce RetrievalDecision
  4. BookingActionResolver
       - neu user muon dat "tour do"
       - 1 result -> auto select
       - many result -> ask choose 1/2/3
  5. GeminiAnswerComposer
       - chi viet lai cau tra loi dua tren evidence da chon
  6. Save state
```

### 4.2 State moi can ro hon

Nen tach state:

```text
currentMode:
  IDLE
  SEARCH_PENDING
  SHOWING_RESULTS
  BOOKING_ACTIVE
  SIDE_QUEST_LOOKUP
  SIDE_QUEST_PAYMENT

pendingSearch:
  destination
  startLocation
  datePreference
  adults
  children
  infants
  missingSlots

lastResults:
  tours[]
  sourceQuery
  appliedFilters

bookingDraft:
  selectedTour
  selectedDeparture
  passengerCounts
  passengers[]
  contact

suspendedState:
  previousMode
  previousStage
  pendingSearch/bookingDraft snapshot
```

Hien tai `stage` dang vua dai dien search, vua dai dien booking, vua dai dien lookup. Nen khi re sang booking lookup, no de pha flow cu.

---

## 5. Search/RAG rules bat buoc

### 5.1 Exact route

Input:

```text
hcm di ha noi
ha noi den hai phong
tu da nang den hcm
```

Rule:

- Parse start/destination bang route pattern truoc.
- Khong cho vector dao nguoc.
- Pinecone chi retrieve candidates.
- Filter metadata:
  - `startLocationName == start`
  - `endLocationName == destination`

Neu co:

- Hien dung cac tour do.
- Neu 1 tour nhieu ngay: gom departure trong 1 tour, khong lap tour.

Neu khong co:

```text
Hien chua co tour khoi hanh tu HCM den Ha Noi.
Minh co the goi y:
- tour khoi hanh HCM den diem khac
- tour den Ha Noi tu diem khoi hanh khac
Ban muon xem goi y nao?
```

Khong tu dong chen tour khac la "phu hop".

### 5.2 Destination-only

Input:

```text
co tour di can tho ko
```

Rule:

- Query Pinecone + metadata destination Can Tho.
- Neu co tour va user chua noi start/date/pax:
  - co the hoi them theo kieu Vietravel,
  - nhung nen noi "Hien co tour Can Tho, de loc dung hon..."
- Neu user bo sung du slot trong 1 cau, phai search ngay.

### 5.3 Category/advice

Input:

```text
gia dinh co tre nho nen chuan bi gi
di bien gan hcm nen di dau
```

Rule:

- Khong vao `COLLECTING_SEARCH_INFO` neu cau khong co y dinh mua/search ro.
- Di `GENERAL_RAG` hoac `TOUR_RETRIEVAL` category.
- Neu co goi y tour, phai ghi ro "goi y".

---

## 6. Dat tour "tour do/tour nay" phai xu ly nhu nao

### Case 1: Chi co 1 tour trong `lastResults`

User:

```text
toi dat tour do
```

Expected:

```text
Minh se dat tour Ha Noi - Hai Phong - Cat Ba cho ban.
Tour nay co ngay khoi hanh:
- 25/04/2027 - con 16 cho
Ban chon ngay nao?
```

Stage -> `SELECTING_DEPARTURE`.

### Case 2: Co 3 tour trong `lastResults`

User:

```text
toi dat tour do
```

Expected:

```text
Ban muon dat tour nao?
1. ...
2. ...
3. ...
```

Quick actions:

- Dat tour 1
- Dat tour 2
- Dat tour 3

### Case 3: User go ten tour

User:

```text
toi dat tour Ha Noi - Hai Phong
```

Expected:

- Resolve exact/normalized name trong `lastResults`.
- Neu khong co trong state, retrieve Pinecone by tour name.
- Neu tim thay 1 tour, chon tour.
- Neu nhieu tour, hoi lai.

---

## 7. Booking flow thu thap du thong tin

Sau khi chon tour + ngay:

1. Hoi so luong:
   - adults
   - children
   - infants

2. Hoi thong tin tung hanh khach:
   - Ho ten
   - Gioi tinh
   - Ngay sinh
   - Loai hanh khach suy ra theo count hoac DOB

3. Hoi lien he:
   - Ho ten lien he
   - Phone
   - Email

4. Xac nhan:
   - Tour
   - Ngay khoi hanh
   - Danh sach hanh khach
   - Gia nguoi lon/tre em/em be
   - Tong tien
   - Han thanh toan

5. User confirm:
   - Tao booking
   - Hien ma booking + payment action neu can

Neu user hoi ngang trong bat ky buoc nao:

- Tra loi bang RAG/deterministic.
- Khong mat `bookingDraft`.
- Co nut `Tiep tuc dat tour`.

---

## 8. Plan sua theo pha

### P0 - Dung cac loi gay uc che

1. Sua cau bo sung slot trong `SEARCH_PENDING`:
   - "khoi hanh ho chi minh, ngay gan nhat, 2 nguoi lon 1 tre em" phai fill pendingSearch va search.
2. Sua `toi dat tour do`:
   - 1 result -> select tour.
   - many results -> ask choose.
3. Sua route parser:
   - `hcm di ha noi` dung start/destination.
4. Chan prompt leak:
   - Khong bao gio tra cac cau noi bo nhu "In dam ten Tour..."
5. Cau advice khong vao search:
   - `gia dinh co tre nho...` -> GENERAL_RAG.

### P1 - Rut gon IntentRouter

1. Giu deterministic guard:
   - booking code, booking lookup, payment, cancel, resume, stage input.
2. Gop cac intent tour/detail/slot/price/date/discount vao `TOUR_RETRIEVAL`.
3. Them `retrievalTask` thay cho nhieu intent top-level.

### P2 - Retrieval-first service

Tao service rieng:

```text
TourRetrievalService
  retrieve(message, state)
  extractRoute(message)
  searchVectors(query)
  filterByMetadata(candidates, constraints)
  groupDeparturesByTour(candidates)
  rerank(candidates)
```

Output:

```text
RetrievalResult:
  task
  constraints
  tours[]
  noResultReason
  needsClarification
```

### P3 - State model moi

Them/tach:

- `currentMode`
- `pendingSearch`
- `bookingDraft`
- `suspendedState`
- `lastResults.sourceQuery`
- `lastResults.appliedFilters`

### P4 - Gemini/RAG composer

Gemini chi nhan:

- selected evidence,
- task,
- user tone,
- stage context.

Gemini khong duoc nhan raw topK documents va khong tu tao tour/gia/slot.

---

## 9. Acceptance tests can pass sau khi sua

### Test A: Can Tho slot-filled

```text
tour di can tho ko
khoi hanh ho chi minh, ngay gan nhat, di 2 nguoi lon va 1 tre em
```

Expected:

- Search dung HCM -> Can Tho.
- Co tour thi hien tour Can Tho.
- Khong hien Vung Tau/Phu Quoc/discount rac.

### Test B: Dat tour do sau 1 result

```text
tour ha noi den hai phong thang 4 2 nguoi lon
toi dat tour do
```

Expected:

- Auto select Ha Noi - Hai Phong - Cat Ba.
- Hoi ngay khoi hanh.

### Test C: Dat tour do sau 3 result

```text
tour khoi hanh hcm ko
toi dat tour do
```

Expected:

- Hoi lai tour 1/2/3, khong doan.

### Test D: Route direction

```text
co tour hcm di ha noi khong
```

Expected:

- start = HCM, destination = Ha Noi.
- Khong dao nguoc.

### Test E: Advice

```text
gia dinh co tre nho nen chuan bi gi khi di du lich dai ngay
```

Expected:

- GENERAL_RAG advice.
- Khong coi "dai ngay" la destination.

### Test F: Side quest resume

```text
chon tour + ngay
xem booking sao
tiep tuc dat tour
```

Expected:

- Quay lai dung bookingDraft/stage.

---

## 10. Ket luan

Nen chuyen chatbot sang huong:

```text
Hard deterministic guard
  + retrieval-first cho moi cau tour/advice/detail
  + state resolver cho "tour do/tour nay"
  + Gemini chi compose cau tra loi tu evidence
```

Khong nen tiep tuc them regex intent le te. Moi regex moi co the sua 1 case nhung tao bug case khac. Bot can it "cua" hon, nhung moi cua phai co pipeline chuan: retrieve -> filter -> resolve state -> answer.

