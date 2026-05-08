package com.tourism.iam.feign;

import com.tourism.iam.dto.request.UserStatusEventDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Calls notification-service to push WebSocket update to /topic/admin/users.
 * Fire-and-forget: exceptions are caught so the main transaction is never rolled back.
 */
@FeignClient(name = "notification-service")
public interface NotificationFeignClient {

    @PostMapping("/api/notifications/user-status-updated")
    void notifyUserStatusUpdated(@RequestBody UserStatusEventDTO event);
}
