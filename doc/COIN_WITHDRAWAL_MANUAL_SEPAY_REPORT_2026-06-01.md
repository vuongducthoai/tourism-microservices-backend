# Báo Cáo: Hệ Thống Rút Điểm Thủ Công + Xác Minh SePay
**Ngày:** 01/06/2026  
**Dịch vụ:** `booking-service` (port 8083)  
**Phạm vi:** Luồng rút điểm coin → chuyển khoản ngân hàng thủ công → xác minh giao dịch qua SePay API

---

## 1. Tổng Quan Kiến Trúc

```
[React Frontend]
     │  POST /api/v1/coin-withdrawals          (tạo yêu cầu)
     │  POST /admin/{id}/confirm-manual-payout (admin xác nhận)
     ▼
[API Gateway :8080]
     │  route → booking-service
     ▼
[Booking Service :8083]
     │  CoinWithdrawalController
     │  CoinWithdrawalServiceImpl
     │  SepayService ──────────────► SePay API (my.sepay.vn)
     │  IamFeignClient ────────────► IAM Service (trừ coin)
     │  CoinWithdrawalRepository ──► PostgreSQL (booking_db)
     ▼
[Notification Service :8086]
     │  (gửi thông báo sau khi hoàn tất)
```

### Các thành phần liên quan

| Thành phần | Mô tả |
|---|---|
| `CoinWithdrawalController` | REST API endpoints |
| `CoinWithdrawalServiceImpl` | Business logic chính |
| `SepayService` | Kết nối SePay API, quét giao dịch 24h |
| `IamFeignClient` | Gọi sang IAM service để trừ coin |
| `CoinWithdrawalRepository` | JPA repository, bảng `coin_withdrawals` |
| `OutboxEventRepository` | Bảng outbox cho retry idempotent |

---

## 2. Cơ Sở Dữ Liệu

### Bảng `coin_withdrawals`

| Cột | Kiểu | Mô tả |
|---|---|---|
| `id` | UUID | Khóa chính |
| `user_id` | VARCHAR | ID người dùng yêu cầu rút |
| `coin_amount` | BIGINT | Số coin muốn rút |
| `money_amount` | BIGINT | Số tiền quy đổi (VNĐ) |
| `bank` | VARCHAR | Mã/tên ngân hàng (VD: `MB`, `VCB`) |
| `account_number` | VARCHAR | **Số tài khoản đầy đủ** (không che) |
| `account_holder` | VARCHAR | Tên chủ tài khoản |
| `reference_code` | VARCHAR | Mã tham chiếu duy nhất (VD: `RD-20260601-ABCD`) |
| `status` | ENUM | `PENDING`, `MANUAL`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `transfer_ref` | VARCHAR | Mã giao dịch ngân hàng từ SePay (sau khi xác minh) |
| `note` | TEXT | Ghi chú trạng thái |
| `operation_key` | VARCHAR | Khóa idempotency cho trừ coin |
| `retry_count` | INT | Số lần admin thử xác nhận |
| `created_at` | TIMESTAMP | Thời điểm tạo yêu cầu |
| `updated_at` | TIMESTAMP | Thời điểm cập nhật cuối |

### Enum `CoinWithdrawalStatus`

```
PENDING    → vừa tạo, chưa xử lý
MANUAL     → đang chờ admin chuyển khoản thủ công
PROCESSING → đang xử lý (chuyển tiếp sang trạng thái khác)
COMPLETED  → đã xác minh qua SePay, đã trừ coin
FAILED     → thất bại
```

---

## 3. Luồng Nghiệp Vụ Đầy Đủ

### 3.1 Người Dùng Tạo Yêu Cầu Rút Điểm

```
POST /api/v1/coin-withdrawals
Body: { coinAmount, bank, accountNumber, accountHolder }
```

**Xử lý trong `createWithdrawal()`:**
1. Tạo `referenceCode` duy nhất (VD: `RUTDIEM RD-20260601-XXXX`)
2. Quy đổi coin → VNĐ theo tỷ giá cấu hình
3. Lưu bản ghi với `status = MANUAL`
4. **Không trừ coin ngay** (tránh mất coin nếu admin chưa chuyển)
5. **Không sinh outbox event** (chỉ trừ coin sau khi SePay xác minh)
6. Ghi `note = "Yeu cau dang cho admin xu ly..."`
7. Trả về response với **số tài khoản đầy đủ** (không che `***`)

**Lý do không trừ coin ngay:** Đảm bảo an toàn — chỉ trừ coin sau khi admin đã thực sự chuyển tiền và SePay xác nhận.

### 3.2 Admin Xem Danh Sách Yêu Cầu

```
GET /admin/search?status=MANUAL&page=0&size=20
```

Admin thấy bảng gồm: tên ngân hàng (logo), số tài khoản đầy đủ, tên chủ TK, số coin, số tiền.

### 3.3 Admin Quét QR và Chuyển Khoản

Frontend hiển thị modal xác nhận gồm:
- **Bên trái:** Mã QR VietQR  
  URL: `https://img.vietqr.io/image/{bank_bin}-{accountNumber}-compact2.png?amount={moneyAmount}&addInfo={referenceCode}`  
  > ⚠️ Yêu cầu số tài khoản **không che** — VietQR sẽ báo lỗi nếu STK không hợp lệ
- **Bên phải:** Thông tin chuyển khoản: ngân hàng, STK, tên, số tiền, nội dung chuyển khoản

**Nội dung chuyển khoản bắt buộc:** `RUTDIEM {referenceCode}`  
Ví dụ: `RUTDIEM RD-20260601-ABCD1234`

Admin dùng app ngân hàng quét QR hoặc nhập tay để chuyển khoản, sau đó nhấn **"Xác nhận đã chuyển"**.

### 3.4 Admin Nhấn "Xác Nhận Đã Chuyển"

```
POST /admin/{id}/confirm-manual-payout
Body: {} (không cần input)
```

**Xử lý trong `confirmManualPayout()`:**

```
1. Tải bản ghi withdrawal (kiểm tra status = MANUAL)
2. Gọi SePay API để xác minh giao dịch:
   sepayService.verifyWithdrawalTransaction(referenceCode, moneyAmount)
   
   ├─ Tìm thấy giao dịch → tiếp tục
   └─ Không tìm thấy → ném RuntimeException:
      "Khong tim thay giao dich phu hop trong 24 gio qua.
       Vui long thuc hien chuyen khoan va bam xac nhan lai."
       
3. Nếu xác minh thành công:
   a. Gọi iamFeignClient.deductCoins(userId, coinAmount, operationKey)
   b. withdrawal.status = COMPLETED
   c. withdrawal.transferRef = sepayResult.transactionReference
   d. withdrawal.note = "Da xac minh qua SePay: {transferRef}"
   e. Lưu vào DB
   f. Trả về response thành công
```

**Hành vi khi lỗi:**
- Modal **không đóng** — admin thấy thông báo lỗi inline trong modal
- Admin kiểm tra lại rồi nhấn "Xác nhận đã chuyển" lần nữa (không giới hạn lần thử)
- `retry_count` tăng mỗi lần gọi API

---

## 4. Logic Xác Minh SePay

### 4.1 SePay API

- **Base URL:** `https://my.sepay.vn/userapi`
- **Endpoint:** `GET /transactions/list`
- **Auth:** Bearer token trong header `Authorization`
- **Token:** `5DIRHLIEPC9Y0GFKSNXACCQPEREHL3A1BVNTKS8X8DRFNHWDRXFWK4PWMUZKQG0J`

### 4.2 Phương thức `verifyWithdrawalTransaction(referenceCode, amount)`

```java
// 1. Gọi API lấy danh sách giao dịch 24h gần nhất
GET /transactions/list?limit=100&sort=DESC

// 2. Lọc giao dịch thỏa mãn TẤT CẢ điều kiện:
//    a) transaction_content CHỨA referenceCode (VD: "RUTDIEM RD-20260601-ABCD")
//    b) amount_out ở trong khoảng [amount - 1000, amount + 1000] (VNĐ, dung sai ±1000)
//    c) transaction_date trong 24 giờ qua

// 3. Nếu tìm thấy:
//    → trả về TransactionVerificationDTO { verified: true, transactionReference: "..." }
// 4. Nếu không tìm thấy:
//    → trả về TransactionVerificationDTO { verified: false }
```

### 4.3 Tại Sao Dung Sai ±1000 VNĐ?

Một số ngân hàng tính thêm phí giao dịch hoặc làm tròn số, dẫn đến số tiền thực chuyển có thể lệch nhỏ so với số tiền yêu cầu. Dung sai ±1000 VNĐ bao phủ các trường hợp này.

### 4.4 Tại Sao Quét Theo Nội Dung Chuyển Khoản?

Mã `referenceCode` được thiết kế duy nhất cho từng yêu cầu rút tiền. Admin bắt buộc nhập đúng nội dung chuyển khoản → SePay có thể map chính xác giao dịch với yêu cầu.

---

## 5. API Endpoints

### Người Dùng

| Method | Path | Mô tả |
|---|---|---|
| `POST` | `/api/v1/coin-withdrawals` | Tạo yêu cầu rút điểm |
| `GET` | `/api/v1/coin-withdrawals/my` | Xem lịch sử yêu cầu của mình |
| `GET` | `/api/v1/coin-withdrawals/{id}` | Chi tiết một yêu cầu |

### Admin

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/admin/coin-withdrawals/search` | Tìm kiếm, lọc theo status/userId |
| `GET` | `/admin/coin-withdrawals/{id}` | Chi tiết yêu cầu |
| `POST` | `/admin/coin-withdrawals/{id}/confirm-manual-payout` | Xác nhận + SePay verify |
| `GET` | `/admin/coin-withdrawals/{id}/check-sepay` | Kiểm tra SePay thủ công (debug) |
| `POST` | `/admin/coin-withdrawals/{id}/retry` | Reset về MANUAL để thử lại |
| `POST` | `/admin/coin-withdrawals/{id}/reject` | Từ chối yêu cầu |

---

## 6. Giao Diện Frontend

### 6.1 Trang Admin (`CoinWithdrawalsPage`)

**Bảng danh sách:**
- Logo ngân hàng (từ `BANK_LIST` với field `logo`)
- Tên ngân hàng + số tài khoản đầy đủ (không che `***`)
- Tên chủ tài khoản
- Số coin / số tiền VNĐ
- Trạng thái badge màu
- Nút hành động tùy theo trạng thái

**Modal chi tiết (nằm ngang):**
- Cột trái: Logo ngân hàng lớn + thông tin TK + số tiền + mã tham chiếu (nếu đã xác minh)
- Cột phải: Grid thông tin giao dịch (userId, thời gian, số lần retry) + ghi chú

**Modal xác nhận chuyển khoản:**
- Cột trái: QR code VietQR (STK đầy đủ, số tiền, nội dung)
- Cột phải: Thông tin tóm tắt + gợi ý nội dung chuyển khoản + nút xác nhận
- Khi lỗi SePay: Hiển thị thông báo đỏ inline, **modal không đóng**, admin có thể retry

### 6.2 Trang Người Dùng (`WithdrawCoins`)

- Form tạo yêu cầu rút coin
- Danh sách lịch sử với trạng thái thân thiện
- Nút scroll đến lịch sử
- Ghi chú trạng thái dễ hiểu (không dùng từ kỹ thuật)

### 6.3 VietQR

URL tạo QR:
```
https://img.vietqr.io/image/{bank_bin}-{accountNumber}-compact2.png
  ?amount={moneyAmount}
  &addInfo={referenceCode}
  &accountName={accountHolder}
```
> `bank_bin` được tra từ `BANK_LIST` theo field `bin` (VD: MB = `970422`, VCB = `970436`)

---

## 7. Luồng Bảo Mật & Idempotency

### Idempotency khi trừ coin

- `operationKey` = UUID duy nhất tạo khi tạo yêu cầu
- `iamFeignClient.deductCoins(userId, coinAmount, operationKey)` sẽ bị reject nếu gọi 2 lần với cùng `operationKey`
- Ngăn double-deduction dù admin nhấn confirm nhiều lần

### Retry Flow

```
POST /admin/{id}/retry
→ Kiểm tra outbox event nếu có, đánh dấu đã xử lý
→ Reset withdrawal.status = MANUAL
→ Reset withdrawal.retryCount++
→ Admin có thể confirm-manual-payout lại
```

---

## 8. Kiểm Tra SePay — Checklist

Khi admin nhấn "Xác nhận đã chuyển", hệ thống kiểm tra:

- [ ] Giao dịch xuất hiện trong SePay API (24h gần nhất)
- [ ] Nội dung chuyển khoản **chứa** `referenceCode` của yêu cầu
- [ ] Số tiền **trong khoảng ±1000 VNĐ** so với `moneyAmount`
- [ ] Giao dịch là `amount_out` (tiền ra, không phải tiền vào)

Nếu tất cả pass → trừ coin, đánh dấu COMPLETED.  
Nếu fail → inline error, modal giữ nguyên để admin retry.

---

## 9. Cấu Hình

```yaml
# booking-service/src/main/resources/application.yml
transfer:
  provider: manual        # Dùng luồng thủ công (không auto-transfer)

sepay:
  api-url: https://my.sepay.vn/userapi
  token: 5DIRHLIEPC9Y0GFKSNXACCQPEREHL3A1BVNTKS8X8DRFNHWDRXFWK4PWMUZKQG0J

coin:
  exchange-rate: 1000     # 1 coin = 1000 VNĐ (ví dụ)
```

---

## 10. Trạng Thái Build & Deploy

| Thành phần | Trạng thái |
|---|---|
| `booking-service` Maven build | ✅ BUILD SUCCESS |
| `tourism-booking-service` Docker | ✅ Up (healthy) |
| Frontend `npm run build` | ✅ Thành công (output: `build/`) |
| SCSS styles mới | ✅ Appended (detailModal, sepayHint, confirmError, ...) |
| `CoinWithdrawalsPage.jsx` | ✅ Confirm modal tự động SePay, Detail modal nằm ngang |
| `WithdrawCoins.jsx` | ✅ Friendly notes, scroll history, no masking |
| Backend `toResponse()` | ✅ STK đầy đủ, không che `***` |
