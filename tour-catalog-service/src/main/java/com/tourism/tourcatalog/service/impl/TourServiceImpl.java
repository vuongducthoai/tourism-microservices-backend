package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.TourService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourServiceImpl implements TourService {

    private final TourRepository          tourRepository;
    private final TourDepartureRepository departureRepository;
    private final ModelMapper             modelMapper;

    /**
     * GET /api/tours/display
     *
     * Lấy tất cả tour active rồi map sang TourDisplayResponse qua converter.
     * TourToDisplayResponseConverter:
     *   - Traverse tour.departures -> lấy danh sách date và min price ADULT
     *   - Lazy-load tour.images trong @Transactional session
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourDisplayResponse> getAllToursForDisplay() {
        List<Tour> tours = tourRepository.findAllActiveWithDetails();
        return tours.stream()
                .map(t -> modelMapper.map(t, TourDisplayResponse.class))
                .collect(Collectors.toList());
    }

    /**
     * GET /api/tours/deepest-discount
     *
     * Logic:
     * 1. Lấy tất cả departure active, ngày >= hôm nay, có ADULT discount
     * 2. Map sang TourSpecialResponse — converter tính discountPercentage
     * 3. Sort giảm dần theo discountPercentage (trong Java stream, không ORDER BY JPQL)
     * 4. Lấy top 10
     *
     * Lazy-load tour.images trong @Transactional session (mỗi tour 1 SELECT nhỏ).
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourSpecialResponse> getTop10DeepestDiscountTours() {
        List<TourDeparture> departures =
                departureRepository.findActiveDiscountedDepartures(LocalDateTime.now());

        return departures.stream()
                .map(d -> modelMapper.map(d, TourSpecialResponse.class))
                .filter(r -> r.getDiscountPercentage() != null && r.getDiscountPercentage() > 0)
                // Dedup theo tourCode: mỗi tour chỉ giữ 1 departure có discount cao nhất
                .collect(Collectors.toMap(
                        TourSpecialResponse::getTourCode,
                        r -> r,
                        (existing, replacement) ->
                                existing.getDiscountPercentage() >= replacement.getDiscountPercentage()
                                        ? existing : replacement
                ))
                .values().stream()
                .sorted(Comparator.comparingInt(TourSpecialResponse::getDiscountPercentage).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    /**
     * GET /api/tours/search?searchNameTour=...&startPrice=...&endPrice=...
     *                       &startLocationID=...&endLocationID=...
     *                       &transportation=...&rating=...
     *
     * Logic:
     * - TourRepositoryCustomImpl.searchToursDynamically xây JPQL động:
     *     + nameParam null -> bỏ filter tên
     *     + startLocId/endLocId null -> bỏ filter location
     *     + transportParam null -> bỏ filter phương tiện
     *     + minRating null/0 -> bỏ filter rating (AVG reviews)
     *     + minPrice/maxPrice -> filter MIN(ADULT price) trong [start, end]
     * - Kết quả map sang TourDisplayResponse cùng converter với /display
     * - Lazy-load images và pricings trong @Transactional session
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourSearchResponse> searchTours(SearchToursRequest request) {
        List<Tour> tours = tourRepository.searchToursDynamically(request);
        return tours.stream()
                .map(t -> modelMapper.map(t, TourSearchResponse.class))
                // Lọc bỏ tour không có ngày khởi hành trong tương lai
                .filter(r -> r.getDepartureDates() != null && !r.getDepartureDates().isEmpty())
                .collect(Collectors.toList());
    }
}
