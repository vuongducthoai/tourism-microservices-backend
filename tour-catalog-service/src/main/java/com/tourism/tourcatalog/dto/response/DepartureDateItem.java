package com.tourism.tourcatalog.dto.response;

import lombok.*;

/**
 * Một phần tử trong danh sách ngày khởi hành của TourSearchResponse.
 * Frontend dùng departureID để navigate tới trang đặt tour.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartureDateItem {
    /** ID của departure — dùng để navigate /tour/{code}?departureId={id} */
    private Integer departureID;
    /** Ngày khởi hành dạng "yyyy-MM-dd" */
    private String  departureDate;
}
