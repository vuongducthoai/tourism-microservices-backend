package com.tourism.forum.dto.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PostFilterRequest {
    private Integer categoryId;
    private Integer tagId;
    private String postType;
    private String search;
}
