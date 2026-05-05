# Booking Status — Hiển thị UI cho User

> File này mô tả với mỗi `bookingStatus`, trang **Danh sách giao dịch** của user hiển thị những gì.

---

## Bảng tổng quan

| Status | Nhãn hiển thị | Badge màu | Nút chính | Nút phụ | Thông tin thêm |
|--------|--------------|-----------|-----------|---------|----------------|
| `PENDING_PAYMENT` | Chờ thanh toán | 🟠 cam | **Thanh toán** | — | Đếm ngược thời hạn |
| `OVERDUE_PAYMENT` | Quá hạn thanh toán | 🔴 đỏ | — | — | Thông báo bị hủy tự động |
| `PENDING_CONFIRMATION` | Chờ xác nhận | 🔵 xanh dương | **Hủy tour** | — | — |
| `PAID` (chưa khởi hành) | Đã thanh toán | 🟢 xanh lá | **Hủy tour** | — | — |
| `PAID` (tour đã kết thúc) | Đã thanh toán | 🟢 xanh lá | **Đánh giá** | — | Sau ngày kết thúc tour |
| `PENDING_REVIEW` | Chờ đánh giá | ⬜ xám | **Đánh giá** | — | — |
| `REVIEWED` | Đã đánh giá | 🩵 xanh ngọc | **Xem đánh giá** | — | — |
| `CANCELLED` | Đã hủy | 🔴 đỏ | — | — | Hiện lý do hủy (nếu có) |
| `PENDING_REFUND` | Chờ hoàn tiền | 🟡 vàng | — | — | — |

> Tất cả các status đều có nút **Xem chi tiết**.

---

## Chi tiết từng status

### `PENDING_PAYMENT` — Chờ thanh toán
- **Nút:** Thanh toán (xanh dương) → chuyển đến `/payment-booking?bookingCode=...`
- **Đếm ngược:** hiển thị thời hạn thanh toán còn lại (HH:MM:SS)
- **Hết hạn:** đếm ngược hiện "Đã hết hạn", nút thanh toán disabled — chờ hệ thống chuyển sang `OVERDUE_PAYMENT`

---

### `OVERDUE_PAYMENT` — Quá hạn thanh toán
- **Nút:** Không có nút hành động
- **Thông báo:** "Đơn hàng đã quá hạn thanh toán và bị hủy tự động."
- **Lý do:** User không thanh toán trong thời hạn, hệ thống tự động hủy

---

### `PENDING_CONFIRMATION` — Chờ xác nhận
- **Nút:** Hủy tour (đỏ)
- **Điều kiện hủy:** Nếu tour đã khởi hành → toast cảnh báo, không mở modal

---

### `PAID` — Đã thanh toán
Có 2 trường hợp dựa vào thời điểm hiện tại so với ngày kết thúc tour:

**Trường hợp 1 — Tour chưa kết thúc** (`now ≤ departureDate + duration`):
- **Nút:** Hủy tour (đỏ)
- Tính phí hủy theo số ngày đến khởi hành

**Trường hợp 2 — Tour đã kết thúc** (`now > departureDate + duration`):
- **Nút:** Đánh giá ⭐ (xanh dương)
- Mở `ReviewComponent` modal

> **Tính `duration`:** Parse chuỗi (vd: "4 Ngày 3 Đêm") lấy số đầu tiên = 4 ngày.
> Nếu `duration` null → fallback dùng `hasDeparted` (đã qua ngày khởi hành).

---

### `PENDING_REVIEW` — Chờ đánh giá
- **Nút:** Đánh giá ⭐ (xanh dương)
- Status này được set bởi hệ thống sau khi tour kết thúc (thay thế `PAID`)
- Mở `ReviewComponent` modal

---

### `REVIEWED` — Đã đánh giá
- **Nút:** Xem đánh giá 👁 (xám)
- Mở `ViewReviewModal`

---

### `CANCELLED` — Đã hủy
- **Nút:** Không có nút hành động
- **Thông tin:** Hiện lý do hủy nếu `cancelReason` không rỗng

---

### `PENDING_REFUND` — Chờ hoàn tiền
- **Nút:** Không có nút hành động (admin đang xử lý)
- Badge màu vàng cam

---

## Luồng trạng thái

```
PENDING_PAYMENT
    │ (user thanh toán)               │ (hết hạn)
    ▼                                 ▼
PENDING_CONFIRMATION            OVERDUE_PAYMENT
    │ (admin xác nhận)               (kết thúc)
    ▼
PAID
    │ (tour kết thúc — hệ thống)     │ (user hủy — coin)
    ▼                                ▼
PENDING_REVIEW                  CANCELLED
    │ (user đánh giá)
    ▼
REVIEWED

PAID / PENDING_CONFIRMATION
    │ (user hủy — chuyển khoản)
    ▼
PENDING_REFUND
    │ (admin duyệt)
    ▼
(REFUNDED — chỉ trong refund_information)
```

---

## Bảng phí hủy tour

| Số ngày đến khởi hành | Phí hủy | Hoàn lại |
|-----------------------|---------|---------|
| > 15 ngày             | 10%     | 90%     |
| 6 – 15 ngày           | 50%     | 50%     |
| 3 – 5 ngày            | 70%     | 30%     |
| 0 – 2 ngày            | 90%     | 10%     |
| Đã qua ngày khởi hành | 100%    | 0%      |

- **Hủy bằng xu:** Hoàn xu ngay lập tức (`CANCELLED`)
- **Hủy chuyển khoản:** Gửi yêu cầu, admin duyệt (`PENDING_REFUND`)
- **Tỷ giá:** 1 xu = 1.000 VND
