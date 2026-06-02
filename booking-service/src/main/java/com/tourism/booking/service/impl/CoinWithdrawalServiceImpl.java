package com.tourism.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.dto.request.CoinWithdrawalRequest;
import com.tourism.booking.dto.request.ConfirmManualPayoutRequest;
import com.tourism.booking.dto.response.CoinWithdrawalResponse;
import com.tourism.booking.dto.response.SepayCheckResult;
import com.tourism.booking.dto.sepay.TransactionVerificationDTO;
import com.tourism.booking.entity.CoinWithdrawal;
import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import com.tourism.booking.entity.CoinWithdrawalStatus;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.dto.UserProfileResponse;
import com.tourism.booking.messaging.OutboxEventFactory;
import com.tourism.booking.repository.CoinWithdrawalRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.service.CoinWithdrawalService;
import com.tourism.booking.service.SepayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoinWithdrawalServiceImpl implements CoinWithdrawalService {

    private static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("5");
    private static final BigDecimal EXCHANGE_RATE = new BigDecimal("1000");
    private final CoinWithdrawalRepository coinWithdrawalRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IamFeignClient iamFeignClient;
    private final SepayService sepayService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CoinWithdrawalResponse createWithdrawal(CoinWithdrawalRequest request) {
        validateRequest(request);

        UserProfileResponse userProfile = iamFeignClient.getUserProfile(request.getUserId());
        BigDecimal balance = userProfile.getCoinBalance() != null
                ? userProfile.getCoinBalance()
                : BigDecimal.ZERO;
        if (balance.compareTo(request.getCoinAmount()) < 0) {
            throw new RuntimeException("So du diem khong du de rut");
        }

        String referenceCode = buildReferenceCode(request.getUserId());
        String operationKey = referenceCode + "_WITHDRAW";
        BigDecimal moneyAmount = request.getCoinAmount().multiply(EXCHANGE_RATE);

        // He thong hoan coin thu cong: KHONG tru coin ngay
        // Coin chi bi tru khi admin xac nhan chuyen khoan thanh cong
        CoinWithdrawal withdrawal = coinWithdrawalRepository.save(CoinWithdrawal.builder()
                .referenceCode(referenceCode)
                .userId(request.getUserId())
                .coinAmount(request.getCoinAmount())
                .moneyAmount(moneyAmount)
                .bank(request.getBank())
                .accountNumber(request.getAccountNumber())
                .accountName(request.getAccountName())
                .status(CoinWithdrawalStatus.MANUAL)
                .operationKey(operationKey)
                .note("Yeu cau dang cho admin xu ly. Tien se duoc chuyen ve tai khoan cua ban trong 24 gio.")
                .build());

        // Gui thong bao qua RabbitMQ: luu vao DB + WebSocket cho user
        try {
            queueWithdrawalNotification(withdrawal, "COIN_WITHDRAWAL_MANUAL", userProfile, null);
        } catch (Exception e) {
            log.warn("Failed to queue COIN_WITHDRAWAL_MANUAL notification for {}: {}", referenceCode, e.getMessage());
        }

        return toResponse(withdrawal);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CoinWithdrawalResponse> getUserHistory(Integer userId) {
        return coinWithdrawalRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CoinWithdrawalResponse> searchAdmin(String status, Integer userId, String errorSource, Pageable pageable) {
        CoinWithdrawalStatus parsedStatus = parseStatus(status);
        CoinWithdrawalErrorSource parsedErrorSource = parseErrorSource(errorSource);
        return coinWithdrawalRepository.searchAdmin(parsedStatus, userId, parsedErrorSource, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CoinWithdrawalResponse getById(Long id) {
        return toResponse(findWithdrawal(id));
    }

    @Override
    @Transactional
    public void retry(Long id) {
        CoinWithdrawal withdrawal = findWithdrawal(id);
        if (withdrawal.getStatus() != CoinWithdrawalStatus.FAILED && withdrawal.getStatus() != CoinWithdrawalStatus.MANUAL) {
            throw new RuntimeException("Chi cho phep reset giao dich FAILED hoac MANUAL");
        }

        // Xu ly outbox neu ton tai (giao dich cu qua relay tu dong)
        outboxEventRepository.findByIdempotencyKey(withdrawal.getOperationKey()).ifPresent(event -> {
            event.setStatus(OutboxStatus.NEW);
            event.setRetries(0);
            event.setErrorMessage(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setSentAt(null);
            event.setNextRetryAt(LocalDateTime.now());
            outboxEventRepository.save(event);
        });

        withdrawal.setStatus(CoinWithdrawalStatus.MANUAL);
        withdrawal.setRetryCount(0);
        withdrawal.setNote("Da reset ve trang thai cho admin xu ly");
        withdrawal.setErrorSource(null);
        coinWithdrawalRepository.save(withdrawal);
    }

    private CoinWithdrawal findWithdrawal(Long id) {
        return coinWithdrawalRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Khong tim thay giao dich rut diem: " + id));
    }

    @Override
    @Transactional
    public CoinWithdrawalResponse confirmManualPayout(Long id, ConfirmManualPayoutRequest request) {
        CoinWithdrawal withdrawal = findWithdrawal(id);
        if (withdrawal.getStatus() != CoinWithdrawalStatus.MANUAL) {
            throw new RuntimeException("Chi co the xac nhan cho giao dich co trang thai MANUAL");
        }

        // Kiem tra giao dich tren SePay (quet 24h gan nhat)
        TransactionVerificationDTO sepayResult = sepayService.verifyWithdrawalTransaction(
                withdrawal.getReferenceCode(), withdrawal.getMoneyAmount());

        if (!sepayResult.isVerified()) {
            throw new RuntimeException(
                "Khong tim thay giao dich phu hop trong 24 gio qua. " +
                "Vui long thuc hien chuyen khoan va bam xac nhan lai.");
        }

        String transferRef = sepayResult.getTransactionReference();
        log.info("SePay verified withdrawal {} with ref={}", withdrawal.getReferenceCode(), transferRef);

        // Tru coin sau khi SePay xac nhan giao dich thanh cong
        try {
            iamFeignClient.deductCoins(withdrawal.getUserId(), withdrawal.getCoinAmount(), withdrawal.getOperationKey());
            log.info("Deducted {} coins for user {} on withdrawal {}",
                    withdrawal.getCoinAmount(), withdrawal.getUserId(), withdrawal.getReferenceCode());
        } catch (Exception e) {
            log.error("Failed to deduct coins for withdrawal {}: {}", withdrawal.getReferenceCode(), e.getMessage());
            throw new RuntimeException("Khong the tru diem cho user. Vui long kiem tra lai: " + e.getMessage());
        }

        withdrawal.setStatus(CoinWithdrawalStatus.COMPLETED);
        withdrawal.setTransferRef(transferRef);
        withdrawal.setNote("Da xac minh qua SePay: " + transferRef);
        withdrawal.setErrorSource(null);
        coinWithdrawalRepository.save(withdrawal);

        // Gui thong bao cho user qua outbox
        try {
            UserProfileResponse userProfile = iamFeignClient.getUserProfile(withdrawal.getUserId());
            queueWithdrawalNotification(withdrawal, "COIN_WITHDRAWAL", userProfile, transferRef);
        } catch (Exception e) {
            log.warn("Failed to queue COIN_WITHDRAWAL notification for {}: {}", withdrawal.getReferenceCode(), e.getMessage());
        }

        return toResponse(withdrawal);
    }

    @Override
    @Transactional(readOnly = true)
    public SepayCheckResult checkSepayTransaction(Long id) {
        CoinWithdrawal withdrawal = findWithdrawal(id);
        TransactionVerificationDTO result = sepayService.verifyWithdrawalTransaction(
                withdrawal.getReferenceCode(), withdrawal.getMoneyAmount());
        if (result.isVerified()) {
            return SepayCheckResult.builder()
                    .verified(true)
                    .transactionReference(result.getTransactionReference())
                    .message("Tim thay giao dich phu hop tren SePay: " + result.getTransactionReference())
                    .build();
        } else {
            return SepayCheckResult.builder()
                    .verified(false)
                    .message("Chua tim thay giao dich khop tren SePay trong 24 gio qua. Vui long nhap ma giao dich thu cong.")
                    .build();
        }
    }

    private void validateRequest(CoinWithdrawalRequest request) {
        if (request.getCoinAmount().compareTo(MIN_WITHDRAWAL) < 0) {
            throw new RuntimeException("So diem rut toi thieu la 5");
        }
        if (request.getCoinAmount().stripTrailingZeros().scale() > 0) {
            throw new RuntimeException("So diem rut phai la so nguyen");
        }
    }

    private String buildReferenceCode(Integer userId) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "WD" + userId + timestamp + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private BookingEventDTO toEvent(CoinWithdrawal withdrawal) {
        return BookingEventDTO.builder()
                .userId(withdrawal.getUserId())
                .eventType("COIN_WITHDRAWAL")
                .bookingCode(withdrawal.getReferenceCode())
                .referenceCode(withdrawal.getReferenceCode())
                .coinWithdrawalAmount(withdrawal.getCoinAmount())
                .withdrawalMoneyAmount(withdrawal.getMoneyAmount())
                .withdrawalBank(withdrawal.getBank())
                .withdrawalAccountName(withdrawal.getAccountName())
                .withdrawalAccountNumberMasked(maskAccountNumber(withdrawal.getAccountNumber()))
                .withdrawalStatus(withdrawal.getStatus().name())
                .withdrawalNote(withdrawal.getNote())
                .withdrawalErrorSource(withdrawal.getErrorSource() != null ? withdrawal.getErrorSource().name() : null)
                .build();
    }

    private void queueWithdrawalNotification(CoinWithdrawal withdrawal,
                                             String eventType,
                                             UserProfileResponse userProfile,
                                             String transferRef) {
        String key = withdrawalNotificationKey(withdrawal, eventType);
        if (outboxEventRepository.existsByIdempotencyKey(key)) {
            log.info("Skip duplicate coin withdrawal notification outbox: key={}", key);
            return;
        }

        BookingEventDTO event = toEvent(withdrawal);
        event.setEventType(eventType);
        event.setBookingCode(withdrawal.getReferenceCode());
        event.setReferenceCode(withdrawal.getReferenceCode());
        event.setWithdrawalTransferRef(transferRef != null ? transferRef : withdrawal.getTransferRef());
        if (userProfile != null) {
            event.setContactEmail(userProfile.getEmail());
            event.setContactFullName(userProfile.getFullName());
        }

        OutboxEvent outboxEvent = OutboxEventFactory.notificationWithKey(event, eventType, key, objectMapper);
        outboxEventRepository.save(outboxEvent);
    }

    private String withdrawalNotificationKey(CoinWithdrawal withdrawal, String eventType) {
        return withdrawal.getReferenceCode() + "_" + eventType;
    }

    private CoinWithdrawalResponse toResponse(CoinWithdrawal withdrawal) {
        return CoinWithdrawalResponse.builder()
                .id(withdrawal.getId())
                .referenceCode(withdrawal.getReferenceCode())
                .userId(withdrawal.getUserId())
                .coinAmount(withdrawal.getCoinAmount())
                .moneyAmount(withdrawal.getMoneyAmount())
                .bank(withdrawal.getBank())
                .accountNumberMasked(withdrawal.getAccountNumber()) // Hien thi so TK day du, khong che
                .accountName(withdrawal.getAccountName())
                .status(withdrawal.getStatus())
                .transferRef(withdrawal.getTransferRef())
                .operationKey(withdrawal.getOperationKey())
                .retryCount(withdrawal.getRetryCount())
                .errorSource(withdrawal.getErrorSource())
                .note(withdrawal.getNote())
                .createdAt(withdrawal.getCreatedAt())
                .updatedAt(withdrawal.getUpdatedAt())
                .build();
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) return accountNumber;
        return "*".repeat(Math.max(0, accountNumber.length() - 4)) + accountNumber.substring(accountNumber.length() - 4);
    }

    private CoinWithdrawalStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return CoinWithdrawalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    }

    private CoinWithdrawalErrorSource parseErrorSource(String errorSource) {
        if (errorSource == null || errorSource.isBlank()) return null;
        return CoinWithdrawalErrorSource.valueOf(errorSource.trim().toUpperCase(Locale.ROOT));
    }
}
