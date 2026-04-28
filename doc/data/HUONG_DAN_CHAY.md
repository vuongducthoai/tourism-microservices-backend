# HƯỚNG DẪN CHẠY LẠI POSTGRES, PGADMIN VÀ IMPORT DỮ LIỆU MỚI

## Yêu cầu
- Docker Desktop đang chạy
- PowerShell hoặc Terminal (từ thư mục `D:\HK8\tourism-microservices-backend`)

---

## BƯỚC 1: Dừng và xóa containers cũ (nếu đang chạy)

```bash
docker-compose down -v
```

> **`-v` sẽ xóa volumes** (kể cả dữ liệu PostgreSQL). Dùng khi cần reset hoàn toàn.  
> Nếu chỉ muốn restart mà giữ dữ liệu: `docker-compose down`

---

## BƯỚC 2: Khởi động PostgreSQL và pgAdmin

### 2.1 Khởi động hạ tầng cơ sở (PostgreSQL + Redis + RabbitMQ)

```bash
docker-compose up -d postgres redis rabbitmq
```

Đợi PostgreSQL healthy (khoảng 15-30 giây):
```bash
docker-compose ps postgres
# Cột Status phải hiện "healthy"
```

### 2.2 Chạy pgAdmin (nếu cần giao diện web)

pgAdmin không có trong docker-compose mặc định. Chạy riêng:

```bash
docker run -d --name pgadmin4 `
  --network tourism-microservices-backend_tourism-network `
  -p 5050:80 `
  -e PGADMIN_DEFAULT_EMAIL=admin@admin.com `
  -e PGADMIN_DEFAULT_PASSWORD=admin123 `
  dpage/pgadmin4:latest
```

Truy cập: **http://localhost:5050**  
- Email: `admin@admin.com`  
- Password: `admin123`

#### Kết nối PostgreSQL trong pgAdmin:
- Host: `tourism-postgres` (tên container)
- Port: `5432` (port nội bộ)
- Username: `postgres`
- Password: `postgres`

> Từ máy host dùng port `5433`: `localhost:5433`

---

## BƯỚC 3: Kiểm tra các database đã được tạo

Script `docker/postgres/init-databases.sh` tự động tạo 7 databases khi container khởi động lần đầu:

```bash
docker exec -it tourism-postgres psql -U postgres -c "\l"
```

Các database cần có:
- `iam_db`
- `tour_catalog_db`
- `booking_db`
- `payment_db`
- `forum_db`
- `notification_db`
- `analytics_db`

---

## BƯỚC 4: Khởi động các microservices (để tạo schema)

Hibernate `ddl-auto: create` sẽ tạo tất cả bảng khi service khởi động:

```bash
# Khởi động service discovery trước
docker-compose up -d service-discovery
# Đợi healthy (30s)

# Khởi động config server
docker-compose up -d config-server
# Đợi healthy (30s)

# Khởi động toàn bộ service còn lại
docker-compose up -d
```

Xem log để đảm bảo không có lỗi:
```bash
docker-compose logs -f iam-service
docker-compose logs -f tour-catalog-service
docker-compose logs -f booking-service
docker-compose logs -f payment-service
```

---

## BƯỚC 5: Import dữ liệu seed

Sau khi schema đã được tạo (Hibernate chạy xong), import dữ liệu theo thứ tự:

### Cách 1: Import từng file (khuyến nghị)

```bash
# 1. IAM database
docker exec -i tourism-postgres psql -U postgres -d iam_db < doc/data/01_iam_db_seed.sql

# 2. Tour Catalog database
docker exec -i tourism-postgres psql -U postgres -d tour_catalog_db < doc/data/02_tour_catalog_db_seed.sql

# 3. Booking database
docker exec -i tourism-postgres psql -U postgres -d booking_db < doc/data/03_booking_db_seed.sql

# 4. Payment database
docker exec -i tourism-postgres psql -U postgres -d payment_db < doc/data/04_payment_db_seed.sql

# 5. Forum database
docker exec -i tourism-postgres psql -U postgres -d forum_db < doc/data/05_forum_db_seed.sql

# 6. Notification database
docker exec -i tourism-postgres psql -U postgres -d notification_db < doc/data/06_notification_db_seed.sql

# 7. Analytics database
docker exec -i tourism-postgres psql -U postgres -d analytics_db < doc/data/07_analytics_db_seed.sql
```

### Cách 2: Script PowerShell tự động

```powershell
$databases = @(
    @{db="iam_db"; file="01_iam_db_seed.sql"},
    @{db="tour_catalog_db"; file="02_tour_catalog_db_seed.sql"},
    @{db="booking_db"; file="03_booking_db_seed.sql"},
    @{db="payment_db"; file="04_payment_db_seed.sql"},
    @{db="forum_db"; file="05_forum_db_seed.sql"},
    @{db="notification_db"; file="06_notification_db_seed.sql"},
    @{db="analytics_db"; file="07_analytics_db_seed.sql"}
)

foreach ($item in $databases) {
    Write-Host "Importing $($item.file) into $($item.db)..."
    Get-Content "doc/data/$($item.file)" | docker exec -i tourism-postgres psql -U postgres -d $item.db
    Write-Host "Done: $($item.file)"
}
```

---

## BƯỚC 6: Kiểm tra dữ liệu

```bash
# Kiểm tra users
docker exec -it tourism-postgres psql -U postgres -d iam_db -c "SELECT userid, full_name, role FROM users;"

# Kiểm tra tours
docker exec -it tourism-postgres psql -U postgres -d tour_catalog_db -c "SELECT tourid, tour_code, tour_name FROM tours;"

# Kiểm tra locations (chỉ Việt Nam)
docker exec -it tourism-postgres psql -U postgres -d tour_catalog_db -c "SELECT locationid, name, region FROM locations;"

# Kiểm tra bookings
docker exec -it tourism-postgres psql -U postgres -d booking_db -c "SELECT bookingid, booking_code, booking_status FROM bookings;"

# Kiểm tra payments
docker exec -it tourism-postgres psql -U postgres -d payment_db -c "SELECT paymentid, payment_method, status, amount FROM payments;"
```

---

## BƯỚC 7: Kiểm tra kết nối bằng pgAdmin

Sau khi import thành công:
1. Mở pgAdmin: http://localhost:5050
2. Đăng nhập: `admin@admin.com` / `admin123`
3. Thêm server mới:
   - General > Name: `Tourism Local`
   - Connection > Host: `tourism-postgres`
   - Port: `5432`
   - Database: `postgres`
   - Username: `postgres`
   - Password: `postgres`
4. Duyệt các database: `iam_db`, `tour_catalog_db`, `booking_db`, v.v.

---

## LƯU Ý QUAN TRỌNG

### Enum values sau khi sửa đổi:
| Enum | Giá trị đúng |
|---|---|
| `BookingStatus` | `PENDING_PAYMENT, OVERDUE_PAYMENT, PENDING_CONFIRMATION, PAID, CANCELLED, PENDING_REVIEW, REVIEWED, PENDING_REFUND` |
| `CouponType` | `GLOBAL, DEPARTURE` |
| `PassengerType` | `ADULT, CHILD, INFANT, SINGLE_SUPPLEMENT` |
| `Role` | `CUSTOMER, ADMIN, TOUR_OWNER` |
| `PaymentMethod` | `VNPAY, PAYOS` |
| `PaymentStatus` | `PENDING, SUCCESS, FAILED, REFUNDED` |
| `Region` | `NORTH, CENTRAL, SOUTH` |
| `TransportType` | `OUTBOUND, INBOUND` |
| `VehicleType` | `PLANE, BUS, TRAIN, SHIP, CAR` |

### Khi rebuild service sau khi sửa enum/entity:
```bash
# Rebuild service cụ thể
docker-compose build --no-cache tour-catalog-service booking-service payment-service iam-service

# Khởi động lại
docker-compose up -d
```

### Password mặc định (tất cả users):
- Password: `Password123!` (BCrypt hash đã được nhúng sẵn trong seed)

---

## KHẮC PHỤC SỰ CỐ

### Lỗi: "relation does not exist"
- **Nguyên nhân**: Schema chưa được tạo (service chưa chạy)
- **Giải pháp**: Khởi động services trước (Bước 4), đợi Hibernate tạo schema

### Lỗi: "invalid input value for enum"
- **Nguyên nhân**: Enum trong DB chưa cập nhật hoặc seed dùng giá trị cũ
- **Giải pháp**: Rebuild service, drop và recreate schema

### Lỗi: "duplicate key value violates unique constraint"
- **Nguyên nhân**: Dữ liệu đã được import trước đó
- **Giải pháp**: File seed đã có `TRUNCATE ... RESTART IDENTITY CASCADE` ở đầu

### Reset hoàn toàn:
```bash
docker-compose down -v
docker-compose up -d postgres
# Đợi 30s rồi chạy lại từ Bước 4
```