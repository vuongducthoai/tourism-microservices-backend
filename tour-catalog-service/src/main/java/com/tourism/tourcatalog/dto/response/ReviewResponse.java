package com.tourism.tourcatalog.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ReviewResponse {
    private Integer       reviewID;
    private Integer       rating;
    private String        comment;
    private String        bookingCode;
    private String        tourCode;
    private List<String>  imageUrls;
}
