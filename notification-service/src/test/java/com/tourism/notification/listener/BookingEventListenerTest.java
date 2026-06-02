package com.tourism.notification.listener;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.entity.ProcessedEvent;
import com.tourism.notification.repository.ProcessedEventRepository;
import com.tourism.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedEventRepository processedEventRepo;

    @InjectMocks
    private BookingEventListener listener;

    @Test
    void skipsDuplicateCoinWithdrawalEventByIdempotencyKey() {
        BookingEventDTO event = coinWithdrawalManualEvent("WD001", "WD001_COIN_WITHDRAWAL_MANUAL");
        when(processedEventRepo.existsByIdempotencyKey("WD001_COIN_WITHDRAWAL_MANUAL")).thenReturn(true);

        listener.onBookingEvent(event);

        verify(notificationService, never()).handleCoinWithdrawalManual(event);
        verify(processedEventRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void normalizesLegacyCoinWithdrawalKeyBeforeDuplicateCheck() {
        BookingEventDTO event = coinWithdrawalManualEvent("WD003", "null_COIN_WITHDRAWAL_MANUAL_1780290776215");
        when(processedEventRepo.existsByIdempotencyKey("WD003_COIN_WITHDRAWAL_MANUAL")).thenReturn(true);

        listener.onBookingEvent(event);

        verify(notificationService, never()).handleCoinWithdrawalManual(event);
        verify(processedEventRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksCoinWithdrawalEventProcessedAfterHandling() {
        BookingEventDTO event = coinWithdrawalManualEvent("WD002", "WD002_COIN_WITHDRAWAL_MANUAL");
        when(processedEventRepo.existsByIdempotencyKey("WD002_COIN_WITHDRAWAL_MANUAL")).thenReturn(false);

        listener.onBookingEvent(event);

        verify(notificationService).handleCoinWithdrawalManual(event);
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepo).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("WD002_COIN_WITHDRAWAL_MANUAL");
        assertThat(captor.getValue().getEventType()).isEqualTo("COIN_WITHDRAWAL_MANUAL");
    }

    private BookingEventDTO coinWithdrawalManualEvent(String referenceCode, String idempotencyKey) {
        BookingEventDTO event = new BookingEventDTO();
        event.setEventType("COIN_WITHDRAWAL_MANUAL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReferenceCode(referenceCode);
        event.setBookingCode(referenceCode);
        event.setUserId(2);
        event.setContactEmail("customer@example.com");
        return event;
    }
}
