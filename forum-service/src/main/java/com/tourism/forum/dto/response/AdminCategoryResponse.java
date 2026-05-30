package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCategoryResponse {
    private Integer categoryId;
    private String name;
    private String slug;
    private String description;
    private String iconUrl;
    private String icon;
    private String color;
    private Integer displayOrder;
    private Boolean isActive;
    private Integer postCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
