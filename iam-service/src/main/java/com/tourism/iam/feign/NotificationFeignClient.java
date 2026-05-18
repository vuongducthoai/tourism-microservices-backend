package com.tourism.iam.feign;

import com.tourism.iam.dto.request.UserStatusEventDTO;
import com.tourism.iam.dto.request.VerificationEmailRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service")
public interface NotificationFeignClient {

    @PostMapping("/api/notifications/user-status-updated")
    void notifyUserStatusUpdated(@RequestBody UserStatusEventDTO event);

    @PostMapping("/api/notifications/send-verification-email")
    void sendVerificationEmail(@RequestBody VerificationEmailRequest request);
}
