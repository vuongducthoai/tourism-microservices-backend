# Báo cáo fix spam email rút điểm - 2026-06-01

## 1. Hiện tượng

Khi user tạo hoặc admin hoàn tất yêu cầu rút điểm, Gmail nhận nhiều email giống nhau liên tục cho cùng một mã rút điểm như:

- `WD2202606011301275FF638`
- `WD220260601143514B1C72E`

Trang admin `http://localhost:3000/admin/coin-withdrawals` vẫn hiển thị được dữ liệu, nhưng notification/email/socket bị lặp.

## 2. Nguyên nhân gốc

Có 2 nguyên nhân kết hợp:

1. `notification-service` lưu notification với type mới như `COIN_WITHDRAWAL`, `COIN_WITHDRAWAL_MANUAL`, `COIN_WITHDRAWAL_FAILED`, nhưng DB constraint `notifications_type_check` cũ chưa cho phép các giá trị này.
2. Khi insert `notifications` lỗi, transaction listener bị abort, `processed_events` không lưu được. RabbitMQ xem event là chưa xử lý xong nên retry. Mỗi lần retry lại chạy luồng gửi mail.

Log lỗi chính:

```text
ERROR: new row for relation "notifications" violates check constraint "notifications_type_check"
ERROR: current transaction is aborted, commands ignored until end of transaction block
```

Ngoài ra còn có event cũ trong queue dùng idempotency key dạng timestamp hoặc `null_...`, ví dụ:

```text
null_COIN_WITHDRAWAL_MANUAL_1780290776215
WD..._COIN_WITHDRAWAL_MANUAL_1780293594931
```

Các key này làm cùng một withdrawal bị coi là nhiều event khác nhau.

## 3. Thay đổi đã thực hiện

### Notification service

- Thêm `NotificationSchemaInitializer` để khi service start sẽ đồng bộ lại constraint `notifications_type_check` theo toàn bộ enum `NotificationType`.
- Thêm `NotificationPersistenceService` và `NotificationPersistenceServiceImpl`.
- Tách thao tác lưu notification sang transaction riêng `REQUIRES_NEW`, tránh lỗi insert notification làm abort transaction listener chính.
- Cập nhật `BookingEventListener` để coin withdrawal dùng key xử lý chuẩn:

```text
{referenceCode}_{eventType}
```

Ví dụ:

```text
WD220260601143514B1C72E_COIN_WITHDRAWAL_MANUAL
WD220260601143514B1C72E_COIN_WITHDRAWAL
```

- Nhờ đó event cũ có key `null_..._timestamp` hoặc key có timestamp cũng được normalize về key chuẩn, không gửi mail/socket lặp.
- Không thay đổi logic tạo yêu cầu rút điểm, duyệt rút điểm, hoàn coin, booking, refund, payment, IAM.

### Mail config

- Đã đổi tài khoản gửi mail của `notification-service` sang tài khoản mới user yêu cầu.
- Đã cập nhật trong:
  - `notification-service/src/main/resources/application.yml`
  - `docker-compose.yml`

Ghi chú: báo cáo không ghi lại app password để tránh lộ secret.

## 4. Test đã thêm

Đã thêm test cho `notification-service`:

- `BookingEventListenerTest`
  - Skip event trùng theo idempotency key chuẩn.
  - Normalize legacy key dạng `null_COIN_WITHDRAWAL_MANUAL_...` về `{referenceCode}_COIN_WITHDRAWAL_MANUAL`.
  - Lưu `processed_events` sau khi xử lý event hợp lệ.

- `NotificationServiceImplTest`
  - Coin withdrawal manual không throw nếu lưu notification lỗi.
  - Mail/socket vẫn không làm listener transaction bị abort vì lỗi notification persistence đã được chặn.

- `NotificationSchemaInitializerTest`
  - Constraint SQL có đủ `COIN_WITHDRAWAL`, `COIN_WITHDRAWAL_MANUAL`, `COIN_WITHDRAWAL_FAILED`.

Kết quả:

```powershell
mvn -pl notification-service test "-Dtest=BookingEventListenerTest,NotificationServiceImplTest,NotificationSchemaInitializerTest"
```

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 5. Build, Docker và API check

Đã build:

```powershell
mvn -pl notification-service -DskipTests package
docker compose build notification-service
docker compose up -d notification-service
```

Container:

```text
tourism-notification-service - healthy
```

Health API:

```powershell
curl http://localhost:8086/actuator/health
```

```json
{"status":"UP"}
```

Notification API:

```powershell
curl "http://localhost:8086/api/notifications/unread-count?userId=2"
```

```json
{"count":19}
```

Mail username trong container:

```text
tranthu270904@gmail.com
```

RabbitMQ queue check:

```text
booking.notification.queue    0    0
booking.notification.dlq      0    0
```

Log sau restart không còn xuất hiện:

- `notifications_type_check`
- `Failed to process booking event`
- `ListenerExecutionFailed`
- `Error processing booking event`

DB constraint check:

```sql
SELECT pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conname = 'notifications_type_check';
```

Kết quả constraint hiện đã chứa:

```text
COIN_WITHDRAWAL
COIN_WITHDRAWAL_FAILED
COIN_WITHDRAWAL_MANUAL
```

## 6. Kết luận

Spam mail xảy ra vì event bị retry sau khi `notification-service` fail DB constraint và không ghi được `processed_events`.

Bản fix hiện tại xử lý cả 2 lớp:

1. DB constraint được tự đồng bộ để các notification type rút điểm insert được.
2. Coin withdrawal event được dedupe bằng key chuẩn `{referenceCode}_{eventType}`, kể cả event cũ có key timestamp hoặc `null_...`.

Sau khi rebuild/restart, `notification-service` healthy, API hoạt động, queue notification không còn message kẹt/retry.
