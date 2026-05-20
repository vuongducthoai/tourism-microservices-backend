package com.tourism.forum.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.validator.constraints.Length;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostUpdateRequest {

    @NotBlank(message = "Tiêu đề không được để trống")
    @Length(min = 5, max = 200, message = "Tiêu đề phải từ 5-200 ký tự")
    private String title;

    @NotBlank(message = "Nội dung không được để trống")
    @Length(min = 20, max = 5000, message = "Nội dung phải từ 20-5000 ký tự")
    private String content;

    @Length(max = 500, message = "Tóm tắt tối đa 500 ký tự")
    private String summary;

    private String thumbnailUrl;

    @NotNull(message = "Danh mục không được để trống")
    private Integer categoryId;

    private List<Integer> tagIds;
}
