package com.tourism.booking.controller;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.CreateBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingOrderResponse;
import com.tourism.booking.dto.response.BookingPaymentDetailResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.CouponChatbotSyncResponse;
import com.tourism.booking.dto.response.CreateBookingResponse;
import com.tourism.booking.repository.CouponRepository;
import com.tourism.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Dat tour, huy booking, hoan tien va coupon giam gia")
public class BookingController {

    private final BookingService    bookingService;
    private final CouponRepository  couponRepository;

    @Operation(summary = "[Internal] Chatbot sync coupons", description = "Endpoint noi bo - analytics-service lay coupon active cho Pinecone")
    @ApiResponse(responseCode = "200", description = "Danh sach coupon active")
    @GetMapping("/coupons/chatbot-sync")
    public ResponseEntity<List<CouponChatbotSyncResponse>> getCouponsForChatbotSync() {
        List<CouponChatbotSyncResponse> result = couponRepository.findActiveCoupons(LocalDateTime.now())
                .stream()
                .map(c -> CouponChatbotSyncResponse.builder()
                        .couponID(c.getCouponID())
                        .couponCode(c.getCouponCode())
                        .description(c.getDescription())
                        .discountAmount(c.getDiscountAmount())
                        .startDate(c.getStartDate() != null ? c.getStartDate().toString() : null)
                        .endDate(c.getEndDate() != null ? c.getEndDate().toString() : null)
                        .usageLimit(c.getUsageLimit())
                        .usageCount(c.getUsageCount())
                        .couponType(c.getCouponType() != null ? c.getCouponType().name() : "GLOBAL")
                        .departureId(c.getDepartureId())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "[Internal] Chatbot sync coupon by id", description = "Endpoint noi bo cho incremental sync")
    @GetMapping("/coupons/chatbot-sync/{couponId}")
    public ResponseEntity<CouponChatbotSyncResponse> getCouponForChatbotSync(@PathVariable Integer couponId) {
        return couponRepository.findActiveCoupons(LocalDateTime.now())
                .stream()
                .filter(c -> couponId.equals(c.getCouponID()))
                .map(c -> CouponChatbotSyncResponse.builder()
                        .couponID(c.getCouponID())
                        .couponCode(c.getCouponCode())
                        .description(c.getDescription())
                        .discountAmount(c.getDiscountAmount())
                        .startDate(c.getStartDate() != null ? c.getStartDate().toString() : null)
                        .endDate(c.getEndDate() != null ? c.getEndDate().toString() : null)
                        .usageLimit(c.getUsageLimit())
                        .usageCount(c.getUsageCount())
                        .couponType(c.getCouponType() != null ? c.getCouponType().name() : "GLOBAL")
                        .departureId(c.getDepartureId())
                        .build())
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Lấy thông tin trang đặt tour", description = "Trả về giá, coupon, chuyến bay — dùng để hiển thị form đặt tour")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thành công"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy departure")
    })
    @GetMapping("/order")
    public ResponseEntity<BookingOrderResponse> getOrderInfo(
            @RequestParam String tourCode,
            @RequestParam Integer departureId) {
        return ResponseEntity.ok(bookingService.getOrderInfo(tourCode, departureId));
    }

    @Operation(summary = "Tạo booking mới", description = "Tính giá, apply coupon, trừ điểm, lưu booking — trả về bookingCode để redirect sang payment")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đặt tour thành công"),
            @ApiResponse(responseCode = "400", description = "Hết chỗ hoặc coupon không hợp lệ")
    })
    @PostMapping("/create")
    public ResponseEntity<CreateBookingResponse> createBooking(
            @RequestBody CreateBookingRequest request) {
        return ResponseEntity.ok(bookingService.createBooking(request));
    }

    @Operation(summary = "Lay thong tin booking de hien thi trang thanh toan", description = "Tra ve gia, hang khach, tour info cho trang /payment-booking")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thong tin booking"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay booking")
    })
    @GetMapping("/payment/{bookingCode}")
    public ResponseEntity<BookingPaymentDetailResponse> getBookingPaymentDetail(
            @PathVariable String bookingCode) {
        return ResponseEntity.ok(bookingService.getBookingPaymentDetail(bookingCode));
    }

    @Operation(summary = "[Internal] Lay thong tin booking theo ID", description = "Endpoint noi bo - tour-catalog-service goi sau khi review duoc gui")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thong tin booking"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay")
    })
    @GetMapping("/{bookingID}")
    public ResponseEntity<BookingBriefResponse> getBookingById(@PathVariable Integer bookingID) {
        return ResponseEntity.ok(bookingService.getBookingById(bookingID));
    }

    @Operation(summary = "[Internal] Cap nhat trang thai booking", description = "Goi noi bo - tour-catalog-service cap nhat status sau khi nhan review")
    @ApiResponse(responseCode = "200", description = "Cap nhat thanh cong")
    @PostMapping("/{bookingID}/status")
    public ResponseEntity<Void> updateBookingStatus(
            @PathVariable Integer bookingID,
            @RequestParam String status) {
        bookingService.updateBookingStatus(bookingID, status);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Danh sach booking cua user", description = "Lay booking theo userID, co the loc theo bookingStatus")
    @ApiResponse(responseCode = "200", description = "Danh sach booking")
    @GetMapping("/user/{userID}")
    public ResponseEntity<List<BookingResponse>> getBookingsByUser(
            @PathVariable Integer userID,
            @RequestParam(required = false) String bookingStatus) {
        return ResponseEntity.ok(bookingService.getBookingsByUser(userID, bookingStatus));
    }

    @Operation(summary = "Huy booking (phia khach hang)", description = "Huy booking - neu du dieu kien, coin se duoc hoan ve vi")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Huy thanh cong"),
            @ApiResponse(responseCode = "400", description = "Khong the huy")
    })
    @PostMapping("/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@RequestBody CancelBookingRequest request) {
        return ResponseEntity.ok(bookingService.cancelBooking(request));
    }

    @Operation(summary = "Yeu cau hoan tien", description = "Gui thong tin tai khoan ngan hang de hoan tien. Booking phai o trang thai PENDING_REFUND")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Yeu cau da duoc ghi nhan"),
            @ApiResponse(responseCode = "400", description = "Trang thai booking khong hop le")
    })
    @PostMapping("/refund-request/{bookingID}")
    public ResponseEntity<BookingResponse> submitRefundRequest(
            @PathVariable Integer bookingID,
            @RequestBody RefundInformationRequest request) {
        return ResponseEntity.ok(bookingService.submitRefundRequest(bookingID, request));
    }

    @Operation(summary = "[Admin] Tim kiem booking", description = "Tim theo bookingCode, bookingStatus, bookingDate voi phan trang va sap xep")
    @ApiResponse(responseCode = "200", description = "Ket qua tim kiem phan trang")
    @PostMapping("/admin/search")
    public ResponseEntity<Page<BookingResponse>> adminSearchBookings(
            @RequestBody AdminSearchBookingRequest request,
            @RequestParam(defaultValue = "0")             int    page,
            @RequestParam(defaultValue = "10")            int    size,
            @RequestParam(defaultValue = "bookingDate")   String sortBy,
            @RequestParam(defaultValue = "DESC")          String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ResponseEntity.ok(bookingService.adminSearchBookings(request, pageable));
    }

    @Operation(summary = "[Admin] Cap nhat trang thai booking", description = "Ho tro chuyen: PENDING_CONFIRMATION->PAID, *->CANCELLED (co/khong hoan tien)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @ApiResponse(responseCode = "400", description = "Chuyen trang thai khong hop le")
    })
    @PostMapping("/admin/update-status")
    public ResponseEntity<?> adminUpdateBookingStatus(
            @RequestBody AdminUpdateStatusRequest request) {
        try {
            BookingResponse updated = bookingService.adminUpdateBookingStatus(request);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }
}
