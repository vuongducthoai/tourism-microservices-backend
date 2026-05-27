# CHATBOT RAG FLOW IMPLEMENTATION REPORT - 2026-05-27

**Pham vi:** Sua luong dieu phoi chatbot, search tour, state booking, resume flow va encoding.  
**Backend:** `D:\HK8\tourism-microservices-backend`  
**Frontend:** `D:\HK8\tourism_frontend`  
**Trang thai:** Da build backend, rebuild/restart container `analytics-service`, build frontend va test API.

---

## 1. Ket luan ngan

Da sua cac loi lam chatbot "ngao" nhat trong luong hien tai:

- Dang hien danh sach tour, user nhap `1/2/3` khong con bi search lai; bot chuyen dung sang chon ngay khoi hanh.
- Dang hoi them thong tin cho tour Nha Trang, user tra `ha noi` khong con bi hieu sai thanh Hoi An/destination moi.
- Query du route nhu `tour ha noi den hai phong thang 4 2 nguoi lon` co the ra dung 1 tour Ha Noi - Hai Phong - Cat Ba.
- Khi dang booking ma user hoi `xem booking sao`, bot tam dung luong, hoi ma booking, va `tiep tuc dat tour` quay lai stage truoc.
- Java build da ep UTF-8, API tra tieng Viet dung khi client decode dung UTF-8.

Khong lam chunking/RabbitMQ trong dot nay theo dung pham vi hien tai. RAG/Pinecone van duoc dung cho search/retrieval, nhung cac thao tac giao dich nhu chon tour, chon ngay, booking lookup, payment, passenger/contact khong dua cho AI doan.

---

## 2. Kien truc sau khi sua

### 2.1 Nguyen tac dieu phoi

Luong chatbot hien tai duoc giu theo huong:

```text
User message
  -> ChatbotService load ConversationState
  -> IntentRouter phan loai intent va entity co ban
  -> deterministic guard cho booking/payment/resume/cancel/stage input
  -> tour retrieval/search qua Pinecone + metadata filter
  -> save lastSearchResults/selectedTour/previousStage
  -> Gemini/RAG chi dung cho tu van/dien dat khi khong phai giao dich chinh xac
```

Ly do khong day moi cau qua Gemini truoc: cac buoc nhu `1`, `20/03`, email, phone, passenger, ma BK la thao tac chinh xac. Neu de AI doan se tao booking sai, sai slot, sai gia, hoac mat stage.

### 2.2 Vai tro cua RAG/Pinecone

RAG/Pinecone dung cho:

- Tim ung vien tour theo cau tu nhien.
- Tim tour theo diem di/diem den/category.
- Lay context cho chi tiet tour, lich trinh, uu dai neu co du lieu vector.

RAG/Pinecone khong duoc tu quyet:

- Gia/slot/payment/booking status.
- Chon tour `1/2/3`.
- Chon ngay khoi hanh.
- Tao booking va tinh tien hanh khach.

---

## 3. Cac thay doi code chinh

### 3.1 `IntentRouter.java`

File: `analytics-service/src/main/java/com/tourism/analytics/service/IntentRouter.java`

- Stage `SHOWING_SEARCH_RESULTS`:
  - `1`, `2`, `3`, `tour dau`, `dau tien` -> `BOOKING_FLOW`.
  - Khong con ep thanh `TOUR_SEARCH`.
- Stage `SELECTING_DEPARTURE`:
  - Chi input giong ngay/thang hoac so moi vao booking stage.
  - Cau hoi ngang nhu booking/payment/discount/detail duoc de global handler xu ly.
- Stage `COLLECTING_SEARCH_INFO`:
  - Khi bot dang thieu start location, cau ngan nhu `ha noi` duoc hieu la diem khoi hanh.
  - Cau day du co `den ... thang ... nguoi` duoc strip modifier truoc khi resolve location.

### 3.2 `LocationResolverService.java`

File: `analytics-service/src/main/java/com/tourism/analytics/service/LocationResolverService.java`

- Tat vector fallback cho cau tra loi ngan neu khong co exact/catalog match.
- Acronym match chi chap nhan tu 3 ky tu tro len, tranh `ha noi` match nham `Hoi An` qua acronym `ha`.

Ket qua: `toi muon di nha trang` -> `ha noi` giu destination Nha Trang va set start location Ha Noi.

### 3.3 `ConversationState.java`

File: `analytics-service/src/main/java/com/tourism/analytics/dto/chatbot/ConversationState.java`

- Them `previousStage`.

Muc dich: khi user dang booking ma re sang `xem booking sao`/payment help, bot co the pause stage hien tai va resume lai dung buoc.

### 3.4 `ChatbotService.java`

File: `analytics-service/src/main/java/com/tourism/analytics/service/ChatbotService.java`

- Them/hoan thien `buildResumeResponse()`.
- Khi lookup booking tu active flow, luu `previousStage`.
- Khi `tiep tuc dat tour`, neu dang `COLLECTING_LOOKUP_CODE` thi restore previous stage.
- Khi prefill search entity tu intent, set dung cac flag:
  - `searchStartLocationProvided`
  - `searchDateRangeProvided`
  - `searchAdultsProvided`

Ket qua: query du route `tour ha noi den hai phong thang 4 2 nguoi lon` khong bi hoi lai cac thong tin da co.

### 3.5 `BookingConversationService.java`

File: `analytics-service/src/main/java/com/tourism/analytics/service/BookingConversationService.java`

- Cancel clear `previousStage`.
- Booking lookup success co the quay lai stage truoc.
- `isCancel()` bot nham hon, khong bat cac cau co chu `huy` nhung khong phai huy flow.
- Giu booking flow thu thap hanh khach theo so luong, roi moi hoi contact/email/xac nhan.

### 3.6 `pom.xml`

File: `pom.xml`

- Them build encoding UTF-8:

```xml
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
```

Ly do: source Java co chuoi tieng Viet. Neu Maven compiler doc sai encoding, container build sinh mojibake.

---

## 4. Luong hoat dong sau khi sua

### 4.1 Search kieu Vietravel

User:

```text
toi muon di nha trang
ha noi
thang 6
2 nguoi lon
```

Bot:

- Ghi nho destination = Nha Trang.
- Hoi thieu start/time/adults.
- `ha noi` -> start location.
- `thang 6` -> date range.
- `2 nguoi lon` -> adults.
- Sau khi du slot, search Pinecone + filter metadata, tra 1 tour Nha Trang.

### 4.2 Route day du

User:

```text
tour ha noi den hai phong thang 4 2 nguoi lon
```

Bot:

- Resolve start = Ha Noi.
- Resolve destination = Hai Phong/Cat Ba theo vector + metadata.
- Co adults/date nen search ngay.
- Tra 1 tour Ha Noi - Hai Phong - Cat Ba.

### 4.3 Chon tour va dat tour

User:

```text
tour khoi hanh hcm ko
1
20/03
```

Bot:

- Tra 3 tour khoi hanh HCM.
- `1` -> chon tour 1, stage `SELECTING_DEPARTURE`.
- `20/03` -> chon ngay, stage `COLLECTING_PASSENGERS`.

### 4.4 Hoi ngang khi dang booking

User dang o `COLLECTING_PASSENGERS`:

```text
xem booking sao
tiep tuc dat tour
```

Bot:

- Tam dung luong, stage `COLLECTING_LOOKUP_CODE`.
- Hoi ma booking BK.
- `tiep tuc dat tour` -> restore stage `COLLECTING_PASSENGERS`.

---

## 5. Build, deploy va test

### 5.1 Backend unit test

Da chay:

```bash
mvn -pl analytics-service -Dtest=ChatbotBugFixTest test
mvn -pl analytics-service test -DskipITs
```

Ket qua:

- `ChatbotBugFixTest`: 83 tests passed.
- `analytics-service test`: 145 tests passed.

### 5.2 Backend package + Docker

Da chay:

```bash
mvn -pl analytics-service clean package -DskipTests
docker compose build analytics-service
docker compose up -d analytics-service
docker compose ps analytics-service
```

Ket qua:

- Build jar thanh cong.
- Docker image `analytics-service` build thanh cong.
- Container `tourism-analytics-service` dang `healthy`, port `8087`.

### 5.3 Frontend build

Da chay:

```bash
cd D:\HK8\tourism_frontend\client-side
npm run build
```

Ket qua:

- Build thanh cong.
- Co nhieu warning lint cu trong cac module admin/tour/payment/forum, khong lam fail build.
- Trong dot nay khong sua UI chatbot vi loi chinh nam o backend orchestration/state/encoding.

### 5.4 API smoke test

Endpoint:

```text
POST http://localhost:8087/api/chatbot/chat
```

#### Flow Nha Trang

| Input | Stage | Ket qua |
|---|---|---|
| `toi muon di nha trang` | `COLLECTING_SEARCH_INFO` | Hoi them start/time/adults, giu destination Nha Trang |
| `ha noi` | `COLLECTING_SEARCH_INFO` | Hoi thang/thoi gian, khong doi sang Hoi An |
| `thang 6` | `COLLECTING_SEARCH_INFO` | Hoi so nguoi |
| `2 nguoi lon` | `SHOWING_SEARCH_RESULTS` | Tra 1 tour Ha Noi - Nha Trang |
| `xem chi tiet tour` | `SHOWING_SEARCH_RESULTS` | Chi tiet dung tour Nha Trang |
| `con may slot` | `SHOWING_SEARCH_RESULTS` | Slot dung cua tour Nha Trang |

#### Flow chon tour/booking/resume

| Input | Stage | Ket qua |
|---|---|---|
| `tour khoi hanh hcm ko` | `SHOWING_SEARCH_RESULTS` | Tra 3 tour HCM |
| `1` | `SELECTING_DEPARTURE` | Chon tour 1, hien ngay |
| `20/03` | `COLLECTING_PASSENGERS` | Chon ngay, hoi hanh khach |
| `xem booking sao` | `COLLECTING_LOOKUP_CODE` | Hoi ma BK, co resume |
| `tiep tuc dat tour` | `COLLECTING_PASSENGERS` | Quay lai dung stage |

#### Flow route/no-result

| Input | Stage | Ket qua |
|---|---|---|
| `tour ha noi den hai phong thang 4 2 nguoi lon` | `SHOWING_SEARCH_RESULTS` | Tra 1 tour Ha Noi - Hai Phong - Cat Ba |
| `co tour di phu yen ko` | `COLLECTING_SEARCH_INFO` | No-result sach, khong hien tour rac |

### 5.5 Encoding

PowerShell `Invoke-RestMethod` co the in mojibake tren terminal neu console decode sai. Kiem tra bang Node/fetch cho thay API JSON tra UTF-8 dung:

```text
Dạ tuyệt vời, **Nha Trang** là lựa chọn rất thú vị...
Tôi tìm được **1 tour** phù hợp cho bạn...
```

Browser/frontend dung fetch JSON nen se decode theo UTF-8 dung sau khi image moi da deploy.

---

## 6. Nhung gi chua lam trong dot nay

- Chua tach rieng `TourRetrievalService`; hien retrieval/filter van nam rải trong `BookingConversationService` va handler trong `ChatbotService` de giam blast radius.
- Chua lam lai chunking Pinecone.
- Chua them RabbitMQ auto sync.
- Chua sua cac warning lint frontend cu vi ngoai pham vi chatbot.
- Chua them Playwright UI automation vi project hien tai khong co Playwright setup; da verify bang frontend production build va API flow.

---

## 7. De xuat buoc tiep theo

Neu muon chatbot tien gan kieu Vietravel hon, buoc tiep theo nen lam theo thu tu:

1. Tach `TourRetrievalService` thanh service rieng cho search/detail/slot/price/discount.
2. Chuan hoa evidence object truoc khi dua vao Gemini: selected tours, selected itinerary, selected policy.
3. Gioi han Gemini chi viet cau tra loi dua tren evidence, khong tao tour/cards tu raw docs.
4. Lam RabbitMQ sync sau khi chunking metadata on dinh.
5. Them Playwright test rieng cho chatbot widget: open widget, send messages, assert stage/cards/buttons.

---

## 8. Danh sach file chatbot da cham

- `analytics-service/src/main/java/com/tourism/analytics/service/IntentRouter.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/LocationResolverService.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/ChatbotService.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/BookingConversationService.java`
- `analytics-service/src/main/java/com/tourism/analytics/dto/chatbot/ConversationState.java`
- `analytics-service/src/test/java/com/tourism/analytics/service/ChatbotBugFixTest.java`
- `pom.xml` - chi them UTF-8 build encoding.

