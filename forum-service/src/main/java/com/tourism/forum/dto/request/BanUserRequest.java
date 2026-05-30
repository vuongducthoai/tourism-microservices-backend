package com.tourism.forum.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BanUserRequest {
    private String reason;
    private Integer durationDays;   // null = cấm vĩnh viễn
}
