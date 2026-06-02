package com.tourism.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.dto.request.CoinWithdrawalRequest;
import com.tourism.booking.dto.request.ConfirmManualPayoutRequest;
import com.tourism.booking.dto.response.CoinWithdrawalResponse;
import com.tourism.booking.dto.sepay.TransactionVerificationDTO;
import com.tourism.booking.entity.CoinWithdrawal;
import com.tourism.booking.entity.CoinWithdrawalStatus;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.dto.UserProfileResponse;
import com.tourism.booking.repository.CoinWithdrawalRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.service.SepayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoinWithdrawalServiceImpl")
class CoinWithdrawalServiceImplTest {

    @Mock CoinWithdrawalRepository coinWithdrawalRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock IamFeignClient iamFeignClient;
    @Mock SepayService sepayService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks CoinWithdrawalServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{\"stub\":true}");
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(coinWithdrawalRepository.save(any(CoinWithdrawal.class))).thenAnswer(invocation -> {
            CoinWithdrawal withdrawal = invocation.getArgument(0);
            if (withdrawal.getId() == null) {
                withdrawal.setId(1L);
            }
            if (withdrawal.getCreatedAt() == null) {
                withdrawal.setCreatedAt(LocalDateTime.now());
            }
            if (withdrawal.getUpdatedAt() == null) {
                withdrawal.setUpdatedAt(LocalDateTime.now());
            }
            return withdrawal;
        });
    }

    @Test
    @DisplayName("createWithdrawal creates manual request and stable notification outbox")
    void createWithdrawal_success() {
        CoinWithdrawalRequest request = new CoinWithdrawalRequest();
        request.setUserId(15);
        request.setCoinAmount(new BigDecimal("10"));
        request.setBank("VCB");
        request.setAccountNumber("1234567890");
        request.setAccountName("NGUYEN VAN A");

        UserProfileResponse profile = new UserProfileResponse();
        profile.setUserID(15);
        profile.setCoinBalance(new BigDecimal("50"));

        when(iamFeignClient.getUserProfile(15)).thenReturn(profile);

        CoinWithdrawalResponse response = service.createWithdrawal(request);

        assertThat(response.getStatus()).isEqualTo(CoinWithdrawalStatus.MANUAL);
        assertThat(response.getMoneyAmount()).isEqualByComparingTo("10000");
        assertThat(response.getAccountNumberMasked()).isEqualTo("1234567890");
        assertThat(response.getReferenceCode()).startsWith("WD15");

        ArgumentCaptor<CoinWithdrawal> withdrawalCaptor = ArgumentCaptor.forClass(CoinWithdrawal.class);
        verify(coinWithdrawalRepository).save(withdrawalCaptor.capture());
        CoinWithdrawal savedWithdrawal = withdrawalCaptor.getValue();
        assertThat(savedWithdrawal.getOperationKey()).endsWith("_WITHDRAW");
        assertThat(savedWithdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.MANUAL);

        verify(iamFeignClient, never()).deductCoins(anyInt(), any(BigDecimal.class), anyString());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getIdempotencyKey())
                .isEqualTo(savedWithdrawal.getReferenceCode() + "_COIN_WITHDRAWAL_MANUAL");
        assertThat(outboxCaptor.getValue().getIdempotencyKey()).doesNotStartWith("null_");
    }

    @Test
    @DisplayName("createWithdrawal rejects non-integer coin amounts")
    void createWithdrawal_rejectsDecimalCoinAmount() {
        CoinWithdrawalRequest request = new CoinWithdrawalRequest();
        request.setUserId(15);
        request.setCoinAmount(new BigDecimal("5.5"));
        request.setBank("VCB");
        request.setAccountNumber("1234567890");
        request.setAccountName("NGUYEN VAN A");

        assertThatThrownBy(() -> service.createWithdrawal(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("so nguyen");

        verify(iamFeignClient, never()).getUserProfile(anyInt());
        verify(coinWithdrawalRepository, never()).save(any(CoinWithdrawal.class));
    }

    @Test
    @DisplayName("retry resets failed withdrawal and its outbox event back to NEW")
    void retry_failedWithdrawal_resetsOutboxAndState() {
        CoinWithdrawal withdrawal = CoinWithdrawal.builder()
                .id(8L)
                .referenceCode("WD15999999ABCDEF")
                .userId(15)
                .coinAmount(new BigDecimal("10"))
                .moneyAmount(new BigDecimal("10000"))
                .bank("VCB")
                .accountNumber("1234567890")
                .accountName("NGUYEN VAN A")
                .status(CoinWithdrawalStatus.FAILED)
                .operationKey("WD15999999ABCDEF_WITHDRAW")
                .retryCount(2)
                .note("SePay timeout")
                .build();

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(3L)
                .idempotencyKey(withdrawal.getOperationKey())
                .exchange("booking.exchange")
                .routingKey("booking.coin.withdrawal.event")
                .payload("{}")
                .status(OutboxStatus.DEAD)
                .retries(2)
                .build();

        when(coinWithdrawalRepository.findById(8L)).thenReturn(Optional.of(withdrawal));
        when(outboxEventRepository.findByIdempotencyKey(withdrawal.getOperationKey())).thenReturn(Optional.of(outboxEvent));

        service.retry(8L);

        assertThat(withdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.MANUAL);
        assertThat(withdrawal.getRetryCount()).isEqualTo(0);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(outboxEvent.getRetries()).isEqualTo(0);
        assertThat(outboxEvent.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("confirmManualPayout marks MANUAL withdrawal as COMPLETED and saves notification outbox event")
    void confirmManualPayout_manualWithdrawal_becomesCompleted() {
        CoinWithdrawal withdrawal = CoinWithdrawal.builder()
                .id(20L)
                .referenceCode("WD30MANUAL001")
                .userId(30)
                .coinAmount(new BigDecimal("20"))
                .moneyAmount(new BigDecimal("20000"))
                .bank("TCB")
                .accountNumber("9876543210")
                .accountName("TRAN VAN B")
                .status(CoinWithdrawalStatus.MANUAL)
                .operationKey("WD30MANUAL001_WITHDRAW")
                .retryCount(0)
                .note("SePay khong ho tro")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(coinWithdrawalRepository.findById(20L)).thenReturn(Optional.of(withdrawal));
        when(sepayService.verifyWithdrawalTransaction("WD30MANUAL001", new BigDecimal("20000")))
                .thenReturn(TransactionVerificationDTO.builder()
                        .verified(true)
                        .transactionReference("FT2605ABCDE")
                        .build());
        UserProfileResponse profile = new UserProfileResponse();
        profile.setUserID(30);
        profile.setEmail("user@example.com");
        profile.setFullName("TRAN VAN B");
        when(iamFeignClient.getUserProfile(30)).thenReturn(profile);
        doNothing().when(iamFeignClient).deductCoins(30, new BigDecimal("20"), "WD30MANUAL001_WITHDRAW");

        ConfirmManualPayoutRequest request = new ConfirmManualPayoutRequest();
        request.setNote("Da chuyen du tien");

        CoinWithdrawalResponse response = service.confirmManualPayout(20L, request);

        assertThat(withdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getTransferRef()).isEqualTo("FT2605ABCDE");
        assertThat(withdrawal.getNote()).isEqualTo("Da xac minh qua SePay: FT2605ABCDE");
        assertThat(withdrawal.getErrorSource()).isNull();

        assertThat(response.getStatus()).isEqualTo(CoinWithdrawalStatus.COMPLETED);

        // Phải save 2 lần: 1 cho withdrawal, 1 cho outbox notification event
        verify(coinWithdrawalRepository).save(withdrawal);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getRoutingKey()).isEqualTo("booking.notification.event");
        assertThat(event.getIdempotencyKey()).isEqualTo("WD30MANUAL001_COIN_WITHDRAWAL");
    }

    @Test
    @DisplayName("confirmManualPayout rejects non-MANUAL status withdrawals")
    void confirmManualPayout_nonManualStatus_throwsException() {
        CoinWithdrawal withdrawal = CoinWithdrawal.builder()
                .id(21L)
                .referenceCode("WD31PEND001")
                .userId(31)
                .coinAmount(new BigDecimal("10"))
                .moneyAmount(new BigDecimal("10000"))
                .bank("VCB")
                .accountNumber("1111111111")
                .accountName("LE VAN C")
                .status(CoinWithdrawalStatus.COMPLETED) // wrong status
                .operationKey("WD31PEND001_WITHDRAW")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(coinWithdrawalRepository.findById(21L)).thenReturn(Optional.of(withdrawal));

        ConfirmManualPayoutRequest request = new ConfirmManualPayoutRequest();
        assertThatThrownBy(() -> service.confirmManualPayout(21L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MANUAL");

        verify(coinWithdrawalRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmManualPayout rejects when SePay cannot verify transfer")
    void confirmManualPayout_unverifiedTransfer_throwsException() {
        CoinWithdrawal withdrawal = CoinWithdrawal.builder()
                .id(22L)
                .referenceCode("WD32MAN002")
                .userId(32)
                .coinAmount(new BigDecimal("5"))
                .moneyAmount(new BigDecimal("5000"))
                .bank("MB")
                .accountNumber("2222222222")
                .accountName("PHAM THI D")
                .status(CoinWithdrawalStatus.MANUAL)
                .operationKey("WD32MAN002_WITHDRAW")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(coinWithdrawalRepository.findById(22L)).thenReturn(Optional.of(withdrawal));
        when(sepayService.verifyWithdrawalTransaction("WD32MAN002", new BigDecimal("5000")))
                .thenReturn(TransactionVerificationDTO.builder().verified(false).build());

        ConfirmManualPayoutRequest request = new ConfirmManualPayoutRequest();

        assertThatThrownBy(() -> service.confirmManualPayout(22L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Khong tim thay giao dich");

        assertThat(withdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.MANUAL);
        verify(iamFeignClient, never()).deductCoins(anyInt(), any(BigDecimal.class), anyString());
    }
}
