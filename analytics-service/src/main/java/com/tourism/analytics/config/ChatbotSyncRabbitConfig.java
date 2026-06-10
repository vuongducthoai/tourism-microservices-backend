package com.tourism.analytics.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class ChatbotSyncRabbitConfig {

    public static final String EXCHANGE_NAME = "tourism.events";
    public static final String QUEUE_NAME = "analytics.chatbot-sync.queue";
    public static final String DLQ_NAME = "analytics.chatbot-sync.dlq";
    public static final String ROUTING_PATTERN = "chatbot.sync.*";

    @Bean
    TopicExchange tourismEventsExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    Queue chatbotSyncQueue() {
        return new Queue(QUEUE_NAME, true, false, false, Map.of(
                "x-dead-letter-exchange", EXCHANGE_NAME,
                "x-dead-letter-routing-key", "chatbot.sync.dlq"
        ));
    }

    @Bean
    Queue chatbotSyncDeadLetterQueue() {
        return new Queue(DLQ_NAME, true);
    }

    @Bean
    Binding chatbotSyncBinding(Queue chatbotSyncQueue, TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(chatbotSyncQueue)
                .to(tourismEventsExchange)
                .with(ROUTING_PATTERN);
    }

    @Bean
    Binding chatbotSyncDlqBinding(Queue chatbotSyncDeadLetterQueue, TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(chatbotSyncDeadLetterQueue)
                .to(tourismEventsExchange)
                .with("chatbot.sync.dlq");
    }
}
