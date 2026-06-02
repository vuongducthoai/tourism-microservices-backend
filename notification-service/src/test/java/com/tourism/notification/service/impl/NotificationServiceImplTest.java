package com.tourism.notification.service.impl;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.entity.NotificationType;
import com.tourism.notification.service.MailService;
import com.tourism.notification.service.NotificationPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private MailService mailService;

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private NotificationPersistenceService notificationPersistenceService;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void coinWithdrawalManualDoesNotThrowWhenNotificationPersistenceFails() {
        BookingEventDTO event = coinWithdrawalManualEvent();
        doThrow(new RuntimeException("constraint failure"))
                .when(notificationPersistenceService)
                .saveNotification(eq(2), eq(NotificationType.COIN_WITHDRAWAL_MANUAL), any(), any(), eq("WD001"));

        assertThatCode(() -> notificationService.handleCoinWithdrawalManual(event)).doesNotThrowAnyException();

        verify(webSocketService).notifyUserWithdrawalUpdate(2, event);
        verify(mailService).sendCoinWithdrawalCreatedEmail(event);
    }

    private BookingEventDTO coinWithdrawalManualEvent() {
        BookingEventDTO event = new BookingEventDTO();
        event.setEventType("COIN_WITHDRAWAL_MANUAL");
        event.setIdempotencyKey("WD001_COIN_WITHDRAWAL_MANUAL");
        event.setReferenceCode("WD001");
        event.setBookingCode("WD001");
        event.setUserId(2);
        event.setContactEmail("customer@example.com");
        event.setContactFullName("Tran Phuong Thao");
        return event;
    }
}
