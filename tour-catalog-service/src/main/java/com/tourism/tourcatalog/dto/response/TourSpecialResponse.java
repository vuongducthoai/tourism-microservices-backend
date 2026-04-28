package com.tourism.tourcatalog.dto.response;

import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourSpecialResponse {
    private Integer    departureID;
    private Integer    tourID;
    private String     tourName;
    private String     tourCode;
    /** Tên điểm khởi hành (startLocation.name) */
    private String     startLocationName;
    private String     duration;
    /** Ngày khởi hành dạng "yyyy-MM-dd" */
    private String     departureDate;
    private Integer    availableSlots;
    /** Giá sale ADULT (DeparturePricing.price) */
    private BigDecimal salePrice;
    /** Giá gốc ADULT */
    private BigDecimal originalPrice;
    /** % giảm giá: (original - sale) / original * 100 */
    private Integer    discountPercentage;
    private String     image;
}
