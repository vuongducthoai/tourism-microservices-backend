package com.tourism.tourcatalog.dto.request.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartureTransportRequest {
    private String type;
    private String vehicleType;
    private String transportCode;
    private String vehicleName;
    private String startPoint;
    private String endPoint;
    private String departTime;
    private String arrivalTime;
}
