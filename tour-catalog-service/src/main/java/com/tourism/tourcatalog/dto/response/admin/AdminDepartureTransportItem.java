package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDepartureTransportItem {
    private String type;
    private String vehicleType;
    private String transportCode;
    private String vehicleName;
    private String startPoint;
    private String endPoint;
    private String departTime;
    private String arrivalTime;
}
