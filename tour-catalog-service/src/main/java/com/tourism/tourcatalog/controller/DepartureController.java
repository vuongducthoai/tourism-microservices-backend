package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.DepartureInfoResponse;
import com.tourism.tourcatalog.dto.response.TourBookingInfoResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.DepartureTransport;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.repository.LocationRepository;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/departures")
@RequiredArgsConstructor
@Tag(name = "Departures", description = "[Internal] Lịch khởi hành — booking-service truy vấn thông tin chuyến đi qua Feign")
public class DepartureController {

    private final TourDepartureRepository tourDepartureRepository;
    private final TourRepository tourRepository;
    private final LocationRepository locationRepository;

    @Operation(summary = "[Internal] Lấy thông tin departure", description = "Trả về tour + departure info theo departureId — dung nội bộ")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thông tin departure"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy departure")
    })
    @GetMapping("/{departureId}")
    public ResponseEntity<DepartureInfoResponse> getDepartureInfo(@PathVariable Integer departureId) {
        TourDeparture dep = tourDepartureRepository.findById(departureId)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + departureId));
        return ResponseEntity.ok(toDepartureInfo(dep));
    }

    @Operation(summary = "[Internal] Lấy tất cả lịch gắn 1 coupon",
            description = "Trả về danh sách lịch khởi hành đang gắn couponId — booking-service dùng để hiển thị/sửa coupon nhiều lịch.")
    @ApiResponse(responseCode = "200", description = "Danh sách lịch")
    @GetMapping("/by-coupon/{couponId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<DepartureInfoResponse>> getDeparturesByCoupon(@PathVariable Integer couponId) {
        List<DepartureInfoResponse> list = tourDepartureRepository.findByCouponIdWithTour(couponId)
                .stream().map(this::toDepartureInfo).toList();
        return ResponseEntity.ok(list);
    }

    private DepartureInfoResponse toDepartureInfo(TourDeparture dep) {
        DepartureInfoResponse res = new DepartureInfoResponse();
        res.setDepartureID(dep.getDepartureID());
        if (dep.getDepartureDate() != null) {
            res.setDepartureDate(dep.getDepartureDate().toString());
        }
        if (dep.getTour() != null) {
            var tour = dep.getTour();
            res.setTourID(tour.getTourID());
            res.setTourCode(tour.getTourCode());
            res.setTourName(tour.getTourName());
            res.setDuration(tour.getDuration());
            if (tour.getImages() != null && !tour.getImages().isEmpty()) {
                res.setImage(tour.getImages().get(0).getImageUrl());
            }
        }
        return res;
    }

    @Operation(summary = "[Internal] Thông tin booking form", description = "Trả về pricing, transport, couponId — booking-service gọi qua Feign để hiển thị trang đặt tour")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thành công"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy departure")
    })
    @GetMapping("/order-info")
    @Transactional(readOnly = true)
    public ResponseEntity<TourBookingInfoResponse> getOrderInfo(
            @RequestParam(required = false) String tourCode,
            @RequestParam Integer departureId) {

        TourDeparture dep = tourDepartureRepository.findById(departureId)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + departureId));

        TourBookingInfoResponse res = new TourBookingInfoResponse();
        res.setAvailableSlots(dep.getAvailableSlots());
        res.setCouponId(dep.getCouponId());

        var tour = dep.getTour();
        if (tour != null) {
            res.setTourId(tour.getTourID());
            res.setTourCode(tour.getTourCode());
            res.setTourName(tour.getTourName());
            if (tour.getImages() != null && !tour.getImages().isEmpty()) {
                res.setImage(tour.getImages().get(0).getImageUrl());
            }
        }

        // Map pricings
        List<DeparturePricing> pricings = dep.getPricings();
        if (pricings != null) {
            for (DeparturePricing p : pricings) {
                String type = p.getPassengerType() != null ? p.getPassengerType().toUpperCase() : "";
                switch (type) {
                    case "ADULT"              -> res.setAdultPrice(p.getSalePrice());
                    case "CHILD"              -> res.setChildPrice(p.getSalePrice());
                    case "TODDLER"            -> res.setToddlerPrice(p.getSalePrice());
                    case "INFANT"             -> res.setInfantPrice(p.getSalePrice());
                    case "SINGLE_SUPPLEMENT"  -> res.setSingleRoomSurcharge(p.getSalePrice());
                }
            }
        }

        // Map transports — sort ASC, outbound = first, inbound = last
        List<DepartureTransport> transports = dep.getTransports();
        if (transports != null && !transports.isEmpty()) {
            transports.sort(Comparator.comparing(
                    DepartureTransport::getDepartTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            res.setOutboundFlight(toFlightInfo(transports.get(0)));
            if (transports.size() > 1) {
                res.setInboundFlight(toFlightInfo(transports.get(transports.size() - 1)));
            }
        }

        return ResponseEntity.ok(res);
    }

    @Operation(summary = "[Internal] Giảm số chỗ trống", description = "Atomic decrease — trả 400 nếu không đủ chỗ. booking-service gọi qua Feign khi tạo booking")
    @ApiResponse(responseCode = "200", description = "Giảm thành công")
    @ApiResponse(responseCode = "400", description = "Không đủ chỗ trống")
    @PostMapping("/{departureId}/decrease-slots")
    @Transactional
    public ResponseEntity<Void> decreaseSlots(
            @PathVariable Integer departureId,
            @RequestParam int count) {
        int updated = tourDepartureRepository.decreaseAvailableSlots(departureId, count);
        if (updated == 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Tăng số chỗ trống", description = "Trả lại slot khi booking bị hủy. booking-service gọi qua Feign khi hủy booking")
    @ApiResponse(responseCode = "200", description = "Tăng thành công")
    @PostMapping("/{departureId}/increase-slots")
    @Transactional
    public ResponseEntity<Void> increaseSlots(
            @PathVariable Integer departureId,
            @RequestParam int count) {
        tourDepartureRepository.increaseAvailableSlots(departureId, count);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Gán coupon cho các lịch khởi hành",
            description = "Gỡ coupon khỏi mọi lịch cũ rồi gắn couponId cho các lịch được chọn. booking-service gọi khi tạo/sửa coupon theo tour.")
    @ApiResponse(responseCode = "200", description = "Đồng bộ thành công")
    @PutMapping("/coupon-assignment")
    @Transactional
    public ResponseEntity<Void> assignCouponToDepartures(
            @RequestParam Integer couponId,
            @RequestBody(required = false) List<Integer> departureIds) {
        // Luôn gỡ couponId này khỏi mọi lịch trước (tránh sót lịch cũ), rồi gắn lại đúng lịch được chọn
        tourDepartureRepository.clearCoupon(couponId);
        if (departureIds != null && !departureIds.isEmpty()) {
            tourDepartureRepository.setCouponForDepartures(couponId, departureIds);
        }
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Internal] Gỡ coupon khỏi mọi lịch",
            description = "Xóa couponId khỏi tất cả lịch đang gắn. booking-service gọi khi xóa coupon.")
    @ApiResponse(responseCode = "200", description = "Gỡ thành công")
    @DeleteMapping("/coupon-assignment/{couponId}")
    @Transactional
    public ResponseEntity<Void> clearCouponFromDepartures(@PathVariable Integer couponId) {
        tourDepartureRepository.clearCoupon(couponId);
        return ResponseEntity.ok().build();
    }

    private TourBookingInfoResponse.FlightInfo toFlightInfo(DepartureTransport t) {
        TourBookingInfoResponse.FlightInfo f = new TourBookingInfoResponse.FlightInfo();
        f.setVehicleType(t.getVehicleType() != null ? t.getVehicleType().name() : null);
        f.setVehicleName(t.getVehicleName());
        f.setStartPoint(t.getStartPoint());
        f.setEndPoint(t.getEndPoint());
        f.setDepartTime(t.getDepartTime() != null ? t.getDepartTime().toString() : null);
        f.setArrivalTime(t.getArrivalTime() != null ? t.getArrivalTime().toString() : null);
        // Resolve airport names from location table
        if (t.getStartPoint() != null) {
            locationRepository.findByAirportCode(t.getStartPoint())
                    .ifPresent(loc -> f.setStartPointName(loc.getName()));
        }
        if (t.getEndPoint() != null) {
            locationRepository.findByAirportCode(t.getEndPoint())
                    .ifPresent(loc -> f.setEndPointName(loc.getName()));
        }
        return f;
    }
}
