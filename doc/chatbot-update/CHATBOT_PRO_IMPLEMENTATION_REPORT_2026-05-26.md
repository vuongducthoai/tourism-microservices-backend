# CHATBOT PRO IMPLEMENTATION REPORT - 2026-05-26

## 1. Pham vi da lam

Chi thay doi logic chatbot va test lien quan chatbot.

Backend:
- `analytics-service/src/main/java/com/tourism/analytics/service/ChatbotService.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/IntentRouter.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/ReferenceResolverService.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/BookingConversationService.java`
- `analytics-service/src/main/java/com/tourism/analytics/service/GeminiIntentService.java`
- `analytics-service/src/main/java/com/tourism/analytics/dto/chatbot/IntentResult.java`

Frontend:
- `tourism_frontend/client-side/src/components/ChatbotWidget/ChatbotWidget.jsx`

Test:
- `analytics-service/src/test/java/com/tourism/analytics/service/IntentRouterTest.java`
- `analytics-service/src/test/java/com/tourism/analytics/service/ReferenceResolverServiceTest.java`
- `analytics-service/src/test/java/com/tourism/analytics/service/ChatbotServiceTest.java`

## 2. Thay doi chinh

### 2.1 Router intent chay truoc stage

`ChatbotService` bay gio cho `IntentRouter` phan loai truoc khi dua message vao `BookingConversationService`.

Muc tieu:
- Dang o `SELECTING_DEPARTURE` van hoi duoc booking/giam gia/thanh toan.
- Khong con ep moi cau thanh "ngay khoi hanh".
- Cac cau co data that di deterministic handler truoc, khong de Gemini doan.

### 2.2 Intent moi

Da bo sung/cung co cac intent:
- `GREETING`
- `CANCEL`
- `RESUME_BOOKING`
- `BOOKING_LOOKUP`
- `START_LOCATION_SEARCH`
- `ASK_DETAIL`
- `ASK_SLOT`
- `ASK_PRICE`
- `ASK_DEPARTURE_DATE`
- `ASK_DISCOUNT`
- `ASK_COUPON`
- `PAYMENT_HELP`
- `TOUR_SEARCH`
- `CHANGE_SEARCH`

### 2.3 Context question deterministic

Bot tra loi bang state/API thay vi doan:
- `xem chi tiet tour`
- `con may slot`
- `gia tour`
- `ngay khoi hanh`
- `tiep tuc dat tour`

Neu dang co 1 tour Nha Trang, user go `xem chi tiet tour` thi xem dung tour do, khong reset ve "ban muon tim tour den dau".

### 2.4 ReferenceResolver ho tro khong dau

`ReferenceResolverService` da duoc chuan hoa lai de hieu:
- `tour do`
- `tour nay`
- `chuyen nay`
- `con may slot`
- `gia bao nhieu`

Truoc day service nay gan voi pattern co dau/mojibake nen test khong dau va mot so cau user that de bi miss.

### 2.5 Search theo diem khoi hanh

Da sua logic phan biet:
- `destination`: diem den
- `startLocation`: diem khoi hanh

Case `co tour khoi hanh hcm khong` tra ve cac tour co khoi hanh HCM thay vi hieu sai HCM la diem den.

### 2.6 Frontend quick actions

`ChatbotWidget.jsx` xu ly quick action theo object day du:
- `RESUME_BOOKING`
- `CANCEL`
- `LOOKUP`
- `VIEW_DEALS`
- `VIEW_FAVORITES`
- `VIEW_UPCOMING`
- `navigate`

Frontend van giu session/message localStorage de F5 khong mat UI phien hien tai.

## 3. Ket qua build va deploy

Backend:
- `mvn -pl analytics-service test`: PASS
- `mvn -pl analytics-service -DskipTests package`: PASS
- `docker compose build analytics-service`: PASS
- `docker compose up -d analytics-service`: PASS
- `tourism-analytics-service`: healthy

Frontend:
- `npm run build` trong `tourism_frontend/client-side`: PASS
- Co warning lint san co trong nhieu component ngoai chatbot; build van thanh cong.
- `http://localhost:3000`: tra HTTP 200.
- Khong thay frontend container trong `docker ps`, nen khong restart container frontend.
- `npm test -- --watchAll=false --passWithNoTests`: FAIL do test harness hien co khong resolve duoc `react-router-dom` tu `src/App.tsx`, trong khi `node_modules/react-router-dom` ton tai va production build pass. Chua sua vi day khong thuoc logic chatbot.

Container sau deploy:
- `tourism-analytics-service`: healthy, port `8087`
- `tourism-api-gateway`: healthy, port `8080`
- `tourism-tour-catalog-service`: healthy, port `8082`
- `tourism-booking-service`: healthy, port `8083`
- `tourism-payment-service`: healthy, port `8084`
- `tourism-redis`, `tourism-postgres`, `tourism-rabbitmq`: healthy

## 4. Unit test

Ket qua `mvn -pl analytics-service test`:

| Test class | Result |
|---|---|
| `ChatbotControllerTest` | 7 pass |
| `ChatbotServiceTest` | 11 pass |
| `IntentRouterTest` | 11 pass |
| `ReferenceResolverServiceTest` | 12 pass |
| `VectorServiceTest` | 9 pass |
| `VectorSyncServiceTest` | 8 pass |

Tong: `58 tests`, `0 failures`, `0 errors`.

## 5. API smoke test qua gateway

Endpoint: `POST http://localhost:8080/api/chatbot/chat`

| Input | Ket qua |
|---|---|
| `xin chao` | Stage `IDLE`, tra greeting ngan gon, khong spam tour |
| `nha trang` | Stage `SHOWING_SEARCH_RESULTS`, tra 1 tour Nha Trang |
| `xem chi tiet tour` | Tra chi tiet dung tour Nha Trang dang hien |
| `con may slot vay` | Tra slot that theo ngay cua tour Nha Trang |
| `1` | Chuyen `SELECTING_DEPARTURE`, hien ngay khoi hanh |
| `tour nao dang giam gia ko` | Khong bi date parser nuot; tra giam gia va giu flow |
| `toi muon xem 1 booking thi sao` | Khong bao sai ngay; hoi ma booking BK va giu stage |
| `tiep tuc dat tour` | Hien lai dung buoc chon ngay cua tour dang dat |
| `co tour khoi hanh hcm khong` | Tra 3 tour khoi hanh HCM |

## 6. Van de da giam ro

Da xu ly cac loi trong transcript:
- Khong con loop "Toi chua tim thay ngay do" khi user hoi booking/giam gia trong luc chon ngay.
- `xem chi tiet tour` sau search co the resolve theo tour dang hien.
- `con may slot` tra data tu state, khong bat user nhap 1/2/3.
- `toi muon xem 1 booking thi sao` duoc route ve lookup help.
- `co tour khoi hanh hcm khong` khong bi hieu sai la tour den HCM.
- Greeting khong tu dong quang cao tour giam gia.

## 7. Diem con nen nang cap tiep

1. Cau `tour nao dang giam gia ko` hien van di qua RAG/Pinecone va co the tra hoi dai, co 6 tour card. Nen tach thanh deterministic discount handler lay coupon/departure tu API de format ngan hon.
2. Detail itinerary hien uu tien state/Pinecone content; neu muon "nhu nhan vien that" hon nen goi them API chi tiet tour/departure de lay lich trinh ngay 1/ngay 2 day du.
3. UI da build va endpoint frontend reachable, nhung chua co Playwright automation screenshot/click do project chua co pipeline UI test ro rang trong turn nay.
4. Frontend Jest test hien fail o `src/App.test.js` voi loi `Cannot find module 'react-router-dom' from 'src/App.tsx'`; can xu ly rieng neu muon co pipeline FE test sach.
5. IAM/Keycloak container dang bao unhealthy tu truoc trong `docker ps`; khong nam trong scope chatbot va khong bi restart.

## 8. Ket luan

Ban chatbot hien tai da qua muc "wizard cung" o cac case chinh: intent global chay truoc, state khong nuot cau hoi ngang, slot/detail/booking lookup di theo du lieu that va co resume flow.

Muc can lam tiep de dat chat luong Vietravel-style la P2/P3: deterministic discount/payment/booking detail sau hon, va UI test tu dong bang Playwright.
