package com.tourism.forum.dto.request;

import com.tourism.forum.entity.ContentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPostFilterRequest {
    private ContentStatus status;
    private Integer categoryId;
    private String postType;
    private Integer userId;
    private String search;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String moderationLabel;
}
