# Kế hoạch triển khai chi tiết để duyệt
## Tính năng: Rút điểm tự động về tài khoản ngân hàng cá nhân

Ngày: 2026-05-30
Phạm vi code:

- Backend: D:/HK8/tourism-microservices-backend
- Frontend: D:/HK8/tourism_frontend

Trạng thái: Plan only, chưa code

## 1. Mục tiêu chính

1. User có thêm option rút điểm về tài khoản ngân hàng cá nhân từ header/profile.
2. Sau khi user submit yêu cầu rút điểm, hệ thống tự động chuyển khoản (không cần admin duyệt tay).
3. User xem được lịch sử rút điểm và trạng thái xử lý.
4. Admin chỉ giám sát và retry khi lỗi, không phải duyệt từng lệnh.

## 2. Phạm vi và không-phạm-vi (ràng buộc cứng)

### 2.1 Có trong phạm vi

1. Rút điểm tự động sang tài khoản cá nhân user.
2. API, scheduler, outbox, notification cho riêng luồng rút điểm.
3. UI user để tạo yêu cầu rút điểm + xem lịch sử.
4. UI admin để theo dõi trạng thái và xử lý retry lỗi.

### 2.2 Ngoài phạm vi (không làm)

1. Không thay đổi bất kỳ code nào của luồng hủy booking hiện tại.
2. Không thay đổi luồng refund booking thủ công của admin.
3. Không thêm endpoint auto-refund cho booking.
4. Không sửa logic cancel/refund cũ đang chạy.

## 3. Điều kiện bắt buộc không được vi phạm

1. Chỉ append vào hệ thống hiện tại, không xóa hoặc thay behavior cũ.
2. Mọi thao tác tài chính phải có idempotency key.
3. Trừ điểm ngay khi tạo yêu cầu rút điểm thành công.
4. Nếu chuyển khoản fail vĩnh viễn thì rollback điểm đầy đủ.
5. Không log token SePay và không lộ full số tài khoản.

## 4. Quy tắc nghiệp vụ

1. 1 điểm = 1.000 VND.
2. Mức rút tối thiểu: 5 điểm.
3. Không thu phí rút điểm ở phiên bản này.
4. User chỉ được rút <= số điểm hiện có.
5. Trạng thái giao dịch chuẩn:
   - PENDING
   - PROCESSING
   - COMPLETED
   - FAILED
   - MANUAL (fallback nếu bật)

## 5. Thiết kế backend chi tiết

## 5.1 booking-service: module mới cho coin withdrawal

### 5.1.1 Bảng mới

coin_withdrawals:

- id
- user_id
- coin_amount
- money_amount
- bank
- account_number
- account_name
- status
- transfer_ref
- operation_key
- retry_count
- error_source: IAM | RABBITMQ | NOTIFICATION | SEPAY | SYSTEM
- note
- created_at
- updated_at

Index đề xuất:

- idx_coin_withdrawals_user_created
- idx_coin_withdrawals_status
- idx_coin_withdrawals_operation_key

### 5.1.2 Thành phần code mới

1. Entity CoinWithdrawal
2. Repository CoinWithdrawalRepository
3. DTO:
   - CoinWithdrawalRequest
   - CoinWithdrawalResponse
   - CoinWithdrawalEventDTO
4. Service:
   - CoinWithdrawalService
   - CoinWithdrawalServiceImpl
5. Controller CoinWithdrawalController
6. Scheduler CoinWithdrawalRelayScheduler
7. Transfer strategy:
   - TransferService
   - SepayTransferServiceImpl (mặc định)
   - ManualTransferServiceImpl (fallback theo config)

### 5.1.3 API cho user

1. POST /api/coin-withdrawals
2. GET /api/coin-withdrawals/my-history

Luồng POST /api/coin-withdrawals:

1. Validate request và coinAmount >= 5.
2. Lấy userId từ token.
3. Check số dư qua IAM.
4. deductCoins(userId, coinAmount, operationKey).
5. Tạo record coin_withdrawals status=PENDING.
6. Ghi outbox event: booking.coin.withdrawal.event.
7. Commit transaction.
8. Trả response đã mask account number.

### 5.1.4 Scheduler xử lý chuyển khoản tự động

CoinWithdrawalRelayScheduler:

1. Claim outbox theo routing key booking.coin.withdrawal.event.
2. Set status=PROCESSING.
3. Gọi TransferService (SePay outbound theo config).
4. Nếu SUCCESS:
   - status=COMPLETED
   - lưu transfer_ref
   - mark outbox SENT
   - publish COIN_WITHDRAWAL notification
5. Nếu fail tạm thời:
   - retry_count +1
   - lưu note, error_source
6. Nếu DEAD:
   - status=FAILED
   - gọi IAM addCoins rollback bằng operationKey_ROLLBACK
   - publish COIN_WITHDRAWAL_FAILED notification

### 5.1.5 Cấu hình SePay outbound

Config runtime:

- transfer.provider=sepay
- sepay.api-url
- sepay.token

Yêu cầu bảo mật:

1. Token nằm ở env/secret manager.
2. Không log token trong request/response.
3. Chỉ lưu account_number dạng mask trong log.

## 5.2 iam-service: idempotency cho trừ/hoàn điểm

Append method hỗ trợ operationKey:

1. deductCoins(userId, amount, operationKey)
2. addCoins(userId, amount, operationKeyRollback)

Mục tiêu: tránh double-deduct và double-rollback khi retry.

## 5.3 notification-service

Append event cases mới cho withdrawal:

1. COIN_WITHDRAWAL
2. COIN_WITHDRAWAL_FAILED
3. COIN_WITHDRAWAL_MANUAL

Append send mail/in-app message tương ứng.

## 5.4 RabbitMQ + Outbox

Append routing key và queue mới chỉ cho withdrawal:

1. booking.coin.withdrawal.event

Không thay đổi queue/routing key của booking cancel/refund cũ.

## 6. Thiết kế frontend chi tiết

## 6.1 Header/Profile

Append menu item mới:

1. Rút điểm về tài khoản ngân hàng

Action: navigate /information/withdraw-coins.

## 6.2 Trang user /information/withdraw-coins

Gồm 2 khối:

1. Khối tạo yêu cầu rút điểm.
2. Khối lịch sử rút điểm của chính user.

## 6.3 Modal rút điểm 2 bước

1. Bước 1: nhập điểm
   - min 5
   - không vượt số dư
   - hiển thị tiền quy đổi realtime
2. Bước 2: nhập thông tin ngân hàng
   - bank
   - account_number
   - account_name
   - confirm submit

Submit gọi POST /api/coin-withdrawals.

## 6.4 Lịch sử user

Danh sách hiển thị:

1. thời gian
2. số điểm
3. số tiền
4. ngân hàng (mask account)
5. trạng thái
6. ghi chú lỗi nếu có

## 6.5 Style/UI đồng bộ

Giữ style hiện hành để đồng bộ hệ thống:

1. Primary: #2274b8
2. Text: #172033
3. Muted: #64748b
4. Border: #dbe5f0
5. Surface: #f7fafc
6. Success: #059669
7. Danger: #ef4444

Không đổi font global.

## 7. Admin theo dõi (không duyệt tay)

Trang /admin/coin-withdrawals:

1. Danh sách giao dịch rút điểm.
2. Filter: status, userId, date range, error_source.
3. Xem chi tiết: transfer_ref, retry_count, operation_key, note.
4. Retry thủ công với giao dịch FAILED.

Lưu ý: Admin chỉ vận hành/giám sát, không duyệt từng giao dịch.

## 8. Danh sách lỗi phải quan sát được

1. IAM lỗi khi trừ điểm: gắn error_source=IAM.
2. RabbitMQ/outbox relay lỗi: error_source=RABBITMQ.
3. Notification lỗi: error_source=NOTIFICATION.
4. SePay lỗi chuyển khoản: error_source=SEPAY.
5. Lỗi nội bộ khác: error_source=SYSTEM.

## 9. Chia phase triển khai

## Phase 1 - Backend withdrawal core

1. migration bảng coin_withdrawals
2. entity/repo/service/controller
3. transfer strategy + sepay provider
4. outbox event + scheduler

## Phase 2 - IAM + Notification

1. idempotency method theo operationKey
2. event + template notification cho withdrawal

## Phase 3 - Frontend user

1. header option rút điểm
2. route /information/withdraw-coins
3. modal 2 bước + history user

## Phase 4 - Frontend admin

1. trang giám sát /admin/coin-withdrawals
2. filter + detail + retry action

## 10. Kế hoạch kiểm thử

## 10.1 Functional

1. User rút >= 5 điểm thành công: coin trừ đúng, trạng thái COMPLETED.
2. User rút < 5 điểm: bị chặn đúng rule.
3. SePay fail tạm thời: retry hoạt động.
4. SePay DEAD: rollback điểm đúng và status FAILED.
5. User history hiển thị đúng theo trạng thái thực tế.

## 10.2 Regression

1. Luồng booking cancel cũ không đổi.
2. Luồng refund booking thủ công cũ không đổi.
3. Endpoint và scheduler cũ không bị ảnh hưởng.

## 11. Definition of Done

Hoàn thành khi đáp ứng đủ:

1. Rút điểm tự động end-to-end chạy ổn định.
2. Không cần admin duyệt tay cho rút điểm.
3. Có rollback điểm khi giao dịch fail vĩnh viễn.
4. User có đầy đủ màn tạo lệnh + lịch sử.
5. Admin có màn giám sát + retry vận hành.
6. Không có thay đổi logic luồng booking cancel/refund cũ.

Sau khi duyệt plan này, bước tiếp theo là tách task implementation theo file cho từng phase và bắt đầu code từ Phase 1.
