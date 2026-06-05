package com.tourism.forum.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Event cộng coin gửi sang iam-service qua RabbitMQ
 * (exchange tourism.events, routing key forum.coin.reward).
 * operationKey = idempotency key — IAM bỏ qua nếu đã xử lý.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinRewardEvent {
    private String operationKey;
    private Integer userId;
    private BigDecimal amount;
    /** POST | COMMENT | LIKE_MILESTONE | COMMENT_LIKE_MILESTONE | FOLLOW | DAILY */
    private String action;
    private String reason;
    private Integer refId;
}
