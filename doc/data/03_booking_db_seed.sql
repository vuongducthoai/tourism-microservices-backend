-- ============================================================
-- BOOKING DB SEED DATA
-- Database: booking_db
-- Enums:
--   BookingStatus = PENDING_PAYMENT | OVERDUE_PAYMENT | PENDING_CONFIRMATION | PAID |
--                   CANCELLED | PENDING_REVIEW | REVIEWED | PENDING_REFUND
--   CouponType = GLOBAL | DEPARTURE  (NO PERSONAL!)
--   PassengerType = ADULT | CHILD | INFANT | SINGLE_SUPPLEMENT
-- Hibernate naming: bookingID -> bookingid, passengerID -> passengerid
-- ============================================================


-- Fix check constraints (schema created from old code)
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_booking_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_booking_status_check
    CHECK (booking_status IN ('PENDING_PAYMENT', 'OVERDUE_PAYMENT', 'PENDING_CONFIRMATION', 'PAID', 'CANCELLED', 'PENDING_REVIEW', 'REVIEWED', 'PENDING_REFUND'));

ALTER TABLE coupons DROP CONSTRAINT IF EXISTS coupons_coupon_type_check;
ALTER TABLE coupons ADD CONSTRAINT coupons_coupon_type_check
    CHECK (coupon_type IN ('GLOBAL', 'DEPARTURE'));

ALTER TABLE booking_passengers DROP CONSTRAINT IF EXISTS booking_passengers_passenger_type_check;
ALTER TABLE booking_passengers ADD CONSTRAINT booking_passengers_passenger_type_check
    CHECK (passenger_type IN ('ADULT', 'CHILD', 'INFANT', 'SINGLE_SUPPLEMENT'));

-- Truncate (order matters for FK)
TRUNCATE TABLE refund_information RESTART IDENTITY CASCADE;
TRUNCATE TABLE booking_passengers RESTART IDENTITY CASCADE;
TRUNCATE TABLE bookings RESTART IDENTITY CASCADE;
TRUNCATE TABLE coupons RESTART IDENTITY CASCADE;

-- ============================================================
-- COUPONS
-- coupon_type: GLOBAL | DEPARTURE only
-- ============================================================
INSERT INTO coupons (
    couponid, coupon_code, description, discount_amount,
    start_date, end_date, usage_limit, usage_count,
    min_order_value, coupon_type, departure_id,
    created_at, updated_at, is_deleted
) VALUES
(1, 'WELCOME10',  'Giảm 10% cho khách hàng mới',  10,
 '2025-01-01 00:00:00', '2025-12-31 23:59:59', 100, 5,
 500000, 'GLOBAL', NULL, NOW(), NOW(), false),

(2, 'SUMMER20',   'Giảm 20% mùa hè 2025',         20,
 '2025-06-01 00:00:00', '2025-08-31 23:59:59', 50, 0,
 1000000, 'GLOBAL', NULL, NOW(), NOW(), false),

(3, 'DEP5HALONG', 'Ưu đãi 5% chuyến Hạ Long tháng 3', 5,
 '2025-03-01 00:00:00', '2025-03-31 23:59:59', 20, 3,
 2000000, 'DEPARTURE', 1, NOW(), NOW(), false),

(4, 'TET2025',    'Ưu đãi Tết Nguyên Đán 2025',   15,
 '2025-01-15 00:00:00', '2025-02-15 23:59:59', 30, 2,
 3000000, 'GLOBAL', NULL, NOW(), NOW(), false),

(5, 'PHUQUOC15',  'Giảm 15% cho tour Phú Quốc',   15,
 '2025-02-01 00:00:00', '2025-04-30 23:59:59', 25, 4,
 5000000, 'DEPARTURE', 5, NOW(), NOW(), false);

SELECT setval('coupons_couponid_seq', (SELECT MAX(couponid) FROM coupons));

-- ============================================================
-- BOOKINGS
-- All Vietnam tours only (departure_id 1-15)
-- booking_status flow: PENDING_PAYMENT -> PENDING_CONFIRMATION -> PAID
--                   -> PENDING_REVIEW -> REVIEWED
--                   or -> OVERDUE_PAYMENT / CANCELLED / PENDING_REFUND
-- ============================================================
INSERT INTO bookings (
    bookingid, booking_code, booking_date, contact_email, contact_full_name,
    contact_phone, contact_address, customer_note,
    total_passengers, subtotal_price, surcharge, coupon_discount, paid_by_coin,
    total_price, cancel_reason, refund_amount,
    booking_status, user_id, departure_id, applied_coupon_codes,
    created_at, updated_at, is_deleted
) VALUES
-- Booking 1: Khách hàng 3 đặt tour HN-HL (dep 1), đã thanh toán, đã review
(1, 'BK20250101', '2025-02-15 10:00:00', 'customer1@gmail.com', 'Lê Văn Khách',
 '0903000003', '123 Nguyễn Trãi, Hà Nội', 'Cần hỗ trợ chỗ ngồi đầu xe',
 2, 5600000, 0, 0, 0,
 5600000, NULL, NULL,
 'REVIEWED', 3, 1, NULL,
 '2025-02-15 10:00:00', '2025-03-15 08:00:00', false),

-- Booking 2: Khách hàng 4 đặt tour HN-SA (dep 3), đã thanh toán, chờ review
(2, 'BK20250102', '2025-02-20 14:00:00', 'customer2@gmail.com', 'Phạm Thị Mai',
 '0904000004', '456 Lê Lợi, Đà Nẵng', NULL,
 1, 4500000, 0, 450000, 0,
 4050000, NULL, NULL,
 'PENDING_REVIEW', 4, 3, 'WELCOME10',
 '2025-02-20 14:00:00', '2025-03-25 09:00:00', false),

-- Booking 3: Khách hàng 5 đặt tour HCM-PQ (dep 5), đang chờ xác nhận
(3, 'BK20250103', '2025-01-25 09:00:00', 'customer3@gmail.com', 'Hoàng Minh Tuấn',
 '0905000005', '789 Trần Hưng Đạo, Hà Giang', 'Gia đình có 1 em bé',
 3, 19000000, 0, 2850000, 0,
 16150000, NULL, NULL,
 'PENDING_CONFIRMATION', 5, 5, 'PHUQUOC15',
 '2025-01-25 09:00:00', '2025-01-25 10:00:00', false),

-- Booking 4: Khách hàng 6 đặt tour HCM-VT (dep 7), đã thanh toán và review
(4, 'BK20250104', '2025-02-01 11:00:00', 'customer4@gmail.com', 'Nguyễn Thị Hương',
 '0906000006', '321 Nguyễn Trãi, Nha Trang', NULL,
 2, 3000000, 0, 0, 200000,
 2800000, NULL, NULL,
 'REVIEWED', 6, 7, NULL,
 '2025-02-01 11:00:00', '2025-03-08 10:00:00', false),

-- Booking 5: Khách hàng 7 đặt tour DN-HAN (dep 9), chờ thanh toán
(5, 'BK20250105', '2025-03-01 16:00:00', 'customer5@gmail.com', 'Đặng Quốc Bảo',
 '0907000007', '654 Cách Mạng Tháng 8, Cần Thơ', NULL,
 1, 6500000, 0, 0, 0,
 6500000, NULL, NULL,
 'PENDING_PAYMENT', 7, 9, NULL,
 '2025-03-01 16:00:00', '2025-03-01 16:00:00', false),

-- Booking 6: Khách hàng 3 đặt tour HN-NT (dep 11), đã huỷ
(6, 'BK20250106', '2025-03-05 08:00:00', 'customer1@gmail.com', 'Lê Văn Khách',
 '0903000003', '123 Nguyễn Trãi, Hà Nội', NULL,
 2, 15000000, 0, 0, 0,
 15000000, 'Thay đổi kế hoạch cá nhân', 7500000,
 'CANCELLED', 3, 11, NULL,
 '2025-03-05 08:00:00', '2025-03-06 09:00:00', false),

-- Booking 7: Khách hàng 8 đặt tour HCM-CT (dep 13), đã thanh toán
(7, 'BK20250107', '2025-02-28 13:00:00', 'customer6@gmail.com', 'Vũ Thị Lan',
 '0908000008', '987 Trần Phú, Đà Lạt', NULL,
 2, 4400000, 0, 660000, 0,
 3740000, NULL, NULL,
 'PAID', 8, 13, 'WELCOME10',
 '2025-02-28 13:00:00', '2025-03-01 10:00:00', false),

-- Booking 8: Khách hàng 6 đặt tour HCM-PQ (dep 6), chờ hoàn tiền
(8, 'BK20250108', '2025-03-10 10:00:00', 'customer4@gmail.com', 'Nguyễn Thị Hương',
 '0906000006', '321 Nguyễn Trãi, Nha Trang', NULL,
 2, 18000000, 0, 0, 0,
 18000000, 'Bệnh đột xuất không đi được', 9000000,
 'PENDING_REFUND', 6, 6, NULL,
 '2025-03-10 10:00:00', '2025-03-12 09:00:00', false);

SELECT setval('bookings_bookingid_seq', (SELECT MAX(bookingid) FROM bookings));

-- ============================================================
-- BOOKING PASSENGERS
-- Fields: passengerid, full_name, gender, date_of_birth (NOT NULL),
--         passenger_type, base_price, requires_single_room, single_room_surcharge,
--         booking_id
-- Schema: passengerid, full_name, gender, passenger_type, base_price, date_of_birth, booking_id
-- ============================================================
INSERT INTO booking_passengers (
    passengerid, full_name, gender,
    passenger_type, base_price, date_of_birth,
    booking_id, created_at, updated_at, is_deleted
) VALUES
-- Booking 1 (dep 1, tour HN-HL, 2 adults)
(1, 'Lê Văn Khách',    'MALE',   'ADULT', 2800000, '1985-03-15', 1, NOW(), NOW(), false),
(2, 'Lê Thị Hà',       'FEMALE', 'ADULT', 2800000, '1988-07-20', 1, NOW(), NOW(), false),

-- Booking 2 (dep 3, tour HN-SA, 1 adult)
(3, 'Phạm Thị Mai',    'FEMALE', 'ADULT', 4500000, '1990-05-10', 2, NOW(), NOW(), false),

-- Booking 3 (dep 5, tour HCM-PQ, 2 adults + 1 infant)
(4, 'Hoàng Minh Tuấn', 'MALE',   'ADULT',  8500000, '1982-11-25', 3, NOW(), NOW(), false),
(5, 'Hoàng Thị Bích',  'FEMALE', 'ADULT',  8500000, '1985-04-12', 3, NOW(), NOW(), false),
(6, 'Hoàng Bé Bi',     'MALE',   'INFANT', 1000000, '2023-06-01', 3, NOW(), NOW(), false),

-- Booking 4 (dep 7, tour HCM-VT, 2 adults)
(7, 'Nguyễn Thị Hương','FEMALE', 'ADULT', 1500000, '1992-09-18', 4, NOW(), NOW(), false),
(8, 'Trần Văn Minh',   'MALE',   'ADULT', 1500000, '1989-12-30', 4, NOW(), NOW(), false),

-- Booking 5 (dep 9, tour DN-HAN, 1 adult)
(9, 'Đặng Quốc Bảo',   'MALE',   'ADULT', 6500000, '1987-02-14', 5, NOW(), NOW(), false),

-- Booking 6 (dep 11, tour HN-NT, 2 adults)
(10,'Lê Văn Khách',    'MALE',   'ADULT', 7500000, '1985-03-15', 6, NOW(), NOW(), false),
(11,'Lê Thị Hà',       'FEMALE', 'ADULT', 7500000, '1988-07-20', 6, NOW(), NOW(), false),

-- Booking 7 (dep 13, tour HCM-CT, 2 adults)
(12,'Vũ Thị Lan',      'FEMALE', 'ADULT', 2200000, '1993-08-22', 7, NOW(), NOW(), false),
(13,'Vũ Văn Hùng',     'MALE',   'ADULT', 2200000, '1991-01-07', 7, NOW(), NOW(), false),

-- Booking 8 (dep 6, tour HCM-PQ, 2 adults - pending refund)
(14,'Nguyễn Thị Hương','FEMALE', 'ADULT', 9000000, '1992-09-18', 8, NOW(), NOW(), false),
(15,'Nguyễn Văn Đức',  'MALE',   'ADULT', 9000000, '1986-05-03', 8, NOW(), NOW(), false);

SELECT setval('booking_passengers_passengerid_seq', (SELECT MAX(passengerid) FROM booking_passengers));

-- ============================================================
-- REFUND INFORMATION
-- Table: refund_information
-- Fields: refundid, bank (NOT bank_name!), account_number, account_name,
--         refund_amount, refund_status, refund_date, note, booking_id (UNIQUE)
-- Only for bookings with CANCELLED or PENDING_REFUND status
-- ============================================================
INSERT INTO refund_information (
    refundid, bank_name, account_number, account_name,
    refund_amount, refund_status, refund_date, note, booking_id,
    created_at, updated_at, is_deleted
) VALUES
-- Booking 6 - CANCELLED
(1, 'Ngân hàng Vietcombank', '0123456789', 'LE VAN KHACH',
 7500000, 'PENDING', '2025-03-07 00:00:00',
 'Hoàn 50% vì hủy trước 7 ngày', 6,
 NOW(), NOW(), false),

-- Booking 8 - PENDING_REFUND
(2, 'Ngân hàng BIDV', '9876543210', 'NGUYEN THI HUONG',
 9000000, 'PENDING', NULL,
 'Hoàn tiền do bệnh, có xác nhận y tế', 8,
 NOW(), NOW(), false);

SELECT setval('refund_information_refundid_seq', (SELECT MAX(refundid) FROM refund_information));