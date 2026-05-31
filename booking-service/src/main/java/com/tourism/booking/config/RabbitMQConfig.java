package com.tourism.booking.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the tourism.events topic exchange and all queues / bindings.
 * Both booking-service and notification-service declare the same topology —
 * RabbitMQ makes this idempotent (durable declarations with same args are no-ops).
 *
 * Exchange  : tourism.events  (TopicExchange, durable)
 * Queues    : booking.notification.queue  ← routing key booking.notification.*
 *             booking.analytics.queue     ← routing key booking.analytics.*
 * DLQs      : booking.notification.dlq
 *             booking.analytics.dlq
 */
@Configuration
public class RabbitMQConfig {

    // ── Exchange ─────────────────────────────────────────────────────────────
    public static final String EXCHANGE = "tourism.events";

    // ── Routing keys ─────────────────────────────────────────────────────────
    public static final String RK_NOTIFICATION = "booking.notification.event";
    public static final String RK_ANALYTICS    = "booking.analytics.event";

    /**
     * Special internal routing key for coin refund events.
     * These are NOT published to RabbitMQ — the OutboxRelayScheduler
     * reads them and calls IAM via Feign. Stored in outbox_events
     * with this routing key so CoinRefundRelayScheduler can filter them.
     */
    public static final String RK_COIN_REFUND  = "booking.coin.refund";

    /**
     * Special internal routing key for automatic coin withdrawal events.
     * These are NOT published to RabbitMQ — CoinWithdrawalRelayScheduler
     * reads them from outbox_events and calls the transfer provider.
     */
    public static final String RK_COIN_WITHDRAWAL = "booking.coin.withdrawal.event";

    // ── Queues ────────────────────────────────────────────────────────────────
    public static final String QUEUE_NOTIFICATION = "booking.notification.queue";
    public static final String QUEUE_ANALYTICS    = "booking.analytics.queue";
    public static final String DLQ_NOTIFICATION   = "booking.notification.dlq";
    public static final String DLQ_ANALYTICS      = "booking.analytics.dlq";

    // ── Exchange declaration ──────────────────────────────────────────────────
    @Bean
    public TopicExchange tourismEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    // ── DLQ queues ────────────────────────────────────────────────────────────
    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(DLQ_NOTIFICATION).build();
    }

    @Bean
    public Queue analyticsDlq() {
        return QueueBuilder.durable(DLQ_ANALYTICS).build();
    }

    // ── Main queues (point to their DLQ on rejection) ─────────────────────────
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATION)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_NOTIFICATION)
                .build();
    }

    @Bean
    public Queue analyticsQueue() {
        return QueueBuilder.durable(QUEUE_ANALYTICS)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_ANALYTICS)
                .build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────
    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(tourismEventsExchange)
                .with("booking.notification.*");
    }

    @Bean
    public Binding analyticsBinding(Queue analyticsQueue, TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(analyticsQueue)
                .to(tourismEventsExchange)
                .with("booking.analytics.*");
    }

    // ── Message converter (JSON) ──────────────────────────────────────────────
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Enable publisher confirms so OutboxRelayScheduler can mark SENT
     * only after the broker has acked the message.
     * spring.rabbitmq.publisher-confirm-type=correlated must be set in application.yml.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setMandatory(true); // trigger returns callback on unroutable messages
        return template;
    }
}
