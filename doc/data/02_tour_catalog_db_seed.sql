-- ============================================================
-- TOUR CATALOG DB SEED DATA
-- Database: tour_catalog_db
-- Enums:
--   Region = NORTH | CENTRAL | SOUTH  (Vietnam only!)
--   VehicleType = PLANE | BUS | TRAIN | SHIP | CAR
--   TransportType = OUTBOUND | INBOUND
-- Hibernate naming notes:
--   tourID -> tourid, pricingID -> pricingid, etc.
--   table departure_pricing (no s!), departure_pricings in code = departure_pricing in DB
-- ============================================================


-- Fix check constraints (schema created from old code)
ALTER TABLE locations DROP CONSTRAINT IF EXISTS locations_region_check;
ALTER TABLE locations ADD CONSTRAINT locations_region_check
    CHECK (region IN ('NORTH', 'CENTRAL', 'SOUTH'));

ALTER TABLE departure_transports DROP CONSTRAINT IF EXISTS departure_transports_transport_type_check;
ALTER TABLE departure_transports ADD CONSTRAINT departure_transports_transport_type_check
    CHECK (transport_type IN ('OUTBOUND', 'INBOUND'));

ALTER TABLE departure_transports DROP CONSTRAINT IF EXISTS departure_transports_vehicle_type_check;
ALTER TABLE departure_transports ADD CONSTRAINT departure_transports_vehicle_type_check
    CHECK (vehicle_type IN ('PLANE', 'BUS', 'TRAIN', 'SHIP', 'CAR'));

-- Truncate (order matters for FK)
TRUNCATE TABLE departure_transports RESTART IDENTITY CASCADE;
TRUNCATE TABLE departure_pricings RESTART IDENTITY CASCADE;
TRUNCATE TABLE tour_departures RESTART IDENTITY CASCADE;
TRUNCATE TABLE favorite_tours RESTART IDENTITY CASCADE;
TRUNCATE TABLE image_reviews RESTART IDENTITY CASCADE;
TRUNCATE TABLE reviews RESTART IDENTITY CASCADE;
TRUNCATE TABLE itinerary_days RESTART IDENTITY CASCADE;
TRUNCATE TABLE tour_media RESTART IDENTITY CASCADE;
TRUNCATE TABLE tour_images RESTART IDENTITY CASCADE;
TRUNCATE TABLE tours RESTART IDENTITY CASCADE;
TRUNCATE TABLE policy_templates RESTART IDENTITY CASCADE;
TRUNCATE TABLE branch_contacts RESTART IDENTITY CASCADE;
TRUNCATE TABLE locations RESTART IDENTITY CASCADE;

-- ============================================================
-- LOCATIONS (Vietnam only: NORTH | CENTRAL | SOUTH)
-- ============================================================
INSERT INTO locations (locationid, name, slug, image, region, description, airport_code, airport_name, status, created_at, updated_at, is_deleted)
VALUES
(1,  'Hà Nội',          'ha-noi',          'https://res.cloudinary.com/demo/image/upload/hanoi.jpg',        'NORTH',   'Thủ đô nghìn năm văn hiến',                   'HAN', 'Sân bay Nội Bài',           true, NOW(), NOW(), false),
(2,  'Hải Phòng',       'hai-phong',        'https://res.cloudinary.com/demo/image/upload/haiphong.jpg',     'NORTH',   'Thành phố Cảng',                              'HPH', 'Sân bay Cát Bi',            true, NOW(), NOW(), false),
(3,  'Hạ Long',         'ha-long',          'https://res.cloudinary.com/demo/image/upload/halong.jpg',       'NORTH',   'Vịnh Hạ Long - Di sản thiên nhiên thế giới',  NULL,  NULL,                        true, NOW(), NOW(), false),
(4,  'Sa Pa',           'sa-pa',            'https://res.cloudinary.com/demo/image/upload/sapa.jpg',         'NORTH',   'Thị trấn sương mù vùng Tây Bắc',             NULL,  NULL,                        true, NOW(), NOW(), false),
(5,  'Đà Nẵng',         'da-nang',          'https://res.cloudinary.com/demo/image/upload/danang.jpg',       'CENTRAL', 'Thành phố đáng sống',                         'DAD', 'Sân bay Đà Nẵng',           true, NOW(), NOW(), false),
(6,  'Hội An',          'hoi-an',           'https://res.cloudinary.com/demo/image/upload/hoian.jpg',        'CENTRAL', 'Phố cổ Hội An - Di sản văn hóa thế giới',    NULL,  NULL,                        true, NOW(), NOW(), false),
(7,  'Huế',             'hue',              'https://res.cloudinary.com/demo/image/upload/hue.jpg',          'CENTRAL', 'Cố đô Huế - Di sản văn hóa thế giới',        'HUI', 'Sân bay Phú Bài',           true, NOW(), NOW(), false),
(8,  'Nha Trang',       'nha-trang',        'https://res.cloudinary.com/demo/image/upload/nhatrang.jpg',     'CENTRAL', 'Thành phố biển xinh đẹp',                     'CXR', 'Sân bay Cam Ranh',          true, NOW(), NOW(), false),
(9,  'TP. Hồ Chí Minh', 'tp-ho-chi-minh',  'https://res.cloudinary.com/demo/image/upload/hcm.jpg',          'SOUTH',   'Thành phố năng động nhất cả nước',            'SGN', 'Sân bay Tân Sơn Nhất',     true, NOW(), NOW(), false),
(10, 'Vũng Tàu',        'vung-tau',         'https://res.cloudinary.com/demo/image/upload/vungtau.jpg',      'SOUTH',   'Thành phố biển nghỉ dưỡng',                  NULL,  NULL,                        true, NOW(), NOW(), false),
(11, 'Phú Quốc',        'phu-quoc',         'https://res.cloudinary.com/demo/image/upload/phuquoc.jpg',      'SOUTH',   'Đảo ngọc Phú Quốc',                          'PQC', 'Sân bay Phú Quốc',         true, NOW(), NOW(), false),
(12, 'Cần Thơ',         'can-tho',          'https://res.cloudinary.com/demo/image/upload/cantho.jpg',       'SOUTH',   'Thủ phủ miền Tây Nam Bộ',                    'VCA', 'Sân bay Cần Thơ',          true, NOW(), NOW(), false);

SELECT setval('locations_locationid_seq', (SELECT MAX(locationid) FROM locations));

-- ============================================================
-- BRANCH CONTACTS
-- ============================================================
INSERT INTO branch_contacts (contactid, branch_name, phone, email, address, is_head_office, created_at, updated_at, is_deleted)
VALUES
(1, 'Chi nhánh Hà Nội',         '02462626262', 'hanoi@tourismvn.com',   '123 Đinh Tiên Hoàng, Hoàn Kiếm, Hà Nội',         true,  NOW(), NOW(), false),
(2, 'Chi nhánh TP. Hồ Chí Minh','02838383838', 'hcm@tourismvn.com',    '456 Nguyễn Huệ, Quận 1, TP. Hồ Chí Minh',       false, NOW(), NOW(), false),
(3, 'Chi nhánh Đà Nẵng',        '02363636363', 'danang@tourismvn.com', '789 Bạch Đằng, Hải Châu, Đà Nẵng',              false, NOW(), NOW(), false);

SELECT setval('branch_contacts_contactid_seq', (SELECT MAX(contactid) FROM branch_contacts));

-- ============================================================
-- POLICY TEMPLATES
-- ============================================================
INSERT INTO policy_templates (
    policy_templateid, template_name,
    tour_price_includes, tour_price_excludes, child_pricing_notes,
    payment_conditions, registration_conditions,
    regular_day_cancellation_rules, holiday_cancellation_rules, force_majeure_rules,
    packing_list, contact_id, status, created_at, updated_at, is_deleted
) VALUES
(1, 'Chính sách Tour Tiêu Chuẩn',
 'Vé máy bay/xe khách khứ hồi, khách sạn 3-4 sao, bữa ăn theo chương trình, hướng dẫn viên, bảo hiểm du lịch',
 'Visa (nếu cần), chi phí cá nhân, đồ uống ngoài bữa ăn, tip hướng dẫn viên',
 'Trẻ em dưới 2 tuổi: miễn phí. Từ 2-11 tuổi: 50% giá người lớn. Từ 12 tuổi: 100% giá người lớn',
 'Đặt cọc 30% khi đặt tour. Thanh toán 100% trước ngày khởi hành 7 ngày',
 'Xuất trình CCCD/hộ chiếu. Đặt tour trước tối thiểu 3 ngày',
 'Hủy trước 15 ngày: hoàn 90%. Hủy trước 7 ngày: hoàn 50%. Hủy trước 3 ngày: hoàn 20%. Hủy trong vòng 3 ngày: không hoàn',
 'Lễ Tết: hủy trước 30 ngày hoàn 80%, trước 15 ngày hoàn 50%, trước 7 ngày hoàn 20%',
 'Trường hợp bất khả kháng (thiên tai, dịch bệnh): hoàn 100% hoặc đổi lịch miễn phí',
 'Quần áo thoải mái, kem chống nắng, mũ/nón, thuốc cá nhân, máy ảnh',
 1, true, NOW(), NOW(), false),

(2, 'Chính sách Tour Cao Cấp',
 'Vé máy bay hạng thương gia, khách sạn 5 sao, toàn bộ bữa ăn, xe riêng, HDV riêng, bảo hiểm toàn diện',
 'Chi phí cá nhân, tip, mua sắm',
 'Trẻ em dưới 5 tuổi: miễn phí. Từ 5-11 tuổi: 70% giá người lớn. Từ 12 tuổi: 100%',
 'Đặt cọc 50% khi đặt. Thanh toán 100% trước 14 ngày',
 'Xuất trình hộ chiếu còn hạn tối thiểu 6 tháng. Đặt trước tối thiểu 7 ngày',
 'Hủy trước 30 ngày: hoàn 90%. Trước 14 ngày: hoàn 60%. Trước 7 ngày: hoàn 30%',
 'Lễ Tết: hủy trước 45 ngày hoàn 80%, trước 30 ngày hoàn 50%',
 'Bất khả kháng: hoàn 100% hoặc đổi lịch',
 'Trang phục lịch sự, mỹ phẩm cá nhân, thuốc dự phòng',
 2, true, NOW(), NOW(), false);

SELECT setval('policy_templates_policy_templateid_seq', (SELECT MAX(policy_templateid) FROM policy_templates));

-- ============================================================
-- TOURS (Vietnam only, 9 tours)
-- start_location_id / end_location_id references locations
-- ============================================================
INSERT INTO tours (
    tourid, tour_code, tour_name, duration, transportation,
    start_location_id, end_location_id,
    attractions, meals, ideal_time, status,
    trip_transportation, suitable_customer, hotel,
    created_at, updated_at, is_deleted
) VALUES
(1, 'HN-HL-3N2D', 'Hà Nội - Hạ Long 3 Ngày 2 Đêm', '3 ngày 2 đêm', 'Xe khách',
 1, 3,
 'Vịnh Hạ Long, hang Sửng Sốt, đảo Titov, làng chài Cửa Vạn',
 'Sáng: Phở/Bánh cuốn. Trưa: Hải sản. Tối: Buffet trên tàu',
 'Quanh năm, đẹp nhất tháng 3-5 và 9-11', true,
 'Xe khách giường nằm', 'Gia đình, cặp đôi, nhóm bạn',
 'Khách sạn 3 sao trên tàu du lịch', NOW(), NOW(), false),

(2, 'HN-SA-4N3D', 'Hà Nội - Sa Pa 4 Ngày 3 Đêm', '4 ngày 3 đêm', 'Xe khách, tàu hỏa',
 1, 4,
 'Đỉnh Fansipan, thung lũng Mường Hoa, làng Cát Cát, chợ Bắc Hà',
 'Ăn sáng buffet tại khách sạn, trưa và tối theo chương trình',
 'Tháng 3-5 (hoa đào, hoa mận), tháng 9-11 (lúa chín)', true,
 'Tàu hỏa + xe địa phương', 'Nhóm bạn trẻ, gia đình, người yêu thiên nhiên',
 'Khách sạn 4 sao tại Sa Pa', NOW(), NOW(), false),

(3, 'HCM-PQ-5N4D', 'TP. Hồ Chí Minh - Phú Quốc 5 Ngày 4 Đêm', '5 ngày 4 đêm', 'Máy bay',
 9, 11,
 'Vinpearl Safari, Grand World, Bãi Sao, Mũi Ông Đội, làng chài Hàm Ninh',
 'Buffet sáng tại resort, hải sản tươi mỗi bữa',
 'Tháng 11 - tháng 4 (mùa khô)', true,
 'Máy bay', 'Gia đình, cặp đôi, người đi công tác kết hợp nghỉ dưỡng',
 'Resort 4-5 sao bãi biển', NOW(), NOW(), false),

(4, 'HCM-VT-2N1D', 'TP. Hồ Chí Minh - Vũng Tàu 2 Ngày 1 Đêm', '2 ngày 1 đêm', 'Xe khách',
 9, 10,
 'Bãi Sau, Bãi Dâu, Hải đăng Vũng Tàu, Thích Ca Phật Đài, núi Lớn',
 'Hải sản tươi, bánh khọt, lẩu cá',
 'Quanh năm, tránh mùa mưa tháng 6-9', true,
 'Xe khách limousine', 'Gia đình, cặp đôi, đi ngắn ngày',
 'Khách sạn 3-4 sao mặt biển', NOW(), NOW(), false),

(5, 'DN-HAN-4N3D', 'Đà Nẵng - Hội An - Huế 4 Ngày 3 Đêm', '4 ngày 3 đêm', 'Máy bay, xe khách',
 9, 7,
 'Bà Nà Hills, cầu Vàng, phố cổ Hội An, Đại Nội Huế, lăng Tự Đức, bãi biển Mỹ Khê',
 'Ẩm thực miền Trung: Mì Quảng, Bánh mì Hội An, Bún bò Huế',
 'Tháng 3-8 (ít mưa, nắng đẹp)', true,
 'Máy bay + xe địa phương', 'Nhóm bạn, gia đình, du lịch văn hóa',
 'Khách sạn 4 sao tại Đà Nẵng', NOW(), NOW(), false),

(6, 'HN-NT-5N4D', 'Hà Nội - Nha Trang 5 Ngày 4 Đêm', '5 ngày 4 đêm', 'Máy bay',
 1, 8,
 'Vinpearl Land, đảo Hòn Mun, tháp Chàm Po Nagar, chợ Đầm, bãi biển Trần Phú',
 'Buffet sáng, hải sản trưa và tối',
 'Tháng 1-8 (biển calme, ít mưa)', true,
 'Máy bay', 'Gia đình, cặp đôi, nhóm bạn',
 'Khách sạn 4 sao ven biển', NOW(), NOW(), false),

(7, 'HCM-CT-3N2D', 'TP. Hồ Chí Minh - Cần Thơ 3 Ngày 2 Đêm', '3 ngày 2 đêm', 'Xe khách, thuyền',
 9, 12,
 'Chợ nổi Cái Răng, vườn trái cây, làng du lịch Mỹ Khánh, cù lao Tân Lộc',
 'Cơm miền Tây, bánh tét, hủ tiếu Nam Vang',
 'Quanh năm, lý tưởng tháng 12-4', true,
 'Xe khách + thuyền trên sông', 'Gia đình, người yêu ẩm thực, văn hóa sông nước',
 'Khách sạn 3-4 sao trung tâm Cần Thơ', NOW(), NOW(), false),

(8, 'HN-HP-2N1D', 'Hà Nội - Hải Phòng - Cát Bà 2 Ngày 1 Đêm', '2 ngày 1 đêm', 'Xe khách, phà',
 1, 2,
 'Vịnh Lan Hạ, vườn quốc gia Cát Bà, hang Đầu Gỗ, bãi biển Cát Cò',
 'Hải sản tươi, bánh đa cua, nem cua bể Hải Phòng',
 'Tháng 4-10 (biển đẹp, ít sóng)', true,
 'Xe khách + phà + thuyền', 'Nhóm bạn, gia đình thích khám phá',
 'Khách sạn 3 sao trên đảo Cát Bà', NOW(), NOW(), false),

(9, 'DN-HCM-4N3D', 'Đà Nẵng - TP. Hồ Chí Minh 4 Ngày 3 Đêm', '4 ngày 3 đêm', 'Máy bay',
 5, 9,
 'Bán đảo Sơn Trà, Bảo tàng Chăm, Phố đi bộ Nguyễn Huệ, Chợ Bến Thành, địa đạo Củ Chi',
 'Ẩm thực đặc sắc hai miền Nam - Trung',
 'Quanh năm', true,
 'Máy bay nội địa', 'Khách thương mại, nhóm bạn',
 'Khách sạn 4 sao tại từng điểm dừng', NOW(), NOW(), false);

SELECT setval('tours_tourid_seq', (SELECT MAX(tourid) FROM tours));

-- ============================================================
-- TOUR DEPARTURES
-- Schema fields: departureid, departure_code (NOT NULL UNIQUE), departure_date (DATE),
--                return_date (DATE), available_slots, total_slots, status,
--                tour_id, policy_template_id
-- ============================================================
INSERT INTO tour_departures (
    departureid, departure_code, departure_date, return_date,
    available_slots, total_slots, status, tour_id, policy_template_id,
    created_at, updated_at, is_deleted
) VALUES
-- Tour 1: HN-HL (3 ngày)
(1,  'HN-HL-2025-03-10', '2025-03-10', '2025-03-12', 20, 20, true, 1, 1, NOW(), NOW(), false),
(2,  'HN-HL-2025-04-15', '2025-04-15', '2025-04-17', 15, 15, true, 1, 1, NOW(), NOW(), false),
-- Tour 2: HN-SA (4 ngày)
(3,  'HN-SA-2025-03-22', '2025-03-22', '2025-03-25', 18, 18, true, 2, 1, NOW(), NOW(), false),
(4,  'HN-SA-2025-05-01', '2025-05-01', '2025-05-04', 12, 12, true, 2, 1, NOW(), NOW(), false),
-- Tour 3: HCM-PQ (5 ngày)
(5,  'HCM-PQ-2025-02-14', '2025-02-14', '2025-02-18', 25, 25, true, 3, 1, NOW(), NOW(), false),
(6,  'HCM-PQ-2025-04-20', '2025-04-20', '2025-04-24', 20, 20, true, 3, 1, NOW(), NOW(), false),
-- Tour 4: HCM-VT (2 ngày)
(7,  'HCM-VT-2025-03-05', '2025-03-05', '2025-03-06', 30, 30, true, 4, 1, NOW(), NOW(), false),
(8,  'HCM-VT-2025-03-20', '2025-03-20', '2025-03-21', 25, 25, true, 4, 1, NOW(), NOW(), false),
-- Tour 5: DN-HAN (4 ngày)
(9,  'DN-HAN-2025-04-01', '2025-04-01', '2025-04-04', 22, 22, true, 5, 1, NOW(), NOW(), false),
(10, 'DN-HAN-2025-05-15', '2025-05-15', '2025-05-18', 18, 18, true, 5, 1, NOW(), NOW(), false),
-- Tour 6: HN-NT (5 ngày)
(11, 'HN-NT-2025-04-10', '2025-04-10', '2025-04-14', 20, 20, true, 6, 1, NOW(), NOW(), false),
(12, 'HN-NT-2025-06-20', '2025-06-20', '2025-06-24', 22, 22, true, 6, 1, NOW(), NOW(), false),
-- Tour 7: HCM-CT (3 ngày)
(13, 'HCM-CT-2025-03-15', '2025-03-15', '2025-03-17', 25, 25, true, 7, 1, NOW(), NOW(), false),
-- Tour 8: HN-HP (2 ngày)
(14, 'HN-HP-2025-04-25', '2025-04-25', '2025-04-26', 16, 16, true, 8, 1, NOW(), NOW(), false),
-- Tour 9: DN-HCM (4 ngày)
(15, 'DN-HCM-2025-05-10', '2025-05-10', '2025-05-13', 20, 20, true, 9, 1, NOW(), NOW(), false);

SELECT setval('tour_departures_departureid_seq', (SELECT MAX(departureid) FROM tour_departures));

-- ============================================================
-- DEPARTURE PRICING
-- Table: departure_pricings (actual DB table name)
-- Fields: pricingid, passenger_type, price, original_price, departure_id
-- ============================================================
INSERT INTO departure_pricings (
    pricingid, passenger_type, price, original_price,
    departure_id, created_at, updated_at, is_deleted
) VALUES
-- Dep 1 (Tour 1 - HN-HL)
(1,  'ADULT',  2800000,  3200000,  1, NOW(), NOW(), false),
(2,  'CHILD',  1800000,  2000000,  1, NOW(), NOW(), false),
(3,  'INFANT',  500000,   700000,  1, NOW(), NOW(), false),
-- Dep 2 (Tour 1)
(4,  'ADULT',  2900000,  3200000,  2, NOW(), NOW(), false),
(5,  'CHILD',  1900000,  2000000,  2, NOW(), NOW(), false),
(6,  'INFANT',  600000,   700000,  2, NOW(), NOW(), false),
-- Dep 3 (Tour 2 - HN-SA)
(7,  'ADULT',  4500000,  5000000,  3, NOW(), NOW(), false),
(8,  'CHILD',  2800000,  3200000,  3, NOW(), NOW(), false),
(9,  'INFANT',  800000,  1000000,  3, NOW(), NOW(), false),
-- Dep 4 (Tour 2)
(10, 'ADULT',  4800000,  5000000,  4, NOW(), NOW(), false),
(11, 'CHILD',  3000000,  3200000,  4, NOW(), NOW(), false),
(12, 'INFANT',  900000,  1000000,  4, NOW(), NOW(), false),
-- Dep 5 (Tour 3 - HCM-PQ)
(13, 'ADULT',  8500000, 10000000,  5, NOW(), NOW(), false),
(14, 'CHILD',  5500000,  6500000,  5, NOW(), NOW(), false),
(15, 'INFANT', 1000000,  1500000,  5, NOW(), NOW(), false),
-- Dep 6 (Tour 3)
(16, 'ADULT',  9000000, 10000000,  6, NOW(), NOW(), false),
(17, 'CHILD',  5800000,  6500000,  6, NOW(), NOW(), false),
(18, 'INFANT', 1200000,  1500000,  6, NOW(), NOW(), false),
-- Dep 7 (Tour 4 - HCM-VT)
(19, 'ADULT',  1500000,  1800000,  7, NOW(), NOW(), false),
(20, 'CHILD',  1000000,  1200000,  7, NOW(), NOW(), false),
(21, 'INFANT',  300000,   500000,  7, NOW(), NOW(), false),
-- Dep 8 (Tour 4)
(22, 'ADULT',  1600000,  1800000,  8, NOW(), NOW(), false),
(23, 'CHILD',  1100000,  1200000,  8, NOW(), NOW(), false),
(24, 'INFANT',  350000,   500000,  8, NOW(), NOW(), false),
-- Dep 9 (Tour 5 - DN-HAN)
(25, 'ADULT',  6500000,  7500000,  9, NOW(), NOW(), false),
(26, 'CHILD',  4000000,  5000000,  9, NOW(), NOW(), false),
(27, 'INFANT',  800000,  1000000,  9, NOW(), NOW(), false),
-- Dep 10 (Tour 5)
(28, 'ADULT',  7000000,  7500000, 10, NOW(), NOW(), false),
(29, 'CHILD',  4500000,  5000000, 10, NOW(), NOW(), false),
(30, 'INFANT',  900000,  1000000, 10, NOW(), NOW(), false),
-- Dep 11 (Tour 6 - HN-NT)
(31, 'ADULT',  7500000,  8500000, 11, NOW(), NOW(), false),
(32, 'CHILD',  4800000,  5500000, 11, NOW(), NOW(), false),
(33, 'INFANT', 1000000,  1200000, 11, NOW(), NOW(), false),
-- Dep 12 (Tour 6)
(34, 'ADULT',  8000000,  8500000, 12, NOW(), NOW(), false),
(35, 'CHILD',  5000000,  5500000, 12, NOW(), NOW(), false),
(36, 'INFANT', 1100000,  1200000, 12, NOW(), NOW(), false),
-- Dep 13 (Tour 7 - HCM-CT)
(37, 'ADULT',  2200000,  2600000, 13, NOW(), NOW(), false),
(38, 'CHILD',  1500000,  1800000, 13, NOW(), NOW(), false),
(39, 'INFANT',  500000,   700000, 13, NOW(), NOW(), false),
-- Dep 14 (Tour 8 - HN-HP)
(40, 'ADULT',  1900000,  2200000, 14, NOW(), NOW(), false),
(41, 'CHILD',  1200000,  1500000, 14, NOW(), NOW(), false),
(42, 'INFANT',  400000,   600000, 14, NOW(), NOW(), false),
-- Dep 15 (Tour 9 - DN-HCM)
(43, 'ADULT',  6000000,  7000000, 15, NOW(), NOW(), false),
(44, 'CHILD',  4000000,  4800000, 15, NOW(), NOW(), false),
(45, 'INFANT',  700000,   900000, 15, NOW(), NOW(), false);

SELECT setval('departure_pricings_pricingid_seq', (SELECT MAX(pricingid) FROM departure_pricings));

-- ============================================================
-- DEPARTURE TRANSPORTS
-- Actual DB columns: transportid, transport_type (OUTBOUND|INBOUND after ALTER),
--   vehicle_type (PLANE|BUS|TRAIN|SHIP|CAR after ALTER),
--   departure_location, arrival_location, departure_time (varchar), arrival_time (varchar),
--   transport_code, note, departure_id
-- ============================================================
INSERT INTO departure_transports (
    transportid, transport_type, vehicle_type, transport_code,
    departure_location, arrival_location, departure_time, arrival_time,
    departure_id, created_at, updated_at, is_deleted
) VALUES
-- Dep 1 (Tour 1 HN->HL, xe khách)
(1,  'OUTBOUND', 'BUS',   'BUS-DEP1-OUT', 'Hà Nội (Bến xe Mỹ Đình)', 'Cảng Tuần Châu, Hạ Long', '2025-03-10 07:00', '2025-03-10 11:00', 1, NOW(), NOW(), false),
(2,  'INBOUND',  'BUS',   'BUS-DEP1-IN',  'Cảng Tuần Châu, Hạ Long', 'Hà Nội (Bến xe Mỹ Đình)', '2025-03-12 15:00', '2025-03-12 19:00', 1, NOW(), NOW(), false),
-- Dep 2 (Tour 1)
(3,  'OUTBOUND', 'BUS',   'BUS-DEP2-OUT', 'Hà Nội (Bến xe Mỹ Đình)', 'Cảng Tuần Châu, Hạ Long', '2025-04-15 07:00', '2025-04-15 11:00', 2, NOW(), NOW(), false),
(4,  'INBOUND',  'BUS',   'BUS-DEP2-IN',  'Cảng Tuần Châu, Hạ Long', 'Hà Nội (Bến xe Mỹ Đình)', '2025-04-17 15:00', '2025-04-17 19:00', 2, NOW(), NOW(), false),
-- Dep 3 (Tour 2 HN->SA, tàu hỏa)
(5,  'OUTBOUND', 'TRAIN', 'TRN-DEP3-OUT', 'Ga Hà Nội', 'Ga Lào Cai', '2025-03-22 06:00', '2025-03-22 14:30', 3, NOW(), NOW(), false),
(6,  'INBOUND',  'TRAIN', 'TRN-DEP3-IN',  'Ga Lào Cai', 'Ga Hà Nội', '2025-03-25 20:00', '2025-03-26 05:00', 3, NOW(), NOW(), false),
-- Dep 4 (Tour 2)
(7,  'OUTBOUND', 'TRAIN', 'TRN-DEP4-OUT', 'Ga Hà Nội', 'Ga Lào Cai', '2025-05-01 06:00', '2025-05-01 14:30', 4, NOW(), NOW(), false),
(8,  'INBOUND',  'TRAIN', 'TRN-DEP4-IN',  'Ga Lào Cai', 'Ga Hà Nội', '2025-05-04 20:00', '2025-05-05 05:00', 4, NOW(), NOW(), false),
-- Dep 5 (Tour 3 HCM->PQ, máy bay)
(9,  'OUTBOUND', 'PLANE', 'PLN-DEP5-OUT', 'Sân bay Tân Sơn Nhất (SGN)', 'Sân bay Phú Quốc (PQC)', '2025-02-14 08:00', '2025-02-14 09:10', 5, NOW(), NOW(), false),
(10, 'INBOUND',  'PLANE', 'PLN-DEP5-IN',  'Sân bay Phú Quốc (PQC)', 'Sân bay Tân Sơn Nhất (SGN)', '2025-02-18 16:00', '2025-02-18 17:10', 5, NOW(), NOW(), false),
-- Dep 6 (Tour 3)
(11, 'OUTBOUND', 'PLANE', 'PLN-DEP6-OUT', 'Sân bay Tân Sơn Nhất (SGN)', 'Sân bay Phú Quốc (PQC)', '2025-04-20 08:00', '2025-04-20 09:10', 6, NOW(), NOW(), false),
(12, 'INBOUND',  'PLANE', 'PLN-DEP6-IN',  'Sân bay Phú Quốc (PQC)', 'Sân bay Tân Sơn Nhất (SGN)', '2025-04-24 17:00', '2025-04-24 18:10', 6, NOW(), NOW(), false),
-- Dep 7 (Tour 4 HCM->VT, xe)
(13, 'OUTBOUND', 'CAR',   'CAR-DEP7-OUT', 'TP. Hồ Chí Minh (Bến xe Miền Đông)', 'Trung tâm Vũng Tàu', '2025-03-05 07:30', '2025-03-05 10:00', 7, NOW(), NOW(), false),
(14, 'INBOUND',  'CAR',   'CAR-DEP7-IN',  'Trung tâm Vũng Tàu', 'TP. Hồ Chí Minh (Bến xe Miền Đông)', '2025-03-06 16:00', '2025-03-06 18:30', 7, NOW(), NOW(), false),
-- Dep 8 (Tour 4)
(15, 'OUTBOUND', 'CAR',   'CAR-DEP8-OUT', 'TP. Hồ Chí Minh (Bến xe Miền Đông)', 'Trung tâm Vũng Tàu', '2025-03-20 07:30', '2025-03-20 10:00', 8, NOW(), NOW(), false),
(16, 'INBOUND',  'CAR',   'CAR-DEP8-IN',  'Trung tâm Vũng Tàu', 'TP. Hồ Chí Minh (Bến xe Miền Đông)', '2025-03-21 16:00', '2025-03-21 18:30', 8, NOW(), NOW(), false),
-- Dep 9 (Tour 5 HCM->DN, máy bay)
(17, 'OUTBOUND', 'PLANE', 'PLN-DEP9-OUT', 'Sân bay Tân Sơn Nhất (SGN)', 'Sân bay Đà Nẵng (DAD)', '2025-04-01 06:30', '2025-04-01 07:50', 9, NOW(), NOW(), false),
(18, 'INBOUND',  'PLANE', 'PLN-DEP9-IN',  'Sân bay Phú Bài (HUI)', 'Sân bay Tân Sơn Nhất (SGN)', '2025-04-04 18:00', '2025-04-04 19:20', 9, NOW(), NOW(), false),
-- Dep 10 (Tour 5)
(19, 'OUTBOUND', 'PLANE', 'PLN-DEP10-OUT', 'Sân bay Tân Sơn Nhất (SGN)', 'Sân bay Đà Nẵng (DAD)', '2025-05-15 06:30', '2025-05-15 07:50', 10, NOW(), NOW(), false),
(20, 'INBOUND',  'PLANE', 'PLN-DEP10-IN',  'Sân bay Phú Bài (HUI)', 'Sân bay Tân Sơn Nhất (SGN)', '2025-05-18 19:00', '2025-05-18 20:20', 10, NOW(), NOW(), false),
-- Dep 11 (Tour 6 HN->NT, máy bay)
(21, 'OUTBOUND', 'PLANE', 'PLN-DEP11-OUT', 'Sân bay Nội Bài (HAN)', 'Sân bay Cam Ranh (CXR)', '2025-04-10 05:00', '2025-04-10 06:50', 11, NOW(), NOW(), false),
(22, 'INBOUND',  'PLANE', 'PLN-DEP11-IN',  'Sân bay Cam Ranh (CXR)', 'Sân bay Nội Bài (HAN)', '2025-04-14 19:00', '2025-04-14 21:00', 11, NOW(), NOW(), false),
-- Dep 12 (Tour 6)
(23, 'OUTBOUND', 'PLANE', 'PLN-DEP12-OUT', 'Sân bay Nội Bài (HAN)', 'Sân bay Cam Ranh (CXR)', '2025-06-20 05:00', '2025-06-20 06:50', 12, NOW(), NOW(), false),
(24, 'INBOUND',  'PLANE', 'PLN-DEP12-IN',  'Sân bay Cam Ranh (CXR)', 'Sân bay Nội Bài (HAN)', '2025-06-24 19:00', '2025-06-24 21:00', 12, NOW(), NOW(), false),
-- Dep 13 (Tour 7 HCM->CT, xe khách)
(25, 'OUTBOUND', 'BUS',   'BUS-DEP13-OUT', 'TP. Hồ Chí Minh (Bến xe Miền Tây)', 'Bến xe Cần Thơ', '2025-03-15 07:00', '2025-03-15 10:30', 13, NOW(), NOW(), false),
(26, 'INBOUND',  'BUS',   'BUS-DEP13-IN',  'Bến xe Cần Thơ', 'TP. Hồ Chí Minh (Bến xe Miền Tây)', '2025-03-17 15:00', '2025-03-17 18:30', 13, NOW(), NOW(), false),
-- Dep 14 (Tour 8 HN->HP)
(27, 'OUTBOUND', 'BUS',   'BUS-DEP14-OUT', 'Hà Nội (Bến xe Gia Lâm)', 'Cảng Đình Vũ, Hải Phòng', '2025-04-25 07:00', '2025-04-25 09:30', 14, NOW(), NOW(), false),
(28, 'INBOUND',  'BUS',   'BUS-DEP14-IN',  'Cảng Đình Vũ, Hải Phòng', 'Hà Nội (Bến xe Gia Lâm)', '2025-04-26 17:00', '2025-04-26 19:30', 14, NOW(), NOW(), false),
-- Dep 15 (Tour 9 DN->HCM, máy bay)
(29, 'OUTBOUND', 'PLANE', 'PLN-DEP15-OUT', 'Sân bay Đà Nẵng (DAD)', 'Sân bay Tân Sơn Nhất (SGN)', '2025-05-10 09:00', '2025-05-10 10:10', 15, NOW(), NOW(), false),
(30, 'INBOUND',  'PLANE', 'PLN-DEP15-IN',  'Sân bay Tân Sơn Nhất (SGN)', 'Sân bay Đà Nẵng (DAD)', '2025-05-13 19:30', '2025-05-13 20:40', 15, NOW(), NOW(), false);

SELECT setval('departure_transports_transportid_seq', (SELECT MAX(transportid) FROM departure_transports));