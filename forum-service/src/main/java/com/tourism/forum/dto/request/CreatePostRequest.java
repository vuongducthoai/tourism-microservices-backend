package com.tourism.forum.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreatePostRequest {

    @NotNull(message = "userId không được để trống")
    private Integer userId;

    @NotBlank(message = "Tiêu đề không được để trống")
    @Size(max = 300, message = "Tiêu đề không được vượt quá 300 ký tự")
    private String title;

    @NotBlank(message = "Nội dung không được để trống")
    private String content;

    @Size(max = 200)
    private String summary;

    @NotNull(message = "Category ID không được để trống")
    private Integer categoryId;

    private String postType = "BLOG";

    private List<String> tagNames;

    private Boolean isDraft = false;
}
