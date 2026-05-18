package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelatedTourResponse {
    private Integer tourId;
    private String tourCode;
    private String tourName;
    private String startLocation;
    private String duration;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String image;
}
