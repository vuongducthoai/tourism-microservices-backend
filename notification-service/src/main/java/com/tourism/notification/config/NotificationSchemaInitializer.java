package com.tourism.notification.config;

import com.tourism.notification.entity.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSchemaInitializer implements ApplicationRunner {

    private static final String NOTIFICATION_TYPE_CONSTRAINT = "notifications_type_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String allowedTypes = Arrays.stream(NotificationType.values())
                .map(Enum::name)
                .map(value -> "'" + value.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));

        try {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS " + NOTIFICATION_TYPE_CONSTRAINT);
            jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + NOTIFICATION_TYPE_CONSTRAINT
                    + " CHECK (type::text IN (" + allowedTypes + "))");
            log.info("Notification type constraint synchronized with {} enum values", NotificationType.values().length);
        } catch (Exception e) {
            log.error("Failed to synchronize notification type constraint: {}", e.getMessage(), e);
            throw e;
        }
    }
}
