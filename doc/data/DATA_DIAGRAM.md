# DATA DIAGRAM & HƯỚNG DẪN IMPORT DỮ LIỆU

## Tổng quan kiến trúc dữ liệu

Hệ thống gồm **7 databases** độc lập, mỗi database thuộc một microservice riêng biệt. Các tham chiếu cross-database là **integer ID không có FK constraint** (loose coupling).

---

## Sơ đồ quan hệ giữa các database

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           IAM_DB (iam-service)                                   │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │ users (userid PK)                                                         │   │
│  │   userid | username | email | password | role | status                    │   │
│  │   1-10   | admin, staff, customer1..8                                     │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
        │ user_id (integer, no FK)
        ▼
┌────────────────────────────┐  ┌──────────────────────────────────────────────────┐
│   BOOKING_DB               │  │   TOUR_CATALOG_DB (tour-catalog-service)          │
│ ┌────────────────────────┐ │  │ ┌──────────────────────────────────────────────┐ │
│ │ bookings               │ │  │ │ locations (locationid PK)  ← 15 records      │ │
│ │  bookingid PK          │ │  │ │ branch_contacts (contactid PK)               │ │
│ │  user_id (→ iam_db)    │ │  │ │ policy_templates (policy_templateid PK)      │ │
│ │  departure_id (→ TC)   │ │  │ │                                              │ │
│ │  booking_code          │ │  │ │ tours (tourid PK)          ← 12 records      │ │
│ │  booking_status        │ │  │ │  start_location_id → locations               │ │
│ │  total_price           │ │  │ │  end_location_id → locations                 │ │
│ └──────────┬─────────────┘ │  │ │                                              │ │
│            │               │  │ │ tour_images (tour_imageid PK)                │ │
│ ┌──────────▼─────────────┐ │  │ │  tour_id → tours                            │ │
│ │ booking_passengers     │ │  │ │                                              │ │
│ │  passengerid PK        │ │  │ │ itinerary_days (itinerary_dayid PK)          │ │
│ │  booking_id → bookings │ │  │ │  tour_id → tours                            │ │
│ │  passenger_type        │ │  │ │                                              │ │
│ │  price                 │ │  │ │ tour_departures (departureid PK)  ← 24 dep  │ │
│ └────────────────────────┘ │  │ │  tour_id → tours                            │ │
│                            │  │ │  policy_template_id → policy_templates       │ │
│ ┌────────────────────────┐ │  │ │                                              │ │
│ │ coupons                │ │  │ │ departure_pricings (pricingid PK)            │ │
│ │  couponid PK           │ │  │ │  departure_id → tour_departures             │ │
│ │  coupon_code UNIQUE    │ │  │ │                                              │ │
│ │  departure_id (→ TC)   │ │  │ │ departure_transports (transportid PK)        │ │
│ └────────────────────────┘ │  │ │  departure_id → tour_departures             │ │
└────────────────────────────┘  │ │                                              │ │
        │ booking_id             │ │ reviews (reviewid PK)                        │ │
        ▼                       │ │  tour_id → tours                            │ │
┌────────────────────────────┐  │ │  user_id (→ iam_db)                         │ │
│   PAYMENT_DB               │  │ │  booking_id (→ booking_db, UNIQUE)          │ │
│ ┌────────────────────────┐ │  │ │                                              │ │
│ │ payments               │ │  │ │ favorite_tours (favoriteid PK)               │ │
│ │  paymentid PK          │ │  │ │  tour_id → tours                            │ │
│ │  booking_id UNIQUE     │ │  │ │  user_id (→ iam_db)                         │ │
│ │  payment_method        │ │  │ └──────────────────────────────────────────────┘ │
│ │  amount                │ │  └──────────────────────────────────────────────────┘
│ │  status                │ │
│ └────────────────────────┘ │
└────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│   FORUM_DB (forum-service)                                                       │
│ ┌─────────────────────────────────────────────────────────────────────────────┐ │
│ │ post_categories (categoryid PK)    tags (tagid PK)                          │ │
│ │                                                                              │ │
│ │ forum_posts (postid PK)                                                      │ │
│ │  user_id (→ iam_db)                                                          │ │
│ │  tour_id (→ tour_catalog_db)                                                 │ │
│ │  category_id → post_categories                                               │ │
│ │  post_type: BLOG/REVIEW/QUESTION/GUIDE                                       │ │
│ │                                                                              │ │
│ │ post_comments (commentid PK)       post_tags (posttagid PK)                  │ │
│ │  post_id → forum_posts              post_id → forum_posts                    │ │
│ │  user_id (→ iam_db)                tag_id → tags                             │ │
│ │  parent_comment_id → self                                                    │ │
│ │                                                                              │ │
│ │ post_likes (postlikeid PK)          post_images (postimageid PK)             │ │
│ │  post_id → forum_posts              post_id → forum_posts                    │ │
│ │  user_id (→ iam_db)                                                          │ │
│ └─────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│   NOTIFICATION_DB (notification-service)                                         │
│ ┌─────────────────────────────────────────────────────────────────────────────┐ │
│ │ notifications (notificationid PK)                                            │ │
│ │  type: BOOKING_*/PAYMENT_*/TOUR_*/COUPON_*/POST_*/SYSTEM_*/WELCOME           │ │
│ │  metadata: JSONB (action_url, icon, related IDs)                             │ │
│ │                                                                              │ │
│ │ user_notifications (usernotificationid PK)                                   │ │
│ │  user_id (→ iam_db)                                                          │ │
│ │  notification_id → notifications                                             │ │
│ │  is_read, is_seen                                                            │ │
│ └─────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│   ANALYTICS_DB (analytics-service)                                               │
│ ┌─────────────────────────────────────────────────────────────────────────────┐ │
│ │ daily_revenue_stats (id PK, UNIQUE stat_date)                                │ │
│ │  stat_date, total_bookings, paid_bookings, total_revenue...                  │ │
│ │                                                                              │ │
│ │ tour_performance_stats (id PK, UNIQUE tour_id)                               │ │
│ │  tour_id (→ tour_catalog_db), tour_code, tour_name                           │ │
│ │  total_bookings, total_revenue, average_rating...                            │ │
│ │                                                                              │ │
│ │ user_growth_stats (id PK, UNIQUE stat_date)                                  │ │
│ │  stat_date, new_users, total_users, active_users...                          │ │
│ └─────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Bảng thống kê dữ liệu đã seed

| Database | Table | Số records |
|---|---|---|
| iam_db | users | 10 |
| tour_catalog_db | locations | 15 |
| tour_catalog_db | branch_contacts | 3 |
| tour_catalog_db | policy_templates | 3 |
| tour_catalog_db | tours | 12 |
| tour_catalog_db | tour_images | 36 |
| tour_catalog_db | itinerary_days | 24 |
| tour_catalog_db | tour_departures | 24 |
| tour_catalog_db | departure_pricings | 48 |
| tour_catalog_db | departure_transports | 14 |
| tour_catalog_db | reviews | 15 |
| tour_catalog_db | favorite_tours | 20 |
| booking_db | coupons | 5 |
| booking_db | bookings | 20 |
| booking_db | booking_passengers | 38 |
| payment_db | payments | 15 |
| forum_db | post_categories | 4 |
| forum_db | tags | 10 |
| forum_db | forum_posts | 10 |
| forum_db | post_comments | 14 |
| forum_db | post_tags | 20 |
| forum_db | post_likes | 20 |
| notification_db | notifications | 18 |
| notification_db | user_notifications | 33 |
| analytics_db | daily_revenue_stats | 30 |
| analytics_db | tour_performance_stats | 12 |
| analytics_db | user_growth_stats | 30 |

---

## Cross-Database References (không có FK constraint)

| Source DB | Source Column | Target DB | Target Table | Notes |
|---|---|---|---|---|
| tour_catalog_db.reviews | user_id | iam_db | users | Khách hàng viết review |
| tour_catalog_db.reviews | booking_id | booking_db | bookings | Booking đã PAID mới được review |
| tour_catalog_db.favorite_tours | user_id | iam_db | users | Yêu thích tour |
| booking_db.bookings | user_id | iam_db | users | Người đặt tour |
| booking_db.bookings | departure_id | tour_catalog_db | tour_departures | Chuyến khởi hành |
| booking_db.coupons | departure_id | tour_catalog_db | tour_departures | Coupon cho chuyến cụ thể (DEPARTURE type) |
| payment_db.payments | booking_id | booking_db | bookings | Thanh toán cho booking |
| forum_db.forum_posts | user_id | iam_db | users | Người viết bài |
| forum_db.forum_posts | tour_id | tour_catalog_db | tours | Bài viết về tour |
| forum_db.post_comments | user_id | iam_db | users | Người bình luận |
| forum_db.post_likes | user_id | iam_db | users | Người thích bài |
| notification_db.user_notifications | user_id | iam_db | users | Người nhận thông báo |
| analytics_db.tour_performance_stats | tour_id | tour_catalog_db | tours | Thống kê theo tour |

---

## Hướng dẫn import vào pgAdmin4

### Bước 1: Kết nối pgAdmin4

```
Host:     localhost
Port:     5433
Username: postgres
Password: postgres (hoặc theo docker-compose.yml)
```

### Bước 2: Import theo thứ tự (BẮT BUỘC)

Phải import theo đúng thứ tự vì có loose coupling giữa các database:

```
1. 01_iam_db_seed.sql         → Database: iam_db
2. 02_tour_catalog_db_seed.sql → Database: tour_catalog_db
3. 03_booking_db_seed.sql      → Database: booking_db
4. 04_payment_db_seed.sql      → Database: payment_db
5. 05_forum_db_seed.sql        → Database: forum_db
6. 06_notification_db_seed.sql → Database: notification_db
7. 07_analytics_db_seed.sql    → Database: analytics_db
```

### Bước 3: Cách import trong pgAdmin4

**Cách 1: Query Tool (khuyến nghị)**
1. Mở pgAdmin4 → Servers → PostgreSQL → Databases
2. Click chuột phải vào database cần import (vd: `iam_db`)
3. Chọn **Query Tool**
4. Mở file SQL: File → Open → Chọn file `.sql`
5. Nhấn **F5** hoặc nút ▶ để chạy

**Cách 2: PSQL (command line)**
```bash
# Connect to container
docker exec -it postgres_container psql -U postgres

# Import từng database
\c iam_db
\i /path/to/01_iam_db_seed.sql

\c tour_catalog_db
\i /path/to/02_tour_catalog_db_seed.sql
# ... tiếp tục với các file còn lại
```

**Cách 3: psql từ host**
```bash
# Windows
set PGPASSWORD=postgres
psql -h localhost -p 5433 -U postgres -d iam_db -f "D:\HK8\tourism-microservices-backend\doc\data\01_iam_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d tour_catalog_db -f "D:\HK8\tourism-microservices-backend\doc\data\02_tour_catalog_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d booking_db -f "D:\HK8\tourism-microservices-backend\doc\data\03_booking_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d payment_db -f "D:\HK8\tourism-microservices-backend\doc\data\04_payment_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d forum_db -f "D:\HK8\tourism-microservices-backend\doc\data\05_forum_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d notification_db -f "D:\HK8\tourism-microservices-backend\doc\data\06_notification_db_seed.sql"
psql -h localhost -p 5433 -U postgres -d analytics_db -f "D:\HK8\tourism-microservices-backend\doc\data\07_analytics_db_seed.sql"
```

### Bước 4: Xác nhận dữ liệu sau import

Chạy query kiểm tra trong pgAdmin4:

```sql
-- iam_db
SELECT count(*) FROM users; -- Kỳ vọng: 10

-- tour_catalog_db
SELECT count(*) FROM tours;           -- 12
SELECT count(*) FROM tour_departures; -- 24
SELECT count(*) FROM reviews;         -- 15

-- booking_db
SELECT count(*) FROM bookings;  -- 20
SELECT count(*) FROM coupons;   -- 5

-- payment_db
SELECT count(*) FROM payments;  -- 15

-- forum_db
SELECT count(*) FROM forum_posts;     -- 10
SELECT count(*) FROM post_comments;   -- 14

-- notification_db
SELECT count(*) FROM notifications;       -- 18
SELECT count(*) FROM user_notifications;  -- 33

-- analytics_db
SELECT count(*) FROM daily_revenue_stats;     -- 30
SELECT count(*) FROM tour_performance_stats;  -- 12
SELECT count(*) FROM user_growth_stats;       -- 30
```

---

## Tài khoản người dùng mẫu

| ID | Username | Email | Password | Role |
|---|---|---|---|---|
| 1 | admin | admin@vietour.com.vn | Tourism@2024 | ADMIN |
| 2 | staff01 | staff01@vietour.com.vn | Tourism@2024 | STAFF |
| 3 | customer01 | an.nguyen@gmail.com | Tourism@2024 | CUSTOMER |
| 4 | customer02 | binh.tran@gmail.com | Tourism@2024 | CUSTOMER |
| 5 | customer03 | nam.le@gmail.com | Tourism@2024 | CUSTOMER |
| ... | ... | ... | Tourism@2024 | CUSTOMER |

> **Lưu ý**: Mật khẩu được hash bằng BCrypt (pgcrypto). Khi login API, dùng mật khẩu gốc `Tourism@2024`.

---

## Lưu ý kỹ thuật quan trọng

### Hibernate Naming Convention
Spring Boot với Hibernate sẽ chuyển đổi tên cột Java → PostgreSQL:
- `userId` → `user_id` (camelCase → snake_case)
- `userID` → `userid` (consecutive caps merge, không có underscore)
- Kiểm tra trong schema trước khi query thủ công

### Sequences
Sau khi INSERT dữ liệu thủ công, sequences đã được reset bằng `SELECT setval(...)` để tránh conflict khi service tự động INSERT tiếp.

### pgcrypto Extension
File `01_iam_db_seed.sql` sử dụng pgcrypto để hash password:
```sql
-- Bật extension (chỉ cần 1 lần/database)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```
Extension này đã được bật trong `init-databases.sql` của Docker setup.

### Image URLs
Tất cả ảnh sử dụng `https://picsum.photos/seed/{keyword}/{width}/{height}` - đây là service ảnh placeholder miễn phí, không cần authentication, luôn trả về ảnh đẹp theo seed keyword.
