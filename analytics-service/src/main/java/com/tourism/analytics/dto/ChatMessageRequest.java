package com.tourism.analytics.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageRequest {
    private String  message;
    private String  sessionId;
    private Integer userId;
}
