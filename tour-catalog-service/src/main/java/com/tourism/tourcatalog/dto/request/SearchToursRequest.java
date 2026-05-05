package com.tourism.tourcatalog.dto.request;

import lombok.*;
import java.math.BigDecimal;

/**
 * Request DTO cho GET /api/tours/search
 * Frontend gửi lên qua query params (@ModelAttribute / @RequestParam).
 *
 * Mapping từ frontend SearchToursRequestDTO.toPlain():
 *   searchNameTour  — tên tour / điểm đến (fuzzy)
 *   startPrice      — giá ADULT tối thiểu (0 = không lọc)
 *   endPrice        — giá ADULT tối đa (999999999 = không lọc)
 *   startLocationID — ID điểm khởi hành (null = tất cả)
 *   endLocationID   — ID điểm đến (null = tất cả)
 *   transportation  — phương tiện, ví dụ "Máy bay" (null/"" = tất cả)
 *   rating          — đánh giá tối thiểu 1-5 (null/0 = tất cả)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchToursRequest {
    private String     searchNameTour;
    private BigDecimal startPrice;
    private BigDecimal endPrice;
    private Integer    startLocationID;
    private Integer    endLocationID;
    private String     transportation;
    private Integer    rating;
    private Integer    userId;
}
