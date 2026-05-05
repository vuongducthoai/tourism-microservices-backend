package com.tourism.tourcatalog.feign;

import com.tourism.tourcatalog.feign.dto.UserBriefResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Feign client for calling iam-service endpoints.
 * Used by ReviewServiceImpl to add reward coins after review submission
 * and to fetch user info (fullName, avatar) for review listing.
 */
@FeignClient(name = "iam-service")
public interface IamFeignClient {

    /**
     * GET /api/users/{userId}
     * Returns user profile (fullName, avatar, email, etc.).
     */
    @GetMapping("/api/users/{userId}")
    UserBriefResponse getUserById(@PathVariable Integer userId);

    /**
     * POST /api/users/{userId}/coins?amount=X
     * Adds coins to user's balance. Mirrors the same endpoint in booking-service's Feign.
     */
    @PostMapping("/api/users/{userId}/coins")
    void addCoins(@PathVariable Integer userId, @RequestParam BigDecimal amount);
}
