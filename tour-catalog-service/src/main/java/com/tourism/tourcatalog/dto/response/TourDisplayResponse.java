package com.tourism.tourcatalog.dto.response;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourDisplayResponse {
    private Integer      tourID;
    private String       tourCode;
    private String       tourName;
    /** Tên điểm đến cuối (endLocation.name) */
    private String       endPointName;
    private String       transportation;
    private String       duration;
    /** Danh sách ngày khởi hành dạng "yyyy-MM-dd" */
    private List<String> departureDate;
    /** Giá ADULT thấp nhất (đơn vị VND) */
    private Long         money;
    /** URL ảnh đầu tiên */
    private String       image;
}
