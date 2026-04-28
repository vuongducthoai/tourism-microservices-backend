package com.tourism.tourcatalog.convert;

import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * TourDeparture -> TourSpecialResponse (dùng cho endpoint deepest-discount)
 *
 * Logic:
 *  - startLocationName  <- departure.tour.startLocation.name
 *  - salePrice          <- DeparturePricing ADULT .salePrice  (map cột "price" trong DB)
 *  - originalPrice      <- DeparturePricing ADULT .originalPrice
 *  - discountPercentage <- (original - sale) / original * 100
 *  - image              <- tour.images.get(0).imageUrl
 */
@Component
public class TourDepartureToSpecialConverter implements Converter<TourDeparture, TourSpecialResponse> {

    @Override
    public TourSpecialResponse convert(MappingContext<TourDeparture, TourSpecialResponse> context) {
        TourDeparture dep = context.getSource();
        TourSpecialResponse res = new TourSpecialResponse();

        res.setDepartureID(dep.getDepartureID());
        res.setAvailableSlots(dep.getAvailableSlots());

        // format to "yyyy-MM-dd"
        if (dep.getDepartureDate() != null) {
            res.setDepartureDate(dep.getDepartureDate().toLocalDate().toString());
        }

        Tour tour = dep.getTour();
        if (tour != null) {
            res.setTourID(tour.getTourID());
            res.setTourName(tour.getTourName());
            res.setTourCode(tour.getTourCode());
            res.setDuration(tour.getDuration());

            // startLocationName <- startLocation.name
            if (tour.getStartLocation() != null) {
                res.setStartLocationName(tour.getStartLocation().getName());
            }

            // image <- imageUrl (microservices TourImage field)
            if (tour.getImages() != null && !tour.getImages().isEmpty()) {
                res.setImage(tour.getImages().get(0).getImageUrl());
            }
        }

        // Tìm pricing ADULT
        if (dep.getPricings() != null) {
            DeparturePricing adult = dep.getPricings().stream()
                    .filter(p -> "ADULT".equals(p.getPassengerType()))
                    .findFirst()
                    .orElse(null);

            if (adult != null) {
                // salePrice trong entity map sang cột "price" (DB microservices)
                res.setSalePrice(adult.getSalePrice());
                res.setOriginalPrice(adult.getOriginalPrice());

                // discountPercentage = (original - sale) / original * 100
                if (adult.getOriginalPrice() != null
                        && adult.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0
                        && adult.getSalePrice() != null) {
                    int pct = adult.getOriginalPrice()
                            .subtract(adult.getSalePrice())
                            .divide(adult.getOriginalPrice(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .intValue();
                    res.setDiscountPercentage(pct);
                }
            }
        }

        return res;
    }
}
