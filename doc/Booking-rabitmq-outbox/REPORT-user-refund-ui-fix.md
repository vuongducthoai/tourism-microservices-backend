# Report: User Refund UI Fix

## Summary

Da sua UI chi tiet giao dich ben user de hien thi hoan xu dung theo `refundAmount`.
Backend khong thay doi.

## Van de cu

- Modal chi tiet booking hien thi "So xu hoan" bang `paidByCoin`.
- `paidByCoin` la so xu/diem khach da dung khi thanh toan, khong phai so xu duoc hoan.
- Vi vay booking da huy co the hien sai so xu hoan, dac biet khi `refundAmount` da bi tru phi huy theo ngay khoi hanh.

## Logic dung sau khi sua

- `refundAmount` la source of truth cho so tien hoan cuoi cung.
- `totalPrice + paidByCoin` chi dung de giai thich gia tri thanh toan va diem dung ban dau.
- Phi/khau tru = `max(totalPrice + paidByCoin - refundAmount, 0)`.
- So xu hoan = `floor(refundAmount / 1000)`.
- `paidByCoin` chi con hien o phan thanh toan ban dau voi y nghia so xu da su dung.
- Neu don co su dung diem ca nhan, gia tri diem da dung duoc tinh vao cong thuc hoan.
- UI phan biet hoan xu/hoan tien nhu sau:
  - Hoan xu: `coinRefundStatus` thuoc `PENDING/COMPLETED/FAILED`, va `refundAmount > 0`.
  - Hoan tien ngan hang: `refundAmount > 0`, khong phai case hoan xu, va co `refundBank/refundAccountNumber/refundAccountName`.
  - Neu co `refundAmount` nhung khong co `coinRefundStatus` hop le va cung khong co thong tin ngan hang, UI hien tieu de trung tinh `Thong tin hoan sau huy`, khong gan nhan sai la ngan hang.

## Files da sua

- `tourism_frontend/client-side/src/components/InformationComponent/TransactionList/TransactionListItem/TransactionDetailModal/TransactionDetailModal.jsx`
- `tourism_frontend/client-side/src/components/InformationComponent/TransactionList/TransactionListItem/TransactionDetailModal/TransactionDetailModal.module.scss`
- `tourism_frontend/client-side/src/dto/responseDTO/BookingResponseDTO.ts`

## Thay doi UI

- Chi hien khoi hoan xu khi `coinRefundStatus` thuoc `PENDING`, `COMPLETED`, `FAILED` va `refundAmount > 0`.
- Khong phu thuoc cung vao `bookingStatus`, vi `coinRefundStatus` moi la dau hieu nghiep vu cua luong hoan xu.
- Doi khoi "Hoan xu" thanh "Thong tin hoan sau huy".
- Hien cac dong:
  - So tien duoc hoan
  - Quy doi xu
  - Gia tri thanh toan va diem dung ban dau
  - Gia tri diem da dung duoc tinh vao hoan (neu co)
  - Phi/khau tru da ap dung
  - Trang thai cong xu
- Them ghi chu quy doi: `1 xu = 1.000d`, phan le duoi 1.000d lam tron xuong.
- Badge trang thai ro hon:
  - `PENDING`: Dang xu ly
  - `COMPLETED`: Da hoan xu
  - `FAILED`: Can ho tro
- Format lai khoi "Thong tin hoan tien ngan hang":
  - Lam noi bat `refundAmount` la so tien duoc hoan.
  - Hien gia tri thanh toan va diem dung ban dau de giai thich cong thuc.
  - Neu co diem ca nhan da dung, hien dong "Gia tri diem da dung duoc tinh vao hoan".
  - Hien phi/khau tru da ap dung.
  - Hien ngan hang, so tai khoan da che, chu tai khoan.
  - Hien ly do huy neu backend co tra `cancelReason`.
- Neu khong co thong tin ngan hang, khong hien tieu de "Thong tin hoan tien ngan hang"; dung tieu de "Thong tin hoan sau huy" va dong "Dang cap nhat phuong thuc hoan".

## Normalize data

- Frontend DTO da normalize `coinRefundStatus`.
- Neu backend/DB tra ve `null`, rong, `"null"` hoac status la, UI se xem la `null`.
- Viec nay tranh loi booking `PAID` nhung van hien khoi hoan xu do chuoi `"null"` la truthy trong JavaScript.

## Test cases can kiem tra

- Booking `PAID` co `paidByCoin > 0`: khong hien khoi hoan xu.
- Booking `CANCELLED`, `refundAmount = 2500500`, `coinRefundStatus = COMPLETED`:
  - Hien `So tien duoc hoan: 2.500.500d`
  - Hien `Quy doi xu: 2.500 xu`
  - Hien `Da hoan xu`
- Booking `CANCELLED`, `coinRefundStatus = PENDING`: hien `Dang xu ly`.
- Booking `CANCELLED`, `coinRefundStatus = FAILED`: hien `Can ho tro`.
- Booking co `coinRefundStatus = "null"`: khong hien khoi hoan xu.
- Booking co `coinRefundStatus = COMPLETED`: hien khoi hoan xu va badge `Da hoan xu`, khong roi vao khoi "Dang cap nhat phuong thuc hoan".
- Booking hoan tien ngan hang:
  - Hien `refundAmount` la so tien duoc hoan.
  - Neu `totalPrice = 16.150.000` va `paidByCoin = 1.000.000`, gia tri thanh toan va diem dung ban dau la `17.150.000`.
  - Neu `refundAmount = 17.150.000`, phi/khau tru hien `0d`.
  - Neu `refundAmount` thap hon tong xet hoan, UI hien phan chenh lech la phi/khau tru.

## Notes

- Khong sua backend, RabbitMQ, outbox hay coin relay.
- Admin UI khong nam trong scope sua nay.
- Neu can hien so hoan chinh xac tuyet doi o nhieu noi khac, tiep tuc lay `refundAmount` lam so chinh, khong tinh tu `paidByCoin`.

## Backend fix sau review refund ngan hang

- Da sua `BookingServiceImpl.adminUpdateBookingStatus()` de khong overwrite `refundAmount` khi booking dang o `PENDING_REFUND`.
- Truoc do, admin verify dung `booking.refundAmount`, nhung sau verify code lai gan `refundAmount = totalPrice + paidByCoin`, lam DB mat so tien hoan da tinh luc user gui yeu cau.
- Logic moi:
  - `PENDING_REFUND`: giu nguyen `booking.refundAmount` da luu tu `submitRefundRequest()`.
  - `PAID` / `PENDING_CONFIRMATION`: admin tu huy truc tiep thi moi tinh full `totalPrice + paidByCoin`.
  - `PENDING_PAYMENT`: khong co refund thuc te, `refundAmount` gui trong event la `0`.
