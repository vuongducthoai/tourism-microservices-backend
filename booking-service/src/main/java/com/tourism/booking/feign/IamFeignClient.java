package com.tourism.booking.feign;

import com.tourism.booking.feign.dto.UserProfileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "iam-service")
public interface IamFeignClient {

    @PostMapping("/api/users/{userId}/coins")
    void addCoins(@PathVariable Integer userId, @RequestParam BigDecimal amount);

    @GetMapping("/api/users/{userId}")
    UserProfileResponse getUserProfile(@PathVariable Integer userId);

    @PostMapping("/api/users/{userId}/deduct-coins")
    void deductCoins(@PathVariable Integer userId, @RequestParam BigDecimal amount);
}
