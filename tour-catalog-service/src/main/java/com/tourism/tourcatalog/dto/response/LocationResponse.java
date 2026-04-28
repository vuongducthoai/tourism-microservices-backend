package com.tourism.tourcatalog.dto.response;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {
    private Integer locationID;
    private String  name;
    /** Map từ location.image (field khác tên!) */
    private String  imageUrl;
    private String  description;
}
