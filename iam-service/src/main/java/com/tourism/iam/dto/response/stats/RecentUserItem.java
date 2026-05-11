package com.tourism.iam.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentUserItem {
    private String fullName;
    private String email;
    private String createdAt;  // ISO datetime string
}
