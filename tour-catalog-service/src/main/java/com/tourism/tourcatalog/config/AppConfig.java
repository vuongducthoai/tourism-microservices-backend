package com.tourism.tourcatalog.config;

import com.tourism.tourcatalog.convert.TourDepartureToSpecialConverter;
import com.tourism.tourcatalog.convert.TourToDisplayResponseConverter;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.entity.Location;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * ModelMapper được cấu hình với:
     * 1. TourToDisplayResponseConverter     — Tour -> TourDisplayResponse (logic phức tạp, có traversal)
     * 2. TourDepartureToSpecialConverter    — TourDeparture -> TourSpecialResponse
     * 3. TypeMap Location -> LocationResponse  — map image -> imageUrl
     * 4. TypeMap Location -> DestinationResponse — map name/image/region sang tên khác
     */
    @Bean
    public ModelMapper modelMapper(TourToDisplayResponseConverter displayConverter,
                                   TourDepartureToSpecialConverter specialConverter) {
        ModelMapper mm = new ModelMapper();

        // Custom converters cho logic phức tạp (traversal qua quan hệ)
        mm.addConverter(displayConverter);
        mm.addConverter(specialConverter);

        // Location -> LocationResponse: field image -> imageUrl (tên khác nhau)
        mm.typeMap(Location.class, LocationResponse.class)
                .addMappings(m -> m.map(Location::getImage, LocationResponse::setImageUrl));

        // Location -> DestinationResponse: map name->endPoint, image->listImage
        // region is mapped manually in LocationServiceImpl (chained lambda not supported by ModelMapper)
        mm.typeMap(Location.class, DestinationResponse.class)
                .addMappings(m -> {
                    m.map(Location::getName,  DestinationResponse::setEndPoint);
                    m.map(Location::getImage, DestinationResponse::setListImage);
                });

        return mm;
    }
}
