package com.tourism.tourcatalog.convert;

import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Tour -> TourDisplayResponse
 *
 * Logic:
 *  - endPointName   <- tour.endLocation.name
 *  - departureDate  <- departures status=true -> departureDate.toString() (LocalDate = "yyyy-MM-dd")
 *  - money          <- giá ADULT thấp nhất (Long) qua tất cả departures active
 *  - image          <- tour.images.get(0).imageUrl (field của microservices TourImage)
 */
@Component
public class TourToDisplayResponseConverter implements Converter<Tour, TourDisplayResponse> {

    @Override
    public TourDisplayResponse convert(MappingContext<Tour, TourDisplayResponse> context) {
        Tour tour = context.getSource();
        TourDisplayResponse res = new TourDisplayResponse();

        res.setTourID(tour.getTourID());
        res.setTourCode(tour.getTourCode());
        res.setTourName(tour.getTourName());
        res.setTransportation(tour.getTransportation());
        res.setDuration(tour.getDuration());

        // endPointName <- endLocation.name
        if (tour.getEndLocation() != null) {
            res.setEndPointName(tour.getEndLocation().getName());
        }

        // image <- imageUrl (microservices TourImage field)
        if (tour.getImages() != null && !tour.getImages().isEmpty()) {
            res.setImage(tour.getImages().get(0).getImageUrl());
        }

        // departureDate[] và money
        List<String> dates = new ArrayList<>();
        BigDecimal minAdultPrice = null;

        if (tour.getDepartures() != null) {
            for (TourDeparture dep : tour.getDepartures()) {
                if (!Boolean.TRUE.equals(dep.getStatus())) continue;

                // format to "yyyy-MM-dd" regardless of LocalDate or LocalDateTime
                if (dep.getDepartureDate() != null) {
                    dates.add(dep.getDepartureDate().toLocalDate().toString());
                }

                if (dep.getPricings() != null) {
                    for (DeparturePricing p : dep.getPricings()) {
                        // passengerType là String trong microservices entity
                        if ("ADULT".equals(p.getPassengerType()) && p.getSalePrice() != null) {
                            if (minAdultPrice == null || p.getSalePrice().compareTo(minAdultPrice) < 0) {
                                minAdultPrice = p.getSalePrice();
                            }
                        }
                    }
                }
            }
        }

        res.setDepartureDate(dates);
        res.setMoney(minAdultPrice != null ? minAdultPrice.longValue() : 0L);

        return res;
    }
}
