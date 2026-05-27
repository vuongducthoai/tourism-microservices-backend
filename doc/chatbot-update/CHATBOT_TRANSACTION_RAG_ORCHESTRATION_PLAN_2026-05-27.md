# CHATBOT TRANSACTION + RAG ORCHESTRATION PLAN - 2026-05-27

**Pham vi:** Plan chuan hoa chatbot theo 2 truc: transaction flow giong trang `order-booking`, va RAG/retrieval cho tat ca cau hoi ngoai giao dich.  
**Can cu:**  
- Frontend: `client-side/src/components/TourBookingComponent/TourBooking.jsx`  
- Backend DTO: `ChatbotCreateBookingRequest`, `CreateBookingRequest`  
- Long-session survey: `CHATBOT_LONG_SESSION_SIMPLIFIED_RAG_PLAN_2026-05-27.md`

---

## 1. Ket luan ngan

Plan truoc dung huong nhung can chinh lai phan `TRANSACTION_FLOW` cho giong trang:

```text
http://localhost:3000/order-booking?tourCode=HN-NT-5N4D&departureId=11
```

Chatbot khong nen tu tao mot luong booking rieng khac form. No nen thu thap dung cung data ma form frontend/gui booking-service dang can:

- `departureId`
- `userId nullable`
- `contactFullName`
- `contactPhone`
- `contactEmail`
- `contactAddress`
- `customerNote`
- `passengers[]`
  - `fullName`
  - `gender`
  - `dateOfBirth`
  - `type`: `ADULT`, `CHILD`, `TODDLER`, `INFANT`
  - `singleRoom`
- `couponCode[]`
- `pointsUsed`

Va quan trong hon: khi dang booking ma user hoi ngang, bot **khong duoc mat bookingDraft**. Bot phai tra loi cau hoi ngang bang RAG/deterministic, roi hoi:

```text
Ban co muon tiep tuc dat tour dang do khong?
[Tiep tuc dat tour] [Huy luong dat tour]
```

Neu user tiep tuc, quay lai dung buoc dang do. Neu huy, clear draft.

---

## 2. Intent co nen rut gon khong?

Co, nhung khong nen rut qua muc chi con 3 intent.

De xuat dung **4 nhom route lon**:

| Nhom | Muc dich | Co goi RAG/Pinecone khong |
|---|---|---|
| `TRANSACTION_FLOW` | Dat tour, chon tour, chon ngay, nhap passenger/contact/email, confirm, cancel, resume | Khong cho AI quyet dinh data; co the goi API/DB |
| `BOOKING_LOOKUP_PAYMENT` | Tra cuu booking, payment help, tao/xem link thanh toan | Khong can RAG truoc; goi booking/payment API |
| `TOUR_RETRIEVAL` | Hoi tour, gia, slot, ngay khoi hanh, lich trinh, chi tiet, discount | Co, Pinecone/RAG + strict filter metadata |
| `GENERAL_RAG` | Tu van chung, chinh sach, hanh ly, gia dinh co tre nho, nen di dau | Co, RAG/FAQ/policy/review |

`UNKNOWN` chi nen la fallback khi khong phan loai duoc va khong co evidence.

Ly do khong chi dung `UNKNOWN/FLOWBOOKING/SEARCHBOOKING`:

- Bot van can biet user dang hoi booking lookup/payment hay dang dat booking.
- Tour retrieval va general advice deu dung RAG, nhung cach filter khac nhau.
- Neu qua thieu route, bot de lay nham tour de tra loi cau tu van chung.

Nhung minh dong y nen **xoa bot cac intent nho** nhu `ASK_SLOT`, `ASK_PRICE`, `ASK_DETAIL`, `ASK_DISCOUNT` khoi lop route chinh. Chuyen chung thanh `retrievalTask` ben trong `TOUR_RETRIEVAL`.

```text
routeGroup = TOUR_RETRIEVAL
retrievalTask = SEARCH | DETAIL | PRICE | SLOT | DATE | ITINERARY | DISCOUNT
```

---

## 3. Kien truc moi de bot khong nhiem flow cu

### 3.1 State phai tach ro

Hien tai `stage` dang om qua nhieu nghia. Nen tach:

```text
conversationMode:
  IDLE
  SEARCH_PENDING
  SHOWING_RESULTS
  BOOKING_DRAFT
  SIDE_QUEST

bookingStage:
  NONE
  SELECTING_TOUR
  SELECTING_DEPARTURE
  SELECTING_PASSENGER_COUNTS
  COLLECTING_PASSENGERS
  COLLECTING_CONTACT
  COLLECTING_NOTE_COUPON
  CONFIRMING_BOOKING
  BOOKING_CREATED

sideQuest:
  NONE
  BOOKING_LOOKUP
  PAYMENT_HELP
  GENERAL_RAG
  TOUR_QA
```

Va co snapshot:

```text
suspendedConversation:
  previousMode
  previousBookingStage
  bookingDraftSnapshot
  pendingSearchSnapshot
```

Muc dich: user hoi ngang thi bot khong pha luong dang lam.

---

## 4. BookingDraft chuan theo `order-booking`

### 4.1 Data model

```text
bookingDraft:
  tourCode
  tourName
  departureId
  departureDate
  availableSlots

  prices:
    adultPrice
    childPrice
    toddlerPrice
    infantPrice
    singleRoomSurcharge

  passengerCounts:
    adult
    child
    toddler
    infant

  passengers:
    - fullName
      gender
      dateOfBirth
      type
      singleRoom

  contact:
    fullName
    phone
    email
    address

  customerNote
  couponCode[]
  pointsUsed
  totals:
    subtotal
    couponDiscount
    pointDiscount
    finalTotal
```

### 4.2 Bat buoc validate nhu frontend

Giong `TourBooking.jsx`:

- It nhat 1 hanh khach.
- Seat count = adult + child + toddler.
- Infant khong tinh ghe.
- Infant count <= adult count.
- Seat count <= availableSlots.
- Contact name bat buoc.
- Phone bat buoc, 10-11 chu so.
- Email bat buoc, dung format.
- Moi passenger bat buoc:
  - fullName
  - gender
  - dateOfBirth
- Adult co `singleRoom`.
- Coupon/points chi ap dung khi hop le.

---

## 5. Luong Transaction Flow chuan

### Step 0: User muon dat tour

Nguon vao co the la:

```text
toi dat tour nay
dat tour do
toi muon dat tour Ha Noi - Nha Trang
click Dat tour 1
nhap 1 khi dang xem ket qua
```

Resolver:

- Neu `lastResults.size == 1` va user noi `tour nay/tour do` -> auto select.
- Neu `lastResults.size > 1` va user noi mo ho -> hoi lai chon tour 1/2/3.
- Neu user go ten tour -> resolve theo ten trong `lastResults`, neu khong co thi retrieve Pinecone exact tour name.
- Neu khong co tour context -> hoi user muon dat tour nao.

### Step 1: Chon departure

Neu tour co 1 ngay:

- Co the auto chon neu user da noi "ngay gan nhat" hoac chi co 1 departure.
- Neu khong, hien ngay va hoi confirm.

Neu tour co nhieu ngay:

```text
Tour nay co cac ngay khoi hanh:
1. 10/04/2027 - con 20 cho
2. 20/06/2027 - con 22 cho
Ban chon ngay nao?
```

Input hop le:

- `1`
- `ngay 1`
- `10/04`
- `20/06/2027`
- `gan nhat`

### Step 2: Hoi passenger counts

Phai hoi nhu form:

```text
Ban di bao nhieu:
- nguoi lon
- tre em
- tre nho
- em be
```

Chap nhan:

```text
2 nguoi lon 1 tre em
2 adult 1 child
2 lon, 1 be
```

Sau khi parse:

- Check slot: adult + child + toddler <= availableSlots.
- Check infant <= adult.
- Neu sai, hoi lai ngay tai buoc nay.

### Step 3: Thu passenger detail

Thu tung nguoi, khong nhay qua contact khi chua du.

Vi du:

```text
Hanh khach 1/3 - Nguoi lon:
Cho minh ho ten, gioi tinh, ngay sinh.
Vi du: Nguyen Van A, nam, 12/08/1990
```

Adult:

- hoi them single room neu can:
  - "Anh/chi co muon phong don cho hanh khach nay khong?"
  - default `false` neu user bo qua.

Child/toddler/infant:

- khong singleRoom.

### Step 4: Contact

```text
Thong tin nguoi lien he:
Ho ten, so dien thoai, email, dia chi neu co.
Vi du: Nguyen Van A, 0901234567, a@gmail.com, 123 Le Loi
```

Neu user chi nhap ten + phone:

- luu phan co duoc,
- hoi tiep email.

### Step 5: Note/coupon/points

Tuong ung form co:

- coupon auto departure/global,
- coupon nhap tay,
- points used,
- customerNote.

Chatbot nen hoi ngan:

```text
Ban co ma giam gia, muon dung diem, hoac co ghi chu gi khong?
Neu khong, nhap "bo qua".
```

Neu user bo qua, tiep confirm.

### Step 6: Confirm card

Hien day du:

- tour/departure,
- hanh khach,
- contact,
- gia tung loai,
- single room surcharge,
- coupon/points,
- finalTotal,
- han thanh toan.

Buttons:

- `Xac nhan dat tour`
- `Sua thong tin`
- `Huy`

### Step 7: Create booking

Payload gui y het frontend:

```json
{
  "departureId": 11,
  "userId": null,
  "contactFullName": "...",
  "contactPhone": "...",
  "contactEmail": "...",
  "contactAddress": "...",
  "customerNote": "...",
  "passengers": [
    {
      "fullName": "...",
      "gender": "Nam",
      "dateOfBirth": "1990-08-12",
      "type": "ADULT",
      "singleRoom": false
    }
  ],
  "couponCode": ["WELCOME100K"],
  "pointsUsed": null
}
```

---

## 6. Khi user hoi ngang trong luong booking

### 6.1 Nguyen tac

Neu `conversationMode = BOOKING_DRAFT` va input **khong phai input hop le cua bookingStage hien tai**, bot khong duoc ep vao stage handler.

Thay vao do:

```text
1. Pause bookingDraft
2. Route cau hoi sang TOUR_RETRIEVAL hoac GENERAL_RAG/BOOKING_LOOKUP_PAYMENT
3. Tra loi cau hoi
4. Append resume prompt:
   "Ban dang dat tour X, dang o buoc Y. Ban muon tiep tuc hay huy?"
5. Quick actions:
   - Tiep tuc dat tour
   - Huy luong dat tour
```

### 6.2 Vi du

Dang thu passenger:

```text
Bot: Hanh khach 2/3, cho minh ho ten/gioi tinh/ngay sinh.
User: tour nay co bao gom an sang khong?
```

Bot:

```text
Theo thong tin tour, ...

Ban dang dat tour Ha Noi - Nha Trang, dang nhap thong tin hanh khach 2/3.
Ban muon tiep tuc dat tour hay huy luong nay?
[Tiep tuc dat tour] [Huy luong dat tour]
```

Neu user bam `Tiep tuc`:

```text
Minh quay lai buoc dang lam:
Hanh khach 2/3 - Nguoi lon: cho minh ho ten, gioi tinh, ngay sinh.
```

Neu user bam `Huy`:

```text
Da huy luong dat tour. Booking chua duoc tao.
```

Va clear `bookingDraft`.

---

## 7. Khong nhiem flow cu

### 7.1 Khi search moi trong booking

Dang dat tour A, user noi:

```text
cho xem tour da nang di
```

Khong nen lap tuc pha booking A.

Bot:

```text
Minh co the tim tour Da Nang cho ban. Hien ban dang dat tour A.
Ban muon:
1. Tam dung booking hien tai de xem tour Da Nang
2. Huy booking hien tai va tim tour moi
3. Tiep tuc dat tour A
```

Neu user chon tam dung:

- luu `bookingDraft`,
- chuyen sang `TOUR_RETRIEVAL`,
- van cho resume lai booking A.

### 7.2 Khi user da search moi thanh cong

Neu user chon dat tour moi, bot phai hoi:

```text
Ban co muon huy draft tour A va chuyen sang dat tour B khong?
```

Khong tu overwrite draft.

---

## 8. RAG/retrieval ngoai transaction

Moi cau khong phai transaction input nen di qua retrieval:

### 8.1 TOUR_RETRIEVAL

Ap dung cho:

- co tour X khong
- HCM di Ha Noi
- tour gia re
- tour giam gia
- slot/gia/ngay/lich trinh/detail cua tour nay

Pipeline:

```text
extract constraints
  -> route pattern first: tu A den B, A di B
  -> Pinecone topK
  -> parse metadata
  -> strict filter:
       startLocation
       endLocation
       tourCode/name
       date preference
       category
  -> group by tour
  -> group departures under tour
  -> return exact results only
```

Rule quan trong:

- Neu hoi HCM -> Ha Noi, chi hien HCM -> Ha Noi.
- Neu khong co, khong chen tour khac.
- Neu muon goi y thay the, hoi user truoc.

### 8.2 GENERAL_RAG

Ap dung cho:

- gia dinh co tre nho nen chuan bi gi
- can luu y gi khi di dai ngay
- thanh toan co an toan khong
- chinh sach huy/hoan

Khong duoc coi "dai ngay" la destination.

---

## 9. Plan implement theo uu tien

### P0 - Transaction flow chuan va thoat luong

1. Tao/hoan thien `bookingDraft`.
2. Implement stage input validator rieng cho tung `bookingStage`.
3. Implement pause/resume/cancel:
   - pause khi hoi ngang,
   - resume ve dung prompt cu,
   - cancel clear draft.
4. Sua `toi dat tour do`:
   - 1 result -> auto select,
   - many -> ask choose,
   - no context -> ask tour.

### P1 - Match `order-booking`

1. Lay order info theo `tourCode + departureId` nhu frontend.
2. Luu prices:
   - adult/child/toddler/infant/singleRoom.
3. Validate slots, infant <= adult.
4. Thu du passenger details.
5. Thu contact/email/address/note/coupon.
6. Confirm card dung nhu frontend.

### P2 - Retrieval-first ngoai transaction

1. Rut gon `IntentRouter` thanh guard deterministic + route group.
2. Tao `TourRetrievalService`.
3. Dung Pinecone/RAG cho search/detail/slot/gia/lich trinh/discount.
4. Strict filter metadata, khong show tour rac.

### P3 - State isolation

1. Tach:
   - `conversationMode`
   - `bookingStage`
   - `sideQuest`
   - `bookingDraft`
   - `pendingSearch`
   - `lastResults`
2. Khong dung 1 `stage` de dai dien moi thu.

### P4 - Test

Long session bat buoc:

```text
tour ha noi den nha trang
thang 6, 2 nguoi lon 1 tre em
toi dat tour do
ngay gan nhat
hoi ngang: tour nay co bao gom an sang khong
tiep tuc dat tour
nhap 3 hanh khach
nhap contact
bo qua coupon
xac nhan
```

Expected:

- Khong mat draft.
- Khong chen tour khac.
- Tong tien dung.
- Tao booking dung payload.

---

## 10. Dinh nghia "on" sau khi sua

Chatbot dat khi:

1. User dang booking hoi ngang van thoat/hoi/tiep tuc duoc.
2. User `tôi đặt tour đó` sau 1 tour -> auto chon tour.
3. User `tôi đặt tour đó` sau 3 tour -> hoi 1/2/3.
4. Booking thu thap du thong tin nhu trang `order-booking`.
5. Search/RAG khong bao gio chen tour khong lien quan cho du so luong.
6. No-result thanh that.
7. GENERAL_RAG khong bi hieu nham thanh tour destination.
8. State cu khong lam nhiem search/booking moi.

