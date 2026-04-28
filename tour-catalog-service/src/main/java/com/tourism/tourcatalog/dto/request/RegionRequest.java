package com.tourism.tourcatalog.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegionRequest {
    @NotBlank(message = "region is required")
    private String region; // "NORTH" | "CENTRAL" | "SOUTH"
}
