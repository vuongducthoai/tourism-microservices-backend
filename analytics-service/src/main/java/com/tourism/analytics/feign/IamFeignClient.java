package com.tourism.analytics.feign;

import com.tourism.analytics.dto.dashboard.feign.UserStatsResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "iam-service")
public interface IamFeignClient {

    @GetMapping("/api/admin/users/stats")
    UserStatsResponse getUserStats(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    );
}
