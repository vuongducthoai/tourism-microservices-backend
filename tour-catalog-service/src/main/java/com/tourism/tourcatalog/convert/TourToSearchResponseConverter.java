package com.tourism.tourcatalog.convert;

import com.tourism.tourcatalog.dto.response.DepartureDateItem;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.DepartureTransport;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.entity.TransportType;
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
 * Logic lọc departure hợp lệ (BẮT BUỘC cả 4 điều kiện):
 *  1. status = true
 *  2. Có ít nhất 1 DeparturePricing (có dữ liệu giá)
 *  3. BẮT BUỘC có DepartureTransport loại OUTBOUND với departTime != null
 *     → Không có OUTBOUND transport thì KHÔNG hiển thị (bỏ qua)
 *  4. OUTBOUND departTime phải trong tương lai (>= now)
 *     → Đã qua rồi thì KHÔNG hiển thị
 *  Ngày hiển thị: OUTBOUND departTime.toLocalDate()
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

        LocalDateTime now = LocalDateTime.now();

        if (tour.getDepartures() != null) {
            for (TourDeparture dep : tour.getDepartures()) {
                // 1. Status phải active
                if (!Boolean.TRUE.equals(dep.getStatus())) continue;

                // 2. Phải có ít nhất 1 pricing (có dữ liệu giá)
                if (dep.getPricings() == null || dep.getPricings().isEmpty()) continue;

                // 3. BẮT BUỘC: tìm OUTBOUND transport với departTime hợp lệ
                LocalDateTime outboundDepartTime = null;
                if (dep.getTransports() != null) {
                    for (DepartureTransport t : dep.getTransports()) {
                        if (TransportType.OUTBOUND.equals(t.getTransportType())
                                && t.getDepartTime() != null) {
                            outboundDepartTime = t.getDepartTime();
                            break;
                        }
                    }
                }

                // Không có OUTBOUND transport → bỏ qua, không hiển thị
                if (outboundDepartTime == null) continue;

                // 4. OUTBOUND departTime phải trong tương lai
                if (outboundDepartTime.isBefore(now)) continue;

                // Ngày hiển thị: OUTBOUND departTime.toLocalDate()
                LocalDate displayDate = outboundDepartTime.toLocalDate();

                departureDates.add(DepartureDateItem.builder()
                        .departureID(dep.getDepartureID())
                        .departureDate(displayDate.toString())
                        .build());

                // Tính giá ADULT thấp nhất
                for (DeparturePricing p : dep.getPricings()) {
                    if ("ADULT".equals(p.getPassengerType()) && p.getSalePrice() != null) {
                        if (minAdultPrice == null || p.getSalePrice().compareTo(minAdultPrice) < 0) {
                            minAdultPrice = p.getSalePrice();
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
