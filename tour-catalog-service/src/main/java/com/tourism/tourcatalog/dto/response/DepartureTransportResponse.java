package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartureTransportResponse {
    private String type;
    private String transportCode;
    private String vehicleType;
    private String vehicleName;
    private String startPoint;
    private String startPointName;
    private String endPoint;
    private String endPointName;
    private String departTime;
    private String arrivalTime;
}
