package com.tourism.tourcatalog.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ReviewRequest {
    private Integer rating;
    private String  comment;
    private Integer tourID;
    private Integer bookingID;
    private Integer userId;
}
