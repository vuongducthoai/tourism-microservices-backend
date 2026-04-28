-- ============================================================
-- IAM DB SEED DATA
-- Database: iam_db
-- Enums: Role = ADMIN | CUSTOMER | TOUR_OWNER
-- Hibernate naming: userID -> userid, isEmailVerified -> isemailverified
-- ============================================================


-- Fix check constraints (schema created from old code)
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CUSTOMER', 'ADMIN', 'TOUR_OWNER'));

-- Truncate tables
TRUNCATE TABLE refresh_tokens RESTART IDENTITY CASCADE;
TRUNCATE TABLE users RESTART IDENTITY CASCADE;

-- ============================================================
-- USERS
-- password: BCrypt của "Password123!" (cost=10)
-- $2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe
-- ============================================================
INSERT INTO users (
    userid, full_name, phone, email, role, password, avatar, status,
    date_of_birth, coin_balance,
    province_code, province_name, district_code, district_name,
    is_email_verified, verification_token, verification_token_expiry,
    last_active_at, created_at, updated_at, is_deleted
) VALUES
(1, 'Nguyễn Văn Admin', '0901000001', 'admin@tourismvn.com', 'ADMIN',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar_admin.jpg', true,
 '1985-03-15', 0,
 '01', 'Hà Nội', '001', 'Hoàn Kiếm',
 true, NULL, NULL,
 '2025-01-10 08:00:00', '2024-01-01 00:00:00', '2025-01-10 08:00:00', false),

(2, 'Trần Thị Tour Owner', '0902000002', 'tourowner@tourismvn.com', 'TOUR_OWNER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar_owner.jpg', true,
 '1988-07-20', 150,
 '01', 'Hà Nội', '002', 'Đống Đa',
 true, NULL, NULL,
 '2025-01-10 09:00:00', '2024-01-01 00:00:00', '2025-01-10 09:00:00', false),

(3, 'Lê Văn Khách', '0903000003', 'customer1@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar1.jpg', true,
 '1992-05-10', 500,
 '79', 'TP. Hồ Chí Minh', '760', 'Quận 1',
 true, NULL, NULL,
 '2025-01-09 15:30:00', '2024-02-01 00:00:00', '2025-01-09 15:30:00', false),

(4, 'Phạm Thị Mai', '0904000004', 'customer2@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar2.jpg', true,
 '1995-11-22', 200,
 '48', 'Đà Nẵng', '490', 'Hải Châu',
 true, NULL, NULL,
 '2025-01-08 10:00:00', '2024-03-01 00:00:00', '2025-01-08 10:00:00', false),

(5, 'Hoàng Minh Tuấn', '0905000005', 'customer3@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 NULL, true,
 '1990-08-15', 0,
 '26', 'Hà Giang', NULL, NULL,
 false, 'verify-token-005', '2025-02-01 00:00:00',
 '2025-01-05 12:00:00', '2024-06-01 00:00:00', '2025-01-05 12:00:00', false),

(6, 'Nguyễn Thị Hương', '0906000006', 'customer4@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar4.jpg', true,
 '1998-02-28', 1000,
 '52', 'Khánh Hoà', '568', 'Nha Trang',
 true, NULL, NULL,
 '2025-01-10 07:00:00', '2024-04-01 00:00:00', '2025-01-10 07:00:00', false),

(7, 'Đặng Quốc Bảo', '0907000007', 'customer5@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 NULL, true,
 '1987-12-01', 300,
 '92', 'Cần Thơ', '916', 'Ninh Kiều',
 true, NULL, NULL,
 '2025-01-07 16:00:00', '2024-05-01 00:00:00', '2025-01-07 16:00:00', false),

(8, 'Vũ Thị Lan', '0908000008', 'customer6@gmail.com', 'CUSTOMER',
 '$2a$10$xJcDlbdlvPY1.kPbNRdNlOQ1hJdEoqLQLN5/GCgCJBLePqDaRRrKe',
 'https://res.cloudinary.com/demo/image/upload/avatar6.jpg', true,
 '1993-04-17', 0,
 '56', 'Lâm Đồng', '672', 'Đà Lạt',
 true, NULL, NULL,
 '2025-01-06 11:00:00', '2024-07-01 00:00:00', '2025-01-06 11:00:00', false);

-- Reset sequence
SELECT setval('users_userid_seq', (SELECT MAX(userid) FROM users));