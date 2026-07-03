# PLAN: Quỹ Trồng Cây Xanh (Green Fund) — Du lịch nhân đạo

> Mục tiêu: Gắn yếu tố nhân đạo / du lịch bền vững vào project. Mỗi chuyến đi góp một phần trồng cây xanh bù carbon → tăng giá trị thương hiệu, khuyến khích user tương tác & đóng góp.
>
> **Quyết định đã chốt:**
> - Nguồn quỹ: **(1) trích % tự động từ mỗi booking thành công** + **(2) user tự nguyện quyên góp bằng coin**.
> - Ghi nhận: **huy hiệu + bảng vinh danh (leaderboard)** + **chứng nhận số cây đã góp trồng** (hiện trang cá nhân, chia sẻ được).
> - Minh bạch: **dashboard công khai đầy đủ** (tổng quỹ, số cây, địa điểm, lịch sử, top đóng góp).

---

## 1. Bối cảnh hệ thống (đã khảo sát code)

- **Coin** ở `iam-service` (`User.coinBalance`, BigDecimal), sổ cái `CoinTransaction` idempotent qua `operationKey`. Trừ coin: `POST /api/users/{id}/deduct-coins?amount=&operationKey=`. **1 coin = 1.000đ.**
- **Booking** ở `booking-service`: `BookingServiceImpl.createBooking()` / luồng thanh toán thành công. Đã có pattern **Outbox + Relay Scheduler** để gọi service khác bất đồng bộ an toàn (dùng cho coin refund).
- **Payment** xác nhận qua PayOS/SePay; khi thanh toán thành công booking chuyển trạng thái CONFIRMED/PAID.
- **Forum** đã có reputation, badge tiềm năng (UserStats), event RabbitMQ (`tourism.events`).
- Quy ước: mọi thao tác trừ coin / cộng quỹ phải **idempotent** qua `operationKey`, không cộng/trừ trực tiếp ở nhiều nơi.

**Quyết định kiến trúc:** Tạo **service mới `green-fund-service`** (hoặc module trong booking-service nếu muốn gọn). → Plan này mặc định **module trong booking-service** cho nhanh, vì nó đã nắm booking + coin + outbox. Có ghi chú để tách service riêng nếu sau này cần.

---

## 2. Mô hình quỹ & quy đổi

| Khái niệm | Giá trị (cấu hình) | Ghi chú |
|---|---|---|
| **Chi phí 1 cây** | **1.000đ** (= 1 coin) | Cấu hình `greenfund.cost-per-tree`. Con số minh họa. |
| **Trích từ booking** | **0.5% giá trị booking** (hoặc 1.000đ/khách — chọn 1) | `greenfund.booking-contribution`. Lấy từ doanh thu công ty, KHÔNG tính thêm vào tiền khách. |
| **Quyên góp bằng coin** | tối thiểu **1 coin** (1.000đ), bội số tùy ý | User tự nguyện, trừ thẳng coinBalance. |
| **Quy đổi quỹ → cây** | đủ 1.000đ quỹ → +1 cây "đã trồng" | Scheduler gộp quỹ tích lũy → tăng `treesPlanted`. |

> Tất cả số liệu là **config** (`application.yml` / bảng config), chỉnh không cần sửa code.

---

## 3. Hai nguồn quỹ — luồng chi tiết

### Nguồn A: Trích % tự động từ booking thành công
```
[Thanh toán tour thành công → booking CONFIRMED/PAID]
        │
        ▼
booking-service: GreenFundService.contributeFromBooking(booking)
   ├─ amount = booking.totalPrice * 0.5%   (hoặc 5.000đ * số khách)
   ├─ operationKey = "GF_BOOKING_{bookingCode}"   (idempotent)
   ├─ ghi GreenFundContribution(source=BOOKING, userId, bookingCode, amount)
   └─ cộng vào tổng quỹ (GreenFundLedger)
```
- **Không trừ tiền khách thêm** — đây là phần công ty trích từ doanh thu (marketing thiện nguyện).
- Gọi tại điểm payment success (nơi booking chuyển PAID), bọc try/catch fail-open (lỗi quỹ không được làm fail booking).
- Idempotent: 1 booking chỉ đóng góp 1 lần (operationKey theo bookingCode).

### Nguồn B: User quyên góp bằng coin
```
[User bấm "Góp trồng cây" trên web, nhập số coin]
        │
        ▼
POST /api/green-fund/donate {userId, coinAmount}
   ├─ validate coinAmount >= 1, là số nguyên
   ├─ check balance qua IAM
   ├─ deductCoins(userId, coinAmount, operationKey="GF_DONATE_{userId}_{timestamp}")
   ├─ ghi GreenFundContribution(source=DONATION, userId, amount = coinAmount*1000)
   ├─ cộng tổng quỹ
   └─ cập nhật badge/leaderboard + phát notification "Cảm ơn bạn đã góp trồng cây 🌳"
```
- Trừ coin **đồng bộ** (user chờ kết quả ngay) — khác booking (bất đồng bộ).
- Idempotent qua operationKey; nếu deduct fail → rollback, không ghi contribution.

### Quy đổi quỹ → số cây (scheduler)
- `GreenFundConverter` chạy định kỳ (vd mỗi giờ / mỗi ngày):
  - `newTrees = floor(tổngQuỹChưaQuyĐổi / costPerTree)`
  - tăng `GreenFundLedger.treesPlanted += newTrees`, trừ phần quỹ đã quy đổi.
  - (Tùy chọn) gán cây vào **đợt trồng** (`TreePlantingBatch`: địa điểm, ngày, ảnh) để dashboard hiển thị thật.

---

## 4. Ghi nhận đóng góp (gắn với forum/coin)

### Huy hiệu (badge)
- Mốc theo **tổng cây cá nhân đã góp** (quy từ tổng đóng góp / costPerTree):
  | Badge | Điều kiện |
  |---|---|
  | 🌱 Mầm xanh | góp ≥ 1 cây |
  | 🌿 Người gieo hạt | ≥ 5 cây |
  | 🌳 Người trồng rừng | ≥ 20 cây |
  | 🏆 Đại sứ xanh | ≥ 50 cây |
- Badge hiển thị trên: profile, cạnh tên trong **forum** (tái dùng UserStats forum → tăng uy tín xã hội, khuyến khích tương tác).

### Bảng vinh danh (leaderboard)
- `GET /api/green-fund/leaderboard?period=all|month` → top user theo tổng đóng góp.
- Hiển thị công khai trên dashboard + 1 widget nhỏ ở trang forum/trang chủ.

### Chứng nhận
- Trang cá nhân: "Bạn đã góp trồng **X cây** 🌳 — cảm ơn vì một Việt Nam xanh hơn!"
- Nút **chia sẻ** (tạo ảnh chứng nhận / link) → đăng lên forum hoặc MXH → lan tỏa.
- (Tùy chọn) sinh ảnh chứng nhận PNG/SVG ở FE để tải về.

> **Không hoàn coin ngược** cho người đóng góp (tránh vòng lặp tài chính & lạm dụng). Phần thưởng là danh dự + badge + có thể +reputation forum.

---

## 5. Minh bạch — Dashboard công khai

`GET /api/green-fund/dashboard` (public, không cần login) trả:
- **Tổng quan:** tổng quỹ (đ), tổng cây đã trồng, tổng cây đang chờ quy đổi, số người đóng góp.
- **Phân bổ nguồn:** % từ booking vs % từ user donation.
- **Đợt trồng cây (`TreePlantingBatch`):** địa điểm, ngày, số cây, ảnh (admin nhập sau mỗi đợt trồng thật).
- **Lịch sử đóng góp gần đây** (ẩn danh hoặc hiện tên tùy user chọn).
- **Top đóng góp** (leaderboard).
- **Tiến trình mục tiêu:** vd "Mục tiêu 2026: 1.000 cây — đã đạt 340 🌳" (progress bar).

Trang FE: `/green-fund` — hero + số liệu động + bản đồ/ảnh các đợt trồng + nút "Góp trồng cây".

---

## 6. Thay đổi cụ thể theo service

### A. booking-service (chứa module Green Fund)

**Entity / bảng mới:**
1. `GreenFundLedger` (singleton, 1 dòng) — totalFundRaised, totalConverted, treesPlanted, pendingFund, updatedAt. (Hoặc tính động từ contributions — nhưng ledger nhanh hơn cho dashboard.)
2. `GreenFundContribution` — id, source(BOOKING/DONATION), userId, bookingCode(nullable), coinAmount(nullable), amountVnd, operationKey(UNIQUE), anonymous(boolean), createdAt.
3. `TreePlantingBatch` — id, location, plantedDate, treeCount, imageUrl, note. (admin nhập sau đợt trồng thật)
4. (Badge có thể tính động từ tổng contribution của user, không cần bảng riêng.)

**Service mới:**
5. `GreenFundService` (+Impl):
   - `contributeFromBooking(booking)` — nguồn A, idempotent.
   - `donate(userId, coinAmount)` — nguồn B, trừ coin qua IAM, ghi contribution.
   - `getDashboard()` — số liệu public.
   - `getUserContribution(userId)` — tổng cây cá nhân + badge.
   - `getLeaderboard(period)`.
6. `GreenFundConverter` (scheduler) — gộp quỹ → tăng treesPlanted định kỳ.

**Sửa logic hiện có:**
7. Điểm **payment success / booking → PAID**: gọi `greenFundService.contributeFromBooking(booking)` (try/catch fail-open). Tìm nơi booking chuyển CONFIRMED/PAID (payment webhook hoặc BookingServiceImpl).

**Controller / API:**
8. `GreenFundController`:
   - `POST /api/green-fund/donate` (user)
   - `GET  /api/green-fund/dashboard` (public)
   - `GET  /api/green-fund/me?userId=` (đóng góp cá nhân + badge)
   - `GET  /api/green-fund/leaderboard?period=`
   - `GET  /api/green-fund/batches` (danh sách đợt trồng)
   - **Admin:** `POST /api/admin/green-fund/batches` (nhập đợt trồng thật), `GET /api/admin/green-fund/contributions` (audit).
9. **Gateway:** thêm route `/api/green-fund/**` + `/api/admin/green-fund/**` → booking-service. Cho phép `/api/green-fund/dashboard`, `/leaderboard`, `/batches` **public** (SecurityConfig).
10. Config `greenfund.*`: enabled, cost-per-tree, booking-contribution-percent (hoặc flat-per-guest), convert-interval.

### B. iam-service (rất ít)
11. Tái dùng `deductCoins` (đã có). Không cần sửa gì ngoài việc đảm bảo trả lỗi rõ khi thiếu số dư.

### C. notification-service (ít)
12. Thêm type `GREEN_FUND_THANKS` (nhớ thêm vào constraint `notifications_type_check` để tránh lỗi như đã gặp). Báo "Cảm ơn bạn đã góp trồng X cây 🌳".

### D. Frontend (tourism_frontend)
13. **Trang `/green-fund`** (public): dashboard số liệu động, ảnh đợt trồng, progress mục tiêu, leaderboard.
14. **Modal "Góp trồng cây"**: nhập số coin, hiện quy đổi "X coin = Y cây", xác nhận → gọi donate → toast + confetti 🌳.
15. **Widget ở checkout/booking success**: "Chuyến đi của bạn đã góp trồng 🌱 vào Quỹ Xanh — cảm ơn bạn!" (hiển thị phần trích từ booking).
16. **Badge xanh** cạnh tên trong forum + profile (tái dùng chỗ hiển thị UserStats).
17. **Trang cá nhân "Đóng góp của tôi"**: tổng cây, badge, nút chia sẻ chứng nhận.
18. Link "Quỹ trồng cây 🌳" ở header/footer.

---

## 7. Chống lạm dụng / lưu ý an toàn

1. **Idempotency:** mọi đóng góp qua operationKey (booking → 1 lần; donate → key theo timestamp). Không double-count khi retry.
2. **Booking contribution lấy từ doanh thu, KHÔNG cộng vào hóa đơn khách** — tránh hiểu lầm thu thêm tiền.
3. **Donate trừ coin đồng bộ + check balance** trước khi ghi contribution; deduct fail → không ghi.
4. **Không hoàn coin ngược** cho đóng góp → tránh vòng lặp tài chính.
5. **Fail-open ở booking:** lỗi quỹ không bao giờ làm fail thanh toán/booking.
6. **Quỹ là nội bộ Docker** (booking ↔ iam) → không dính vấn đề DNS API ngoài.
7. **Minh bạch số liệu thật:** treesPlanted chỉ tăng khi đủ quỹ; đợt trồng thật do admin nhập (ảnh, địa điểm) → tránh "trồng cây ảo" mất uy tín.
8. **Quyền riêng tư:** user chọn ẩn danh khi đóng góp (field `anonymous`).
9. **Audit:** admin xem toàn bộ contributions + ledger để đối soát.

---

## 8. Thứ tự triển khai (đề xuất)

**Giai đoạn 1 — Nền tảng quỹ:**
1. Entity: GreenFundLedger, GreenFundContribution, TreePlantingBatch + repository.
2. `GreenFundService.donate()` (nguồn B — đơn giản, test được ngay: trừ coin + ghi quỹ).
3. `GreenFundController` + gateway route + config (mặc định bật donate).
4. FE: modal "Góp trồng cây" + toast.

**Giai đoạn 2 — Nguồn booking + quy đổi:**
5. `contributeFromBooking()` gọi tại payment success (fail-open).
6. `GreenFundConverter` scheduler quy đổi quỹ → cây.

**Giai đoạn 3 — Minh bạch & ghi nhận:**
7. `getDashboard()` + trang `/green-fund` public.
8. Badge + leaderboard + trang "Đóng góp của tôi" + chứng nhận chia sẻ.
9. Notification GREEN_FUND_THANKS.

**Giai đoạn 4 — Admin & vận hành:**
10. Admin nhập TreePlantingBatch (ảnh, địa điểm đợt trồng thật).
11. Admin audit contributions + ledger.

---

## 9. Tích hợp với các tính năng vừa làm
- **Coin forum ↔ Green Fund:** coin user kiếm từ tương tác forum có thể đem **góp trồng cây** → khép vòng "tương tác nhiều → có coin → làm việc tốt → được vinh danh trên forum". Tăng cả engagement lẫn hình ảnh nhân đạo.
- **Badge xanh hiển thị trong forum** → người đóng góp được cộng đồng nhìn thấy → động lực lan tỏa.
- Dùng lại pattern Outbox/idempotency/notification đã có → ít rủi ro, nhất quán.

---

## 10. Tóm tắt 1 dòng
Quỹ Xanh gom tiền từ **% mỗi booking (doanh thu công ty)** + **coin user tự nguyện góp**, quy đổi thành **cây trồng thật** (admin nhập đợt trồng), ghi nhận người góp bằng **badge + leaderboard + chứng nhận chia sẻ**, và công khai toàn bộ qua **dashboard minh bạch** — gắn chặt với coin/forum vừa xây để vừa nhân đạo vừa tăng tương tác.
