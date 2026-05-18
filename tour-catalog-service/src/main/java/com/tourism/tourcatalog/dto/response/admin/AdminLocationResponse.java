package com.tourism.tourcatalog.dto.response.admin;

import com.tourism.tourcatalog.entity.Region;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminLocationResponse {
    private Integer locationID;
    private String name;
    private String slug;
    private String image;
    private Region region;
    private String description;
    private String airportCode;
    private String airportName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long toursAsStartPoint;
    private Long toursAsEndPoint;
    private boolean status;
}
