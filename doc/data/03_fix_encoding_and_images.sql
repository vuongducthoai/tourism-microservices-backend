-- =============================================================
-- FIX UTF-8 ENCODING + ADD REAL IMAGE URLS
-- Run via: docker exec -i tourism-postgres psql -U postgres -d tour_catalog_db
-- =============================================================
SET client_encoding = 'UTF8';

-- =============================================================
-- 1. FIX LOCATIONS: correct Vietnamese text + real image URLs
-- =============================================================
UPDATE locations SET
  name        = 'Hà Nội',
  description = 'Thủ đô ngàn năm văn hiến với hồ Hoàn Kiếm, phố cổ 36 phường, Văn Miếu - Quốc Tử Giám và ẩm thực đường phố phong phú.',
  image       = 'https://images.unsplash.com/photo-1509023464722-18d996393ca8?w=800&q=80'
WHERE locationid = 1;

UPDATE locations SET
  name        = 'Hải Phòng',
  description = 'Thành phố cảng lớn nhất miền Bắc, cửa ngõ biển với đảo Cát Bà hoang sơ, vịnh Lan Hạ thơ mộng và ẩm thực hải sản tươi ngon.',
  image       = 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800&q=80'
WHERE locationid = 2;

UPDATE locations SET
  name        = 'Hạ Long',
  description = 'Di sản thiên nhiên thế giới UNESCO với hàng nghìn đảo đá vôi hùng vĩ, hang động kỳ ảo và làn nước xanh biếc của vịnh Hạ Long.',
  image       = 'https://images.unsplash.com/photo-1528127269322-539801943592?w=800&q=80'
WHERE locationid = 3;

UPDATE locations SET
  name        = 'Sa Pa',
  description = 'Thị trấn mây mù trên đỉnh núi Tây Bắc, nơi có ruộng bậc thang hữu tình, đỉnh Fansipan cao nhất Đông Dương và văn hóa bản làng H''Mông.',
  image       = 'https://images.unsplash.com/photo-1570632267940-f8e98c7efea2?w=800&q=80'
WHERE locationid = 4;

UPDATE locations SET
  name        = 'Đà Nẵng',
  description = 'Thành phố biển năng động miền Trung với cầu Vàng Bà Nà Hills, bãi biển Mỹ Khê trong xanh và vị trí trung điểm di sản miền Trung.',
  image       = 'https://images.unsplash.com/photo-1559592413-7cec4d0cae2b?w=800&q=80'
WHERE locationid = 5;

UPDATE locations SET
  name        = 'Hội An',
  description = 'Phố cổ hơn 400 năm tuổi, Di sản Văn hóa Thế giới UNESCO với những con phố đèn lồng lung linh, hội họa nghệ thuật và ẩm thực độc đáo.',
  image       = 'https://images.unsplash.com/photo-1540611025311-01df3cef54b5?w=800&q=80'
WHERE locationid = 6;

UPDATE locations SET
  name        = 'Huế',
  description = 'Cố đô triều Nguyễn với Đại Nội hùng tráng, lăng tẩm vua chúa, nhã nhạc cung đình UNESCO và ẩm thực cung đình tinh tế.',
  image       = 'https://images.unsplash.com/photo-1574236170878-f9e2cee83bf1?w=800&q=80'
WHERE locationid = 7;

UPDATE locations SET
  name        = 'Nha Trang',
  description = 'Thành phố biển xinh đẹp Nam Trung Bộ với bãi biển Trần Phú trải dài, đảo san hô đa sắc, tháp Chàm cổ kính và hải sản phong phú.',
  image       = 'https://images.unsplash.com/photo-1553532434-5ab5b6b84993?w=800&q=80'
WHERE locationid = 8;

UPDATE locations SET
  name        = 'TP. Hồ Chí Minh',
  description = 'Trung tâm kinh tế lớn nhất Việt Nam với nhịp sống sôi động, kiến trúc Pháp thuộc đan xen hiện đại, ẩm thực đa dạng và mua sắm phong phú.',
  image       = 'https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=800&q=80'
WHERE locationid = 9;

UPDATE locations SET
  name        = 'Vũng Tàu',
  description = 'Thành phố biển gần Sài Gòn nhất với bãi Sau, bãi Trước êm đềm, hải đăng lịch sử, tượng Chúa Kitô và ẩm thực hải sản tươi sống.',
  image       = 'https://images.unsplash.com/photo-1544644181-1484b3fdfc32?w=800&q=80'
WHERE locationid = 10;

UPDATE locations SET
  name        = 'Phú Quốc',
  description = 'Đảo ngọc lớn nhất Việt Nam với bãi biển cát trắng mịn, nước biển trong xanh như ngọc, rừng nguyên sinh và hải sản tươi ngon bậc nhất.',
  image       = 'https://images.unsplash.com/photo-1551918120-9739cb430c6d?w=800&q=80'
WHERE locationid = 11;

UPDATE locations SET
  name        = 'Cần Thơ',
  description = 'Đô thị trung tâm đồng bằng sông Cửu Long với chợ nổi Cái Răng, vườn trái cây miệt vườn, sông nước hữu tình và ẩm thực Nam Bộ đặc sắc.',
  image       = 'https://images.unsplash.com/photo-1586280268958-9483002d016a?w=800&q=80'
WHERE locationid = 12;

-- =============================================================
-- 2. FIX TOURS: correct Vietnamese text for all 9 tours
-- =============================================================

-- Tour 1: Hà Nội - Hạ Long
UPDATE tours SET
  duration            = '3 Ngày 2 Đêm',
  tour_name           = 'Hà Nội - Hạ Long 3 Ngày 2 Đêm',
  attractions         = 'Vịnh Hạ Long, hang Sửng Sốt, đảo Titov, làng chài Cửa Vạn',
  meals               = 'Sáng: Phở/Bánh cuốn. Trưa: Hải sản. Tối: Buffet trên tàu',
  ideal_time          = 'Quanh năm, đẹp nhất tháng 3-5 và 9-11',
  transportation      = 'Xe khách giường nằm',
  suitable_customer   = 'Gia đình, cặp đôi, nhóm bạn',
  hotel               = 'Khách sạn 3 sao trên tàu du lịch',
  trip_transportation = 'Xe khách'
WHERE tourid = 1;

-- Tour 2: Hà Nội - Sa Pa
UPDATE tours SET
  duration            = '4 Ngày 3 Đêm',
  tour_name           = 'Hà Nội - Sa Pa 4 Ngày 3 Đêm',
  attractions         = 'Đỉnh Fansipan, thung lũng Mường Hoa, làng Cát Cát, chợ Bắc Hà',
  meals               = 'Ăn sáng buffet tại khách sạn, trưa và tối theo chương trình',
  ideal_time          = 'Tháng 3-5 (hoa đào, hoa mận), tháng 9-11 (lúa chín)',
  transportation      = 'Tàu hỏa + xe địa phương',
  suitable_customer   = 'Nhóm bạn trẻ, gia đình, người yêu thiên nhiên',
  hotel               = 'Khách sạn 4 sao tại Sa Pa',
  trip_transportation = 'Xe khách, tàu hỏa'
WHERE tourid = 2;

-- Tour 3: TP.HCM - Phú Quốc
UPDATE tours SET
  duration            = '5 Ngày 4 Đêm',
  tour_name           = 'TP. Hồ Chí Minh - Phú Quốc 5 Ngày 4 Đêm',
  attractions         = 'Vinpearl Safari, Grand World, Bãi Sao, Mũi Ông Đội, làng chài Hàm Ninh',
  meals               = 'Buffet sáng tại resort, hải sản tươi mỗi bữa',
  ideal_time          = 'Tháng 11 - tháng 4 (mùa khô)',
  transportation      = 'Máy bay',
  suitable_customer   = 'Gia đình, cặp đôi, người đi công tác kết hợp nghỉ dưỡng',
  hotel               = 'Resort 4-5 sao bãi biển',
  trip_transportation = 'Máy bay'
WHERE tourid = 3;

-- Tour 4: TP.HCM - Vũng Tàu
UPDATE tours SET
  duration            = '2 Ngày 1 Đêm',
  tour_name           = 'TP. Hồ Chí Minh - Vũng Tàu 2 Ngày 1 Đêm',
  attractions         = 'Bãi Sau, Bãi Dâu, Hải đăng Vũng Tàu, Thích Ca Phật Đài, núi Lớn',
  meals               = 'Hải sản tươi, bánh khọt, lẩu cá',
  ideal_time          = 'Quanh năm, tránh mùa mưa tháng 6-9',
  transportation      = 'Xe khách limousine',
  suitable_customer   = 'Gia đình, cặp đôi, đi ngắn ngày',
  hotel               = 'Khách sạn 3-4 sao mặt biển',
  trip_transportation = 'Xe khách'
WHERE tourid = 4;

-- Tour 5: Đà Nẵng - Hội An - Huế
UPDATE tours SET
  duration            = '4 Ngày 3 Đêm',
  tour_name           = 'Đà Nẵng - Hội An - Huế 4 Ngày 3 Đêm',
  attractions         = 'Bà Nà Hills, cầu Vàng, phố cổ Hội An, Đại Nội Huế, lăng Tự Đức, bãi biển Mỹ Khê',
  meals               = 'Ẩm thực miền Trung: Mì Quảng, Bánh mì Hội An, Bún bò Huế',
  ideal_time          = 'Tháng 3-8 (ít mưa, nắng đẹp)',
  transportation      = 'Máy bay + xe địa phương',
  suitable_customer   = 'Nhóm bạn, gia đình, du lịch văn hóa',
  hotel               = 'Khách sạn 4 sao tại Đà Nẵng',
  trip_transportation = 'Máy bay, xe khách'
WHERE tourid = 5;

-- Tour 6: Hà Nội - Nha Trang
UPDATE tours SET
  duration            = '5 Ngày 4 Đêm',
  tour_name           = 'Hà Nội - Nha Trang 5 Ngày 4 Đêm',
  attractions         = 'Vinpearl Land, đảo Hòn Mun, tháp Chàm Po Nagar, chợ Đầm, bãi biển Trần Phú',
  meals               = 'Buffet sáng, hải sản trưa và tối',
  ideal_time          = 'Tháng 1-8 (biển calme, ít mưa)',
  transportation      = 'Máy bay',
  suitable_customer   = 'Gia đình, cặp đôi, nhóm bạn',
  hotel               = 'Khách sạn 4 sao ven biển',
  trip_transportation = 'Máy bay'
WHERE tourid = 6;

-- Tour 7: TP.HCM - Cần Thơ
UPDATE tours SET
  duration            = '3 Ngày 2 Đêm',
  tour_name           = 'TP. Hồ Chí Minh - Cần Thơ 3 Ngày 2 Đêm',
  attractions         = 'Chợ nổi Cái Răng, vườn trái cây, làng du lịch Mỹ Khánh, cù lao Tân Lộc',
  meals               = 'Cơm miền Tây, bánh tét, hủ tiếu Nam Vang',
  ideal_time          = 'Quanh năm, lý tưởng tháng 12-4',
  transportation      = 'Xe khách + thuyền trên sông',
  suitable_customer   = 'Gia đình, người yêu ẩm thực, văn hóa sông nước',
  hotel               = 'Khách sạn 3-4 sao trung tâm Cần Thơ',
  trip_transportation = 'Xe khách, thuyền'
WHERE tourid = 7;

-- Tour 8: Hà Nội - Hải Phòng - Cát Bà
UPDATE tours SET
  duration            = '2 Ngày 1 Đêm',
  tour_name           = 'Hà Nội - Hải Phòng - Cát Bà 2 Ngày 1 Đêm',
  attractions         = 'Vịnh Lan Hạ, vườn quốc gia Cát Bà, hang Đầu Gỗ, bãi biển Cát Cò',
  meals               = 'Hải sản tươi, bánh đa cua, nem cua bể Hải Phòng',
  ideal_time          = 'Tháng 4-10 (biển đẹp, ít sóng)',
  transportation      = 'Xe khách + phà + thuyền',
  suitable_customer   = 'Nhóm bạn, gia đình thích khám phá',
  hotel               = 'Khách sạn 3 sao trên đảo Cát Bà',
  trip_transportation = 'Xe khách, phà'
WHERE tourid = 8;

-- Tour 9: Đà Nẵng - TP.HCM
UPDATE tours SET
  duration            = '4 Ngày 3 Đêm',
  tour_name           = 'Đà Nẵng - TP. Hồ Chí Minh 4 Ngày 3 Đêm',
  attractions         = 'Bán đảo Sơn Trà, Bảo tàng Chăm, Phố đi bộ Nguyễn Huệ, Chợ Bến Thành, địa đạo Củ Chi',
  meals               = 'Ẩm thực đặc sắc hai miền Nam - Trung',
  ideal_time          = 'Quanh năm',
  transportation      = 'Máy bay nội địa',
  suitable_customer   = 'Khách thương mại, nhóm bạn',
  hotel               = 'Khách sạn 4 sao tại từng điểm dừng',
  trip_transportation = 'Máy bay'
WHERE tourid = 9;

-- =============================================================
-- 3. INSERT TOUR IMAGES (1-2 ảnh mỗi tour)
-- =============================================================
INSERT INTO tour_images (image_url, tour_id, created_at, updated_at, is_deleted) VALUES

-- Tour 1: Hà Nội - Hạ Long
('https://images.unsplash.com/photo-1528127269322-539801943592?w=800&q=80', 1, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1509023464722-18d996393ca8?w=800&q=80', 1, NOW(), NOW(), false),

-- Tour 2: Hà Nội - Sa Pa
('https://images.unsplash.com/photo-1570632267940-f8e98c7efea2?w=800&q=80', 2, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1558642452-9d2a7deb7f62?w=800&q=80', 2, NOW(), NOW(), false),

-- Tour 3: TP.HCM - Phú Quốc
('https://images.unsplash.com/photo-1551918120-9739cb430c6d?w=800&q=80', 3, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1590073844006-33379778ae09?w=800&q=80', 3, NOW(), NOW(), false),

-- Tour 4: TP.HCM - Vũng Tàu
('https://images.unsplash.com/photo-1544644181-1484b3fdfc32?w=800&q=80', 4, NOW(), NOW(), false),

-- Tour 5: Đà Nẵng - Hội An - Huế
('https://images.unsplash.com/photo-1540611025311-01df3cef54b5?w=800&q=80', 5, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1559592413-7cec4d0cae2b?w=800&q=80', 5, NOW(), NOW(), false),

-- Tour 6: Hà Nội - Nha Trang
('https://images.unsplash.com/photo-1553532434-5ab5b6b84993?w=800&q=80', 6, NOW(), NOW(), false),

-- Tour 7: TP.HCM - Cần Thơ
('https://images.unsplash.com/photo-1586280268958-9483002d016a?w=800&q=80', 7, NOW(), NOW(), false),

-- Tour 8: Hà Nội - Hải Phòng - Cát Bà
('https://images.unsplash.com/photo-1528127269322-539801943592?w=800&q=80', 8, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800&q=80', 8, NOW(), NOW(), false),

-- Tour 9: Đà Nẵng - TP.HCM
('https://images.unsplash.com/photo-1559592413-7cec4d0cae2b?w=800&q=80', 9, NOW(), NOW(), false),
('https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=800&q=80', 9, NOW(), NOW(), false);

-- =============================================================
-- 4. FIX DEPARTURE TRANSPORTS: correct Vietnamese location names
-- =============================================================
UPDATE departure_transports SET
  departure_location = 'Hà Nội (Bến xe Mỹ Đình)',
  arrival_location   = 'Cảng Tuần Châu, Hạ Long'
WHERE transport_code IN ('BUS-DEP1-OUT', 'BUS-DEP2-OUT');

UPDATE departure_transports SET
  departure_location = 'Cảng Tuần Châu, Hạ Long',
  arrival_location   = 'Hà Nội (Bến xe Mỹ Đình)'
WHERE transport_code IN ('BUS-DEP1-IN', 'BUS-DEP2-IN');

UPDATE departure_transports SET
  departure_location = 'Ga Hà Nội',
  arrival_location   = 'Ga Lào Cai'
WHERE transport_code IN ('TRN-DEP3-OUT', 'TRN-DEP4-OUT');

UPDATE departure_transports SET
  departure_location = 'Ga Lào Cai',
  arrival_location   = 'Ga Hà Nội'
WHERE transport_code IN ('TRN-DEP3-IN', 'TRN-DEP4-IN');

UPDATE departure_transports SET
  departure_location = 'Sân bay Tân Sơn Nhất (SGN)',
  arrival_location   = 'Sân bay Phú Quốc (PQC)'
WHERE transport_code LIKE 'PLN-DEP5-OUT' OR transport_code LIKE 'PLN-DEP6-OUT';

UPDATE departure_transports SET
  departure_location = 'Sân bay Phú Quốc (PQC)',
  arrival_location   = 'Sân bay Tân Sơn Nhất (SGN)'
WHERE transport_code LIKE 'PLN-DEP5-IN' OR transport_code LIKE 'PLN-DEP6-IN';

-- =============================================================
-- 5. VERIFY
-- =============================================================
SELECT locationid, name, LEFT(image, 60) AS image_preview FROM locations ORDER BY locationid;
SELECT tourid, tour_name FROM tours ORDER BY tourid;
SELECT COUNT(*) AS tour_image_count FROM tour_images;
