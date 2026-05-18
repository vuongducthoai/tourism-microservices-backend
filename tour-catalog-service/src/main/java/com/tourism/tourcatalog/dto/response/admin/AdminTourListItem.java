package com.tourism.tourcatalog.dto.response.admin;

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
public class AdminTourListItem {
    private Integer tourID;
    private String tourCode;
    private String tourName;
    private String duration;
    private String transportation;
    private String startLocationName;
    private String endLocationName;
    private String mainImageUrl;
    private Boolean status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    private Integer departureCount;
}
