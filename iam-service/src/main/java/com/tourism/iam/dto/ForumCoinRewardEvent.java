package com.tourism.iam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Event cộng coin từ forum-service (routing key "forum.coin.reward").
 * Mirror của com.tourism.forum.dto.event.CoinRewardEvent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumCoinRewardEvent {
    private String operationKey;
    private Integer userId;
    private BigDecimal amount;
    private String action;
    private String reason;
    private Integer refId;
}
