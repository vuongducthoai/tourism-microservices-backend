package com.tourism.forum.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagRequest {

    @NotBlank(message = "Tên thẻ không được để trống")
    private String name;

    private String slug;
    private String color;
    private String description;
    private Boolean isActive;
}
