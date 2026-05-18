package com.tourism.booking.service.impl;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.CreateBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingOrderResponse;
import com.tourism.booking.dto.response.BookingPaymentDetailResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.CreateBookingResponse;
import com.tourism.booking.entity.*;
import com.tourism.booking.convert.BookingConverter;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.NotificationFeignClient;
import com.tourism.booking.feign.PaymentFeignClient;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.PaymentInfoResponse;
import com.tourism.booking.feign.dto.TourBookingInfoResponse;
import com.tourism.booking.feign.dto.UserProfileResponse;
import com.tourism.booking.repository.CouponRepository;
import com.tourism.booking.messaging.OutboxEventFactory;
import com.tourism.booking.repository.BookingRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.repository.RefundInformationRepository;
import com.tourism.booking.dto.sepay.TransactionVerificationDTO;
import com.tourism.booking.service.BookingService;
import com.tourism.booking.service.SepayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private final BookingRepository           bookingRepository;
    private final RefundInformationRepository refundRepository;
    private final CouponRepository            couponRepository;
    private final OutboxEventRepository       outboxRepository;
    private final TourCatalogFeignClient      tourCatalogClient;
    private final PaymentFeignClient          paymentClient;
    private final IamFeignClient              iamClient;
    private final NotificationFeignClient     notificationClient;
    private final BookingConverter            bookingConverter;
    private final SepayService               sepayService;
    private final ObjectMapper               objectMapper;

    private static final BigDecimal COIN_RATE = new BigDecimal("1000"); // 1 coin = 1000 VND

    // ── GET order info (for booking form) ────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingOrderResponse getOrderInfo(String tourCode, Integer departureId) {
        TourBookingInfoResponse info = tourCatalogClient.getOrderInfo(tourCode, departureId);

        BookingOrderResponse res = new BookingOrderResponse();
        res.setTourId(info.getTourId());
        res.setTourCode(info.getTourCode());
        res.setTourName(info.getTourName());
        res.setImage(info.getImage());
        res.setAvailableSlots(info.getAvailableSlots());
        res.setAdultPrice(info.getAdultPrice());
        res.setChildPrice(info.getChildPrice());
        res.setToddlerPrice(info.getToddlerPrice());
        res.setInfantPrice(info.getInfantPrice());
        res.setSingleRoomSurcharge(info.getSingleRoomSurcharge());

        // Map transport
        if (info.getOutboundFlight() != null) {
            res.setOutboundFlight(toFlightInfo(info.getOutboundFlight()));
        }
        if (info.getInboundFlight() != null) {
            res.setInboundFlight(toFlightInfo(info.getInboundFlight()));
        }

        LocalDateTime now = LocalDateTime.now();

        // Resolve departure coupon
        if (info.getCouponId() != null) {
            try {
                couponRepository.findActiveCouponById(info.getCouponId(), now)
                        .ifPresent(c -> res.setDepartureCoupon(toCouponInfo(c)));
            } catch (Exception e) {
                log.warn("Could not resolve departure coupon {}: {}", info.getCouponId(), e.getMessage());
            }
        }

        // Global coupons
        List<BookingOrderResponse.CouponInfo> globals = couponRepository.findActiveCoupons(now)
                .stream()
                .filter(c -> CouponType.GLOBAL.equals(c.getCouponType()) && c.getDepartureId() == null)
                .map(this::toCouponInfo)
                .collect(Collectors.toList());
        res.setGlobalCoupons(globals);

        return res;
    }

    // ── CREATE booking ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CreateBookingResponse createBooking(CreateBookingRequest request) {
        // 1. Lấy pricing + departure info từ tour-catalog
        TourBookingInfoResponse info = tourCatalogClient.getOrderInfo(null, request.getDepartureId());

        // 2. Đếm số ghế (không tính INFANT)
        int seatCount = (int) request.getPassengers().stream()
                .filter(p -> !"INFANT".equalsIgnoreCase(p.getType()))
                .count();

        // 3. Giảm chỗ trống (atomic — trả 400 nếu hết chỗ)
        try {
            ResponseEntity<Void> slotResp = tourCatalogClient.decreaseSlots(request.getDepartureId(), seatCount);
            if (slotResp != null && slotResp.getStatusCode().is4xxClientError()) {
                throw new RuntimeException("Không đủ chỗ trống!");
            }
        } catch (FeignException.BadRequest e) {
            throw new RuntimeException("Không đủ chỗ trống!");
        }

        // 4. Tính subtotal + surcharge cho từng passenger
        BigDecimal subTotal = BigDecimal.ZERO;
        BigDecimal surcharge = BigDecimal.ZERO;
        List<BookingPassenger> passengerEntities = new ArrayList<>();

        Booking booking = new Booking();

        for (CreateBookingRequest.PassengerRequest p : request.getPassengers()) {
            BigDecimal price = getPriceByType(info, p.getType());
            BigDecimal sr = p.isSingleRoom() && info.getSingleRoomSurcharge() != null
                    ? info.getSingleRoomSurcharge() : BigDecimal.ZERO;
            subTotal = subTotal.add(price);
            surcharge = surcharge.add(sr);

            BookingPassenger passenger = new BookingPassenger();
            passenger.setFullName(p.getFullName());
            passenger.setGender(p.getGender());
            try {
                passenger.setDateOfBirth(LocalDate.parse(p.getDateOfBirth()));
            } catch (Exception e) {
                throw new RuntimeException("Invalid dateOfBirth format: " + p.getDateOfBirth());
            }
            passenger.setPassengerType(toPassengerType(p.getType()));
            passenger.setBasePrice(price);
            passenger.setRequiresSingleRoom(p.isSingleRoom());
            passenger.setSingleRoomSurcharge(sr);
            passenger.setBooking(booking);
            passengerEntities.add(passenger);
        }

        BigDecimal totalBeforeDiscount = subTotal.add(surcharge);

        // 5. Apply coupons
        BigDecimal couponDiscount = BigDecimal.ZERO;
        List<String> appliedCodes = new ArrayList<>();
        if (request.getCouponCode() != null) {
            for (String code : request.getCouponCode()) {
                if (code == null || code.isBlank()) continue;
                Coupon c = couponRepository.findByCouponCode(code)
                        .orElseThrow(() -> new RuntimeException("Coupon không tồn tại: " + code));
                if (c.getMinOrderValue() != null
                        && totalBeforeDiscount.compareTo(c.getMinOrderValue()) < 0) {
                    throw new RuntimeException("Đơn hàng chưa đủ điều kiện dùng coupon " + code);
                }
                couponDiscount = couponDiscount.add(BigDecimal.valueOf(c.getDiscountAmount()));
                c.setUsageCount(c.getUsageCount() + 1);
                couponRepository.save(c);
                appliedCodes.add(code);
            }
        }

        // 6. Apply points/coins
        BigDecimal pointDiscount = BigDecimal.ZERO;
        if (request.getUserId() != null && request.getPointsUsed() != null && request.getPointsUsed() > 0) {
            try {
                UserProfileResponse userProfile = iamClient.getUserProfile(request.getUserId());
                BigDecimal pointsToUse = BigDecimal.valueOf(request.getPointsUsed());
                if (userProfile.getCoinBalance() == null
                        || userProfile.getCoinBalance().compareTo(pointsToUse) < 0) {
                    throw new RuntimeException("Không đủ điểm thưởng!");
                }
                pointDiscount = pointsToUse.multiply(COIN_RATE);
                iamClient.deductCoins(request.getUserId(), pointsToUse);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to deduct coins for user {}: {}", request.getUserId(), e.getMessage());
            }
        }

        // 7. Tính finalTotal
        BigDecimal finalTotal = totalBeforeDiscount.subtract(couponDiscount).subtract(pointDiscount);
        if (finalTotal.compareTo(BigDecimal.ZERO) < 0) {
            finalTotal = BigDecimal.ZERO;
        }

        // 8. Set booking fields
        booking.setBookingDate(LocalDateTime.now());
        booking.setBookingStatus(BookingStatus.PENDING_PAYMENT);
        booking.setContactFullName(request.getContactFullName());
        booking.setContactEmail(request.getContactEmail());
        booking.setContactPhone(request.getContactPhone());
        booking.setContactAddress(request.getContactAddress());
        booking.setCustomerNote(request.getCustomerNote());
        booking.setTotalPassengers(request.getPassengers().size());
        booking.setSubtotalPrice(subTotal);
        booking.setSurcharge(surcharge);
        booking.setCouponDiscount(couponDiscount);
        booking.setPaidByCoin(pointDiscount);
        booking.setTotalPrice(finalTotal);
        booking.setUserId(request.getUserId());
        booking.setDepartureId(request.getDepartureId());
        if (!appliedCodes.isEmpty()) {
            booking.setAppliedCouponCodes(String.join(",", appliedCodes));
        }
        booking.setPassengers(passengerEntities);

        bookingRepository.save(booking);
        log.info("Created booking {} for departure {}", booking.getBookingCode(), request.getDepartureId());

        return new CreateBookingResponse(
                booking.getBookingCode(),
                booking.getBookingID(),
                finalTotal,
                BookingStatus.PENDING_PAYMENT.name());
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private BigDecimal getPriceByType(TourBookingInfoResponse info, String type) {
        if (type == null) return BigDecimal.ZERO;
        return switch (type.toUpperCase()) {
            case "ADULT"             -> info.getAdultPrice() != null ? info.getAdultPrice() : BigDecimal.ZERO;
            case "CHILD"             -> info.getChildPrice() != null ? info.getChildPrice() : BigDecimal.ZERO;
            case "TODDLER"           -> info.getToddlerPrice() != null ? info.getToddlerPrice() : BigDecimal.ZERO;
            case "INFANT"            -> info.getInfantPrice() != null ? info.getInfantPrice() : BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    private PassengerType toPassengerType(String type) {
        if (type == null) return PassengerType.ADULT;
        try {
            return PassengerType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PassengerType.ADULT;
        }
    }

    private BookingOrderResponse.FlightInfo toFlightInfo(TourBookingInfoResponse.FlightInfo f) {
        BookingOrderResponse.FlightInfo fi = new BookingOrderResponse.FlightInfo();
        fi.setTransportCode(f.getTransportCode());
        fi.setDepartTime(f.getDepartTime());
        fi.setArrivalTime(f.getArrivalTime());
        fi.setVehicleType(f.getVehicleType());
        fi.setVehicleName(f.getVehicleName());
        fi.setStartPoint(f.getStartPoint());
        fi.setEndPoint(f.getEndPoint());
        fi.setStartPointName(f.getStartPointName());
        fi.setEndPointName(f.getEndPointName());
        return fi;
    }

    private BookingOrderResponse.CouponInfo toCouponInfo(Coupon c) {
        BookingOrderResponse.CouponInfo ci = new BookingOrderResponse.CouponInfo();
        ci.setCode(c.getCouponCode());
        ci.setDiscountAmount(c.getDiscountAmount());
        ci.setDescription(c.getDescription());
        ci.setMinOrderValue(c.getMinOrderValue());
        return ci;
    }

    // ── GET booking payment detail (for payment-booking page) ───────────────

    @Override
    @Transactional(readOnly = true)
    public BookingPaymentDetailResponse getBookingPaymentDetail(String bookingCode) {
        Booking booking = bookingRepository.findByBookingCodeWithPassengers(bookingCode)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingCode));

        // Tour info from tour-catalog
        TourBookingInfoResponse info = null;
        try {
            info = tourCatalogClient.getOrderInfo(null, booking.getDepartureId());
        } catch (Exception e) {
            log.warn("Could not fetch tour info for departure {}: {}", booking.getDepartureId(), e.getMessage());
        }

        // Duration from departure info
        String duration = null;
        try {
            DepartureInfoResponse depInfo = tourCatalogClient.getDepartureInfo(booking.getDepartureId());
            if (depInfo != null) duration = depInfo.getDuration();
        } catch (Exception e) {
            log.warn("Could not fetch departure info: {}", e.getMessage());
        }

        // Paid amount from payment-service
        BigDecimal paidAmount = BigDecimal.ZERO;
        try {
            PaymentInfoResponse payment = paymentClient.getPaymentByBooking(booking.getBookingID());
            if (payment != null && "SUCCESS".equals(payment.getStatus())) {
                paidAmount = payment.getAmount();
            }
        } catch (Exception e) {
            // No payment yet — ignore
        }

        BookingPaymentDetailResponse res = new BookingPaymentDetailResponse();
        LocalDateTime createdAt = booking.getCreatedAt() != null
                ? booking.getCreatedAt() : booking.getBookingDate();

        res.setBookingId(booking.getBookingID());
        res.setBookingCode(booking.getBookingCode());
        res.setCreatedDate(createdAt != null ? createdAt.toString() : null);
        res.setStatus(booking.getBookingStatus() != null ? booking.getBookingStatus().name() : null);
        res.setOriginalPrice(booking.getTotalPrice());
        res.setPaidAmount(paidAmount);
        res.setRemainingAmount(booking.getTotalPrice().subtract(paidAmount));
        res.setPaymentDeadline(createdAt != null ? createdAt.plusHours(24).toString() : null);

        if (booking.getAppliedCouponCodes() != null && !booking.getAppliedCouponCodes().isBlank()) {
            res.setAppliedCouponCodes(java.util.Arrays.asList(booking.getAppliedCouponCodes().split(",")));
        } else {
            res.setAppliedCouponCodes(java.util.List.of());
        }

        if (info != null) {
            res.setTourName(info.getTourName());
            res.setTourCode(info.getTourCode());
            res.setTourImage(info.getImage());
            if (info.getOutboundFlight() != null) {
                res.setOutboundTransport(toPaymentFlightInfo(info.getOutboundFlight()));
            }
            if (info.getInboundFlight() != null) {
                res.setInboundTransport(toPaymentFlightInfo(info.getInboundFlight()));
            }
        }
        res.setDuration(duration);

        if (booking.getPassengers() != null) {
            res.setPassengers(booking.getPassengers().stream()
                    .map(p -> {
                        BookingPaymentDetailResponse.PassengerInfo pi = new BookingPaymentDetailResponse.PassengerInfo();
                        pi.setFullName(p.getFullName());
                        pi.setGender(p.getGender());
                        pi.setDateOfBirth(p.getDateOfBirth() != null ? p.getDateOfBirth().toString() : null);
                        pi.setType(p.getPassengerType() != null ? p.getPassengerType().name() : null);
                        pi.setSingleRoom(Boolean.TRUE.equals(p.getRequiresSingleRoom()));
                        return pi;
                    })
                    .collect(Collectors.toList()));
        }

        return res;
    }

    private BookingPaymentDetailResponse.FlightInfo toPaymentFlightInfo(TourBookingInfoResponse.FlightInfo f) {
        BookingPaymentDetailResponse.FlightInfo fi = new BookingPaymentDetailResponse.FlightInfo();
        fi.setVehicleType(f.getVehicleType());
        fi.setDepartTime(f.getDepartTime());
        fi.setArrivalTime(f.getArrivalTime());
        fi.setTransportCode(f.getTransportCode());
        fi.setStartPoint(f.getStartPoint());
        fi.setStartPointName(f.getStartPointName());
        fi.setEndPoint(f.getEndPoint());
        fi.setEndPointName(f.getEndPointName());
        fi.setVehicleName(f.getVehicleName());
        return fi;
    }

    // ── GET booking by ID (internal, for cross-service calls) ────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingBriefResponse getBookingById(Integer bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));
        return new BookingBriefResponse(
                booking.getBookingID(),
                booking.getBookingCode(),
                booking.getBookingStatus() != null ? booking.getBookingStatus().name() : null,
                booking.getUserId()
        );
    }

    // ── Update booking status (called by tour-catalog-service after review) ──

    @Override
    @Transactional
    public void updateBookingStatus(Integer bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));
        BookingStatus newStatus;
        try {
            newStatus = BookingStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid booking status: " + status);
        }
        booking.setBookingStatus(newStatus);
        bookingRepository.save(booking);
        log.info("Updated booking {} status to {}", bookingId, newStatus);
    }

    // ── GET bookings by user ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByUser(Integer userId, String bookingStatus) {
        List<Booking> bookings;
        if (bookingStatus != null && !bookingStatus.isBlank()) {
            BookingStatus status;
            try {
                status = BookingStatus.valueOf(bookingStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid booking status: " + bookingStatus);
            }
            bookings = bookingRepository.findByUserIdAndBookingStatusOrderByBookingDateDesc(userId, status);
        } else {
            bookings = bookingRepository.findByUserIdOrderByBookingDateDesc(userId);
        }

        return bookings.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── Cancel booking (COIN refund path — same as monolith) ─────────────────

    @Override
    @Transactional
    public BookingResponse cancelBooking(CancelBookingRequest request) {
        Booking booking = bookingRepository.findById(request.getBookingID())
                .orElseThrow(() -> new RuntimeException("Booking not found: " + request.getBookingID()));

        // Monolith: only block if already CANCELLED
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new RuntimeException("Booking is already cancelled.");
        }

        booking.setCancelReason(request.getCancelReason());

        // Calculate refundable amount: (totalPrice + paidByCoin) * (1 - feePercent), rounded DOWN
        BigDecimal refundableAmount = calculateRefundableAmount(booking);

        // Convert VND → coins (1000 VND = 1 coin)
        BigDecimal coinRefundAmount = refundableAmount.divide(COIN_RATE, 0, RoundingMode.DOWN);

        // ── ATOMIC: save booking + outbox events in ONE transaction ───────────
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setRefundAmount(refundableAmount);

        // Set coinRefundStatus = PENDING khi có coin refund (cùng transaction với outbox)
        if (coinRefundAmount.compareTo(BigDecimal.ZERO) > 0 && booking.getUserId() != null) {
            booking.setCoinRefundStatus("PENDING");
        }

        Booking saved = bookingRepository.save(booking);
        BookingResponse res = toResponse(saved);

        // Outbox: COIN_REFUND (relay via Feign to IAM — idempotent via coin_transactions)
        if (coinRefundAmount.compareTo(BigDecimal.ZERO) > 0 && saved.getUserId() != null) {
            BookingEventDTO coinDto = buildBaseEventDto(saved, res, "CANCELLED",
                    refundableAmount, null, null, null, request.getCancelReason());
            coinDto.setCoinRefundAmount(coinRefundAmount);
            outboxRepository.save(OutboxEventFactory.coinRefund(coinDto, objectMapper));
            log.info("Queued COIN_REFUND outbox: {} coins for userId={}, booking={}",
                    coinRefundAmount, saved.getUserId(), saved.getBookingCode());
        }

        // Outbox: notification (relay via RabbitMQ to notification-service)
        BookingEventDTO notifDto = buildBaseEventDto(saved, res, "CANCELLED",
                refundableAmount, null, null, null, request.getCancelReason());
        notifDto.setCoinRefundAmount(coinRefundAmount);
        outboxRepository.save(OutboxEventFactory.notification(notifDto, "STATUS_UPDATED", objectMapper));

        return res;
    }

    // ── Submit refund request (BANK refund path — same as monolith) ──────────

    @Override
    @Transactional
    public BookingResponse submitRefundRequest(Integer bookingId, RefundInformationRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        // Monolith: block only if already CANCELLED or already PENDING_REFUND
        if (booking.getBookingStatus() == BookingStatus.CANCELLED
                || booking.getBookingStatus() == BookingStatus.PENDING_REFUND) {
            throw new RuntimeException("Booking is already in or past cancellation/refund process.");
        }

        // Calculate refundable amount
        BigDecimal totalRefundAmount = calculateRefundableAmount(booking);

        // Create / update RefundInformation
        RefundInformation refundInformation = new RefundInformation();
        refundInformation.setAccountName(request.getAccountName());
        refundInformation.setAccountNumber(request.getAccountNumber());
        refundInformation.setBank(request.getBank());
        refundInformation.setBooking(booking);
        refundInformation.setRefundAmount(totalRefundAmount);
        refundInformation.setRefundStatus("PENDING");

        // If already exists, update in-place (same as monolith)
        if (booking.getRefundInformation() != null) {
            refundInformation.setRefundID(booking.getRefundInformation().getRefundID());
        }

        RefundInformation savedRefund = refundRepository.save(refundInformation);
        booking.setRefundInformation(savedRefund);
        booking.setBookingStatus(BookingStatus.PENDING_REFUND);
        booking.setRefundAmount(totalRefundAmount);

        BookingResponse saved = toResponse(bookingRepository.save(booking));

        // Outbox: REFUND_REQUESTED notification (relay via RabbitMQ)
        BookingEventDTO dto = buildBaseEventDto(
                booking, saved, "PENDING_REFUND",
                totalRefundAmount,
                request.getBank(), request.getAccountNumber(), request.getAccountName(),
                null);
        outboxRepository.save(OutboxEventFactory.notification(dto, "REFUND_REQUESTED", objectMapper));

        return saved;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Replicates monolith's calculateRefundableAmount:
     *   (totalPrice + paidByCoin) * (1 - feePercent)  rounded DOWN to VND
     */
    private BigDecimal calculateRefundableAmount(Booking booking) {
        BigDecimal totalPrice = booking.getTotalPrice() != null ? booking.getTotalPrice() : BigDecimal.ZERO;
        BigDecimal paidByCoin = booking.getPaidByCoin() != null ? booking.getPaidByCoin() : BigDecimal.ZERO;
        BigDecimal totalPaid  = totalPrice.add(paidByCoin);

        if (totalPaid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long daysUntilDeparture = getDaysUntilDeparture(booking.getDepartureId());
        BigDecimal feePercent   = determineCancellationFeePercent(daysUntilDeparture);
        BigDecimal refundable   = BigDecimal.ONE.subtract(feePercent);
        if (refundable.compareTo(BigDecimal.ZERO) < 0) refundable = BigDecimal.ZERO;

        return totalPaid.multiply(refundable).setScale(0, RoundingMode.DOWN);
    }

    /**
     * Fetch departure date and calculate days until departure.
     * Returns Long.MAX_VALUE if the departure info is unavailable (results in minimum fee = 10%).
     */
    private long getDaysUntilDeparture(Integer departureId) {
        try {
            DepartureInfoResponse depInfo = tourCatalogClient.getDepartureInfo(departureId);
            if (depInfo != null && depInfo.getDepartureDate() != null) {
                LocalDate depDate = LocalDateTime.parse(depInfo.getDepartureDate()).toLocalDate();
                return ChronoUnit.DAYS.between(LocalDate.now(), depDate);
            }
        } catch (Exception e) {
            log.warn("Could not fetch departure info for departureId={}: {}", departureId, e.getMessage());
        }
        return Long.MAX_VALUE; // Cannot determine → minimum fee (10%)
    }

    /**
     * Cancellation fee % based on days until departure (same as monolith):
     *  > 15 days → 10%
     *  > 5 days  → 50%
     *  > 2 days  → 70%
     *  >= 0 days → 90%
     *  past      → 100%
     */
    private BigDecimal determineCancellationFeePercent(long daysUntilDeparture) {
        if (daysUntilDeparture > 15) return new BigDecimal("0.10");
        if (daysUntilDeparture > 5)  return new BigDecimal("0.50");
        if (daysUntilDeparture > 2)  return new BigDecimal("0.70");
        if (daysUntilDeparture >= 0) return new BigDecimal("0.90");
        return BigDecimal.ONE; // past departure — no refund
    }

    // ── toResponse: delegate to BookingConverter ────────────────────────────

    private BookingResponse toResponse(Booking booking) {
        BookingResponse res = bookingConverter.toResponse(booking);

        // Fetch departure/tour info from tour-catalog-service
        try {
            DepartureInfoResponse depInfo = tourCatalogClient.getDepartureInfo(booking.getDepartureId());
            bookingConverter.enrichFromDeparture(res, depInfo);
        } catch (Exception e) {
            log.warn("Could not fetch departure info for bookingID={}: {}", booking.getBookingID(), e.getMessage());
        }

        // Fetch payment info from payment-service
        try {
            PaymentInfoResponse payInfo = paymentClient.getPaymentByBooking(booking.getBookingID());
            bookingConverter.enrichFromPayment(res, payInfo);
        } catch (FeignException.NotFound ignored) {
            // No payment yet (PENDING_PAYMENT status)
        } catch (Exception e) {
            log.warn("Could not fetch payment info for bookingID={}: {}", booking.getBookingID(), e.getMessage());
        }

        return res;
    }

    // ── ADMIN: paginated + filtered search ───────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<BookingResponse> adminSearchBookings(AdminSearchBookingRequest request, Pageable pageable) {
        Page<Booking> page = bookingRepository.searchBookings(request, pageable);
        return page.map(this::toResponse);
    }

    // ── ADMIN: update booking status with business rules ─────────────────────

    @Override
    @Transactional
    public BookingResponse adminUpdateBookingStatus(AdminUpdateStatusRequest request) {
        Booking booking = bookingRepository.findById(request.getBookingID())
                .orElseThrow(() -> new RuntimeException(
                        "Booking not found with ID: " + request.getBookingID()));

        String newStatus     = request.getBookingStatus() != null
                ? request.getBookingStatus().toUpperCase() : "";
        String currentStatus = booking.getBookingStatus() != null
                ? booking.getBookingStatus().name() : "";

        switch (newStatus) {

            // ── PENDING_CONFIRMATION → PAID ──────────────────────────────────
            case "PAID": {
                if (!"PENDING_CONFIRMATION".equals(currentStatus)) {
                    throw new RuntimeException(
                            "Chỉ có thể xác nhận booking ở trạng thái 'Chờ xác nhận'. " +
                            "Trạng thái hiện tại: " + currentStatus);
                }
                booking.setBookingStatus(BookingStatus.PAID);
                Booking saved = bookingRepository.save(booking);
                BookingResponse res = toResponse(saved);

                // Outbox: BOOKING_CONFIRMED notification
                BookingEventDTO dto = buildBaseEventDto(saved, res, "PAID",
                        null, null, null, null, null);
                outboxRepository.save(OutboxEventFactory.notification(dto, "BOOKING_CONFIRMED", objectMapper));

                log.info("Admin confirmed booking {} → PAID, notification queued", booking.getBookingCode());
                return res;
            }

            // ── * → CANCELLED ────────────────────────────────────────────────
            case "CANCELLED": {
                List<String> allowedStatuses = List.of(
                        "PENDING_PAYMENT", "PENDING_CONFIRMATION", "PAID", "PENDING_REFUND");
                if (!allowedStatuses.contains(currentStatus)) {
                    throw new RuntimeException(
                            "Không thể hủy booking ở trạng thái hiện tại: " + currentStatus);
                }

                // ── SePay verification: bắt buộc khi có hoàn tiền thực tế ──────────
                // PENDING_REFUND: user đã submit TK ngân hàng → admin phải CK trước
                // PAID / PENDING_CONFIRMATION: admin hủy trực tiếp → cũng phải CK đã CK tiền
                boolean requiresSepayCheck = List.of("PENDING_REFUND", "PAID", "PENDING_CONFIRMATION")
                        .contains(currentStatus);

                if (requiresSepayCheck) {
                    // Tính số tiền cần verify:
                    // - PENDING_REFUND: giữ nguyên refundAmount user đã request, không tính lại full
                    // - PAID / PENDING_CONFIRMATION: admin tự hủy trực tiếp → hoàn full
                    BigDecimal totalPrice0 = booking.getTotalPrice() != null ? booking.getTotalPrice() : BigDecimal.ZERO;
                    BigDecimal paidByCoin0 = booking.getPaidByCoin() != null ? booking.getPaidByCoin() : BigDecimal.ZERO;
                    BigDecimal expectedAmount = totalPrice0.add(paidByCoin0);

                    // Nếu PENDING_REFUND đã có refundAmount lưu sẵn thì dùng luôn
                    if ("PENDING_REFUND".equals(currentStatus)) {
                        if (booking.getRefundAmount() == null
                                || booking.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0) {
                            throw new RuntimeException(
                                    "Không tìm thấy số tiền hoàn đã lưu cho yêu cầu hoàn tiền.");
                        }
                        expectedAmount = booking.getRefundAmount();
                    }

                    if (expectedAmount.compareTo(BigDecimal.ZERO) > 0) {
                        RefundInformation refundInfo = booking.getRefundInformation();
                        String accNumber = refundInfo != null ? refundInfo.getAccountNumber() : null;
                        String accName   = refundInfo != null ? refundInfo.getAccountName()   : null;
                        String bank      = refundInfo != null ? refundInfo.getBank()           : null;

                        TransactionVerificationDTO verification = sepayService.verifyRefundTransaction(
                                booking.getBookingCode(),
                                expectedAmount,
                                accNumber,
                                accName,
                                bank
                        );

                        if (!verification.isVerified()) {
                            throw new RuntimeException(
                                    "⚠️ Không tìm thấy giao dịch hoàn tiền trên SePay trong 24 giờ qua. " +
                                    "Vui lòng chuyển khoản " + expectedAmount.toPlainString() + " VND với nội dung '" +
                                    sepayService.generateTransferContent(booking.getBookingCode()) +
                                    "' trước khi xác nhận hoàn tiền.");
                        }

                        // Nếu có RefundInformation → cập nhật trạng thái
                        if (refundInfo != null) {
                            refundInfo.setRefundStatus("COMPLETED");
                            refundInfo.setRefundDate(LocalDateTime.now());
                            refundInfo.setNote("Verified via SePay: ref=" + verification.getTransactionReference());
                            refundRepository.save(refundInfo);
                        }
                        log.info("✅ SePay refund verified for booking {}, ref={}",
                                booking.getBookingCode(), verification.getTransactionReference());
                    }
                }

                booking.setBookingStatus(BookingStatus.CANCELLED);
                booking.setCancelReason(request.getCancelReason());

                BigDecimal refundAmount = booking.getRefundAmount() != null
                        ? booking.getRefundAmount() : BigDecimal.ZERO;

                if ("PENDING_REFUND".equals(currentStatus)) {
                    // User đã gửi yêu cầu hoàn tiền ngân hàng: refundAmount đã được tính và lưu
                    // ở submitRefundRequest(). Admin chỉ xác nhận đã chuyển khoản, không overwrite.
                    if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new RuntimeException(
                                "Không tìm thấy số tiền hoàn đã lưu cho yêu cầu hoàn tiền.");
                    }
                } else if (List.of("PENDING_CONFIRMATION", "PAID").contains(currentStatus)) {
                    // Admin tự hủy trực tiếp: hoàn full số khách đã thanh toán + điểm đã dùng.
                    BigDecimal totalPrice = booking.getTotalPrice() != null
                            ? booking.getTotalPrice() : BigDecimal.ZERO;
                    BigDecimal paidByCoin = booking.getPaidByCoin() != null
                            ? booking.getPaidByCoin() : BigDecimal.ZERO;
                    refundAmount = totalPrice.add(paidByCoin);
                    booking.setRefundAmount(refundAmount);
                } else {
                    refundAmount = BigDecimal.ZERO;
                }

                Booking saved = bookingRepository.save(booking);
                BookingResponse res = toResponse(saved);

                // Resolve refund account: prefer RefundInformation, fallback Payment
                String refundBank   = null;
                String refundAccNum = null;
                String refundAccName = null;
                if (saved.getRefundInformation() != null) {
                    refundBank    = saved.getRefundInformation().getBank();
                    refundAccNum  = saved.getRefundInformation().getAccountNumber();
                    refundAccName = saved.getRefundInformation().getAccountName();
                } else {
                    refundBank    = res.getBank();
                    refundAccNum  = res.getAccountNumber();
                    refundAccName = res.getAccountName();
                }

                final BigDecimal finalRefund = refundAmount;
                final String fBank = refundBank;
                final String fAccNum = refundAccNum;
                final String fAccName = refundAccName;

                // Outbox: REFUND_COMPLETED or STATUS_UPDATED notification
                String eventType = requiresSepayCheck ? "REFUND_COMPLETED" : "STATUS_UPDATED";
                BookingEventDTO dto = buildBaseEventDto(saved, res, "CANCELLED",
                        finalRefund, fBank, fAccNum, fAccName, request.getCancelReason());
                outboxRepository.save(OutboxEventFactory.notification(dto, eventType, objectMapper));

                log.info("Admin cancelled booking {} (from {}), refund={}, notification queued as {}",
                        booking.getBookingCode(), currentStatus, refundAmount, eventType);
                return res;
            }

            default:
                throw new RuntimeException("Trạng thái không hợp lệ: " + newStatus
                        + ". Chỉ hỗ trợ: PAID, CANCELLED");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Unified builder for all outbox event DTOs */
    private BookingEventDTO buildBaseEventDto(Booking booking, BookingResponse res,
                                              String status,
                                              BigDecimal refundAmount,
                                              String refundBank,
                                              String refundAccNum,
                                              String refundAccName,
                                              String cancelReason) {
        return BookingEventDTO.builder()
                .bookingID(booking.getBookingID())
                .bookingCode(booking.getBookingCode())
                .bookingStatus(status)
                .cancelReason(cancelReason)
                .contactFullName(booking.getContactFullName())
                .contactEmail(booking.getContactEmail())
                .contactPhone(booking.getContactPhone())
                .contactAddress(booking.getContactAddress())
                .totalPrice(booking.getTotalPrice())
                .paidByCoin(booking.getPaidByCoin())
                .refundAmount(refundAmount)
                .refundBank(refundBank)
                .refundAccountNumber(refundAccNum)
                .refundAccountName(refundAccName)
                .userId(booking.getUserId())
                .tourName(res.getTourName())
                .tourCode(res.getTourCode())
                .departureDate(res.getDepartureDate())
                .build();
    }
}
