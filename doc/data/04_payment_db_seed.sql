-- ============================================================
-- PAYMENT DB SEED DATA
-- Database: payment_db
-- Enums:
--   PaymentMethod = VNPAY | PAYOS  (NO SEPAY!)
--   PaymentStatus = PENDING | SUCCESS | FAILED | REFUNDED  (NO COMPLETED!)
-- Hibernate naming: paymentID -> paymentid
-- Column: bank (NOT bank_name!)
-- ============================================================


-- Fix check constraints (schema created from old code)
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_payment_method_check;
ALTER TABLE payments ADD CONSTRAINT payments_payment_method_check
    CHECK (payment_method IN ('VNPAY', 'PAYOS'));

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED'));

TRUNCATE TABLE payments RESTART IDENTITY CASCADE;

-- Payments correspond to bookings in booking_db
-- booking_id references are cross-service (no FK enforcement)
INSERT INTO payments (
    paymentid, payment_method, amount, transaction_id, bank_transaction_no,
    bank_code, payment_date, account_name, account_number, bank_name,
    status, time_limit, payment_description, transaction_datetime,
    booking_id, created_at, updated_at, is_deleted
) VALUES
-- Payment for Booking 1 (REVIEWED - SUCCESS)
(1, 'VNPAY', 5600000,
 'VNPAY-TXN-001', 'BANK-TXN-001', 'VCB',
 '2025-02-15 10:30:00',
 NULL, NULL, 'Vietcombank',
 'SUCCESS', '2025-02-15 10:15:00',
 'Thanh toán tour HN-HL - Booking BK20250101',
 '2025-02-15 10:30:00',
 1, NOW(), NOW(), false),

-- Payment for Booking 2 (PENDING_REVIEW - SUCCESS)
(2, 'VNPAY', 4050000,
 'VNPAY-TXN-002', 'BANK-TXN-002', 'TCB',
 '2025-02-20 14:30:00',
 NULL, NULL, 'Techcombank',
 'SUCCESS', '2025-02-20 14:15:00',
 'Thanh toán tour HN-SA - Booking BK20250102',
 '2025-02-20 14:30:00',
 2, NOW(), NOW(), false),

-- Payment for Booking 3 (PENDING_CONFIRMATION - PENDING)
(3, 'PAYOS', 16150000,
 'PAYOS-TXN-003', NULL, NULL,
 NULL,
 NULL, NULL, NULL,
 'PENDING', '2025-01-26 09:00:00',
 'Thanh toán tour HCM-PQ - Booking BK20250103',
 NULL,
 3, NOW(), NOW(), false),

-- Payment for Booking 4 (REVIEWED - SUCCESS)
(4, 'VNPAY', 2800000,
 'VNPAY-TXN-004', 'BANK-TXN-004', 'ACB',
 '2025-02-01 11:30:00',
 NULL, NULL, 'ACB',
 'SUCCESS', '2025-02-01 11:15:00',
 'Thanh toán tour HCM-VT - Booking BK20250104',
 '2025-02-01 11:30:00',
 4, NOW(), NOW(), false),

-- Payment for Booking 5 (PENDING_PAYMENT - PENDING)
(5, 'PAYOS', 6500000,
 'PAYOS-TXN-005', NULL, NULL,
 NULL,
 NULL, NULL, NULL,
 'PENDING', '2025-03-02 16:00:00',
 'Thanh toán tour DN-HAN - Booking BK20250105',
 NULL,
 5, NOW(), NOW(), false),

-- Payment for Booking 6 (CANCELLED - REFUNDED)
(6, 'VNPAY', 15000000,
 'VNPAY-TXN-006', 'BANK-TXN-006', 'VCB',
 '2025-03-05 08:30:00',
 NULL, NULL, 'Vietcombank',
 'REFUNDED', '2025-03-05 08:15:00',
 'Thanh toán tour HN-NT - Booking BK20250106 (đã hoàn)',
 '2025-03-05 08:30:00',
 6, NOW(), NOW(), false),

-- Payment for Booking 7 (PAID - SUCCESS)
(7, 'VNPAY', 3740000,
 'VNPAY-TXN-007', 'BANK-TXN-007', 'BIDV',
 '2025-02-28 13:30:00',
 NULL, NULL, 'BIDV',
 'SUCCESS', '2025-02-28 13:15:00',
 'Thanh toán tour HCM-CT - Booking BK20250107',
 '2025-02-28 13:30:00',
 7, NOW(), NOW(), false),

-- Payment for Booking 8 (PENDING_REFUND - SUCCESS, chờ hoàn tiền)
(8, 'PAYOS', 18000000,
 'PAYOS-TXN-008', 'PAYOS-BANK-008', NULL,
 '2025-03-10 10:30:00',
 'Nguyen Thi Huong', '9876543210', 'BIDV',
 'SUCCESS', '2025-03-10 10:15:00',
 'Thanh toán tour HCM-PQ - Booking BK20250108 (chờ hoàn)',
 '2025-03-10 10:30:00',
 8, NOW(), NOW(), false);

SELECT setval('payments_paymentid_seq', (SELECT MAX(paymentid) FROM payments));