package com.tourism.tourcatalog.dto.response;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DestinationResponse {
    private Integer locationID;
    /** Map từ location.name */
    private String  endPoint;
    /** Map từ location.image */
    private String  listImage;
    /** Map từ region.name() */
    private String  region;
}
