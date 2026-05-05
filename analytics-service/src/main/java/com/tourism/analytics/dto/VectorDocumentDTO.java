package com.tourism.analytics.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorDocumentDTO {

    private String       id;
    private String       content;
    private String       type;
    private Integer      entityId;
    private List<Float>  embedding;
    private String       metadata;
    private Float        score;
}
