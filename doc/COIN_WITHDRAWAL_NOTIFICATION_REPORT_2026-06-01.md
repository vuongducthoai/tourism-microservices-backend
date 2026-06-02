# BÁO CÁO: RÚT ĐIỂM — THÔNG BÁO & GIAO DIỆN
**Ngày:** 2026-06-01  
**Phạm vi:** booking-service · notification-service · frontend (WithdrawCoins, CoinWithdrawalsPage)

---

## 1. Tổng quan thay đổi

| # | Hạng mục | Loại | Kết quả |
|---|----------|------|---------|
| 1 | Đổi nhãn `MANUAL` → "Chờ hoàn tiền" toàn bộ giao diện | Frontend | ✅ |
| 2 | Bỏ cột "Nguồn lỗi" khỏi bảng admin + bộ lọc | Frontend | ✅ |
| 3 | Nút "Rút tiền về ngân hàng" scroll xuống form | Frontend | ✅ |
| 4 | WebSocket auto-reload khi admin xử lý xong | Frontend | ✅ |
| 5 | Thiết kế lại form rút điểm (formPanelHeader, icons, input focus) | Frontend SCSS | ✅ |
| 6 | RabbitMQ thông báo khi tạo yêu cầu rút điểm (COIN_WITHDRAWAL_MANUAL) | Backend | ✅ |
| 7 | RabbitMQ thông báo khi hoàn tiền thành công (COIN_WITHDRAWAL) bổ sung email + contact | Backend | ✅ |
| 8 | WebSocket push `/topic/user/{id}/withdrawals` | Notification | ✅ |
| 9 | Email tạo yêu cầu + email hoàn tiền thành công | Notification | ✅ |

---

## 2. Chi tiết thay đổi Frontend

### 2.1 CoinWithdrawalsPage.jsx
- `STATUS_META.MANUAL.label` = **"Chờ hoàn tiền"** (trước: "Thủ công")
- Stat card: "Chờ hoàn tiền"
- Description text: "Với giao dịch **Chờ hoàn tiền**..."
- Xóa `ERROR_OPTIONS` constant
- Xóa `errorSource` khỏi `filters` state, `handleReset`, filter `<select>`
- Xóa `<th>Nguồn lỗi</th>` + `<td>` tương ứng, cập nhật `colSpan` 9→8
- Xóa "Nguồn lỗi" khỏi `DetailModal`

### 2.2 WithdrawCoins.jsx
- `statusMeta.MANUAL.label` = **"Chờ hoàn tiền"**
- Nút "Rút tiền về ngân hàng": từ `<span>` → `<button>` với `onClick={() => formRef.current?.scrollIntoView({ behavior: 'smooth' })}`
- Thêm `useWebSocket` hook: subscribe `/topic/user/{userId}/withdrawals` → gọi `loadWithdrawals()` tự động
- Form header: `<div className={styles.formPanelHeader}>` với icon Coins
- Label icons: User / CreditCard / Building2 / Coins
- `previewBox`: thiết kế lại dạng flex row với `previewItem` + `previewDivider` + `previewAmount`

### 2.3 WithdrawCoins.module.scss
| Class | Thay đổi |
|-------|---------|
| `.navBtnActive` | Bỏ `pointer-events:none`, thêm `cursor:pointer`, `:hover` shadow |
| `.actionPanel` | `gap:0`, `overflow:hidden` (wrap toàn bộ form) |
| `.formPanelHeader` | **MỚI** — flex row, gradient nền, border-bottom |
| `.formHeaderIcon` | **MỚI** — icon tròn primary, box-shadow |
| `.formGrid` | padding `20px 22px 4px` |
| `.fieldGroup span` | `display:flex`, `align-items:center`, `gap:5px` (cho icon) |
| `.fieldGroup input` | border `1.5px`, border-radius `10px`, `:focus` → primary border + shadow |
| `.bankPickerBtn` | `1.5px border`, `:hover` focus ring, img `44px` với shadow |
| `.previewBox` | **Thiết kế lại** — flex row, gradient nền, `border-top` |
| `.previewItem` | **MỚI** — flex-col, label uppercase muted, value bold |
| `.previewDivider` | **MỚI** — 1px separator dọc |
| `.previewAmount` | **MỚI** — màu success `#059669`, `1.1rem` |

---

## 3. Chi tiết thay đổi Backend

### 3.1 booking-service — CoinWithdrawalServiceImpl.java

**`createWithdrawal`** — sau khi lưu DB, đẩy outbox event:
```java
BookingEventDTO notifEvent = toEvent(withdrawal);
notifEvent.setBookingCode(withdrawal.getReferenceCode());
notifEvent.setContactEmail(userProfile.getEmail());
notifEvent.setContactFullName(userProfile.getFullName());
OutboxEvent notifOutbox = OutboxEventFactory.notification(notifEvent, "COIN_WITHDRAWAL_MANUAL", objectMapper);
outboxEventRepository.save(notifOutbox);
```
Wrapped trong `try/catch` để không fail toàn bộ request nếu notification lỗi.

**`confirmManualPayout`** — bổ sung `contactEmail` / `contactFullName` vào event, wrapped trong `try/catch`.

### 3.2 notification-service — WebSocketService.java
Thêm method:
```java
public void notifyUserWithdrawalUpdate(Integer userId, BookingEventDTO event) {
    messagingTemplate.convertAndSend("/topic/user/" + userId + "/withdrawals", event);
}
```

### 3.3 notification-service — MailService.java
Thêm 2 method signatures:
- `sendCoinWithdrawalCreatedEmail(BookingEventDTO event)`
- `sendCoinWithdrawalCompletedEmail(BookingEventDTO event)`

### 3.4 notification-service — MailServiceImpl.java
**`sendCoinWithdrawalCreatedEmail`**: Email `@Async` gửi tới `contactEmail`:
- Subject: `YÊU CẦU RÚT ĐIỂM ĐÃ ĐƯỢC GHI NHẬN: {referenceCode}`
- Body: mã tham chiếu, số điểm, tiền quy đổi, thông tin ngân hàng, thông báo 24h xử lý

**`sendCoinWithdrawalCompletedEmail`**: Email `@Async` gửi tới `contactEmail`:
- Subject: `RÚT ĐIỂM THÀNH CÔNG: {referenceCode}`
- Body: mã chuyển khoản, số tiền, ngân hàng nhận, hướng dẫn liên hệ nếu chưa nhận tiền

### 3.5 notification-service — NotificationServiceImpl.java

**`handleCoinWithdrawal`** (COMPLETED):
```
saveNotification(...) → webSocketService.notifyUserWithdrawalUpdate(...) → mailService.sendCoinWithdrawalCompletedEmail(...)
```

**`handleCoinWithdrawalManual`** (CREATED):
```
saveNotification(...) → webSocketService.notifyUserWithdrawalUpdate(...) → mailService.sendCoinWithdrawalCreatedEmail(...)
```

---

## 4. Luồng hoạt động hoàn chỉnh

```
[User tạo yêu cầu]
  → bookingService.createWithdrawal()
  → DB: CoinWithdrawal (status=MANUAL)
  → DB: OutboxEvent (COIN_WITHDRAWAL_MANUAL)
  → OutboxRelayScheduler → RabbitMQ
  → notificationService.handleCoinWithdrawalManual()
    → DB: Notification saved
    → WebSocket: /topic/user/{id}/withdrawals  ← WithdrawCoins.jsx auto-reload
    → Email: "Yêu cầu rút điểm đã được ghi nhận"

[Admin xác nhận chuyển khoản]
  → bookingService.confirmManualPayout()
  → DB: CoinWithdrawal (status=COMPLETED), deduct coins
  → DB: OutboxEvent (COIN_WITHDRAWAL)
  → OutboxRelayScheduler → RabbitMQ
  → notificationService.handleCoinWithdrawal()
    → DB: Notification saved
    → WebSocket: /topic/user/{id}/withdrawals  ← WithdrawCoins.jsx auto-reload
    → Email: "Rút điểm thành công"
```

---

## 5. Build & Deploy

| Bước | Lệnh | Kết quả |
|------|------|---------|
| Maven build | `mvn clean package -DskipTests -pl booking-service,notification-service` | ✅ BUILD SUCCESS |
| Docker rebuild | `docker compose up -d --build booking-service notification-service` | ✅ Both Started |
| Frontend build | `npm run build` → `build_new` → rename → `build/` | ✅ OK |

**Containers sau deploy:**
- `tourism-booking-service` — Started ✅
- `tourism-notification-service` — Started ✅

---

## 6. Ghi chú kỹ thuật

- `coinWithdrawalAmount` là tên field trong `BookingEventDTO` của notification-service (không phải `withdrawalCoinAmount`)
- `@JsonIgnoreProperties(ignoreUnknown = true)` trên DTO đảm bảo backward compatibility
- Notification calls wrapped trong `try/catch` — lỗi email/WebSocket không ảnh hưởng luồng chính
- Frontend WebSocket subscription dùng `useCallback` để tránh re-subscribe không cần thiết
