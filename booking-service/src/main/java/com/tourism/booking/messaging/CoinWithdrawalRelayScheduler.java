package com.tourism.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.CoinWithdrawal;
import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import com.tourism.booking.entity.CoinWithdrawalStatus;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.repository.CoinWithdrawalRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.service.transfer.TransferResult;
import com.tourism.booking.service.transfer.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoinWithdrawalRelayScheduler {

    private static final int BATCH_SIZE = 30;
    private static final int STALE_MINUTES = 5;

    private final OutboxEventRepository outboxRepo;
    private final CoinWithdrawalRepository withdrawalRepo;
    private final IamFeignClient iamFeignClient;
    private final ObjectMapper objectMapper;
    @Qualifier("sepayTransferService")
    private final TransferService sepayTransferService;
    @Qualifier("manualTransferService")
    private final TransferService manualTransferService;

    @Value("${transfer.provider:sepay}")
    private String provider;

    @Scheduled(fixedDelay = 5000)
    public void relay() {
        int released = outboxRepo.resetStaleLocks(LocalDateTime.now().minusMinutes(STALE_MINUTES));
        if (released > 0) {
            log.warn("Released {} stale COIN_WITHDRAWAL outbox locks", released);
        }

        List<OutboxEvent> batch = claimBatch();
        if (batch.isEmpty()) return;

        for (OutboxEvent event : batch) {
            processOne(event);
        }
    }

    @Transactional
    public List<OutboxEvent> claimBatch() {
        String instanceId = getInstanceId();
        List<OutboxEvent> allPending = outboxRepo.findAndLockPending(LocalDateTime.now(), BATCH_SIZE * 10);

        List<OutboxEvent> withdrawals = allPending.stream()
                .filter(e -> RabbitMQConfig.RK_COIN_WITHDRAWAL.equals(e.getRoutingKey()))
                .limit(BATCH_SIZE)
                .toList();

        withdrawals.forEach(e -> {
            e.setStatus(OutboxStatus.SENDING);
            e.setLockedBy(instanceId);
            e.setLockedAt(LocalDateTime.now());
        });

        allPending.stream()
                .filter(e -> !RabbitMQConfig.RK_COIN_WITHDRAWAL.equals(e.getRoutingKey()))
                .forEach(e -> {
                    e.setStatus(OutboxStatus.NEW);
                    e.setLockedBy(null);
                    e.setLockedAt(null);
                });

        outboxRepo.saveAll(allPending);
        return withdrawals;
    }

    @Transactional
    public void processOne(OutboxEvent event) {
        BookingEventDTO dto;
        try {
            dto = objectMapper.readValue(event.getPayload(), BookingEventDTO.class);
        } catch (Exception ex) {
            log.error("Cannot parse coin withdrawal payload {}: {}", event.getIdempotencyKey(), ex.getMessage());
            event.incrementRetries(ex.getMessage());
            outboxRepo.save(event);
            return;
        }

        CoinWithdrawal withdrawal = withdrawalRepo.findByOperationKey(event.getIdempotencyKey())
                .orElseThrow(() -> new RuntimeException("Withdrawal not found for outbox " + event.getIdempotencyKey()));

        withdrawal.setStatus(CoinWithdrawalStatus.PROCESSING);
        withdrawal.setNote("He thong dang thuc hien chuyen khoan");
        withdrawalRepo.save(withdrawal);

        TransferResult result = resolveTransferService().transfer(withdrawal);
        switch (result.getType()) {
            case SUCCESS -> handleSuccess(event, withdrawal, result);
            case MANUAL -> handleManual(event, withdrawal, result);
            case RETRYABLE_FAILURE -> handleFailure(event, withdrawal, dto, result);
        }
    }

    private void handleSuccess(OutboxEvent event, CoinWithdrawal withdrawal, TransferResult result) {
        withdrawal.setStatus(CoinWithdrawalStatus.COMPLETED);
        withdrawal.setTransferRef(result.getTransferRef());
        withdrawal.setNote("Chuyen khoan thanh cong");
        withdrawal.setErrorSource(null);
        withdrawalRepo.save(withdrawal);

        event.markSent();
        outboxRepo.save(event);

        saveWithdrawalNotification(withdrawal, "COIN_WITHDRAWAL");
    }

    private void handleManual(OutboxEvent event, CoinWithdrawal withdrawal, TransferResult result) {
        withdrawal.setStatus(CoinWithdrawalStatus.MANUAL);
        withdrawal.setNote(result.getNote());
        withdrawal.setErrorSource(CoinWithdrawalErrorSource.SYSTEM);
        withdrawalRepo.save(withdrawal);

        event.markSent();
        outboxRepo.save(event);

        saveWithdrawalNotification(withdrawal, "COIN_WITHDRAWAL_MANUAL");
    }

    private void handleFailure(OutboxEvent event, CoinWithdrawal withdrawal, BookingEventDTO dto, TransferResult result) {
        event.incrementRetries(result.getNote());
        outboxRepo.save(event);

        withdrawal.setRetryCount(event.getRetries());
        withdrawal.setErrorSource(result.getErrorSource() != null ? result.getErrorSource() : CoinWithdrawalErrorSource.SYSTEM);
        withdrawal.setNote(result.getNote());

        if (event.getStatus() == OutboxStatus.DEAD) {
            withdrawal.setStatus(CoinWithdrawalStatus.FAILED);
            rollbackCoins(withdrawal, dto);
            saveWithdrawalNotification(withdrawal, "COIN_WITHDRAWAL_FAILED");
        } else {
            withdrawal.setStatus(CoinWithdrawalStatus.PENDING);
        }

        withdrawalRepo.save(withdrawal);
    }

    private void rollbackCoins(CoinWithdrawal withdrawal, BookingEventDTO dto) {
        try {
            iamFeignClient.addCoins(dto.getUserId(), withdrawal.getCoinAmount(), withdrawal.getOperationKey() + "_ROLLBACK");
            withdrawal.setNote((withdrawal.getNote() != null ? withdrawal.getNote() + ". " : "") + "Da rollback diem cho user");
        } catch (Exception rollbackEx) {
            log.error("Rollback coin failed for {}: {}", withdrawal.getReferenceCode(), rollbackEx.getMessage());
            withdrawal.setNote((withdrawal.getNote() != null ? withdrawal.getNote() + ". " : "") + "Rollback diem that bai: " + rollbackEx.getMessage());
            withdrawal.setErrorSource(CoinWithdrawalErrorSource.IAM);
        }
    }

    private BookingEventDTO toNotificationEvent(CoinWithdrawal withdrawal, String eventType) {
        return BookingEventDTO.builder()
                .userId(withdrawal.getUserId())
                .eventType(eventType)
                .bookingCode(withdrawal.getReferenceCode())
                .referenceCode(withdrawal.getReferenceCode())
                .coinWithdrawalAmount(withdrawal.getCoinAmount())
                .withdrawalMoneyAmount(withdrawal.getMoneyAmount())
                .withdrawalBank(withdrawal.getBank())
                .withdrawalAccountName(withdrawal.getAccountName())
                .withdrawalAccountNumberMasked(maskAccountNumber(withdrawal.getAccountNumber()))
                .withdrawalStatus(withdrawal.getStatus().name())
                .withdrawalTransferRef(withdrawal.getTransferRef())
                .withdrawalNote(withdrawal.getNote())
                .withdrawalErrorSource(withdrawal.getErrorSource() != null ? withdrawal.getErrorSource().name() : null)
                .build();
    }

    private void saveWithdrawalNotification(CoinWithdrawal withdrawal, String eventType) {
        String key = withdrawal.getReferenceCode() + "_" + eventType;
        if (outboxRepo.existsByIdempotencyKey(key)) {
            log.info("Skip duplicate coin withdrawal notification outbox: key={}", key);
            return;
        }
        outboxRepo.save(OutboxEventFactory.notificationWithKey(
                toNotificationEvent(withdrawal, eventType),
                eventType,
                key,
                objectMapper
        ));
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) return accountNumber;
        return "*".repeat(Math.max(0, accountNumber.length() - 4)) + accountNumber.substring(accountNumber.length() - 4);
    }

    private TransferService resolveTransferService() {
        return "manual".equalsIgnoreCase(provider) ? manualTransferService : sepayTransferService;
    }

    private String getInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
