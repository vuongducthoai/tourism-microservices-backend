package com.tourism.iam.listener;

import com.tourism.iam.config.RabbitMQConfig;
import com.tourism.iam.dto.ForumCoinRewardEvent;
import com.tourism.iam.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer cộng coin thưởng forum (PLAN_FORUM_COIN_REWARD §5.B.15).
 *
 * Idempotent: UserService.addCoins() bỏ qua nếu operationKey đã xử lý
 * (UNIQUE constraint trên coin_transactions.operation_key) → retry/duplicate
 * event không bao giờ cộng 2 lần.
 *
 * Lỗi vĩnh viễn (payload hỏng, user không tồn tại) → DLQ, không requeue vô hạn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForumCoinRewardListener {

    private final UserService userService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FORUM_COIN_REWARD)
    public void onForumCoinReward(ForumCoinRewardEvent event) {
        if (event == null || event.getUserId() == null || event.getAmount() == null
                || event.getOperationKey() == null || event.getOperationKey().isBlank()) {
            log.error("Invalid forum coin reward event (dropped → DLQ): {}", event);
            throw new AmqpRejectAndDontRequeueException("Invalid forum coin reward payload");
        }
        if (event.getAmount().signum() <= 0) {
            log.error("Non-positive coin amount (dropped → DLQ): {}", event);
            throw new AmqpRejectAndDontRequeueException("Non-positive coin amount");
        }

        log.info("Received forum coin reward: key={}, userId={}, amount={}, action={}",
                event.getOperationKey(), event.getUserId(), event.getAmount(), event.getAction());

        try {
            // Idempotent qua operationKey — duplicate event được bỏ qua bên trong addCoins
            userService.addCoins(event.getUserId(), event.getAmount(), event.getOperationKey());
        } catch (RuntimeException e) {
            // User not found = lỗi vĩnh viễn → DLQ; còn lại để Spring retry theo cấu hình
            if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                log.error("User not found for coin reward (dropped → DLQ): key={}, userId={}",
                        event.getOperationKey(), event.getUserId());
                throw new AmqpRejectAndDontRequeueException("User not found: " + event.getUserId(), e);
            }
            log.error("Failed to credit forum coin reward (will retry): key={}, error={}",
                    event.getOperationKey(), e.getMessage());
            throw e;
        }
    }
}
