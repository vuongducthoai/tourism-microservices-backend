package com.tourism.tourcatalog.dto.request.admin;

import com.tourism.tourcatalog.entity.Region;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LocationRequest {

    @NotBlank(message = "Tên địa điểm không được để trống")
    @Size(max = 255, message = "Tên địa điểm không được quá 255 ký tự")
    private String name;

    @NotBlank(message = "Slug không được để trống")
    @Size(max = 255, message = "Slug không được quá 255 ký tự")
    private String slug;

    @NotNull(message = "Vùng miền không được để trống")
    private Region region;

    private String description;

    @Size(max = 10, message = "Mã sân bay không được quá 10 ký tự")
    private String airportCode;

    @Size(max = 255, message = "Tên sân bay không được quá 255 ký tự")
    private String airportName;

    private String image;
}
