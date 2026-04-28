package com.tourism.tourcatalog.dto.response;

import lombok.*;
import java.util.List;

/**
 * Response DTO cho GET /api/tours/search — trang /tours của frontend.
 *
 * Khác với TourDisplayResponse (dùng cho homepage), response này có:
 *  - startPointName : tên điểm khởi hành (tour.startLocation.name)
 *  - departureDates : danh sách {departureID, departureDate} để user click đặt tour
 *  - isFavorite     : trạng thái yêu thích của user (mặc định false, cần auth service)
 *
 * Frontend TourResponseDTO.fromApiResponse() map:
 *   tourID, tourCode, tourName, startPointName, transportation, duration,
 *   departureDates[], money, image, isFavorite
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TourSearchResponse {
    private Integer               tourID;
    private String                tourCode;
    private String                tourName;
    /** Tên điểm khởi hành — frontend hiển thị "Khởi hành: TP. Hồ Chí Minh" */
    private String                startPointName;
    private String                transportation;
    private String                duration;
    /**
     * Danh sách ngày khởi hành (chỉ các departure còn active, ngày >= hôm nay).
     * Mỗi phần tử: { departureID, departureDate("yyyy-MM-dd") }
     * Frontend dùng departureDate để hiển thị badge ngày, departureID để điều hướng.
     */
    private List<DepartureDateItem> departureDates;
    /** Giá ADULT thấp nhất trong tất cả departure active (VND) */
    private Long                  money;
    /** URL ảnh đầu tiên của tour */
    private String                image;
    /** Trạng thái yêu thích — mặc định false, cần auth service để override */
    private Boolean               isFavorite;
}
