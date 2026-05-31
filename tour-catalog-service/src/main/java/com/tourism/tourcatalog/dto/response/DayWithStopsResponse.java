package com.tourism.tourcatalog.dto.response;

import lombok.*;

import java.util.List;

/**
 * 1 ngày của itinerary kèm danh sách điểm dừng đã gắn vào day đó.
 * Dùng cho endpoint composite /itinerary-with-route.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayWithStopsResponse {
    private Integer itineraryDayId;
    private Integer dayNumber;
    private String title;

    /** BE compute on-the-fly từ stop names: "Cảng Tuần Châu → Hang Sửng Sốt → Hang Luồn". */
    private String autoSubtitle;

    private String meals;
    private String details;

    /** Màu hiển thị cho day (cycle theo dayNumber). */
    private String color;

    private List<StopWithIndexResponse> stops;
}
