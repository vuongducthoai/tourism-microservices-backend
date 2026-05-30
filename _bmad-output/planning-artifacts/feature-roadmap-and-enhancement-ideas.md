# Feature Roadmap & Enhancement Ideas — Future Travel

> Tài liệu này tổng hợp các tính năng còn thiếu, các cải tiến nghiệp vụ và **ý tưởng AI** để nâng cao trải nghiệm người dùng, doanh thu và hiệu suất vận hành.
>
> **Nguyên tắc**: Ưu tiên những tính năng (1) tận dụng infrastructure đã có (Groq AI, Pinecone, WebSocket, RabbitMQ, Keycloak), (2) tăng tỉ lệ chuyển đổi đặt tour, (3) tăng retention người dùng.

---

## Mục lục

1. [Quick Wins (1-3 ngày/tính năng)](#1-quick-wins)
2. [Tính năng nghiệp vụ cốt lõi còn thiếu](#2-tính-năng-nghiệp-vụ-cốt-lõi-còn-thiếu)
3. [AI / Personalization](#3-ai--personalization)
4. [Hệ thống Loyalty / Rewards mở rộng](#4-hệ-thống-loyalty--rewards-mở-rộng)
5. [Mobile-first & PWA](#5-mobile-first--pwa)
6. [Vận hành / Admin tools](#6-vận-hành--admin-tools)
7. [Tích hợp bên thứ 3](#7-tích-hợp-bên-thứ-3)
8. [Roadmap đề xuất 3-6 tháng](#8-roadmap-đề-xuất-3-6-tháng)

---

## 1. Quick Wins

> Mỗi tính năng < 3 ngày dev, ROI cao, không cần thay đổi architecture.

### 1.1. **Lưu lịch sử tìm kiếm + Tìm kiếm gần đây**
- Lưu vào `localStorage` 10 từ khóa tìm kiếm gần nhất
- Hiện dropdown gợi ý khi user focus vào search box
- **Lợi ích**: giảm friction khi user quay lại search lần 2
- **Effort**: Frontend only, 1 ngày

### 1.2. **Wishlist (khác Favorites)**
- Favorites hiện tại = bookmark tour
- Wishlist = "ước muốn đi" với mục tiêu thời gian (Q1 2026, mùa hè...)
- User có thể đặt **alert giá**: "Báo tôi khi tour Đà Lạt < 2 triệu"
- **Lợi ích**: tăng email re-engagement, tăng conversion
- **Effort**: 2-3 ngày backend + frontend + scheduled job check giá

### 1.3. **PDF Invoice / Vé điện tử**
- Sau khi booking PAID → gen PDF gồm: thông tin tour, hành khách, mã QR vé, chính sách
- Email kèm attachment + link download
- **Tech**: dùng `iText` hoặc `OpenPDF` trong booking-service
- **Effort**: 2 ngày

### 1.4. **Copy-paste mã booking**
- Trang chi tiết booking: nút "Copy mã" thay vì user chọn text thủ công
- Tooltip "Đã copy!" 2 giây
- **Effort**: 30 phút

### 1.5. **Đánh giá nhanh sau tour**
- 7 ngày sau ngày kết thúc tour → gửi email "Bạn đã có trải nghiệm tuyệt vời chứ?" với 5 ngôi sao clickable
- Click sao → redirect trang review điền chi tiết
- **Lợi ích**: tăng tỉ lệ review từ ~5% lên 20-30% (industry benchmark)
- **Effort**: 1 ngày backend cron + 1 email template

### 1.6. **Đăng nhập bằng Facebook**
- Đã có Google qua Keycloak — thêm Facebook chỉ cần config thêm 1 Identity Provider
- **Effort**: 2 giờ config Keycloak + 1 nút UI

### 1.7. **Share tour qua social**
- Nút share Facebook / Zalo / Copy link trên trang tour detail
- Open Graph meta tags để link share đẹp (ảnh + tiêu đề + giá)
- **Lợi ích**: viral growth, SEO
- **Effort**: 1 ngày

---

## 2. Tính năng nghiệp vụ cốt lõi còn thiếu

### 2.1. **Booking Amendments (sửa booking)**
**Vấn đề hiện tại**: User chỉ có thể CANCEL booking, không thể đổi ngày / đổi số khách.

**Đề xuất**:
- Cho phép user yêu cầu thay đổi: ngày khởi hành, số khách, thông tin liên hệ
- Yêu cầu sửa → status `PENDING_AMENDMENT` → admin duyệt → tính chênh lệch tiền (refund hoặc thu thêm)
- **Quy tắc**: Cho phép sửa miễn phí trước 7 ngày, phí 30% nếu trước 3 ngày, không cho sửa < 24h

**Tech**:
- Endpoint `POST /api/bookings/{id}/amendment` body `{ newDepartureDate, newPassengerCount, reason }`
- Entity `BookingAmendment` (status, before/after JSON, fee, approvedAt)
- Admin có queue duyệt riêng tương tự refund

**Effort**: 1 tuần

---

### 2.2. **Trả góp / Đặt cọc**
**Vấn đề hiện tại**: User phải trả 100% upfront → tour cao cấp (10-50 triệu) khó tiếp cận.

**Đề xuất 2 mode:**
- **Đặt cọc 30%**: Trả 30% giữ chỗ → trả nốt 70% trước ngày khởi hành 14 ngày
- **Trả góp 0%**: Tích hợp **Kredivo**, **Home Credit** (3-6 tháng)

**Tech**:
- Booking status mới: `PARTIAL_PAID`, `DEPOSIT_PAID`
- Scheduled job nhắc trả nốt qua email/SMS
- Trang `/information/payments` hiện schedule trả nốt
- Tích hợp Kredivo API (sandbox free)

**Effort**: 2 tuần (gồm Kredivo integration)

---

### 2.3. **Group Booking / Đặt nhóm doanh nghiệp**
**Đề xuất**:
- Form đặc biệt `/group-booking` cho công ty đặt team-building (10+ người)
- Tự gửi quote email cho sales admin → admin tạo custom price → gửi lại user
- Voucher GROUP_10, GROUP_30 giảm theo số lượng

**Lợi ích**: Mở rộng B2B segment, đơn giá cao hơn nhiều B2C

**Effort**: 1 tuần

---

### 2.4. **Travel Insurance Add-on**
**Đề xuất**:
- Checkout step thêm option "Mua bảo hiểm du lịch" giá ~50k/người
- Hợp tác **Bảo Việt** / **PVI** qua API → tự issue policy
- Hoặc đơn giản: tự bán in-house, hiện chính sách bảo hiểm cơ bản

**Effort**: 3-5 ngày (in-house), 2 tuần (integration)

---

### 2.5. **Passport / Visa requirements per Tour**
**Hiện tại**: Không có thông tin yêu cầu giấy tờ cho tour quốc tế.

**Đề xuất**:
- Field `requirements` JSON trong Tour entity: `{ passport: { minMonths: 6 }, visa: 'required'|'on-arrival'|'free', vaccines: ['COVID-19'] }`
- Trang tour detail hiện checklist rõ ràng
- Banner cảnh báo "Hộ chiếu phải còn hạn ≥ 6 tháng tính từ ngày khởi hành"

**Effort**: 2 ngày

---

### 2.6. **Live Chat hỗ trợ realtime**
**Hiện tại**: Chỉ có chatbot AI, không có live human support.

**Đề xuất**:
- Tận dụng WebSocket đã có (`/topic/admin/chat`)
- Widget chat ở góc phải mọi page
- User gửi message → admin staff thấy queue → reply realtime
- AI bot trả lời FAQ trước, nếu không giải quyết được → escalate to human

**Tech**: 
- New entity `ChatSession`, `ChatMessage` trong notification-service
- Frontend: dùng `@stomp/stompjs` đã có

**Effort**: 1.5 tuần

---

### 2.7. **Đánh giá guide / driver riêng**
**Hiện tại**: Review tour chung.

**Đề xuất**:
- Sau tour: 3 review riêng — Tour (chất lượng dịch vụ), Guide (HDV), Driver (lái xe)
- Admin xem rating guide để phân công tour tiếp theo
- Hall of Fame "Top guide tháng" → tăng motivation

**Effort**: 3-4 ngày

---

## 3. AI / Personalization

> Tận dụng infrastructure Groq + Pinecone đã có.

### 3.1. **AI Tour Recommendation Engine**
**Vấn đề**: User browse 10 tour rồi không biết chọn cái nào.

**Đề xuất**:
- Track behavior: tour đã xem, đã yêu thích, đã book → embedding vector qua Pinecone
- Trang home có section **"Dành riêng cho bạn"** — top 8 tour gợi ý
- Email weekly: "5 tour bạn có thể thích"

**Tech**:
- Service mới `RecommendationService` trong analytics-service
- Pinecone đã có vector tour → tính cosine similarity với user profile vector
- Cron job tính lại embedding user mỗi đêm

**Effort**: 1-1.5 tuần

---

### 3.2. **AI Itinerary Builder ("Lên lộ trình giúp tôi")**
**Đề xuất**:
- User chat với AI: "Tôi muốn đi Đà Nẵng 4 ngày với bạn gái, budget 8 triệu, thích ẩm thực"
- AI (Groq Llama 3.3 70B) gợi ý lộ trình ngày 1-4 với tour hoặc tour kết hợp tự túc
- Output: drag-drop card từng ngày, mỗi card có nút "Đặt tour này"

**Lợi ích**: chuyển đổi cao vì user nhận personalized output

**Tech**:
- Mở rộng chatbot hiện tại (analytics-service) với prompt template structured
- Function calling: AI gọi `search_tours(criteria)` → trả về kết quả

**Effort**: 2 tuần

---

### 3.3. **AI Review Summary**
**Vấn đề**: Tour có 200+ reviews, user không đọc hết.

**Đề xuất**:
- AI tóm tắt thành 3 mục: **Ưu điểm chính** / **Nhược điểm thường gặp** / **Lời khuyên từ khách trước**
- Cache lại, chỉ regen khi có review mới (cron daily)
- Hiện ngay đầu trang tour detail

**Tech**: Groq → prompt "Tóm tắt 50 review sau theo 3 mục..."

**Effort**: 2-3 ngày

---

### 3.4. **AI Price Forecast**
**Đề xuất**:
- Phân tích historical price → predict 7-30 ngày tới
- Banner trên tour detail: **"Giá có khả năng tăng 15% trong 7 ngày tới — Đặt ngay!"**
- Hoặc: **"Giá đang ở mức thấp nhất 3 tháng qua"**

**Tech**: Simple linear regression hoặc Prophet trên data DepartureP­ricing

**Effort**: 1 tuần

---

### 3.5. **AI Auto-tag bài forum**
**Hiện tại**: User phải tự nhập tag.

**Đề xuất**:
- Sau khi user viết content → AI tự đề xuất 3-5 tags dựa trên context
- User accept/reject từng tag
- **Lợi ích**: tag chuẩn hóa, dễ tìm kiếm

**Effort**: 1 ngày (reuse Groq trong forum-service)

---

### 3.6. **Smart Search với NLP**
**Hiện tại**: Search chỉ match keyword.

**Đề xuất**:
- User gõ tự nhiên: "tour biển 3 ngày dưới 5 triệu cho 2 người tháng 6"
- AI parse → filter object: `{ destination_type: 'beach', duration: 3, max_price: 5000000, passengers: 2, month: 6 }`
- Trả về kết quả như filter form

**Tech**: Groq function calling

**Effort**: 1 tuần

---

### 3.7. **AI Content Moderation mở rộng**
**Hiện tại**: Chỉ moderate forum posts/comments.

**Đề xuất mở rộng**:
- Moderate **review tour** (chặn review giả/spam)
- Moderate **avatar upload** (chặn ảnh tục)
- Moderate **forum images** (NSFW detection)
- Dùng Groq Llama 3.3 vision (free) hoặc Cloudflare AI

**Effort**: 3-5 ngày

---

## 4. Hệ thống Loyalty / Rewards mở rộng

> Coin system đã có nhưng chưa có flow redeem hoàn chỉnh.

### 4.1. **Tier system (Silver/Gold/Platinum)**
**Quy tắc đề xuất**:
- **Silver**: Mặc định, tích 1% giá tour vào coin
- **Gold** (≥ 5 booking/năm): Tích 2%, ưu tiên hỗ trợ
- **Platinum** (≥ 15 booking/năm hoặc ≥ 50tr/năm): Tích 3%, free travel insurance, early access tour mới

**Lợi ích**: gamification giữ chân khách hàng VIP

**Effort**: 1 tuần

---

### 4.2. **Coin Marketplace**
**Hiện tại**: Coin tích lũy nhưng dùng làm gì?

**Đề xuất sử dụng coin để đổi**:
- Voucher giảm giá tour (1000 coin = 50k voucher)
- Upgrade phòng khách sạn miễn phí
- Đổi quà thật: balo du lịch, sạc dự phòng (qua tích hợp Shopee/Tiki)
- Donate vào quỹ trồng cây (1000 coin = 1 cây) — eco-friendly branding

**Trang `/coin-shop`** hiển thị catalog quà.

**Effort**: 1.5 tuần

---

### 4.3. **Referral Program**
**Đề xuất**:
- Mỗi user có mã giới thiệu unique (`/ref/THOAI123`)
- Bạn bè đăng ký + book lần đầu qua link → user nhận 100k voucher, bạn nhận 50k voucher
- Leaderboard top inviter mỗi tháng

**Tech**:
- Field `referredBy` trong User entity
- Trang `/my/referrals` hiện danh sách bạn bè đã invite + commission earned

**Effort**: 1 tuần

---

### 4.4. **Daily check-in / Spin wheel**
**Đề xuất**:
- User login mỗi ngày được +10 coin
- Streak 7 ngày liên tục: bonus 100 coin
- Mỗi tháng có 1 lần quay vòng quay may mắn miễn phí — giải: voucher / coin / coupon code

**Lợi ích**: Tăng DAU (Daily Active Users) đáng kể

**Effort**: 1 tuần

---

## 5. Mobile-first & PWA

### 5.1. **Progressive Web App**
- Add Service Worker → app cài được trên homescreen như native app
- Offline mode cho trang booking đã đặt (xem vé khi không có mạng)
- Push notification qua Web Push API
- **Effort**: 3-5 ngày

### 5.2. **Native mobile app (React Native)**
- Reuse 70% code React hiện tại
- Tích hợp camera để scan vé QR
- Geofencing: khi user đến điểm tour → push "Chúc bạn có chuyến đi vui vẻ!"
- **Effort**: 2-3 tháng (phase 2)

---

## 6. Vận hành / Admin tools

### 6.1. **Bulk operations**
- Bulk approve/reject reviews
- Bulk send email marketing đến segment user (Gold tier, không booking 3 tháng...)
- Bulk import tour từ Excel
- **Effort**: 1 tuần

### 6.2. **Realtime dashboard**
- Số booking đang được tạo realtime (WebSocket)
- Top tour bán chạy trong 1h gần nhất
- Map heatmap: đơn đặt theo tỉnh thành
- **Tech**: Grafana đã setup, chỉ cần thêm queries
- **Effort**: 3-5 ngày

### 6.3. **Audit log**
- Log mọi thao tác admin: đăng tour, sửa giá, ban user...
- Trang `/admin/audit-logs` filter theo admin/action/date
- Bắt buộc cho compliance
- **Effort**: 4-5 ngày

### 6.4. **A/B Testing framework**
- Test 2 phiên bản giá tour, button text, layout
- Tích hợp **GrowthBook** (open source, self-hosted)
- **Effort**: 1 tuần setup + ongoing

---

## 7. Tích hợp bên thứ 3

### 7.1. **SMS notification (OTP, booking confirm)**
- Tích hợp **eSMS.vn** hoặc **VnPay SMS** (rẻ ~250đ/SMS)
- OTP qua SMS thay vì chỉ email
- Booking confirm SMS để khách yên tâm
- **Effort**: 2-3 ngày

### 7.2. **Zalo OA notification**
- Push booking confirm qua Zalo Official Account (rẻ hơn SMS)
- User Việt Nam dùng Zalo > SMS
- **Effort**: 1 tuần

### 7.3. **Google Maps tour route**
- Trang tour detail nhúng Google Maps hiện lộ trình
- Pin các điểm dừng theo Itinerary Day
- **Effort**: 2 ngày

### 7.4. **Calendar integration**
- Sau booking confirmed → nút "Thêm vào Google Calendar"
- File `.ics` đính kèm email
- **Effort**: 1 ngày

### 7.5. **WhatsApp / Telegram bot**
- User chat bot qua WhatsApp để tra cứu booking, đặt tour
- **Effort**: 2 tuần (cần WhatsApp Business API)

---

## 8. Roadmap đề xuất 3-6 tháng

### Sprint 1 (2 tuần) — Foundation
- ✅ PDF Invoice
- ✅ Search history + Recently searched
- ✅ Share social (Facebook/Zalo/Copy)
- ✅ Wishlist với alert giá
- ✅ Đánh giá nhanh sau tour
- ✅ Đăng nhập Facebook

### Sprint 2 (2 tuần) — AI Boost
- ✅ AI Tour Recommendation
- ✅ AI Review Summary
- ✅ Smart Search NLP
- ✅ AI Auto-tag forum

### Sprint 3 (3 tuần) — Loyalty
- ✅ Tier system Silver/Gold/Platinum
- ✅ Coin Marketplace với 10 items
- ✅ Referral program

### Sprint 4 (3 tuần) — Business
- ✅ Booking Amendments
- ✅ Đặt cọc 30%
- ✅ Group Booking form B2B

### Sprint 5 (2 tuần) — Engagement
- ✅ Daily check-in
- ✅ Live Chat support
- ✅ PWA setup
- ✅ Email re-engagement campaigns

### Sprint 6 (2 tuần) — Operations
- ✅ Admin audit log
- ✅ Bulk operations
- ✅ Realtime Grafana dashboard
- ✅ A/B Testing framework

---

## 9. Quick decision matrix

> Ưu tiên theo Impact × Effort.

| Tính năng | Impact | Effort | Priority |
|---|---|---|---|
| AI Tour Recommendation | ⭐⭐⭐⭐⭐ | 1.5w | **P0** |
| Coin Marketplace + Referral | ⭐⭐⭐⭐⭐ | 2.5w | **P0** |
| PDF Invoice + Calendar | ⭐⭐⭐⭐ | 3d | **P0** |
| Booking Amendments | ⭐⭐⭐⭐ | 1w | **P0** |
| Share social + SEO | ⭐⭐⭐⭐ | 1d | **P0** |
| Wishlist + Price alert | ⭐⭐⭐⭐ | 3d | **P1** |
| Đặt cọc 30% | ⭐⭐⭐⭐ | 1w | **P1** |
| AI Itinerary Builder | ⭐⭐⭐⭐ | 2w | **P1** |
| Live Chat | ⭐⭐⭐ | 1.5w | **P1** |
| Tier system | ⭐⭐⭐ | 1w | **P1** |
| Smart NLP Search | ⭐⭐⭐ | 1w | **P2** |
| Group Booking | ⭐⭐⭐ | 1w | **P2** |
| PWA | ⭐⭐ | 5d | **P2** |
| SMS / Zalo | ⭐⭐ | 1w | **P2** |
| React Native app | ⭐⭐⭐⭐⭐ | 3m | **P3** |

---

## 10. Tech debt & cải tiến nội bộ

> Không phải feature user-facing nhưng cần thiết cho scale.

### 10.1. Distributed tracing
- Tích hợp **Zipkin** hoặc **Tempo** → trace request xuyên các microservice
- Hiện tại debug bug 500 phải đọc log từng service riêng
- **Effort**: 2-3 ngày

### 10.2. Centralized logging
- ELK stack hoặc **Loki** (nhẹ hơn) → tổng hợp log 8 services
- Tìm kiếm log theo userId, bookingId
- **Effort**: 1 tuần setup

### 10.3. Rate limiting ở API Gateway
- Hiện tại không giới hạn → dễ bị abuse
- Spring Cloud Gateway có `RequestRateLimiter` filter sẵn
- **Effort**: 1 ngày

### 10.4. Database read replicas
- Khi traffic tăng, các query đọc nặng (tour listing, search) chuyển sang replica
- **Effort**: 3-5 ngày (PostgreSQL streaming replication)

### 10.5. CDN cho ảnh tour
- Hiện ảnh chứa trên Cloudinary là OK, nhưng có thể cache aggressive hơn
- Thêm fallback CDN: Cloudflare R2 (rẻ hơn Cloudinary cho high traffic)
- **Effort**: 2-3 ngày

### 10.6. Test coverage
- Hiện tại chủ yếu test manual
- Viết integration tests cho các flow critical: booking, payment, OTP
- **Tech**: Testcontainers cho PostgreSQL, Wiremock cho 3rd party
- **Effort**: 2 tuần ongoing

---

## 11. Gợi ý đặt OKR Q1 2026

**Objective**: Tăng tỉ lệ chuyển đổi từ visit → booking từ 1.5% → 3%

**Key Results**:
- KR1: AI Recommendation → 30% session click vào tour gợi ý
- KR2: PDF Invoice + Calendar → 80% user save calendar event sau booking
- KR3: Wishlist price alert → 15% conversion từ alert email
- KR4: Smart NLP search → 25% search dùng natural language
- KR5: Live Chat → response time < 2 phút trong giờ làm việc

---

## 12. Lưu ý implementation

- **Mọi feature mới** phải tích hợp WebSocket notification khi cần (đã có sẵn topic `/topic/user/{id}/*`)
- **AI features** ưu tiên dùng Groq (free tier 14,400 req/day) trước khi consider paid model
- **Tracking event** cho mọi action quan trọng → analytics-service ghi nhận để tối ưu sau
- **Mọi notification email** dùng template HTML giống template OTP (đã có sẵn structure đẹp)
- **Background job** chạy schedule (Spring `@Scheduled`) trong service phù hợp, không tạo service riêng cho cron

---

**Tài liệu này là sống** — cập nhật khi đã ship feature hoặc có ý tưởng mới. Review hàng tháng.

> Liên hệ: thoai12309@gmail.com
