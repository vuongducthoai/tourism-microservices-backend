package com.tourism.iam.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology cho iam-service — nhận event cộng coin từ forum-service
 * (PLAN_FORUM_COIN_REWARD §5.B). Khai báo idempotent (re-declare không lỗi).
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "tourism.events";
    public static final String QUEUE_FORUM_COIN_REWARD = "forum.coin.reward.queue";
    public static final String DLQ_FORUM_COIN_REWARD = "forum.coin.reward.dlq";
    public static final String ROUTING_KEY_FORUM_COIN_REWARD = "forum.coin.reward";

    @Bean
    public TopicExchange tourismEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue forumCoinRewardDlq() {
        return QueueBuilder.durable(DLQ_FORUM_COIN_REWARD).build();
    }

    @Bean
    public Queue forumCoinRewardQueue() {
        return QueueBuilder.durable(QUEUE_FORUM_COIN_REWARD)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_FORUM_COIN_REWARD)
                .build();
    }

    @Bean
    public Binding forumCoinRewardBinding(Queue forumCoinRewardQueue,
                                          TopicExchange tourismEventsExchange) {
        return BindingBuilder.bind(forumCoinRewardQueue)
                .to(tourismEventsExchange)
                .with(ROUTING_KEY_FORUM_COIN_REWARD);
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

    /**
     * Listener container dùng JSON converter.
     * Dùng configurer để các cấu hình spring.rabbitmq.listener.simple.* trong yml
     * (retry 3 lần, default-requeue-rejected=false → DLQ) vẫn được áp dụng.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        return factory;
    }
}
