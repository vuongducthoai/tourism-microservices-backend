package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Số khách đã đặt của một lịch khởi hành.
 * tour-catalog-service gọi qua Feign để hiển thị cột "đã đặt" ở trang Quản lý Lịch khởi hành.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartureBookedCountResponse {
    private Integer departureId;
    private Integer bookedPassengers;
}
