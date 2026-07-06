package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Nhóm lịch khởi hành theo tour — dùng cho giao diện accordion trang Quản lý Lịch khởi hành.
 * Mỗi nhóm là 1 tour, chứa danh sách các lịch khởi hành của tour đó cùng vài số liệu tổng hợp.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDepartureGroupResponse {
    private Integer tourID;
    private String tourCode;
    private String tourName;
    private String tourDuration;

    private Integer departureCount;      // số lịch khởi hành
    private Integer activeCount;         // số lịch đang hoạt động
    private Integer totalAvailableSlots; // tổng chỗ trống
    private Integer totalBooked;         // tổng khách đã đặt
    private BigDecimal lowestPrice;      // giá thấp nhất trong các lịch
    private String nextDepartureDate;    // ngày khởi hành gần nhất (đã format)

    private List<AdminDepartureListItem> departures;
}
