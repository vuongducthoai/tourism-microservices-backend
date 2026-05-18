package com.tourism.forum.feign;

import com.tourism.forum.feign.dto.UserBriefResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "iam-service")
public interface IamFeignClient {

    @GetMapping("/api/users/{userId}")
    UserBriefResponse getUserById(@PathVariable Integer userId);
}
