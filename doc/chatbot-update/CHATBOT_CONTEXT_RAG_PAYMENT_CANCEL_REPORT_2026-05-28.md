# CHATBOT CONTEXT RAG PAYMENT CANCEL REPORT - 2026-05-28

## 1. Pham vi trien khai

Chi sua logic chatbot trong `analytics-service`, khong thay doi business logic tao tour/booking/payment hien co.

Service da build va restart:

- `analytics-service`

Khong rebuild cac service khac vi khong thay doi DTO/API phia booking-service, payment-service, tour-catalog-service.

## 2. Kien truc moi

Pipeline hien tai:

```text
POST /api/chatbot/chat
  -> ChatbotController
  -> ChatbotService.handleUserMessage()
  -> IntentRouter.route()
  -> deterministic handler neu la booking/payment/cancel/context
  -> BookingConversationService neu la transaction flow
  -> RAG theo mode neu la cau hoi tu van/tim kiem
  -> ChatMessageResponse cho FE
```

### 2.1 Intent group

- `TRANSACTION_FLOW`: dat tour, chon tour, chon ngay, nhap hanh khach/contact, confirm.
- `BOOKING_LOOKUP_PAYMENT`: tra cuu booking hoac ho tro thanh toan ma BK.
- `BOOKING_CANCEL_HELP`: huong dan huy booking/tour theo ma BK, khong tu huy booking that.
- `TOUR_RETRIEVAL`: search tour, hoi tour 1/2/3, slot/gia/ngay/lich trinh/chinh sach tour dang xem.
- `GENERAL_RAG`: policy chung, bao hiem, review, thanh toan chung, kinh nghiem du lich.

### 2.2 Context resolver

Thu tu resolve tour:

1. `tour 1/2/3` -> lay dung item trong `state.lastSearchResults`.
2. `tour nay/tour do/tour tren` -> lay `state.lastMentionedTourId`.
3. Neu chi co 1 tour -> auto lay tour do.
4. Neu khong co context -> bao ro "chua co danh sach tour", khong goi vector random.

### 2.3 RAG mode

`ChatbotService.handleWithRAG()` duoc tach theo mode:

- `GENERAL_POLICY`: chi tra loi cau hoi chung, khong build cards, khong chen coupon/tour khuyen mai.
- `TOUR_CONTEXT`: chi dung tour dang resolve, khong loi tour khac vao.
- `TOUR_SEARCH`: chi hien tour khop dieu kien search.
- `DISCOUNT`: chi dung khi user hoi uu dai/coupon.

Voi `GENERAL_POLICY`, history chat cu khong duoc dua vao prompt de tranh nhiem ket qua tour cu.

## 3. Cac thay doi chinh

### 3.1 `IntentRouter`

- Them intent `BOOKING_CANCEL_HELP`.
- BK code duoc extract giu nguyen chu hoa/thuong.
- `ho tro thanh toan ma BK...` -> `BOOKING_LOOKUP_PAYMENT`.
- `huy tour ma BK...` -> `BOOKING_CANCEL_HELP`.
- `toi dat tour do` uu tien vao transaction flow truoc reference resolver.
- Review/rating nhu `danh gia tour Da Nang` uu tien `GENERAL_RAG` truoc contextual resolver.
- Policy co context nhu `chinh sach tour 1`, `bao gom tour nay` -> `TOUR_RETRIEVAL/POLICY`.
- Lookup stage khong con nuot cau thuong.

### 3.2 `ChatbotService`

- Them `RagMode`.
- Lookup non-BK trong `COLLECTING_LOOKUP_CODE` se thoat lookup mem va route lai cau hoi.
- Quick actions `RESUME_BOOKING/CANCEL` chi hien khi co draft that.
- Cancel chat xoa draft booking de khong con resume gia.
- Payment help/cancel help route sang `BookingConversationService`.
- `tour 1` resolve theo index truoc khi dung text/vector.

### 3.3 `BookingConversationService`

- `toi dat tour do` sau khi da xem chi tiet tour se dung `lastMentionedTourId` de chon dung tour.
- `ngay gan nhat` trong `SELECTING_DEPARTURE` tu chon departure dau tien.
- Neu user nhap so khach khi chua chon ngay, bot nhac chon ngay truoc.
- No-result search clear context va ve `IDLE`, khong de ket `COLLECTING_SEARCH_INFO`.
- `performPaymentHelpPublic()` tao PayOS link neu booking con tien phai tra.
- `performCancelHelpPublic()` tra hotline `0339263066` va email `admin@futuretravel.vn`, khong tu cancel booking.

### 3.4 `GeminiIntentService`

- Them `responseMimeType: application/json` de giam loi Gemini tra markdown/string thay vi JSON.
- Prompt intent co them `BOOKING_CANCEL_HELP`.

## 4. Build va deploy

Da chay:

```powershell
mvn -pl analytics-service -am package -DskipTests
docker compose build analytics-service
docker compose up -d analytics-service
docker compose ps analytics-service
```

Ket qua:

- Maven: `BUILD SUCCESS`
- Docker image: `tourism-microservices-backend-analytics-service:latest`
- Container: `tourism-analytics-service` healthy
- Frontend check: `http://localhost:3000` tra `200`

## 5. Ket qua test API long session

Session: `codex-context-rag-report-1779955708053`

| # | Input | Ket qua |
|---|---|---|
| 1 | `helllo` | `IDLE`, greeting sach, khong spam tour |
| 2 | `chinh sach huy tour sao` | `IDLE`, `sug=0`, khong coupon/tour khuyen mai |
| 3 | `co tour khoi hanh Ha Noi di Hai Phong khong` | No-result sach, `IDLE`, khong fallback tour random |
| 4 | `xem ... tour 1` sau no-result | Bao chua co danh sach tour, khong lay tour rac |
| 5 | `khoi hanh Ha Noi` | `SHOWING_SEARCH_RESULTS`, 3 tour |
| 6 | `xem ... tour 1` | Resolve dung `Ha Noi - Ha Long`, khong nhay HCM-Vung Tau |
| 7 | `toi dat tour do` | Chuyen `SELECTING_DEPARTURE` dung tour vua mention |
| 8 | `ngay gan nhat` | Chuyen `COLLECTING_PASSENGERS` |
| 9 | `tour nay co bao gom an sang khong` | Tra theo tour dang dat, co resume/cancel do co draft |
| 10 | `tiep tuc dat tour` | Nhac lai buoc nhap hanh khach |
| 11 | `2 nguoi lon 1 tre em` | Ghi nhan passenger count, hoi hanh khach 1 |
| 12 | `ho tro thanh toan ma BKf3845364` | Tao PayOS link, co quick action `PAY_NOW` |
| 13 | `huy tour ma BKf3845364` | Tra huong dan huy + hotline/email, khong tu cancel booking |
| 14 | `xem booking sao` | Hoi ma BK, co resume vi dang co draft dat tour |
| 15 | `moi nguoi danh gia tour Da Nang...` | Thoat lookup mem, ve `IDLE`, tra loi review/tong quan, khong ket lookup |
| 16 | `huy` | Huy luong chat + clear draft |
| 17 | `tiep tuc dat tour` | Bao khong co luong dang cho, khong hien resume gia |

## 6. Ket qua UI/API contract

- Frontend `http://localhost:3000` dang phan hoi `200`.
- API response cho UI da co:
  - `paymentUrl`
  - `paymentWaitingLink`
  - quick action `PAY_NOW`
  - quick action `CALL_SUPPORT`
  - quick action `EMAIL_SUPPORT`
  - `tourSuggestions` chi xuat hien khi search tour
- Chua chay browser automation screenshot; kiem tra lan nay tap trung API contract vi FE dang chay san.

## 7. Gioi han con lai

1. `Ha Noi -> Hai Phong` dang no-result trong Pinecone. Trong ket qua `khoi hanh Ha Noi`, tour code/name hien co mot item `Ha Noi - Vung tau` voi gia/ngay giong tour Hai Phong/Cat Ba truoc day. Can kiem tra data sync/index metadata cua Pinecone hoac source DB, khong nen hardcode alias de che loi du lieu.
2. Cau hoi ve `bao gom an sang/chinh sach chi tiet` hien tra thong tin tong quan tour neu Pinecone chua co chunk policy/bao gom. Muon tra chinh sach sau hon can index policy/itinerary/bao-gom-khong-bao-gom day du.
3. Khi user dang co booking draft ma hoi side quest, quick actions resume/cancel van xuat hien dung y do. Neu muon side quest khong hien resume sau payment/cancel BK thi can them `suspendedDraft` rieng, nhung hien tai van giu draft de khach quay lai dat tour.

