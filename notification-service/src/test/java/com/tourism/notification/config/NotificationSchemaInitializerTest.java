package com.tourism.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotificationSchemaInitializerTest {

    @Test
    void synchronizesNotificationTypeConstraintWithCoinWithdrawalTypes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        NotificationSchemaInitializer initializer = new NotificationSchemaInitializer(jdbcTemplate);

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("DROP CONSTRAINT IF EXISTS notifications_type_check");
        assertThat(sqlCaptor.getAllValues().get(1))
                .contains("ADD CONSTRAINT notifications_type_check")
                .contains("'COIN_WITHDRAWAL'")
                .contains("'COIN_WITHDRAWAL_MANUAL'")
                .contains("'COIN_WITHDRAWAL_FAILED'");
    }
}
