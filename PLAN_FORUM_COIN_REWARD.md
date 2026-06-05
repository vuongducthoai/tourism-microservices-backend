# PLAN: Chính sách thưởng Coin khi tương tác Forum

> Mục tiêu: Khuyến khích người dùng tương tác với diễn đàn nhiều hơn (đăng bài chất lượng, like, comment, được follow…) bằng cách thưởng coin.
> **Quyết định đã chốt:** Dùng CHUNG 1 loại coin với hệ thống hiện tại (1 coin = 1.000đ, **rút ra được tiền thật**). Vì coin có giá trị tiền mặt → **chống gian lận là trọng tâm số 1**.

---

## 1. Bối cảnh hệ thống hiện tại (đã khảo sát code)

### Coin
- Lưu ở **iam-service**: `User.coinBalance` (BigDecimal 19,2), default 0.
- Sổ cái: `CoinTransaction` (operationKey UNIQUE → **idempotent**, direction CREDIT/DEBIT).
- Tỉ giá: **1 coin = 1.000 VND** (`COIN_RATE`/`EXCHANGE_RATE`).
- API nội bộ IAM: `POST /api/users/{id}/coins` (cộng), `POST /api/users/{id}/deduct-coins` (trừ) — đều nhận `operationKey`.
- Rút điểm: tạo `CoinWithdrawal` (booking-service) → admin xác nhận SePay → trừ coin. **Tối thiểu rút 5 coin.**
- Pattern chuẩn để cộng coin bất đồng bộ: **Outbox + Relay Scheduler** (đã dùng cho coin refund). Forum cũng nên đi theo pattern này.

### Forum (forum-service)
- 8 hành động: createPost, like bài, comment, like comment, bookmark, follow, share, report.
- Trạng thái bài sau AI moderation: `PUBLISHED` (SAFE) / `PENDING_REVIEW` (BORDERLINE, OFF_TOPIC) / `HIDDEN` (TOXIC).
- Rate-limit sẵn có (Redis): 5 bài/ngày, 100 comment/ngày, cooldown 60s/post, 15s/comment, chống duplicate 5 phút.
- Reputation (ảo, không phải coin): `postCount*5 + likeCount*2 + commentCount`.
- Đã publish event qua RabbitMQ (exchange `tourism.events`): POST_LIKED, POST_COMMENTED, COMMENT_REPLIED, COMMENT_LIKED, NEW_POST_FROM_FOLLOWING, POST_REJECTED, POST_PENDING.
- **Quan trọng:** forum-service hiện CHƯA gọi sang iam-service để cộng coin. Đây là phần cần xây mới.

---

## 2. Bảng tỉ lệ thưởng coin (đề xuất)

> Nguyên tắc: thưởng nhỏ, thưởng cho **giá trị tạo ra cho cộng đồng**, không thưởng cho thao tác cơ học. Coin lẻ → dùng `BigDecimal` (vd 0.5 coin = 500đ).

| Hành động | Người được thưởng | Coin | Điều kiện thưởng | Giới hạn |
|---|---|---|---|---|
| **Đăng bài được duyệt** (PUBLISHED) | Tác giả | **+2.0** | Bài qua AI = SAFE và không bị admin ẩn trong 24h. KHÔNG thưởng ngay lúc đăng — thưởng có độ trễ (xem §4). | Tối đa 3 bài/ngày được thưởng (dù rate-limit cho 5) |
| **Bài nhận được like** (mốc) | Tác giả bài | **+0.5** mỗi mốc | Khi bài đạt mốc 5 / 20 / 50 / 100 like từ **người khác** | Mỗi mốc 1 lần/bài |
| **Comment được duyệt** | Người comment | **+0.2** | Comment SAFE, độ dài ≥ 15 ký tự (chặn "hay quá", "ok") | Tối đa 10 comment/ngày được thưởng |
| **Comment của mình được like** (mốc) | Tác giả comment | **+0.2** | Khi comment đạt 5 / 15 like từ người khác | Mỗi mốc 1 lần/comment |
| **Có người mới follow** | Người được follow | **+0.3** | Follower là tài khoản hợp lệ, chưa từng follow rồi unfollow lặp | Tối đa 10 lần/ngày |
| **Đăng nhập + tương tác hằng ngày** (daily streak) | User | **+0.5** → tăng theo streak | Đăng nhập VÀ có ≥1 tương tác hợp lệ trong ngày | 1 lần/ngày; streak 7 ngày thưởng bonus +2 |
| Like bài người khác | (người đi like) | **0** | Không thưởng — tránh spam like để cày | — |
| Bookmark / Share / Report | — | **0** | Không thưởng (dễ lạm dụng) | — |

### Trần coin/ngày toàn cục
- **Tối đa mỗi user kiếm 6 coin/ngày** từ forum (= 6.000đ). Vượt trần → các hành động vẫn chạy nhưng không cộng coin nữa (im lặng, không báo lỗi).
- Lý do: chặn kịch bản cày tương tác ảo để đổi tiền. 6 coin/ngày là đủ tạo động lực mà không thành "máy in tiền".

> Các con số trên là **cấu hình** (đặt trong `application.yml` / DB config), chỉnh được mà không sửa code.

---

## 3. Chống gian lận (anti-abuse) — TRỌNG TÂM

Vì coin rút ra tiền thật, mọi quy tắc thưởng phải qua các lớp chặn sau:

1. **Không tự thưởng (self-action):** like/comment/follow chính nội dung của mình → 0 coin. (forum-service đã có sẵn check `recipientUserId == actorUserId` khi publish event — tái dùng.)
2. **Chỉ thưởng nội dung đã duyệt:** bài/comment phải `status = PUBLISHED` (SAFE). Bài PENDING_REVIEW/HIDDEN/DRAFT → không thưởng. → tự động loại nội dung rác/off-topic/toxic.
3. **Thưởng có độ trễ (delayed credit) cho bài viết:** không cộng coin ngay khi đăng. Đặt lịch kiểm tra sau **24h**; nếu bài vẫn PUBLISHED (không bị admin ẩn, không bị report thành công) → mới cộng. Chặn đăng bài → nhận coin → xóa bài.
4. **Chống like/unlike cày mốc:** mốc like chỉ tính **1 lần/bài/mốc** và lưu cờ đã thưởng (`PostRewardMilestone`). Unlike rồi like lại không thưởng thêm. Chỉ đếm like từ user khác (loại self-like).
5. **Chống comment rác:** comment phải ≥ 15 ký tự, SAFE, không trùng nội dung (đã có `checkDuplicate`). Quota 10 comment được thưởng/ngày.
6. **Chống follow/unfollow lặp:** thưởng follow chỉ 1 lần cho mỗi cặp (follower→following) **trọn đời** (lưu lịch sử). Unfollow rồi follow lại → không thưởng.
7. **Idempotency tuyệt đối:** mọi lần cộng coin đi qua `operationKey` duy nhất (đã có ở IAM). Ví dụ key: `FORUM_POST_REWARD_{postId}`, `FORUM_LIKE_MILESTONE_{postId}_{milestone}`, `FORUM_COMMENT_REWARD_{commentId}`, `FORUM_FOLLOW_{followerId}_{followingId}`, `FORUM_DAILY_{userId}_{yyyymmdd}`. → retry/duplicate event không bao giờ cộng 2 lần.
8. **Trần ngày:** kiểm tra tổng coin forum đã cộng cho user trong ngày (Redis counter `forum:coin:daily:{userId}:{date}`), vượt 6 → bỏ qua.
9. **Tài khoản mới (cooldown):** chỉ thưởng coin forum cho tài khoản đã đăng ký ≥ 3 ngày (chặn tạo acc ảo để cày). (Tùy chọn — bật qua config.)
10. **Audit:** mọi lần thưởng ghi `ForumCoinRewardLog` (ai, hành động, số coin, lý do, operationKey) để admin truy vết và phát hiện bất thường.

---

## 4. Kiến trúc kỹ thuật

### Luồng tổng quát (theo pattern Outbox đã có)

```
[Hành động forum hợp lệ]
        │
        ▼
forum-service: RewardService.evaluate(action)
   ├─ kiểm tra điều kiện + anti-abuse (§3)
   ├─ kiểm tra trần ngày (Redis)
   ├─ ghi ForumCoinRewardLog (PENDING) + cờ milestone
   └─ ghi OutboxEvent(routing_key = "forum.coin.reward", payload{userId, amount, operationKey, reason})
        │
        ▼ (RabbitMQ / Relay Scheduler — fire & forget, retry an toàn)
iam-service: nhận event → UserService.addCoins(userId, amount, operationKey)
   └─ idempotent qua operationKey → cập nhật coinBalance + CoinTransaction(CREDIT)
        │
        ▼
forum-service: cập nhật ForumCoinRewardLog (CREDITED) + Redis counter += amount
        │
        ▼
notification-service: thông báo "Bạn nhận được X coin từ diễn đàn 🎉"
```

> Lý do dùng Outbox + event thay vì gọi Feign trực tiếp: nhất quán với coin refund đang chạy, không làm chậm/không làm fail hành động forum nếu iam-service tạm lỗi, và retry an toàn.

### Thưởng có độ trễ cho bài viết (§3.3)
- Khi bài PUBLISHED: tạo bản ghi `PostRewardSchedule(postId, userId, eligibleAt = now + 24h, status = WAITING)`.
- **Scheduler** (chạy mỗi 5–10 phút) quét các bản ghi `eligibleAt <= now && status = WAITING`:
  - Nếu bài vẫn PUBLISHED & không bị report thành công → phát OutboxEvent thưởng, set `REWARDED`.
  - Nếu bài đã HIDDEN/DELETED → set `CANCELLED`, không thưởng.

### Mốc like/comment (§2)
- Khi `toggleLike()` làm `likeCount` tăng và vượt một mốc (5/20/50/100):
  - Check `PostRewardMilestone(postId, milestone)` chưa tồn tại → tạo + phát event thưởng tác giả.
- Tương tự cho comment like (mốc 5/15).

---

## 5. Thay đổi cụ thể theo service

### A. forum-service (phần lớn công việc)

**Entity / bảng mới:**
1. `ForumCoinRewardLog` — id, userId, action(POST/COMMENT/LIKE_MILESTONE/FOLLOW/DAILY), amount, operationKey(UNIQUE), reason, status(PENDING/CREDITED/CANCELLED), refId(postId/commentId), createdAt.
2. `PostRewardMilestone` — id, targetType(POST/COMMENT), targetId, milestone(int), createdAt. UNIQUE(targetType, targetId, milestone).
3. `PostRewardSchedule` — id, postId, userId, eligibleAt, status(WAITING/REWARDED/CANCELLED).
4. `ForumFollowRewardHistory` — id, followerId, followingId, createdAt. UNIQUE(followerId, followingId). (chống follow/unfollow lặp)

**Service mới:**
5. `ForumRewardService` (+Impl):
   - `onPostPublished(post)` → tạo PostRewardSchedule (delay 24h).
   - `onPostLikeChanged(post)` → check mốc like → thưởng tác giả.
   - `onCommentPublished(comment)` → thưởng nếu đủ điều kiện (len ≥15, SAFE, quota).
   - `onCommentLikeChanged(comment)` → check mốc.
   - `onNewFollow(followerId, followingId)` → thưởng nếu chưa từng.
   - `onDailyInteraction(userId)` → daily streak.
   - Hàm chung `award(userId, amount, action, operationKey, reason)`: anti-abuse + trần ngày + ghi log + đẩy Outbox.

6. `ForumRewardScheduler` — quét `PostRewardSchedule` mỗi 5–10 phút, phát event thưởng bài đủ 24h.

**Sửa các method hiện có (gọi hook reward, không phá logic cũ):**
7. `ForumServiceImpl.createPost()` — sau khi save, nếu PUBLISHED → `rewardService.onPostPublished(post)`.
8. `ForumServiceImpl.toggleLike()` — sau cập nhật likeCount → `rewardService.onPostLikeChanged(post)`.
9. `ForumServiceImpl.addComment()` — nếu comment PUBLISHED → `rewardService.onCommentPublished(comment)`.
10. `ForumServiceImpl.toggleCommentLike()` → `onCommentLikeChanged`.
11. `ForumServiceImpl.toggleFollow()` — khi follow mới (không phải unfollow) → `onNewFollow`.

**Messaging:**
12. `OutboxEventFactory` (hoặc tương đương trong forum-service) thêm `coinReward(...)`. Nếu forum-service chưa có Outbox → có thể publish thẳng qua RabbitMQ exchange `tourism.events` routing key `forum.coin.reward` (đơn giản hơn, chấp nhận retry ở consumer).
13. Config: thêm block `forum.reward.*` trong `application.yml` (bật/tắt, tỉ lệ, trần ngày, delay giờ, min comment length, account-age-days).

**API (cho FE hiển thị):**
14. `GET /api/forum/posts/coin-summary?userId=` → trả {todayEarned, dailyCap, totalFromForum, recentRewards[]}. Để FE show "Hôm nay bạn đã kiếm X/6 coin".

### B. iam-service (ít)
15. **Consumer** lắng nghe `forum.coin.reward` (queue mới bind routing key này) → gọi `UserService.addCoins(userId, amount, operationKey)`. Tái dùng logic addCoins idempotent đã có. (Nếu muốn nhanh hơn: forum-service gọi thẳng `IamFeignClient.addCoins` — nhưng kém bền hơn event.)

### C. notification-service (ít)
16. Thêm loại notification `COIN_REWARD` (enum) + xử lý event để báo "Nhận X coin từ diễn đàn". (Bảng notifications đã có constraint type — nhớ thêm 'COIN_REWARD' vào danh sách hợp lệ, tránh lỗi constraint như đã từng gặp.)

### D. Frontend (tourism_frontend)
17. **Toast/animation** khi nhận coin (vd dùng react-confetti nhẹ hoặc toast "🎉 +2 coin").
18. **Widget tiến trình** ở ForumPage / UserStats: "Coin hôm nay: 3.5 / 6" + danh sách hoạt động kiếm coin gần đây.
19. **Trang giải thích chính sách** (modal "Cách kiếm coin trên diễn đàn") — minh bạch để khuyến khích đúng hành vi.
20. Hiển thị mốc like sắp tới trên bài của user ("Còn 3 like nữa để +0.5 coin").

---

## 6. Thứ tự triển khai (đề xuất)

**Giai đoạn 1 — Nền tảng (an toàn trước):**
1. Entity + repository: ForumCoinRewardLog, PostRewardMilestone, PostRewardSchedule, ForumFollowRewardHistory.
2. `ForumRewardService.award()` lõi: anti-abuse + trần ngày (Redis) + idempotency + ghi log.
3. Kênh cộng coin: event `forum.coin.reward` + consumer ở iam-service (hoặc Feign tạm thời).
4. Config `forum.reward.*` (mặc định TẮT để test an toàn).

**Giai đoạn 2 — Thưởng từng hành động:**
5. Thưởng comment được duyệt (đơn giản, ít rủi ro nhất → làm trước để test luồng).
6. Thưởng follow mới.
7. Mốc like bài / like comment.
8. Thưởng bài viết có độ trễ 24h + ForumRewardScheduler.
9. Daily streak.

**Giai đoạn 3 — Trải nghiệm & minh bạch:**
10. API coin-summary + notification COIN_REWARD.
11. FE: toast, widget tiến trình, trang chính sách.

**Giai đoạn 4 — Giám sát:**
12. Admin xem ForumCoinRewardLog (lọc theo user/ngày) để phát hiện bất thường; nút khóa thưởng cho user nghi gian lận.

---

## 7. Rủi ro & lưu ý

- **Lạm phát coin → rủi ro tiền mặt:** trần 6 coin/ngày + chỉ thưởng nội dung duyệt + delay 24h là 3 lớp chính. Theo dõi tổng coin phát ra/ngày, sẵn sàng hạ tỉ lệ qua config.
- **DNS/mạng:** forum-service gọi iam-service là nội bộ Docker (không phải API ngoài) → không dính vấn đề DNS như Groq/PayOS.
- **Constraint notifications:** nhớ thêm type `COIN_REWARD` vào danh sách hợp lệ trước khi notification-service khởi động (tránh lỗi `notifications_type_check` đã gặp).
- **Tính nhất quán:** luôn cộng coin qua operationKey; tuyệt đối không cộng trực tiếp `coinBalance` ở nơi khác.
- **Hiệu năng:** hook reward chạy sau hành động chính, nên bọc try/catch fail-open — lỗi thưởng KHÔNG được làm fail like/comment/post.
- **Pháp lý/chính sách:** vì đổi ra tiền, nên có điều khoản "coin thưởng có thể bị thu hồi nếu phát hiện gian lận" + trang chính sách rõ ràng.

---

## 8. Tóm tắt 1 dòng
Thưởng coin nhỏ cho **nội dung & tương tác được cộng đồng công nhận** (bài duyệt sau 24h, mốc like, comment chất lượng, follow mới, daily streak), cộng coin **bất đồng bộ qua event + idempotency**, chặn gian lận bằng **trần ngày 6 coin + chỉ thưởng nội dung SAFE + delay 24h + chống self/lặp**.
