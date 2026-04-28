package com.tourism.tourcatalog.convert;

import com.tourism.tourcatalog.dto.response.DepartureDateItem;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tour -> TourSearchResponse (dùng cho GET /api/tours/search).
 *
 * Logic giống TourToDisplayResponseConverter nhưng trả thêm:
 *  - startPointName : tour.startLocation.name (điểm khởi hành)
 *  - departureDates : List<{departureID, departureDate}> — chỉ các departure
 *                     có status=true VÀ departureDate >= hôm nay, sort tăng dần
 *  - isFavorite     : false (auth service không có ở catalog service)
 */
@Component
public class TourToSearchResponseConverter implements Converter<Tour, TourSearchResponse> {

    @Override
    public TourSearchResponse convert(MappingContext<Tour, TourSearchResponse> context) {
        Tour tour = context.getSource();
        TourSearchResponse res = new TourSearchResponse();

        res.setTourID(tour.getTourID());
        res.setTourCode(tour.getTourCode());
        res.setTourName(tour.getTourName());
        res.setTransportation(tour.getTransportation());
        res.setDuration(tour.getDuration());
        res.setIsFavorite(false);

        // startPointName <- startLocation.name
        if (tour.getStartLocation() != null) {
            res.setStartPointName(tour.getStartLocation().getName());
        }

        // image <- imageUrl (microservices TourImage field)
        if (tour.getImages() != null && !tour.getImages().isEmpty()) {
            res.setImage(tour.getImages().get(0).getImageUrl());
        }

        // departureDates và money
        List<DepartureDateItem> departureDates = new ArrayList<>();
        BigDecimal minAdultPrice = null;

        LocalDate today = LocalDate.now();

        if (tour.getDepartures() != null) {
            for (TourDeparture dep : tour.getDepartures()) {
                if (!Boolean.TRUE.equals(dep.getStatus())) continue;
                if (dep.getDepartureDate() == null) continue;

                LocalDate depDate = dep.getDepartureDate().toLocalDate();
                // Chỉ lấy các chuyến từ hôm nay trở về sau
                if (depDate.isBefore(today)) continue;

                departureDates.add(DepartureDateItem.builder()
                        .departureID(dep.getDepartureID())
                        .departureDate(depDate.toString())
                        .build());

                // Tính giá ADULT thấp nhất
                if (dep.getPricings() != null) {
                    for (DeparturePricing p : dep.getPricings()) {
                        if ("ADULT".equals(p.getPassengerType()) && p.getSalePrice() != null) {
                            if (minAdultPrice == null || p.getSalePrice().compareTo(minAdultPrice) < 0) {
                                minAdultPrice = p.getSalePrice();
                            }
                        }
                    }
                }
            }
        }

        // Sort ngày tăng dần
        departureDates.sort(Comparator.comparing(DepartureDateItem::getDepartureDate));

        res.setDepartureDates(departureDates);
        res.setMoney(minAdultPrice != null ? minAdultPrice.longValue() : 0L);

        return res;
    }
}
