# CHATBOT PRO FLOW FIX REPORT - 2026-05-26

## 1. Muc tieu

Sua tiep luong chatbot theo transcript moi nhat:

- Khi user noi diem den nhu `nha trang`, `da nang`, bot khong show tour ngay neu con thieu thong tin. Bot phai hoi them noi khoi hanh, thoi gian va so nguoi.
- Neu diem den khong co tour dang mo ban, bot khong duoc hien tour rac.
- Khi dang dat tour, cau hoi ngang nhu `co ho tro dat tour tren khong` khong duoc reset ve tim tour.
- Sau khi chon ngay khoi hanh, neu user noi `2 nguoi`, bot phai hieu la so luong hanh khach va tiep tuc hoi thong tin hanh khach 1, 2. Khong duoc nhay thang sang nguoi lien he va tinh 1 nguoi.
- Card xac nhan tren frontend phai hien danh sach hanh khach de user kiem tra truoc khi xac nhan.

## 2. Thay doi backend

### 2.1 Search consultative flow

File: `analytics-service/src/main/java/com/tourism/analytics/service/BookingConversationService.java`

- Them logic `parseAndFillSearchParamsV2`.
- Them cac flag trong `ConversationState` de biet user da cung cap:
  - diem khoi hanh
  - thoi gian
  - so nguoi lon
  - tre em/em be neu co
- Them `askForMissingSearchInfoIfNeeded`.
- Them precheck `destinationHasAnyTour` de neu diem den khong co tour thi tra no-result that, khong fallback sang tour khac.

Ket qua mong doi:

- `nha trang` -> hoi them noi khoi hanh, thoi gian, so nguoi.
- `2 nguoi` -> ghi nhan so nguoi, hoi tiep thong tin con thieu.
- `ha noi` -> ghi nhan khoi hanh.
- `gan nhat` -> luc nay moi search va hien tour.

### 2.2 Passenger composition before passenger details

File: `BookingConversationService.java`

- Sau khi user chon ngay khoi hanh, neu chua co so luong hanh khach, bot hoi so luong nguoi lon/tre em/em be truoc.
- `2 nguoi` duoc parse thanh `searchAdults = 2`.
- Tao slot hanh khach theo dung so luong.
- Lan luot hoi thong tin tung hanh khach.

Ket qua mong doi:

- Chon tour -> chon ngay -> `2 nguoi` -> hoi hanh khach 1.
- Nhap hanh khach 1 -> hoi hanh khach 2.
- Nhap hanh khach 2 -> moi hoi nguoi lien he.
- Xac nhan booking co `adultCount = 2`, `passengerCount = 2`, tong tien tinh 2 nguoi.

### 2.3 System help intent

Files:

- `IntentRouter.java`
- `ChatbotService.java`
- `IntentRouterTest.java`

Them/hoan thien intent `SYSTEM_HELP` cho cac cau:

- `co ho tro dat tour tren chat khong`
- `co ho tro dat tour tren khong`
- `dat online duoc khong`

Ket qua mong doi:

- Bot tra loi quy trinh dat tour tren chat.
- Khong reset state sang `COLLECTING_SEARCH_INFO`.
- Neu dang trong flow, user van co the bam `Tiep tuc dat tour`.

## 3. Thay doi frontend

Files:

- `client-side/src/components/ChatbotWidget/BookingConfirmCard.jsx`
- `client-side/src/components/ChatbotWidget/BookingConfirmCard.module.scss`

Them section `Danh sach hanh khach` trong card xac nhan:

- Hien ten hanh khach.
- Hien gioi tinh neu co.
- Hien loai hanh khach neu co.

Muc dich: user nhin thay booking co du 2 nguoi/3 nguoi truoc khi bam xac nhan.

## 4. Ket qua test

### 4.1 Backend unit/integration tests

Command:

```powershell
mvn -pl analytics-service clean test package
```

Ket qua:

- Build success.
- Tests run: 59.
- Failures: 0.
- Errors: 0.
- Analytics service jar duoc package lai thanh cong.

Ghi chu: lan chay Maven trong sandbox bi loi certificate khi tai parent POM. Da chay lai voi quyen ngoai sandbox va thanh cong.

### 4.2 Frontend build

Command:

```powershell
npm run build
```

Ket qua:

- Build success.
- Co cac eslint warnings cu trong nhieu module ngoai chatbot.
- Chatbot bundle build duoc.

Frontend test command:

```powershell
npm test -- --watchAll=false --passWithNoTests
```

Ket qua:

- Fail tai `src/App.test.js`.
- Loi: Jest khong resolve duoc `react-router-dom` tu `src/App.tsx`.
- Loi nay da ton tai o test harness/App test, khong phai do thay doi chatbot. Production build van pass.

### 4.3 Docker deploy

Commands:

```powershell
docker compose build analytics-service
docker compose up -d analytics-service
docker compose ps analytics-service
```

Ket qua:

- Image `tourism-microservices-backend-analytics-service:latest` build thanh cong.
- Container `tourism-analytics-service` restarted.
- Health: healthy.
- Gateway health check `/api/chatbot/health` tra `UP`.

Khong restart frontend container vi `D:\HK8\tourism_frontend` khong co docker-compose rieng. Frontend dev server tren port 3000 dang tra HTTP 200.

## 5. API smoke test

Endpoint:

```text
POST http://localhost:8087/api/chatbot/chat
```

### Case A - Dat tour 2 nguoi khong bi tinh 1 nguoi

Session moi:

1. `co tour khoi hanh hcm khong`
   - Stage: `SHOWING_SEARCH_RESULTS`
   - Type: `TOUR_SUGGESTIONS`

2. `1`
   - Stage: `SELECTING_DEPARTURE`

3. `20/03`
   - Stage: `COLLECTING_PASSENGERS`
   - Bot hoi so luong/thong tin hanh khach.

4. `2 nguoi`
   - Stage: `COLLECTING_PASSENGERS`
   - Bot ghi nhan 2 nguoi va hoi hanh khach 1.

5. `Nguyen Van A, Nam`
   - Stage: `COLLECTING_PASSENGERS`
   - Bot hoi hanh khach 2.

6. `Tran Thi B, Nu`
   - Stage: `COLLECTING_CONTACT_NAME_PHONE`
   - Bot moi hoi nguoi lien he.

7. `Nguyen Van Minh, 09942094204`
   - Stage: `COLLECTING_CONTACT_EMAIL`

8. `thu@gmail.com`
   - Stage: `CONFIRMING_BOOKING`
   - Type: `BOOKING_CONFIRM`
   - `adultCount = 2`
   - `passengerCount = 2`
   - `estimatedTotal = 3200000`

Ket luan: bug "dat 2 nguoi nhung tinh 1 nguoi" da duoc fix.

### Case B - Hoi thieu thong tin truoc khi search

Session moi:

1. `nha trang`
   - Stage: `COLLECTING_SEARCH_INFO`
   - Bot hoi them khoi hanh/thoi gian/so nguoi.

2. `2 nguoi`
   - Stage: `COLLECTING_SEARCH_INFO`
   - Bot tiep tuc hoi thong tin con thieu.

3. `ha noi`
   - Stage: `COLLECTING_SEARCH_INFO`
   - Bot tiep tuc hoi thoi gian.

4. `gan nhat`
   - Stage: `SHOWING_SEARCH_RESULTS`
   - Type: `TOUR_SUGGESTIONS`
   - Hien tour Nha Trang phu hop.

Ket luan: bot da gan luong Viettravel-style hon, khong show tour ngay khi con thieu thong tin quan trong.

### Case C - No-result khong hien tour rac

Input:

```text
toi muon di da lat
```

Ket qua:

- Stage: `COLLECTING_SEARCH_INFO`
- Bot bao chua co tour Da Lat dang mo ban.
- Khong hien tour Da Nang/Sa Pa/Vung Tau la "phu hop".

### Case D - Hoi ho tro dat tour khong bi reset

Trong flow dang nhap thong tin lien he:

```text
co ho tro dat tour tren khong
```

Ket qua:

- Bot tra loi quy trinh dat tour truc tiep tren chat.
- Stage van giu trong flow hien tai.
- Co quick action `Tiep tuc dat tour`.

## 6. Viec con lai nen lam tiep

1. Chuan hoa text tieng Viet trong mot so response moi de tranh terminal hien mojibake khi xem log PowerShell. UI/browser thuc te van render theo UTF-8 neu pipeline dung charset.
2. Them UI automation bang Playwright/Cypress neu muon test thao tac chat tren browser that. Hien repo frontend chua co Playwright/Cypress.
3. Sua rieng Jest config/test harness cua frontend de `npm test` resolve duoc `react-router-dom`. Khong sua trong dot nay vi nam ngoai logic chatbot va production build dang pass.
4. Bo sung test backend rieng cho `BookingConversationService` voi fake vector/booking client de regression truc tiep case `2 nguoi`.

