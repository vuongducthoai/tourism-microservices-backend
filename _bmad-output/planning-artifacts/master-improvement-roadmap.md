# Master Roadmap — Tính năng cần cải tiến & bổ sung cho Tourism Microservices

> **Tài liệu tổng hợp** dựa trên khảo sát toàn bộ 9 service + frontend (06/2026).
> Phản ánh **đúng hiện trạng**: cái gì ĐÃ SHIP, cái gì CÒN THIẾU, cái gì NÊN CẢI TIẾN.
> Mục tiêu: 1 nơi nhìn toàn cảnh để quyết định làm gì tiếp theo.

---

## 1. Hiện trạng — Đã làm được rất nhiều

Project đã hoàn thành 1 nền tảng vững. Tổng hợp **đã ship**:

### Backend
| Mảng | Đã có |
|---|---|
| **Auth** | Keycloak + JWT, Google OAuth2, gateway inject X-User-Role, dev-token fallback |
| **Tour** | CRUD tour/departure/location, search filter (name/location/price/rating), Google Maps route + TourStop lat/lng |
| **Booking** | Tạo/hủy/hoàn tiền, 8 trạng thái, refund (bank + coin), coupon CRUD (GLOBAL/DEPARTURE) |
| **Payment** | PayOS + VNPAY + SePay |
| **Coin** | Tích coin, rút coin (5 trạng thái withdrawal) |
| **Review** | Review tour + ảnh, **AI review summary** (Groq, cron 02:00) |
| **Chatbot** | RAG Gemini + Pinecone: search tour, tra cứu booking, tư vấn policy/coupon |
| **Forum** | CRUD post/comment, bookmark, follow, share, report, ban user, audit log, trash, pin/feature, rate-limit Redis 3 tầng, AI moderation (Groq) |
| **Consultation** | Yêu cầu tư vấn (entity + WebSocket admin alert) |
| **Notification** | WebSocket real-time + Email (SMTP) |
| **Dashboard** | Thống kê + AI analysis (Gemini insights/predictions) |

### Frontend
- Google login, ContactWidget (Messenger/FB/Zalo), favorites, forum UI đầy đủ, admin panels, search history (feature 1.1 đã làm), payment flow PayOS/VNPAY

→ **Hệ thống đã ở mức MVP+ hoàn chỉnh.** Phần dưới là những gì **còn thiếu hoặc nên cải tiến**.

---

## 2. Phân loại gap theo Impact × Effort

### 🔴 NHÓM A — Nghiệp vụ cốt lõi còn thiếu (impact cao)

| # | Tính năng | Vì sao quan trọng | Effort |
|---|---|---|---|
| A1 | **PDF Invoice / Vé điện tử** | Sau PAID chưa có vé/hóa đơn tải về — khách cần chứng từ. Dùng OpenPDF trong booking-service | 2d |
| A2 | **Booking Amendment (sửa booking)** | Hiện chỉ CANCEL được, không đổi ngày/số khách → khách phải hủy + đặt lại | 1 tuần |
| A3 | **Đặt cọc 30% / thanh toán bộ phận** | Tour cao cấp (10-50tr) khó trả 100% upfront. Cần status PARTIAL_PAID/DEPOSIT_PAID | 1-2 tuần |
| A4 | **Review guide/driver riêng** | Review hiện gộp chung tour. Tách rating HDV + lái xe để admin phân công | 3-4d |
| A5 | **Passport/Visa requirements** | Tour quốc tế chưa có thông tin giấy tờ. Thêm field `requirements` JSON | 2d |

### 🟡 NHÓM B — AI / Personalization (tận dụng Groq+Pinecone đã có)

| # | Tính năng | Vì sao | Effort |
|---|---|---|---|
| B1 | **AI Tour Recommendation** | User browse nhiều không biết chọn. Embed behavior → Pinecone similarity → "Dành cho bạn" | 1.5 tuần |
| B2 | **Smart Search NLP** | "tour biển 3 ngày dưới 5tr cho 2 người" → Groq parse thành filter | 1 tuần |
| B3 | **AI Itinerary Builder** | Chat "Đà Nẵng 4 ngày 8tr" → AI gợi lộ trình từng ngày + nút đặt | 2 tuần |
| B4 | **AI Auto-tag forum** | User tự nhập tag → AI gợi ý từ content (reuse Groq forum) | 1d |
| B5 | **Content moderation mở rộng** | Hiện moderate post/comment/avatar. Thêm: review giả/spam, NSFW ảnh forum | 3-5d |

### 🟢 NHÓM C — Loyalty / Engagement (giữ chân khách)

| # | Tính năng | Vì sao | Effort |
|---|---|---|---|
| C1 | **Tier system (Silver/Gold/Platinum)** | Coin đã có nhưng chưa có hạng thành viên → thiếu gamification VIP | 1 tuần |
| C2 | **Coin Marketplace** | Coin tích lũy chưa biết đổi gì. Trang `/coin-shop` đổi voucher/quà | 1.5 tuần |
| C3 | **Referral Program** | Mã giới thiệu → cả 2 nhận voucher. Viral growth | 1 tuần |
| C4 | **Daily check-in / Spin wheel** | Tăng DAU. Login mỗi ngày +coin, streak bonus | 1 tuần |
| C5 | **Wishlist + Price Alert** | Favorites chỉ là bookmark. Wishlist + "báo khi tour < X triệu" | 2-3d |

### 🔵 NHÓM D — Kênh thông báo & tích hợp

| # | Tính năng | Vì sao | Effort |
|---|---|---|---|
| D1 | **SMS notification (OTP, booking)** | Hiện chỉ email. SMS qua eSMS.vn cho confirm + OTP | 2-3d |
| D2 | **Zalo OA notification** | User VN dùng Zalo > SMS, rẻ hơn. Push booking confirm qua Zalo OA | 1 tuần |
| D3 | **Web Push notification** | Push cả khi không mở tab (Web Push API) | 3-5d |
| D4 | **Calendar integration (.ics)** | Sau booking → "Thêm vào Google Calendar" + file .ics | 1d |
| D5 | **Facebook login** | Đã có Google qua Keycloak — thêm FB chỉ config 1 IdP | 2h |

### 🟣 NHÓM E — Mobile & PWA

| # | Tính năng | Vì sao | Effort |
|---|---|---|---|
| E1 | **PWA (Service Worker)** | manifest.json đã có nhưng CHƯA có service worker → chưa cài được homescreen, chưa offline | 3-5d |
| E2 | **React Native app** | Reuse 70% code React, scan QR vé, geofencing | 2-3 tháng (phase sau) |

### ⚙️ NHÓM F — Vận hành / Tech debt (không user-facing nhưng cần cho scale)

| # | Hạng mục | Vì sao | Effort |
|---|---|---|---|
| F1 | **Rate limit ở API Gateway** | Forum/consultation có rate-limit nhưng **gateway chưa** → dễ bị abuse toàn hệ thống | 1d |
| F2 | **Centralized logging (Loki)** | Debug bug phải đọc log 9 service riêng. Loki gom + search theo userId/bookingId | 1 tuần |
| F3 | **Distributed tracing (Tempo/Zipkin)** | Bug 500 xuyên service khó trace. Thêm trace ID xuyên suốt | 2-3d |
| F4 | **Grafana monitoring** | Đã có guide nhưng chưa setup trong docker-compose | 3-5d |
| F5 | **Integration tests** | Chủ yếu test manual. Testcontainers cho flow booking/payment/OTP | 2 tuần ongoing |
| F6 | **DB read replicas** | Khi traffic tăng, query đọc nặng (tour list/search) → replica | 3-5d |

---

## 3. Roadmap đề xuất theo quý

### Quý 1 — "Hoàn thiện nghiệp vụ + Quick wins" (4-5 tuần)
Ưu tiên cái khách nhìn thấy ngay + lấp lỗ hổng nghiệp vụ:
- **Tuần 1**: A1 PDF Invoice + D4 Calendar + D5 Facebook login + C5 Wishlist/price alert (quick wins)
- **Tuần 2**: A4 Review guide/driver + A5 Passport/visa + B4 Auto-tag forum
- **Tuần 3-4**: A2 Booking Amendment (sửa booking) — pain point lớn
- **Tuần 5**: F1 Gateway rate-limit + B5 moderation mở rộng

### Quý 2 — "AI & Personalization" (5-6 tuần)
Tận dụng Groq+Pinecone tạo khác biệt cạnh tranh:
- B1 AI Recommendation (1.5w)
- B2 Smart Search NLP (1w)
- B3 AI Itinerary Builder (2w)
- A3 Đặt cọc 30% (1-2w)

### Quý 3 — "Loyalty & Growth" (5-6 tuần)
Giữ chân + viral:
- C1 Tier system → C2 Coin Marketplace → C3 Referral (kết hợp 1 hệ sinh thái)
- C4 Daily check-in / spin wheel
- D1 SMS + D2 Zalo OA

### Quý 4 — "Scale & Mobile" (6-8 tuần)
- E1 PWA → E2 React Native
- F2 Loki + F3 Tracing + F4 Grafana + F6 Read replica
- F5 Integration tests

---

## 4. Top 5 nên làm NGAY (Impact cao, Effort thấp)

| Ưu tiên | Tính năng | Effort | Lý do |
|---|---|---|---|
| **#1** | A1 PDF Invoice / vé điện tử | 2d | Khách cần chứng từ ngay sau thanh toán, hiện hoàn toàn thiếu |
| **#2** | F1 Gateway rate-limit | 1d | Bảo mật — chặn abuse toàn hệ thống, rẻ |
| **#3** | D5 Facebook login | 2h | Gần như free (chỉ config Keycloak IdP) |
| **#4** | B4 AI auto-tag forum | 1d | Reuse Groq sẵn có, cải thiện UX forum |
| **#5** | C5 Wishlist + price alert | 2-3d | Tăng re-engagement qua email, conversion cao |

→ Cả 5 cái này gộp lại < 1.5 tuần, ROI cao nhất.

---

## 5. Đã có plan chi tiết riêng (không trùng lặp)

Những mục dưới đã có file plan riêng trong `planning-artifacts/` — tham khảo khi làm:

| Tính năng | File plan |
|---|---|
| Share tour social + OG | `share-tour-social-plan.md` |
| Google Maps route | `google-maps-tour-route-implementation-plan.md` + `sync-itinerary-with-route-map-plan.md` |
| AI review summary | `ai-review-summary-implementation-plan.md` (đã ship) |
| Forum user features (bookmark/follow/share) | `forum-user-features-improvement-plan.md` (đã ship Sprint A-C) |
| Forum admin | `admin-forum-improvement-plan.md` (đã ship Sprint 0-5) |
| Consultation | `consultation-request-plan.md` (đã ship) |
| Search history | `feature-1.1-search-history-implementation.md` (đã ship) |

---

## 6. Cải tiến chất lượng cái ĐÃ CÓ (không phải feature mới)

Ngoài tính năng mới, một số cái đã ship nên **polish**:

| Mục | Vấn đề hiện tại | Cải tiến |
|---|---|---|
| **Chatbot location resolver** | Đã fix Levenshtein fuzzy nhưng vẫn dựa exact match | Thêm alias đầy đủ cho mọi tỉnh tên kép, hoặc accent-insensitive search |
| **Tour search vs detail** | Đã đồng bộ filter departure nhưng data seed lệch năm (2026 vs 2027) | Chuẩn hóa lại data departure cho nhất quán |
| **UserStats "Lượt thích"** | Đang đếm like user đã bấm, không phải like nhận được | Đổi sang đếm like trên bài của user (đúng ngữ nghĩa) |
| **dev-token** | Mỗi service tự parse, dễ sinh bug (đã từng lỗi ForumPostController) | Gom thành 1 utility chung; production tắt hẳn dev-token |
| **OutboxRelayScheduler** | Poll mỗi 5s → notification trễ tới 5s | Giảm xuống 1-2s hoặc trigger ngay sau khi save outbox |
| **Header notification polling** | Poll unread count mỗi 60s | Chuyển sang WebSocket push (đã có hạ tầng) |

---

## 7. Lưu ý khi triển khai

- **AI features**: ưu tiên Groq (free 14,400 req/day) trước paid model
- **Mọi feature mới**: tích hợp WebSocket notification nếu cần real-time (hạ tầng `/topic/user/{id}/*` đã có)
- **Background job**: dùng Spring `@Scheduled` trong service phù hợp, không tạo service cron riêng
- **Entity mới**: Hibernate `ddl-auto: update` tự tạo bảng — không cần migration thủ công
- **DNS Docker**: service gọi API ngoài (PayOS/Pinecone/Gemini/SMS) cần `dns: [8.8.8.8, 1.1.1.1]` trong docker-compose (đã học từ bug analytics + payment)
- **Secrets**: API key luôn vào `.env` (gitignored), không hardcode vào yml/compose

---

## 8. Câu hỏi định hướng cần quyết

1. **Mục tiêu chính quý tới**: tăng conversion (→ ưu tiên AI Recommendation + Smart Search), hay giữ chân khách (→ Loyalty), hay hoàn thiện nghiệp vụ (→ Amendment + Invoice + Đặt cọc)?
2. **Có làm mobile app không** (React Native = 2-3 tháng) hay PWA là đủ?
3. **Kênh thông báo**: đầu tư SMS (tốn phí/SMS) hay Zalo OA (rẻ hơn, hợp user VN)?
4. **Tech debt**: có cần Loki/Tracing/Grafana ngay (nếu sắp lên production traffic cao) hay để sau?

---

**Ngày tạo**: 2026-06-02
**Phương pháp**: khảo sát thực tế 9 service + frontend, đối chiếu với các planning doc đã có để tránh trùng lặp.
