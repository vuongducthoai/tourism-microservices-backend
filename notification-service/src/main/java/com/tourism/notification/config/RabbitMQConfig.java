package com.tourism.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mirror of booking-service RabbitMQConfig — same topology declarations.
 * RabbitMQ idempotently re-declares existing durable queues/exchanges.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE           = "tourism.events";
    public static final String QUEUE_NOTIFICATION = "booking.notification.queue";
    public static final String QUEUE_ANALYTICS    = "booking.analytics.queue";
    public static final String QUEUE_FORUM_NOTIFICATION = "forum.notification.queue";
    public static final String DLQ_NOTIFICATION   = "booking.notification.dlq";
    public static final String DLQ_ANALYTICS      = "booking.analytics.dlq";
    public static final String DLQ_FORUM_NOTIFICATION = "forum.notification.dlq";

    @Bean
    public TopicExchange tourismEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(DLQ_NOTIFICATION).build();
    }

    @Bean
    public Queue analyticsDlq() {
        return QueueBuilder.durable(DLQ_ANALYTICS).build();
    }

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

    @Bean
    public Queue forumNotificationDlq() {
        return QueueBuilder.durable(DLQ_FORUM_NOTIFICATION).build();
    }

    
    @Bean
    public Queue forumNotificationQueue() {
        return QueueBuilder.durable(QUEUE_FORUM_NOTIFICATION)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_FORUM_NOTIFICATION)
                .build();
    }

    @Bean
    public Binding forumNotificationBinding(Queue forumNotificationQueue,
                                            TopicExchange tourismEventsExchange) {
        // "forum.notification.*" khớp với mọi routing key bắt đầu bằng "forum.notification."
        return BindingBuilder.bind(forumNotificationQueue)
                .to(tourismEventsExchange)
                .with("forum.notification.*");
    }

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

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    /** Configure listener container to use JSON converter */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return factory;
    }
}
