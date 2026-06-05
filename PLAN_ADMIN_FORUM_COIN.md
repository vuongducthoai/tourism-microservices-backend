# PLAN: Quản lý Coin Forum trong trang Admin

> Tiếp nối PLAN_FORUM_COIN_REWARD.md (Giai đoạn 4 — Giám sát). Vì coin rút được tiền thật,
> admin cần: NHÌN THẤY (dashboard, log), CHẶN ĐƯỢC (khóa thưởng), SỬA ĐƯỢC (thu hồi),
> và PHÁT HIỆN SỚM (cảnh báo bất thường).

---

## 1. Dashboard tổng quan (trang chính)

| Khối | Nội dung | Mục đích |
|---|---|---|
| Thẻ số liệu | Tổng coin phát hôm nay / 7 ngày / 30 ngày; số user được thưởng hôm nay; số lượt PENDING/CANCELLED | Nhìn 1 phát biết hệ thống có "in tiền" bất thường không |
| Biểu đồ đường | Coin phát ra theo ngày (30 ngày) | Phát hiện đột biến (spike = nghi gian lận hoặc bug) |
| Biểu đồ tròn | Phân bổ theo action: POST / COMMENT / LIKE_MILESTONE / FOLLOW / DAILY | Loại nào đang bị lạm dụng |
| Top 10 user | Nhận nhiều coin nhất trong 7 ngày + % chạm trần ngày | Ai đang "cày" |

API gợi ý: `GET /api/forum/admin/coin/stats?days=30` (forum-service, role ADMIN).
Dữ liệu lấy từ `forum_coin_reward_logs` — đã có sẵn đủ cột (userId, action, amount, status, createdAt).

## 2. Bảng lịch sử thưởng (log viewer)

- Bảng phân trang toàn bộ `ForumCoinRewardLog`, lọc theo: userId, khoảng ngày, action, status; tìm theo operationKey.
- Mỗi dòng: user (tên + avatar qua Feign IAM), action, amount, reason, status, thời gian, refId.
- Click refId → mở bài viết/comment liên quan để admin thẩm định nội dung có xứng đáng không.
- Nút xuất CSV theo bộ lọc (đối soát kế toán).

API: `GET /api/forum/admin/coin/logs?userId=&from=&to=&action=&status=&page=`.

## 3. Khóa thưởng user (reward ban) — quan trọng nhất

- Bảng mới `ForumRewardRestriction(userId, reason, bannedUntil nullable = vĩnh viễn, active, createdBy)`.
- `ForumRewardService.award()` check thêm: user đang bị khóa thưởng → bỏ qua (im lặng).
  User vẫn like/comment/đăng bài bình thường — chỉ không nhận coin (khác ban forum hiện có).
- UI: từ bảng log hoặc top-user, nút "Khóa thưởng" → chọn thời hạn (7 ngày / 30 ngày / vĩnh viễn) + lý do.
- API: `POST /api/forum/admin/coin/restrict`, `DELETE .../restrict/{userId}` (gỡ khóa), `GET .../restrictions`.

## 4. Thu hồi coin (clawback)

- Nút "Thu hồi" trên từng dòng log CREDITED: gọi IAM `deduct-coins` với operationKey `REVOKE_{operationKey gốc}`
  (idempotent — bấm 2 lần không trừ 2 lần), set log → CANCELLED, ghi chú lý do + admin thực hiện.
- Thu hồi hàng loạt: chọn nhiều dòng / "thu hồi toàn bộ coin forum của user X trong khoảng ngày".
- Gửi notification cho user: "X coin bị thu hồi do vi phạm chính sách" (type COIN_REWARD hoặc type mới COIN_REVOKED).
- Lưu ý: user có thể đã rút coin → số dư có thể âm? Quyết định chính sách: chỉ trừ tới 0, phần thiếu ghi nợ vào log.

## 5. Cảnh báo bất thường (anti-fraud signals) — phát hiện sớm

Tab "Nghi vấn" chạy các rule query đơn giản (chưa cần ML):

1. User chạm trần 6 coin/ngày ≥ 5 ngày liên tiếp.
2. Cặp user like/follow chéo nhau bất thường (A like mọi bài B, B like mọi bài A trong ngày).
3. Nhiều tài khoản cùng thưởng FOLLOW cho 1 user trong thời gian ngắn (mua follow ảo).
4. Comment được thưởng có độ dài đúng sát min (15–17 ký tự) chiếm > 80% — comment đối phó.
5. Bài viết đạt mốc like quá nhanh (5 like trong < 2 phút sau đăng).

Mỗi cảnh báo: mức độ + user liên quan + nút đi nhanh tới "Khóa thưởng" / "Thu hồi".
Có thể chạy bằng scheduler 1 giờ/lần, lưu bảng `ForumRewardAlert` để admin xem + đánh dấu đã xử lý.

## 6. Cấu hình chính sách runtime (giai đoạn sau)

- Hiện tại config nằm trong `application.yml` (`forum.reward.*`) — đổi phải restart.
- Nâng cấp: bảng `forum_reward_config(key, value)` + cache; UI admin chỉnh tỉ lệ, trần ngày, bật/tắt từng loại thưởng;
  nút "TẮT KHẨN CẤP toàn bộ thưởng" (kill switch) khi phát hiện sự cố — quan trọng vì liên quan tiền thật.

## 7. Vận hành & đối soát

- Tab "Hàng kẹt": reward PENDING > 30 phút (RabbitMQ lỗi) + nút re-publish thủ công; xem số message trong DLQ.
- Đối soát: so tổng CREDITED trong forum_coin_reward_logs với tổng CoinTransaction (operationKey LIKE 'FORUM_%')
  bên iam-service — lệch = có lỗi luồng, hiện cảnh báo đỏ.

---

## Thứ tự triển khai đề xuất

**Đợt 1 (cốt lõi, làm trước):** §2 log viewer + §3 khóa thưởng + §4 thu hồi từng dòng.
→ Đủ để admin xử lý gian lận thủ công. Ít code nhất, dùng bảng đã có.

**Đợt 2 (nhìn tổng thể):** §1 dashboard + §7 hàng kẹt/đối soát.

**Đợt 3 (chủ động):** §5 cảnh báo bất thường + §4 thu hồi hàng loạt.

**Đợt 4 (tiện vận hành):** §6 config runtime + kill switch.

## Ghi chú kỹ thuật

- Backend: tất cả API đặt dưới `/api/forum/admin/coin/**`, kiểm tra role ADMIN (theo pattern admin hiện có của forum-service).
- FE: thêm mục "Coin diễn đàn" vào menu admin (`/admin/forum-coin`), theo cấu trúc AdminComponent hiện tại.
- Mọi hành động admin (khóa, thu hồi) phải ghi audit: ai làm, lúc nào, lý do — chính nó cũng cần truy vết được.
