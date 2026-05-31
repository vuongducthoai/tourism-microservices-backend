package com.tourism.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.dto.request.CoinWithdrawalRequest;
import com.tourism.booking.dto.request.ConfirmManualPayoutRequest;
import com.tourism.booking.dto.response.CoinWithdrawalResponse;
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
import lombok.RequiredArgsConstructor;
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
public class CoinWithdrawalServiceImpl implements CoinWithdrawalService {

    private static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("5");
    private static final BigDecimal EXCHANGE_RATE = new BigDecimal("1000");

    private final CoinWithdrawalRepository coinWithdrawalRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IamFeignClient iamFeignClient;
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

        iamFeignClient.deductCoins(request.getUserId(), request.getCoinAmount(), operationKey);

        CoinWithdrawal withdrawal = coinWithdrawalRepository.save(CoinWithdrawal.builder()
                .referenceCode(referenceCode)
                .userId(request.getUserId())
                .coinAmount(request.getCoinAmount())
                .moneyAmount(moneyAmount)
                .bank(request.getBank())
                .accountNumber(request.getAccountNumber())
                .accountName(request.getAccountName())
                .status(CoinWithdrawalStatus.PENDING)
                .operationKey(operationKey)
                .note("Yeu cau da duoc tao, he thong dang xu ly")
                .build());

        BookingEventDTO event = toEvent(withdrawal);
        OutboxEvent outboxEvent = OutboxEventFactory.coinWithdrawal(event, withdrawal, objectMapper);
        outboxEventRepository.save(outboxEvent);

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
        if (withdrawal.getStatus() != CoinWithdrawalStatus.FAILED) {
            throw new RuntimeException("Chi cho phep retry giao dich FAILED");
        }

        OutboxEvent event = outboxEventRepository.findByIdempotencyKey(withdrawal.getOperationKey())
                .orElseThrow(() -> new RuntimeException("Khong tim thay outbox event cho giao dich"));

        event.setStatus(OutboxStatus.NEW);
        event.setRetries(0);
        event.setErrorMessage(null);
        event.setLockedAt(null);
        event.setLockedBy(null);
        event.setSentAt(null);
        event.setNextRetryAt(LocalDateTime.now());
        outboxEventRepository.save(event);

        withdrawal.setStatus(CoinWithdrawalStatus.PENDING);
        withdrawal.setRetryCount(0);
        withdrawal.setNote("Da duoc dua vao hang cho retry boi admin");
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
            throw new RuntimeException("Chi co the xac nhan chuyen khoan thu cong cho giao dich co trang thai MANUAL");
        }

        String transferRef = (request.getTransferRef() != null && !request.getTransferRef().isBlank())
                ? request.getTransferRef()
                : "ADMIN_MANUAL_" + System.currentTimeMillis();
        String confirmNote = (request.getNote() != null && !request.getNote().isBlank())
                ? request.getNote()
                : "Admin da xac nhan chuyen khoan thu cong thanh cong";

        withdrawal.setStatus(CoinWithdrawalStatus.COMPLETED);
        withdrawal.setTransferRef(transferRef);
        withdrawal.setNote(confirmNote);
        withdrawal.setErrorSource(null);
        coinWithdrawalRepository.save(withdrawal);

        // Publish notification event via RabbitMQ outbox (routes to notification-service)
        BookingEventDTO event = toEvent(withdrawal);
        event.setBookingCode(withdrawal.getReferenceCode()); // dùng referenceCode làm idempotency key base
        event.setWithdrawalTransferRef(transferRef);
        OutboxEvent outboxEvent = OutboxEventFactory.notification(event, "COIN_WITHDRAWAL", objectMapper);
        outboxEventRepository.save(outboxEvent);

        return toResponse(withdrawal);
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

    private CoinWithdrawalResponse toResponse(CoinWithdrawal withdrawal) {
        return CoinWithdrawalResponse.builder()
                .id(withdrawal.getId())
                .referenceCode(withdrawal.getReferenceCode())
                .userId(withdrawal.getUserId())
                .coinAmount(withdrawal.getCoinAmount())
                .moneyAmount(withdrawal.getMoneyAmount())
                .bank(withdrawal.getBank())
                .accountNumberMasked(maskAccountNumber(withdrawal.getAccountNumber()))
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