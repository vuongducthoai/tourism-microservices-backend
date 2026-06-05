package com.tourism.forum.feign;

import com.tourism.forum.feign.dto.UserBriefResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.Map;

@FeignClient(name = "iam-service")
public interface IamFeignClient {

    @GetMapping("/api/users/{userId}")
    UserBriefResponse getUserById(@PathVariable Integer userId);

    /** Trừ coin (thu hồi thưởng) — idempotent qua operationKey. Throw nếu số dư không đủ. */
    @PostMapping("/api/users/{userId}/deduct-coins")
    void deductCoins(@PathVariable Integer userId,
                     @RequestParam("amount") BigDecimal amount,
                     @RequestParam("operationKey") String operationKey);

    /** Đối soát: tổng coin forum đã cộng/thu hồi ghi nhận bên IAM. */
    @GetMapping("/api/users/coin-stats/forum")
    Map<String, Object> getForumCoinStats();
}
