package com.tourism.forum.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportRequest {
    @NotNull
    private String targetType;   // POST | COMMENT

    @NotNull
    private Integer targetId;

    @NotNull
    private String reason;        // ReportReason enum name

    private String detail;
}
