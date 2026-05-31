# CHATBOT PRO DEPLOY AND TEST REPORT - 2026-05-26

**Pham vi:** Trien khai va kiem thu luong chatbot tren backend `analytics-service` va frontend `ChatbotWidget`.  
**Endpoint backend test:** `POST http://localhost:8087/api/chatbot/chat`  
**Endpoint frontend/gateway test:** `POST http://localhost:8080/api/chatbot/chat`  
**Container da restart:** `tourism-analytics-service`  
**Frontend:** khong thay frontend container rieng trong `docker ps`; da verify React build va gateway API. Neu frontend dang chay bang `npm start`, source change se hot reload.  
**Trang thai cuoi:** `healthy`

---

## 1. Ket luan ngan

Da sua cac loi lam chatbot bi "ngu" trong transcript:

- `toi dat tour` khong con roi xuong RAG/quang cao tour, ma vao luong dat tour va hoi thieu thong tin.
- `toi muon di nha trang -> hcm -> gan nhat -> 2 nguoi` giu dung context Nha Trang, hieu `hcm` la diem khoi hanh, neu khong co tour thi bao ro va goi y diem khoi hanh khac dang co.
- `co tour di phu yen/da lat ko` khong can hardcode alias, van tach duoc destination tu cau hoi va tra no-result sach, khong show tour rac.
- `Ha Noi - Hai Phong - Cat Ba ... xem chi tiet` resolve dung theo ten tour vua nhap, khong fallback ve tour HCM/Vung Tau cu.
- `con may slot/gia tour` khi khong co context thi hoi lai tour nao, khong lay random tour.
- Booking 2 nguoi thu du thong tin 2 hanh khach, chap nhan dang `Ten, Gioi tinh, yyyy-mm-dd`, tong tien tinh 2 nguoi.
- Frontend tu clear transcript cu neu phat hien noi dung loi encoding mojibake trong `localStorage`.

---

## 2. Kien truc chatbot hien tai

### 2.1 Luong xu ly request

```text
Frontend ChatbotWidget
  -> API Gateway :8080/api/chatbot/chat
  -> Analytics Service :8087/api/chatbot/chat
  -> ChatbotService.handleUserMessage()
       1. Load ConversationState tu Redis theo sessionId
       2. Luu turn user vao recentTurns
       3. IntentRouter phan loai intent truoc moi stage
       4. handleDeterministic xu ly intent co data that
       5. handleBookingFlow delegate BookingConversationService neu la dat/search tour
       6. handleWithRAG chi fallback cho tu van chung
       7. Luu assistant turn + state
```

Nguyen tac moi: **IntentRouter luon chay truoc state machine**, nen dang o buoc chon ngay van hoi booking/giam gia/thanh toan duoc.

### 2.2 Vai tro AI, RAG, Pinecone, DB/API

| Thanh phan | Vai tro dung |
|---|---|
| `IntentRouter` | Fast-path phan loai cau hoi: booking, search, slot, gia, detail, payment, discount. |
| `GeminiIntentService` | Fallback phan loai khi regex/reference resolver khong bat duoc. Khong lam source of truth. |
| `Pinecone/VectorService` | Tim ung vien tour/noi dung theo ngu nghia, detail tour, discount candidates. |
| Redis `ConversationState` | Nho stage, search params, selected tour, lastSearchResults, passengers, contact. |
| Booking/payment API | Source of truth cho booking detail, create booking, payment URL/status. |
| Tour/departure metadata trong vector | Dung de search/filter nhanh; gia/slot trong response phai lay tu metadata da sync hoac API that khi co. |

Chatbot hien dai khong phai "Pinecone truoc tat ca". Cach dung dung la:

```text
Intent dung -> Data dung -> AI dien dat neu can
```

Gia, slot, booking, payment khong de AI tu doan.

---

## 3. Cac thay doi chinh

### 3.1 Backend

#### `IntentRouter.java`

- Sua normalize tieng Viet: ky tu `d-stroke` duoc chuyen ve `d` truoc khi strip dau.
- Them nhan dien booking intent: `toi dat tour`, `toi muon dat tour`, `dat tour nay`.
- Them free-form destination extraction: cau `co tour di phu yen ko` tach destination `phu yen` ma khong can hardcode alias.
- Bo hardcode exact location trong `isTourSearch`.
- `ASK_DETAIL` gan `queryText` de handler resolve theo ten tour user vua go.

#### `ChatbotService.java`

- Dieu phoi moi: `IntentRouter -> deterministic -> booking flow -> RAG`.
- `ASK_SLOT/ASK_PRICE/ASK_DEPARTURE_DATE` neu khong co context thi hoi lai, khong lay tour lung tung.
- `ASK_DISCOUNT/ASK_COUPON` xu ly deterministic tu Pinecone metadata, gioi han ket qua, co resume/cancel khi dang trong flow.
- `ASK_DETAIL` uu tien ten tour trong message; neu ten tour khong nam trong state thi search vector theo ten, khong fallback ve tour cu.
- Khi dang thieu startLocation va user nhap `hcm`, message delegate sang booking flow duoc normalize thanh `khoi hanh HCM`, tranh bi hieu la destination moi.

#### `BookingConversationService.java`

- No-result clear context cu: `lastSearchResults`, `lastDepartures`, `lastMentionedTourId`, selected tour/departure.
- No-result khong fallback tour rac.
- Neu destination co tour nhung startLocation khong co, bot goi y startLocation khac dang co trong data.
- Passenger flow thu du hanh khach theo count; chap nhan input gom ten/gioi tinh/ngay sinh cung mot cau.

#### `LocationResolverService.java` / `ReferenceResolverService.java`

- Sua normalize tieng Viet `đ/Đ -> d/D`, giup `đà lạt`, `đặt`, `đi` khong bi miss.

### 3.2 Frontend

#### `ChatbotWidget.jsx`

- Them guard `hasBrokenVietnameseEncoding()` khi load transcript tu `localStorage`.
- Neu transcript cu co `Ã/Â/Ä/Æ/�`, widget bo transcript cu va hien welcome sach.
- Sua regex parse PayOS link de het warning `no-useless-escape`.

---

## 4. Dong bo du lieu/Pinecone

He thong hien co cac lop sync vector trong analytics service (`VectorSyncService`, `VectorService`). Luong nen van hanh:

```text
Tour/Location/Review/Coupon update
  -> sync document vao Pinecone
  -> chatbot search semantic tu Pinecone
  -> filter lai theo metadata: destination, startLocation, slot, date
  -> neu la booking/payment thi goi Booking/Payment API
```

Neu du lieu thay doi lien tuc, nen di tiep bang RabbitMQ:

```text
tour-service / booking-service / review-service
  -> publish event TOUR_UPDATED / DEPARTURE_UPDATED / REVIEW_CREATED / COUPON_UPDATED
  -> analytics/indexer consumer nhan event
  -> gom entityId vao Redis buffer
  -> moi 30-120 giay flush batch
  -> lay data moi nhat tu DB/API
  -> upsert/delete Pinecone
```

Khong nen de moi update nho goi Pinecone ngay lap tuc; nen debounce/batch de tranh ton chi phi va tranh sync nhieu lan.

---

## 5. Ket qua test

### 5.1 Backend unit/integration test

Command:

```bash
mvn -pl analytics-service test -DskipITs
```

Ket qua:

```text
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Da them regression cho:

- `tôi đặt tour` -> `BOOKING_FLOW`
- `co tour di phu yen ko` -> `TOUR_SEARCH`, destination `phu yen`
- `có tour đi đà lạt ko` -> `TOUR_SEARCH`, destination `da lat`

### 5.2 Docker deploy

Commands:

```bash
mvn -pl analytics-service package -DskipTests
docker compose build analytics-service
docker compose up -d analytics-service
docker compose ps analytics-service
```

Ket qua:

```text
tourism-analytics-service Up (healthy) 0.0.0.0:8087->8087/tcp
```

### 5.3 API regression test

Endpoint direct:

```text
POST http://localhost:8087/api/chatbot/chat
```

Endpoint qua gateway nhu frontend:

```text
POST http://localhost:8080/api/chatbot/chat
```

| Flow | Ket qua |
|---|---|
| `xin chao` | Chao ngan, quick actions, khong spam tour. |
| `toi dat tour` | Vao `COLLECTING_SEARCH_INFO`, hoi diem den/thoi gian/nguoi lon. |
| `xem booking sao` | Hoi ma `BK...`, co resume/cancel neu dang trong flow. |
| `toi muon xem don hang da dat` | Hoi ma `BK...`, khong quang cao tour. |
| `thanh toan sao` | Huong dan gui ma booking de tao/xem thanh toan. |
| `toi muon di nha trang -> hcm -> gan nhat -> 2 nguoi` | Giu destination Nha Trang, hieu HCM la startLocation; neu khong co tour HCM->Nha Trang thi goi y co tour Nha Trang khoi hanh Ha Noi. |
| `co tour di phu yen ko` | No-result sach, khong show tour khac. |
| `co tour di da lat ko` | No-result sach, khong show tour khac. |
| `tour khoi hanh hcm ko` | Tra 3 tour khoi hanh HCM, stage `SHOWING_SEARCH_RESULTS`. |
| `Ha Noi - Hai Phong - Cat Ba ... xem chi tiet` sau HCM results | Resolve dung tour Cat Ba, khong fallback Vung Tau. |
| Search fail -> `con may slot` | Khong lay old HCM results; hoi lai tour nao. |
| Booking 2 nguoi | Thu du 2 hanh khach, contact, email; card confirm tong tien 2 nguoi. |
| Dang booking -> `tour nao dang giam gia ko` | Tra discount deterministic ngan hon, co resume/cancel. |
| Dang booking -> `xem booking sao` | Hoi ma booking, preserve state. |

### 5.4 Frontend build

Command:

```bash
BUILD_PATH=build_codex_verify npm run build
```

Ket qua:

```text
Compiled with warnings.
```

Warnings con lai la warnings san co cua repo (`unused vars`, `hook deps`, `bundle size`, browserslist old). Warning chatbot `no-useless-escape` da het. Thu muc build tam `build_codex_verify` da xoa sau khi verify.

Gateway UTF-8 test bang .NET client:

```text
HTTP=OK
STAGE=COLLECTING_SEARCH_INFO
TYPE=TEXT
SUG=0
REPLY=Bạn muốn đến **đâu** và đi vào **khoảng thời gian** nào? Mấy **người lớn**? 🙂
```

---

## 6. Luu y con lai

1. Mot so file chatbot/backend/frontend da co thay doi/untracked tu truoc trong working tree. Khong revert cac thay doi do.
2. Frontend production `build` co the dang bi process khac lock, nen build verify duoc thuc hien vao `build_codex_verify`.
3. De dat muc "nhan vien that" hon nua, buoc tiep theo nen them:
   - API lay tour detail/departure realtime tu tour-service khi user hoi detail/slot/gia.
   - DB chat history theo `sessionId`, `userId nullable` de reload UI cross-device.
   - RabbitMQ batch sync Pinecone khi tour/departure/coupon/review thay doi.
   - Playwright E2E screenshot neu can xac nhan visual tren trinh duyet that.

---

## 7. Danh gia kien truc

Kien truc moi gan cach chatbot hien dai hon:

- Router truoc stage machine, nen khong bi ket luong.
- Deterministic handler cho business facts, nen khong hallucinate gia/slot/booking.
- RAG/Pinecone dung dung vai tro: tim ung vien va bo sung context.
- Redis giu memory ngan han cua hoi thoai.
- Frontend giu session/transcript, F5 khong mat phien; transcript loi encoding cu duoc tu don.

No khong con la form wizard cung nhu truoc, nhung van giu state machine cho phan can chinh xac: chon tour, ngay, hanh khach, contact, confirm booking.
