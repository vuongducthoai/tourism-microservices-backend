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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoinWithdrawalRelayScheduler")
class CoinWithdrawalRelaySchedulerTest {

    @Mock OutboxEventRepository outboxRepo;
    @Mock CoinWithdrawalRepository withdrawalRepo;
    @Mock IamFeignClient iamFeignClient;
    @Mock ObjectMapper objectMapper;
    @Mock TransferService sepayTransferService;
    @Mock TransferService manualTransferService;

    private CoinWithdrawalRelayScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new CoinWithdrawalRelayScheduler(
                outboxRepo,
                withdrawalRepo,
                iamFeignClient,
                objectMapper,
                sepayTransferService,
                manualTransferService
        );
        ReflectionTestUtils.setField(scheduler, "provider", "sepay");
        lenient().when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(withdrawalRepo.save(any(CoinWithdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{\"stub\":true}");
    }

    private OutboxEvent event(int maxRetries) {
        return OutboxEvent.builder()
                .id(1L)
                .idempotencyKey("WD15ABC_WITHDRAW")
                .exchange(RabbitMQConfig.EXCHANGE)
                .routingKey(RabbitMQConfig.RK_COIN_WITHDRAWAL)
                .payload("{}")
                .status(OutboxStatus.SENDING)
                .maxRetries(maxRetries)
                .build();
    }

    private BookingEventDTO dto() {
        return BookingEventDTO.builder()
                .userId(15)
                .referenceCode("WD15ABC")
                .coinWithdrawalAmount(new BigDecimal("10"))
                .withdrawalMoneyAmount(new BigDecimal("10000"))
                .withdrawalBank("VCB")
                .withdrawalAccountName("NGUYEN VAN A")
                .withdrawalAccountNumberMasked("******7890")
                .build();
    }

    private CoinWithdrawal withdrawal() {
        return CoinWithdrawal.builder()
                .id(2L)
                .referenceCode("WD15ABC")
                .userId(15)
                .coinAmount(new BigDecimal("10"))
                .moneyAmount(new BigDecimal("10000"))
                .bank("VCB")
                .accountNumber("1234567890")
                .accountName("NGUYEN VAN A")
                .status(CoinWithdrawalStatus.PENDING)
                .operationKey("WD15ABC_WITHDRAW")
                .build();
    }

    @Nested
    @DisplayName("processOne")
    class ProcessOne {

        @Test
        @DisplayName("successful transfer completes withdrawal and marks outbox as SENT")
        void success_marksCompletedAndSent() throws Exception {
            OutboxEvent event = event(3);
            CoinWithdrawal withdrawal = withdrawal();

            when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(BookingEventDTO.class))).thenReturn(dto());
            when(withdrawalRepo.findByOperationKey("WD15ABC_WITHDRAW")).thenReturn(Optional.of(withdrawal));
            when(sepayTransferService.transfer(withdrawal)).thenReturn(TransferResult.success("SEP123"));

            scheduler.processOne(event);

            assertThat(withdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.COMPLETED);
            assertThat(withdrawal.getTransferRef()).isEqualTo("SEP123");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
            verify(outboxRepo).save(event);
        }

        @Test
        @DisplayName("final retry failure marks withdrawal FAILED and rolls back points")
        void finalFailure_marksFailedAndRollsBackCoins() throws Exception {
            OutboxEvent event = event(1);
            CoinWithdrawal withdrawal = withdrawal();

            when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(BookingEventDTO.class))).thenReturn(dto());
            when(withdrawalRepo.findByOperationKey("WD15ABC_WITHDRAW")).thenReturn(Optional.of(withdrawal));
            when(sepayTransferService.transfer(withdrawal)).thenReturn(
                    TransferResult.retryableFailure(CoinWithdrawalErrorSource.SEPAY, "Timeout from provider")
            );
            doNothing().when(iamFeignClient).addCoins(15, new BigDecimal("10"), "WD15ABC_WITHDRAW_ROLLBACK");

            scheduler.processOne(event);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
            assertThat(withdrawal.getStatus()).isEqualTo(CoinWithdrawalStatus.FAILED);
            assertThat(withdrawal.getErrorSource()).isEqualTo(CoinWithdrawalErrorSource.SEPAY);
            assertThat(withdrawal.getNote()).contains("rollback");
            verify(iamFeignClient).addCoins(15, new BigDecimal("10"), "WD15ABC_WITHDRAW_ROLLBACK");
        }
    }
}