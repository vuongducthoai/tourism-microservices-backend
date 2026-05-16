# Plan: Database Migration - Import Đúng Tables Vào Đúng Service

## Context
Database local `tourism` là monolith 33 tables. Cần import đúng table vào đúng service database trong Docker.
- Nếu tên table local **khớp** với entity → import thẳng
- Nếu tên table local **mismatch** (khác tên) với entity → import vào và rename theo tên entity
- Nếu local **không có** table nhưng entity có → bỏ qua (Hibernate tự tạo)
- Nếu local **có** table nhưng không có entity nào → bỏ qua (không import)

---

## Mapping Đầy Đủ: Local Table → Entity Table Name → Target Database

### iam_db
| Local table | Entity table | Action |
|---|---|---|
| `users` | `users` | ✅ Import thẳng |
| `refresh_tokens` | *(không có entity)* | ❌ Bỏ qua |

### booking_db
| Local table | Entity table | Action |
|---|---|---|
| `bookings` | `bookings` | ✅ Import thẳng |
| `booking_passengers` | `booking_passengers` | ✅ Import thẳng |
| `coupons` | `coupons` | ✅ Import thẳng |
| `refund_information` | `refund_information` | ✅ Import thẳng |

### payment_db
| Local table | Entity table | Action |
|---|---|---|
| `payments` | `payments` | ✅ Import thẳng |

### tour_catalog_db
| Local table | Entity table | Action |
|---|---|---|
| `tours` | `tours` | ✅ Import thẳng |
| `locations` | `locations` | ✅ Import thẳng |
| `tour_departures` | `tour_departures` | ✅ Import thẳng |
| `departure_pricing` | `departure_pricings` | ⚠️ Import + rename sang `departure_pricings` |
| `departure_transports` | `departure_transports` | ✅ Import thẳng |
| `tour_images` | `tour_images` | ✅ Import thẳng |
| `tour_media` | `tour_media` | ✅ Import thẳng |
| `itinerary_days` | `itinerary_days` | ✅ Import thẳng |
| `branch_contacts` | `branch_contacts` | ✅ Import thẳng |
| `policy_templates` | `policy_templates` | ✅ Import thẳng |
| `review_images` | `image_reviews` | ⚠️ Import + rename sang `image_reviews` |
| `reviews` | `reviews` | ✅ Import thẳng |
| `favorite_tours` | `favorite_tours` | ✅ Import thẳng |

### forum_db
| Local table | Entity table | Action |
|---|---|---|
| `forum_posts` | `forum_posts` | ✅ Import thẳng |
| `post_categories` | `post_categories` | ✅ Import thẳng |
| `post_comments` | `post_comments` | ✅ Import thẳng |
| `post_images` | `post_images` | ✅ Import thẳng |
| `post_likes` | `post_likes` | ✅ Import thẳng |
| `post_tags` | `post_tags` | ✅ Import thẳng |
| `post_views` | `post_views` | ✅ Import thẳng |
| `post_bookmarks` | `post_bookmarks` | ✅ Import thẳng |
| `comment_likes` | `comment_likes` | ✅ Import thẳng |
| `tags` | `tags` | ✅ Import thẳng |
| `followers` | `followers` | ✅ Import thẳng |

### notification_db
| Local table | Entity table | Action |
|---|---|---|
| `notifications` | `notifications` | ✅ Import thẳng |
| `user_notifications` | `user_notifications` | ✅ Import thẳng |

### analytics_db
| Local table | Entity table | Action |
|---|---|---|
| *(không có)* | `daily_revenue_stats` | ❌ Bỏ qua (Hibernate tự tạo) |
| *(không có)* | `tour_performance_stats` | ❌ Bỏ qua (Hibernate tự tạo) |
| *(không có)* | `user_growth_stats` | ❌ Bỏ qua (Hibernate tự tạo) |

> analytics_db không có data từ local vì đây là aggregate computed data — service tự build.

---

## Các Bước Thực Hiện

### Bước 1: Dừng services, xóa volumes
```bash
cd d:\Tourism_Microservices
docker-compose down -v
```

### Bước 2: Khởi động PostgreSQL
```bash
docker-compose up -d postgres
until docker exec tourism-postgres psql -U postgres -c "SELECT 1" > /dev/null 2>&1; do sleep 2; done
echo "PostgreSQL ready"
```

### Bước 3: Dump từ local bằng bash (đảm bảo UTF-8)
```bash
export PGPASSWORD="Thoai12309@"
pg_dump -U postgres -h localhost --encoding=UTF8 tourism > /d/Tourism_Microservices/tourism_local.sql
```

### Bước 4: Restore vào temp database và verify tiếng Việt
```bash
export PGPASSWORD="postgres"
docker exec tourism-postgres psql -U postgres -c "CREATE DATABASE tourism_temp ENCODING 'UTF8' TEMPLATE template0;"
cat /d/Tourism_Microservices/tourism_local.sql | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tourism_temp

# Verify tiếng Việt
docker exec tourism-postgres psql -U postgres -d tourism_temp -c "SELECT tour_name FROM tours LIMIT 1;"
# Kỳ vọng: Hà Nội - Yên Tử - Vịnh Hạ Long...
```

### Bước 5: Import từng service (schema-only → data-only → rename nếu cần)

#### iam_db — chỉ table `users`
```bash
docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.users tourism_temp \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d iam_db
docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.users tourism_temp \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d iam_db
```

#### booking_db — 4 tables
```bash
for table in bookings booking_passengers coupons refund_information; do
  docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d booking_db
  docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d booking_db
done
```

#### payment_db — 1 table
```bash
docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.payments tourism_temp \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d payment_db
docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.payments tourism_temp \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d payment_db
```

#### tour_catalog_db — 13 tables (gồm 2 mismatch cần rename)
```bash
# 11 tables thẳng
for table in tours locations tour_departures departure_transports tour_images tour_media itinerary_days branch_contacts policy_templates reviews favorite_tours; do
  docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db
  docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db
done

# ⚠️ departure_pricing → departure_pricings (mismatch)
docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.departure_pricing tourism_temp \
  | sed 's/departure_pricing/departure_pricings/g' \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db
docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.departure_pricing tourism_temp \
  | sed 's/departure_pricing/departure_pricings/g' \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db

# ⚠️ review_images → image_reviews (mismatch)
docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.review_images tourism_temp \
  | sed 's/review_images/image_reviews/g' \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db
docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.review_images tourism_temp \
  | sed 's/review_images/image_reviews/g' \
  | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d tour_catalog_db

# Fix: policy_template_id NOT NULL constraint trên tour_departures
docker exec tourism-postgres psql -U postgres -d tour_catalog_db -c \
  "ALTER TABLE tour_departures ALTER COLUMN policy_template_id DROP NOT NULL;"
```

#### forum_db — 11 tables
```bash
for table in forum_posts post_categories post_comments post_images post_likes post_tags post_views post_bookmarks comment_likes tags followers; do
  docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d forum_db
  docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d forum_db
done
```

#### notification_db — 2 tables
```bash
for table in notifications user_notifications; do
  docker exec tourism-postgres pg_dump -U postgres --schema-only -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d notification_db
  docker exec tourism-postgres pg_dump -U postgres --data-only --disable-triggers -t public.$table tourism_temp \
    | docker exec -i -e PGCLIENTENCODING=UTF8 tourism-postgres psql -U postgres -d notification_db
done
```

#### analytics_db — không có data từ local, bỏ qua

### Bước 6: Cleanup
```bash
docker exec tourism-postgres psql -U postgres -c "DROP DATABASE tourism_temp;"
rm /d/Tourism_Microservices/tourism_local.sql
```

### Bước 7: Start toàn bộ services
```bash
cd d:\Tourism_Microservices
docker-compose up -d
```
Hibernate `ddl-auto: update` sẽ tự thêm cột mới / tạo bảng mới nếu entity có field mà local chưa có.

### Bước 8: Verify
```bash
# Kiểm tra từng DB chỉ có đúng tables
docker exec tourism-postgres psql -U postgres -d iam_db -c "\dt public.*"
docker exec tourism-postgres psql -U postgres -d tour_catalog_db -c "\dt public.*"

# Kiểm tra tiếng Việt
docker exec tourism-postgres psql -U postgres -d tour_catalog_db -c "SELECT tour_name FROM tours LIMIT 2;"

# Kiểm tra rename đúng
docker exec tourism-postgres psql -U postgres -d tour_catalog_db -c "SELECT COUNT(*) FROM departure_pricings;"
docker exec tourism-postgres psql -U postgres -d tour_catalog_db -c "SELECT COUNT(*) FROM image_reviews;"

# Test API qua Gateway
curl http://localhost:8080/api/tours/search?startPrice=0&endPrice=999999999
```

---

## Tổng kết

| DB | Tables import | Mismatch rename |
|---|---|---|
| `iam_db` | 1 (`users`) | — |
| `booking_db` | 4 | — |
| `payment_db` | 1 (`payments`) | — |
| `tour_catalog_db` | 13 | `departure_pricing`→`departure_pricings`, `review_images`→`image_reviews` |
| `forum_db` | 11 | — |
| `notification_db` | 2 | — |
| `analytics_db` | 0 (Hibernate tự tạo) | — |

**Bỏ qua**: `refresh_tokens` (local có, không có entity)
