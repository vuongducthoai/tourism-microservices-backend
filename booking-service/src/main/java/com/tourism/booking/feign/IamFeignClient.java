package com.tourism.booking.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "iam-service")
public interface IamFeignClient {

    /**
     * Add coins to user's balance after a coin-refund cancellation.
     * Maps to PATCH /api/users/{userId}/coins?amount=X in iam-service.
     */
    @PatchMapping("/api/users/{userId}/coins")
    void addCoins(@PathVariable Integer userId, @RequestParam BigDecimal amount);
}
