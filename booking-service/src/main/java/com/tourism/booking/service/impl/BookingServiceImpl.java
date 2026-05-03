package com.tourism.booking.service.impl;

import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.entity.*;
import com.tourism.booking.convert.BookingConverter;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.NotificationFeignClient;
import com.tourism.booking.feign.PaymentFeignClient;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.PaymentInfoResponse;
import com.tourism.booking.repository.BookingRepository;
import com.tourism.booking.repository.RefundInformationRepository;
import com.tourism.booking.service.BookingService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private final BookingRepository           bookingRepository;
    private final RefundInformationRepository refundRepository;
    private final TourCatalogFeignClient      tourCatalogClient;
    private final PaymentFeignClient          paymentClient;
    private final IamFeignClient              iamClient;
    private final NotificationFeignClient     notificationClient;
    private final BookingConverter            bookingConverter;

    private static final BigDecimal COIN_RATE = new BigDecimal("1000"); // 1 coin = 1000 VND

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

        // Convert VND → coins (1000 VND = 1 coin) and credit to user
        BigDecimal coinRefundAmount = refundableAmount.divide(COIN_RATE, 0, RoundingMode.DOWN);
        if (coinRefundAmount.compareTo(BigDecimal.ZERO) > 0 && booking.getUserId() != null) {
            try {
                iamClient.addCoins(booking.getUserId(), coinRefundAmount);
                log.info("Added {} coins to userId={} for booking cancellation", coinRefundAmount, booking.getUserId());
            } catch (Exception e) {
                log.error("Failed to add coins to userId={}: {}", booking.getUserId(), e.getMessage());
                throw new RuntimeException("Không thể cộng xu cho người dùng. Vui lòng thử lại.");
            }
        }

        // Always CANCELLED (not PENDING_REFUND) for coin-refund path
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setRefundAmount(refundableAmount);

        BookingResponse saved = toResponse(bookingRepository.save(booking));

        // Gọi notification-service trực tiếp (fire-and-forget)
        try {
            notificationClient.notifyStatusUpdated(BookingEventDTO.builder()
                .bookingID(booking.getBookingID())
                .bookingCode(booking.getBookingCode())
                .bookingStatus("CANCELLED")
                .cancelReason(booking.getCancelReason())
                .contactFullName(booking.getContactFullName())
                .contactEmail(booking.getContactEmail())
                .contactPhone(booking.getContactPhone())
                .totalPrice(booking.getTotalPrice())
                .paidByCoin(booking.getPaidByCoin())
                .refundAmount(refundableAmount)
                .coinRefundAmount(coinRefundAmount)
                .userId(booking.getUserId())
                .tourName(saved.getTourName())
                .tourCode(saved.getTourCode())
                .departureDate(saved.getDepartureDate())
                .build());
        } catch (Exception e) {
            log.error("Failed to notify status update for booking {}: {}", saved.getBookingCode(), e.getMessage());
        }

        return saved;
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

        // Gọi notification-service trực tiếp (fire-and-forget)
        try {
            notificationClient.notifyRefundRequested(BookingEventDTO.builder()
                .bookingID(booking.getBookingID())
                .bookingCode(booking.getBookingCode())
                .bookingStatus("PENDING_REFUND")
                .contactFullName(booking.getContactFullName())
                .contactEmail(booking.getContactEmail())
                .contactPhone(booking.getContactPhone())
                .contactAddress(booking.getContactAddress())
                .totalPrice(booking.getTotalPrice())
                .paidByCoin(booking.getPaidByCoin())
                .refundAmount(totalRefundAmount)
                .refundBank(request.getBank())
                .refundAccountNumber(request.getAccountNumber())
                .refundAccountName(request.getAccountName())
                .userId(booking.getUserId())
                .tourName(saved.getTourName())
                .tourCode(saved.getTourCode())
                .departureDate(saved.getDepartureDate())
                .build());
        } catch (Exception e) {
            log.error("Failed to notify refund request for booking {}: {}", saved.getBookingCode(), e.getMessage());
        }

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
}
