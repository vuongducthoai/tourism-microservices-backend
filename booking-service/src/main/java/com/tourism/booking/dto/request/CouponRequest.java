package com.tourism.booking.dto.request;

import com.tourism.booking.entity.CouponType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CouponRequest {

    @NotBlank(message = "Mã coupon không được để trống")
    @Pattern(regexp = "^[A-Z0-9]+$", message = "Mã coupon chỉ gồm chữ hoa và số")
    private String couponCode;

    private String description;

    @NotNull(message = "Số tiền giảm không được để trống")
    @Min(value = 1, message = "Số tiền giảm phải lớn hơn 0")
    private Integer discountAmount;

    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Integer usageLimit;
    private BigDecimal minOrderValue;

    @NotNull(message = "Loại coupon không được để trống")
    private CouponType couponType;

    private List<Integer> departureIds;
    private Boolean sendNotification;
}
