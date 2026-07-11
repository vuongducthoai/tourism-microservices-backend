package com.tourism.booking.config;

import com.tourism.booking.service.AsyncBookingService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Topic yêu cầu đặt tour — 3 partition để cùng lịch xử lý tuần tự, khác lịch song song. */
    @Bean
    public NewTopic bookingRequestsTopic() {
        return TopicBuilder.name(AsyncBookingService.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
