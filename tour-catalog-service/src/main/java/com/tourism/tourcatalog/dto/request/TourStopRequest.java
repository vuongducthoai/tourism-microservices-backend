package com.tourism.tourcatalog.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourStopRequest {

    @NotBlank(message = "Tên điểm dừng không được trống")
    private String name;

    @NotNull
    @DecimalMin(value = "-90",  message = "Lat phải >= -90")
    @DecimalMax(value = "90",   message = "Lat phải <= 90")
    private Double latitude;

    @NotNull
    @DecimalMin(value = "-180", message = "Lng phải >= -180")
    @DecimalMax(value = "180",  message = "Lng phải <= 180")
    private Double longitude;

    @NotNull
    private Integer stopOrder;

    private String description;
    private String stopType;

    /** FE gửi 1 trong 2: itineraryDayId hoặc dayNumber. BE ưu tiên itineraryDayId. */
    private Integer itineraryDayId;
    private Integer dayNumber;
}
