# BÁO CÁO: LUỒNG RÚT ĐIỂM VÀ SỬA LỖI XÁC NHẬN SEPAY

**Ngày:** 2026-06-01  
**Scope:** `booking-service` (backend) + `WithdrawCoins`, `CoinWithdrawalsPage` (frontend)

---

## 1. TỔNG QUAN LUỒNG RÚT ĐIỂM

```
User tạo yêu cầu
        │
        ▼
POST /coin-withdrawals
        │
        ▼
CoinWithdrawal lưu DB (status=PENDING)
        │
        ▼ (outbox pattern – 5 giây/lần)
CoinWithdrawalRelayScheduler
        │
        ├─► SePay API (chuyển khoản tự động)
        │       ├─ Thành công → status=COMPLETED, transferRef = mã SePay
        │       └─ Lỗi (404/405) → status=MANUAL
        │
        └─► RabbitMQ → notification-service → email/thông báo user
```

### 1.1 Trạng thái giao dịch

| Status | Ý nghĩa |
|--------|---------|
| `PENDING` | Mới tạo, chờ relay xử lý |
| `PROCESSING` | Đang gọi SePay API |
| `COMPLETED` | Đã chuyển tiền thành công |
| `FAILED` | Thất bại, có thể retry |
| `MANUAL` | SePay không hỗ trợ → admin phải chuyển tay |

---

## 2. LỖI ĐÃ SỬA: Admin Confirm Không Cần Xác Minh SePay

### 2.1 Lỗi gốc

**File:** `booking-service/.../CoinWithdrawalServiceImpl.java` – `confirmManualPayout()`

```java
// CODE CŨ (LỖI) – Admin nhấn confirm mà không cần làm gì
String transferRef = (request.getTransferRef() != null && !request.getTransferRef().isBlank())
        ? request.getTransferRef()
        : "ADMIN_MANUAL_" + System.currentTimeMillis();  // ← fake ref!
// Không verify SePay gì cả!
withdrawal.setStatus(CoinWithdrawalStatus.COMPLETED);
```

**Hậu quả:** Admin có thể xác nhận thành công bất kỳ giao dịch MANUAL nào mà không cần thực sự chuyển khoản. Tiền không đến tay user nhưng hệ thống vẫn ghi COMPLETED.

### 2.2 Giải pháp

**Nguyên tắc:** Chỉ cho phép COMPLETED khi **một trong hai** điều kiện được đáp ứng:
1. **SePay tự động xác minh** được giao dịch chuyển khoản đầu ra trong 24h gần nhất
2. **Admin nhập thủ công** mã giao dịch ngân hàng (reference number)

**Code mới:**
```java
TransactionVerificationDTO sepayResult = sepayService.verifyWithdrawalTransaction(
        withdrawal.getReferenceCode(), withdrawal.getMoneyAmount());

if (sepayResult.isVerified()) {
    // SePay tìm thấy → dùng ref SePay
    transferRef = sepayResult.getTransactionReference();
} else if (request.getTransferRef() != null && !request.getTransferRef().isBlank()) {
    // Admin nhập tay → dùng ref admin
    transferRef = request.getTransferRef().trim();
} else {
    // Cả hai đều không có → từ chối
    throw new RuntimeException("Giao dich chua duoc xac minh...");
}
```

---

## 3. LOGIC XÁC MINH SEPAY

### 3.1 API Endpoint mới: `GET /api/coin-withdrawals/admin/{id}/check-sepay`

Admin có thể gọi endpoint này trước khi confirm để kiểm tra xem SePay đã nhận được lệnh chuyển khoản chưa.

**Response:**
```json
{
  "verified": true,
  "transactionReference": "FT26XXXXXXXXXX",
  "message": "Tim thay giao dich phu hop tren SePay: FT26XXXXXXXXXX"
}
```

### 3.2 Cách SePay Matching hoạt động

`SepayServiceImpl.verifyWithdrawalTransaction(referenceCode, amount)`:

1. Lấy danh sách 50 giao dịch gần nhất từ `https://my.sepay.vn/userapi/transactions/list`
2. Lọc giao dịch **outgoing** (`amountOut > 0`)
3. Lọc trong **24 giờ** gần nhất
4. Kiểm tra `transactionContent` chứa `referenceCode` (case-insensitive)
5. Kiểm tra `amountOut` trong phạm vi **±1000 VND** so với `moneyAmount`
6. Nếu tìm thấy → trả về `verified=true` + `transactionReference`

**Tại sao ±1000 VND?** Để chấp nhận các trường hợp phí chuyển khoản nhỏ.

---

## 4. THAY ĐỔI BACKEND

### 4.1 Files đã sửa

| File | Thay đổi |
|------|----------|
| `SepayService.java` | Thêm interface method `verifyWithdrawalTransaction(String, BigDecimal)` |
| `SepayServiceImpl.java` | Implement `verifyWithdrawalTransaction` – scan outgoing txns 24h, match content + amount |
| `CoinWithdrawalService.java` | Thêm interface method `checkSepayTransaction(Long id)` |
| `CoinWithdrawalServiceImpl.java` | Inject `SepayService`, fix `confirmManualPayout`, thêm `checkSepayTransaction` |
| `CoinWithdrawalController.java` | Thêm endpoint `GET /admin/{id}/check-sepay` |
| `SepayCheckResult.java` | DTO mới: `{verified, transactionReference, message}` |

---

## 5. THAY ĐỔI FRONTEND

### 5.1 `/information/withdraw-coins` (WithdrawCoins.jsx)

**Thay đổi:**
- **Header:** Thêm 2 nút điều hướng:
  - `Danh sách giao dịch` → Link đến `/information/transaction`
  - `Rút tiền về ngân hàng` → Active indicator (trang hiện tại)
- **Toast submit:** Đổi từ `"Đã tạo yêu cầu..."` thành `"Yêu cầu rút điểm đã được ghi nhận. Tiền sẽ được chuyển về tài khoản ngân hàng của bạn trong vòng 24 giờ."`
- **Lịch sử rút điểm:** Redesign card với:
  - Thanh màu trái (accent border) theo trạng thái: xanh/tím/xanh lá/đỏ/cam
  - Hiển thị nổi bật: số điểm ↔ số tiền với icon mũi tên
  - Info chips: logo ngân hàng nhỏ + số tài khoản
  - Footer: mã đối soát (SePay ref) và ghi chú nếu có

### 5.2 Admin `/coin-withdrawals` – ConfirmManualModal (CoinWithdrawalsPage.jsx)

**Redesign từ 1-step → 3-step:**

```
Bước 1: Quét QR chuyển khoản
  └─ VietQR code hiển thị số tài khoản + số tiền + referenceCode

Bước 2: Kiểm tra SePay hoặc nhập mã thủ công
  ├─ [Kiểm tra giao dịch SePay] button → gọi GET /check-sepay
  │     ├─ Tìm thấy → badge xanh + auto-fill transferRef input
  │     └─ Không tìm thấy → badge vàng cảnh báo
  └─ Input "Mã giao dịch ngân hàng"
        ├─ Bắt buộc nếu SePay không tìm thấy
        └─ Auto-filled (màu xanh) nếu SePay tìm thấy

Bước 3: Xác nhận
  ├─ Nút "Xác nhận đã chuyển" DISABLED nếu không có sepayVerified VÀ không có transferRef
  └─ Khi enabled: hiển thị thông báo "Sẵn sàng xác nhận..."
```

**Logic guard:**
```js
const canConfirm = (sepayResult && sepayResult.verified) || transferRef.trim() !== '';
// Nút confirm disabled nếu !canConfirm
```

### 5.3 Service `coinWithdrawal.ts`

Thêm:
```typescript
export const checkSepayTransactionApi = async (id: number): Promise<SepayCheckResult> => {
    const response = await api.get(`/coin-withdrawals/admin/${id}/check-sepay`);
    return response.data;
};
```

---

## 6. TESTS

### 6.1 WithdrawCoins.test.jsx

| Test | Mô tả |
|------|-------|
| renders withdrawal history | Hiển thị lịch sử từ API |
| renders navigation header buttons | Kiểm tra 2 nút header |
| shows 24-hour wait message on submit | Toast phải chứa "24 giờ" |
| submits withdrawal form | Gọi createCoinWithdrawalApi đúng payload |

### 6.2 CoinWithdrawalsPage.test.jsx

| Test | Mô tả |
|------|-------|
| renders admin rows | Hiển thị danh sách từ search API |
| shows confirm button only for MANUAL rows | Chỉ MANUAL mới có nút xác nhận |
| opens confirm modal with step UI | Modal hiển thị 3 bước, confirm button disabled ban đầu |
| enables confirm after SePay verification | Sau khi SePay tìm thấy → nút enabled |
| enables confirm when transferRef entered | Khi nhập tay mã GD → nút enabled |
| calls confirmManualPayoutApi with transferRef | Đúng payload khi confirm |
| opens detail modal | Mở modal chi tiết |
| retry button calls retry API | Retry giao dịch FAILED |

---

## 7. DEPLOY STATUS

```
tourism-booking-service        Up 9 minutes (healthy)   ✅ Updated
tourism-service-discovery      Up 9 minutes (healthy)   ✅
tourism-tour-catalog-service   Up 53 minutes (healthy)  ✅
tourism-forum-service          Up 53 minutes (healthy)  ✅
tourism-payment-service        Up 53 minutes (healthy)  ✅
tourism-iam-service            Up 53 minutes (healthy)  ✅
tourism-api-gateway            Up 53 minutes (healthy)  ✅
tourism-notification-service   Up 53 minutes (healthy)  ✅
tourism-config-server          Up 53 minutes (healthy)  ✅
tourism-analytics-service      Up 53 minutes (healthy)  ✅
tourism-postgres               Up About an hour (healthy) ✅
tourism-rabbitmq               Up About an hour (healthy) ✅
tourism-redis                  Up About an hour (healthy) ✅
tourism-keycloak               Up About an hour (unhealthy) ⚠️ (pre-existing issue)
```

---

## 8. CÁCH SỬ DỤNG SAU KHI FIX

### User flow:
1. Vào `/information/withdraw-coins`
2. Nhập số điểm, thông tin tài khoản, chọn ngân hàng
3. Bấm "Tạo yêu cầu rút điểm"
4. Toast: "...trong vòng 24 giờ"
5. Hệ thống tự xử lý qua SePay. Nếu thành công → COMPLETED. Nếu lỗi SePay → MANUAL

### Admin flow (khi status=MANUAL):
1. Vào trang quản lý rút điểm
2. Tìm giao dịch MANUAL, bấm icon xác nhận
3. **Bước 1:** Quét QR VietQR → chuyển tiền thực tế
4. **Bước 2:** Bấm "Kiểm tra giao dịch SePay"
   - Nếu tìm thấy → mã tự động điền, nút xác nhận sáng lên
   - Nếu không tìm thấy → nhập tay mã giao dịch từ app ngân hàng
5. **Bước 3:** Bấm "Xác nhận đã chuyển"

> ⚠️ **Không thể xác nhận nếu không có bằng chứng giao dịch** (SePay tìm thấy HOẶC mã GD thủ công)
