package com.tourism.forum.dto.moderation;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModerationResult {
    private double score;    // 0.0 – 1.0
    private String label;    // "SAFE" | "BORDERLINE" | "TOXIC"
    private String reason;   // giải thích ngắn tiếng Việt từ AI
}
