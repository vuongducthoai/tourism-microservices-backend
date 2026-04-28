-- ============================================================
-- NOTIFICATION DB SEED DATA
-- Database: notification_db
-- Enum: NotificationType (from notification-service)
--   BOOKING_CREATED | BOOKING_CONFIRMED | BOOKING_CANCELLED |
--   BOOKING_REFUND_REQUESTED | BOOKING_REFUNDED |
--   PAYMENT_SUCCESS | PAYMENT_FAILED | PAYMENT_REMINDER |
--   TOUR_DEPARTURE_REMINDER | TOUR_UPDATED |
--   COUPON_NEW | COUPON_EXPIRING |
--   POST_LIKED | POST_COMMENTED | POST_BOOKMARKED | NEW_FOLLOWER |
--   SYSTEM_ANNOUNCEMENT | WELCOME
-- user_id references iam_db.users (1-8)
-- ============================================================


TRUNCATE TABLE user_notifications RESTART IDENTITY CASCADE;
TRUNCATE TABLE notifications RESTART IDENTITY CASCADE;

-- ============================================================
-- NOTIFICATIONS
-- user_id: NULL for system-wide, specific user for user-targeted
-- ============================================================
INSERT INTO notifications (
    notificationid, user_id, type, title, message, metadata,
    created_at, updated_at, is_deleted
) VALUES
-- System-wide notifications (user_id = NULL)
(1,  NULL, 'SYSTEM_ANNOUNCEMENT',
 'Chào mừng đến với VietOur!',
 'VietOur - Nền tảng du lịch Việt Nam hàng đầu. Khám phá hàng trăm tour hấp dẫn khắp ba miền Bắc - Trung - Nam!',
 '{"action_url": "/tours", "icon": "welcome"}',
 '2025-01-01 08:00:00', NOW(), false),

(2,  NULL, 'SYSTEM_ANNOUNCEMENT',
 'Ưu đãi Mùa Hè 2025 - Giảm đến 20%!',
 'VietOur tung ưu đãi hè 2025 cực hấp dẫn! Sử dụng mã SUMMER20 giảm 20% cho mọi tour từ 1 triệu trở lên. Áp dụng đến 31/08/2025.',
 '{"coupon_code": "SUMMER20", "action_url": "/promotions", "icon": "promotion"}',
 '2025-06-01 08:00:00', NOW(), false),

(3,  NULL, 'COUPON_NEW',
 'Mã giảm giá mới: WELCOME10',
 'Chào mừng khách hàng mới! Sử dụng mã WELCOME10 để giảm 10% cho đơn hàng đầu tiên.',
 '{"coupon_code": "WELCOME10", "discount": "10%", "action_url": "/profile/coupons", "icon": "coupon"}',
 '2025-01-01 09:00:00', NOW(), false),

-- User-specific: User 3 (Lê Văn Khách) - Booking 1 (HN-HL reviewed)
(4,  3, 'BOOKING_CREATED',
 'Đặt tour thành công - BK20250101',
 'Tour Hà Nội - Hạ Long ngày 10/03/2025 đã được đặt thành công. Mã đặt tour: BK20250101. Vui lòng thanh toán trong 24 giờ.',
 '{"booking_id": 1, "booking_code": "BK20250101", "action_url": "/bookings/1", "icon": "booking"}',
 '2025-02-15 10:05:00', NOW(), false),

(5,  3, 'PAYMENT_SUCCESS',
 'Thanh toán thành công - BK20250101',
 'Thanh toán 5,600,000 VNĐ cho tour Hà Nội - Hạ Long (BK20250101) đã xác nhận. Chúc bạn có chuyến đi vui vẻ!',
 '{"booking_id": 1, "booking_code": "BK20250101", "amount": 5600000, "action_url": "/bookings/1", "icon": "payment"}',
 '2025-02-15 10:35:00', NOW(), false),

(6,  3, 'BOOKING_CONFIRMED',
 'Tour của bạn đã được xác nhận - BK20250101',
 'Tour Hà Nội - Hạ Long ngày 10/03/2025 (BK20250101) đã được xác nhận. HDV: Nguyễn Văn Hùng sẽ liên hệ trước 3 ngày.',
 '{"booking_id": 1, "booking_code": "BK20250101", "departure_date": "2025-03-10", "action_url": "/bookings/1", "icon": "confirm"}',
 '2025-02-16 09:00:00', NOW(), false),

(7,  3, 'TOUR_DEPARTURE_REMINDER',
 'Nhắc nhở: Tour khởi hành sau 3 ngày!',
 'Tour Hà Nội - Hạ Long (BK20250101) sẽ khởi hành vào 10/03/2025. HDV: Nguyễn Văn Hùng - 0912111001. Chuẩn bị hành lý!',
 '{"booking_id": 1, "departure_date": "2025-03-10", "guide_phone": "0912111001", "action_url": "/bookings/1", "icon": "reminder"}',
 '2025-03-07 08:00:00', NOW(), false),

-- User 3 - Booking 6 (HN-NT cancelled)
(8,  3, 'BOOKING_CANCELLED',
 'Tour đã bị hủy - BK20250106',
 'Đơn đặt tour BK20250106 (HN - Nha Trang) đã được hủy. Hoàn 50% = 7,500,000 VNĐ sẽ xử lý trong 3-5 ngày.',
 '{"booking_id": 6, "booking_code": "BK20250106", "refund_amount": 7500000, "action_url": "/bookings/6", "icon": "cancel"}',
 '2025-03-06 09:05:00', NOW(), false),

-- User 4 (Phạm Thị Mai) - Booking 2 (HN-SA pending review)
(9,  4, 'BOOKING_CREATED',
 'Đặt tour thành công - BK20250102',
 'Tour Hà Nội - Sa Pa ngày 22/03/2025 đã đặt thành công. Mã: BK20250102.',
 '{"booking_id": 2, "booking_code": "BK20250102", "action_url": "/bookings/2", "icon": "booking"}',
 '2025-02-20 14:05:00', NOW(), false),

(10, 4, 'PAYMENT_SUCCESS',
 'Thanh toán thành công - BK20250102',
 'Thanh toán 4,050,000 VNĐ cho tour HN-SA Pa (BK20250102) đã xác nhận.',
 '{"booking_id": 2, "booking_code": "BK20250102", "amount": 4050000, "action_url": "/bookings/2", "icon": "payment"}',
 '2025-02-20 14:35:00', NOW(), false),

-- User 5 (Hoàng Minh Tuấn) - Booking 3 (HCM-PQ pending confirmation)
(11, 5, 'BOOKING_CREATED',
 'Đặt tour thành công - BK20250103',
 'Tour TP. HCM - Phú Quốc ngày 14/02/2025 đã đặt thành công. Mã: BK20250103. Vui lòng thanh toán.',
 '{"booking_id": 3, "booking_code": "BK20250103", "action_url": "/bookings/3", "icon": "booking"}',
 '2025-01-25 09:05:00', NOW(), false),

(12, 5, 'PAYMENT_REMINDER',
 'Nhắc nhở: Đơn đặt tour chưa thanh toán!',
 'Đơn đặt tour BK20250103 (HCM - Phú Quốc) sẽ hết hạn trong 2 giờ. Vui lòng thanh toán ngay để giữ chỗ!',
 '{"booking_id": 3, "booking_code": "BK20250103", "action_url": "/bookings/3/payment", "icon": "warning"}',
 '2025-01-26 07:00:00', NOW(), false),

-- User 6 (Nguyễn Thị Hương) - Booking 4 (HCM-VT reviewed)
(13, 6, 'BOOKING_CONFIRMED',
 'Tour của bạn đã xác nhận - BK20250104',
 'Tour TP. HCM - Vũng Tàu ngày 05/03/2025 (BK20250104) đã xác nhận. Chúc bạn vui!',
 '{"booking_id": 4, "booking_code": "BK20250104", "action_url": "/bookings/4", "icon": "confirm"}',
 '2025-02-02 09:00:00', NOW(), false),

-- User 6 - Booking 8 (HCM-PQ pending refund)
(14, 6, 'BOOKING_REFUND_REQUESTED',
 'Yêu cầu hoàn tiền đã nhận - BK20250108',
 'Yêu cầu hoàn tiền cho BK20250108 (HCM - Phú Quốc) đã nhận. Sẽ xử lý trong 3-7 ngày làm việc.',
 '{"booking_id": 8, "booking_code": "BK20250108", "refund_amount": 9000000, "action_url": "/bookings/8", "icon": "refund"}',
 '2025-03-12 09:05:00', NOW(), false),

-- User 7 (Đặng Quốc Bảo) - Booking 5 (DN-HAN pending payment)
(15, 7, 'BOOKING_CREATED',
 'Đặt tour thành công - BK20250105',
 'Tour Đà Nẵng - Huế ngày 01/04/2025 đã đặt thành công. Mã: BK20250105. Vui lòng thanh toán trong 24 giờ.',
 '{"booking_id": 5, "booking_code": "BK20250105", "action_url": "/bookings/5", "icon": "booking"}',
 '2025-03-01 16:05:00', NOW(), false),

-- User 8 (Vũ Thị Lan) - Booking 7 (HCM-CT paid)
(16, 8, 'BOOKING_CREATED',
 'Đặt tour thành công - BK20250107',
 'Tour TP. HCM - Cần Thơ ngày 15/03/2025 đã đặt thành công. Mã: BK20250107.',
 '{"booking_id": 7, "booking_code": "BK20250107", "action_url": "/bookings/7", "icon": "booking"}',
 '2025-02-28 13:05:00', NOW(), false),

(17, 8, 'PAYMENT_SUCCESS',
 'Thanh toán thành công - BK20250107',
 'Thanh toán 3,740,000 VNĐ cho tour HCM - Cần Thơ (BK20250107) đã xác nhận.',
 '{"booking_id": 7, "booking_code": "BK20250107", "amount": 3740000, "action_url": "/bookings/7", "icon": "payment"}',
 '2025-02-28 13:35:00', NOW(), false),

-- Forum interactions
(18, 3, 'POST_LIKED',
 'Bài viết của bạn được thích!',
 'Phạm Thị Mai đã thích bài viết "Kinh nghiệm tour Hạ Long 3N2Đ" của bạn.',
 '{"post_id": 1, "liker_name": "Phạm Thị Mai", "action_url": "/forum/posts/1", "icon": "like"}',
 '2025-03-20 10:00:00', NOW(), false),

(19, 3, 'POST_COMMENTED',
 'Bình luận mới trên bài viết của bạn',
 'Phạm Thị Mai đã bình luận: "Bài viết rất hay! Mình sắp đi Hạ Long rồi!"',
 '{"post_id": 1, "commenter_name": "Phạm Thị Mai", "action_url": "/forum/posts/1", "icon": "comment"}',
 '2025-03-20 10:30:00', NOW(), false),

(20, NULL, 'COUPON_EXPIRING',
 'Mã giảm giá SUMMER20 sắp hết hạn!',
 'Mã SUMMER20 (giảm 20%) sẽ hết hạn vào 31/08/2025. Đặt tour ngay để không bỏ lỡ!',
 '{"coupon_code": "SUMMER20", "expires_at": "2025-08-31", "action_url": "/tours", "icon": "coupon_expiring"}',
 '2025-08-24 08:00:00', NOW(), false);

SELECT setval('notifications_notificationid_seq', (SELECT MAX(notificationid) FROM notifications));

-- ============================================================
-- USER NOTIFICATIONS (gán notification cho từng user)
-- ============================================================
INSERT INTO user_notifications (
    user_notificationid, user_id, notification_id,
    is_read, is_seen, created_at, updated_at, is_deleted
) VALUES
-- User 3 (Lê Văn Khách)
(1,  3, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(2,  3, 3,  true,  true,  '2025-01-01 09:00:00', NOW(), false),
(3,  3, 4,  true,  true,  '2025-02-15 10:05:00', NOW(), false),
(4,  3, 5,  true,  true,  '2025-02-15 10:35:00', NOW(), false),
(5,  3, 6,  true,  true,  '2025-02-16 09:00:00', NOW(), false),
(6,  3, 7,  true,  true,  '2025-03-07 08:00:00', NOW(), false),
(7,  3, 8,  true,  true,  '2025-03-06 09:05:00', NOW(), false),
(8,  3, 18, true,  true,  '2025-03-20 10:00:00', NOW(), false),
(9,  3, 19, false, true,  '2025-03-20 10:30:00', NOW(), false),

-- User 4 (Phạm Thị Mai)
(10, 4, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(11, 4, 3,  true,  true,  '2025-01-01 09:00:00', NOW(), false),
(12, 4, 9,  true,  true,  '2025-02-20 14:05:00', NOW(), false),
(13, 4, 10, true,  true,  '2025-02-20 14:35:00', NOW(), false),

-- User 5 (Hoàng Minh Tuấn)
(14, 5, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(15, 5, 11, true,  true,  '2025-01-25 09:05:00', NOW(), false),
(16, 5, 12, false, true,  '2025-01-26 07:00:00', NOW(), false),

-- User 6 (Nguyễn Thị Hương)
(17, 6, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(18, 6, 13, true,  true,  '2025-02-02 09:00:00', NOW(), false),
(19, 6, 14, false, true,  '2025-03-12 09:05:00', NOW(), false),

-- User 7 (Đặng Quốc Bảo)
(20, 7, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(21, 7, 15, false, true,  '2025-03-01 16:05:00', NOW(), false),

-- User 8 (Vũ Thị Lan)
(22, 8, 1,  true,  true,  '2025-01-01 08:00:00', NOW(), false),
(23, 8, 16, true,  true,  '2025-02-28 13:05:00', NOW(), false),
(24, 8, 17, true,  true,  '2025-02-28 13:35:00', NOW(), false),

-- All users: system announcement (2) and coupon expiring (20)
(25, 3, 2,  true,  true,  '2025-06-01 08:00:00', NOW(), false),
(26, 4, 2,  false, true,  '2025-06-01 08:00:00', NOW(), false),
(27, 5, 2,  false, false, '2025-06-01 08:00:00', NOW(), false),
(28, 6, 2,  false, false, '2025-06-01 08:00:00', NOW(), false),
(29, 7, 2,  false, false, '2025-06-01 08:00:00', NOW(), false),
(30, 8, 2,  false, false, '2025-06-01 08:00:00', NOW(), false),
(31, 3, 20, false, false, '2025-08-24 08:00:00', NOW(), false),
(32, 4, 20, false, false, '2025-08-24 08:00:00', NOW(), false);

SELECT setval('user_notifications_user_notificationid_seq', (SELECT MAX(user_notificationid) FROM user_notifications));