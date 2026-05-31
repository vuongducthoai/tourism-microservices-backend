# COIN WITHDRAWAL IMPLEMENTATION REPORT (2026-05-30)

## 1) Scope and Constraints
- Feature: automatic withdrawal of user points to personal bank account.
- Strictly additive implementation: old booking create/cancel/refund logic is preserved.
- UI consistency: re-used existing design language (blue/gray palette, card layout, icon libraries, typography).
- Bank selection UX: new modal grid bank picker aligned with existing refund bank selection style.

## 2) Backend Architecture
### 2.1 New domain objects
- `CoinWithdrawal` entity: stores withdrawal request lifecycle and banking metadata.
- `CoinWithdrawalStatus`: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `MANUAL`.
- `CoinWithdrawalErrorSource`: source of failure (`SEPAY`, `IAM`, `SYSTEM`, etc.).

### 2.2 Service workflow
1. User submits withdrawal request (`POST /api/coin-withdrawals`).
2. `CoinWithdrawalServiceImpl#createWithdrawal` validates amount (minimum 5 points, integer).
3. Booking service calls IAM to deduct points with idempotent operation key.
4. Request is persisted and an internal outbox event (`booking.coin.withdrawal.event`) is created.
5. `CoinWithdrawalRelayScheduler` claims outbox messages and executes transfer provider.
6. On success: marks request `COMPLETED` and pushes notification event.
7. On repeated failure: marks request `FAILED`, rolls back points via IAM, sends failure notification.
8. Admin can retry FAILED requests via `POST /api/coin-withdrawals/admin/{id}/retry`.

### 2.3 Notification integration
- Re-used booking notification event channel with new event types:
  - `COIN_WITHDRAWAL`
  - `COIN_WITHDRAWAL_FAILED`
  - `COIN_WITHDRAWAL_MANUAL`

## 3) Frontend Architecture
### 3.1 User UI
- Component: `src/components/InformationComponent/WithdrawCoins/WithdrawCoins.jsx`
- Behavior:
  - Single inline withdrawal form (no multi-step modal).
  - Direct amount input on the page.
  - Bank picker modal grid (card list with logo/name/BIN).
  - Summary panel (balance, exchange rate, minimum rule).
  - History list with status badges and note/transfer reference.

### 3.2 Admin UI
- Page: `src/components/AdminComponent/Pages/CoinWithdrawalsPage/CoinWithdrawalsPage.jsx`
- Features:
  - Filter/search by status, userId, errorSource.
  - Detail modal.
  - Retry action for FAILED transactions.

## 4) Key Files Changed
### 4.1 Backend
- `booking-service/src/main/resources/application.yml`
- `booking-service/src/test/java/com/tourism/booking/service/impl/CoinWithdrawalServiceImplTest.java`
- `booking-service/src/test/java/com/tourism/booking/messaging/CoinWithdrawalRelaySchedulerTest.java`

### 4.2 Frontend
- `client-side/src/components/InformationComponent/WithdrawCoins/WithdrawCoins.jsx`
- `client-side/src/components/InformationComponent/WithdrawCoins/WithdrawCoins.module.scss`
- `client-side/src/components/InformationComponent/WithdrawCoins/WithdrawCoins.test.jsx`
- `client-side/src/components/AdminComponent/Pages/CoinWithdrawalsPage/CoinWithdrawalsPage.test.jsx`

### 4.3 API test helper
- `test_coin_withdrawal_api.ps1`

## 5) Configuration State (as requested)
`booking-service/src/main/resources/application.yml` is set to legacy hardcoded SePay style with TCB:
- `sepay.api-url: https://my.sepay.vn/userapi`
- `sepay.token: <hardcoded token>`
- `sepay.account-number: "10002897094"`
- `sepay.account-name: TRAN ANH THU`
- `sepay.bank-code: TCB`
- `sepay.transfer-path: /transfers`
- `transfer.provider: sepay`

## 6) Validation and Test Results
### 6.1 Unit/integration tests
- Booking service focused tests: PASSED
  - `CoinWithdrawalServiceImplTest`
  - `CoinWithdrawalRelaySchedulerTest`
- Frontend focused tests: PASSED
  - `WithdrawCoins.test.jsx`
  - `CoinWithdrawalsPage.test.jsx`

### 6.2 Frontend production build
- `npm run build` with `BUILD_PATH=build_withdraw_validation_v2`: SUCCESS
- Existing repository-wide ESLint warnings remain (pre-existing, outside withdrawal scope).

### 6.3 API validation for 2-point request
Payload tested:
```json
{
  "userId": 1,
  "coinAmount": 2,
  "bank": "TCB",
  "accountNumber": "1234567890",
  "accountName": "TEST USER"
}
```
Results:
- Direct booking-service (`http://localhost:8083/api/coin-withdrawals`): `400 Bad Request` (rejected).
- Gateway (`http://localhost:8080/api/coin-withdrawals`): `404` (route currently not exposed via gateway path mapping in runtime).

## 7) Packaging and Container Deployment
Completed:
1. Maven package built for changed services:
   - booking-service
   - iam-service
   - notification-service
2. Docker images rebuilt:
   - booking-service
   - iam-service
   - notification-service
   - api-gateway
3. Containers recreated and started with latest images:
   - booking-service
   - iam-service
   - notification-service
   - api-gateway

## 8) Operational Notes
- Business rule check for min withdrawal is effective (2-point request rejected).
- Gateway 404 for `/api/coin-withdrawals` indicates gateway route mapping mismatch at current runtime configuration; direct service endpoint works and enforces validation.
- No legacy booking flow logic was altered; all changes are additive around new withdrawal flow.
