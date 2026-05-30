package com.tourism.booking.convert;

import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.PaymentInfoResponse;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Converts between Booking entity and BookingResponse DTO.
 *
 * Fields mapped automatically by ModelMapper (same name + type):
 *   bookingID, bookingCode, bookingDate, contactEmail, contactFullName,
 *   contactPhone, contactAddress, customerNote, totalPassengers,
 *   subtotalPrice, surcharge, couponDiscount, paidByCoin, totalPrice,
 *   cancelReason, refundAmount, appliedCouponCodes
 *
 * Fields mapped manually (different name or type):
 *   bookingStatus  (BookingStatus enum → String)
 *   departureID    (entity: departureId → response: departureID)
 *   passengers     (entity List → response List via BookingPassengerConverter)
 *   refundBank / refundAccountNumber / refundAccountName / refundStatus
 *                  (from nested RefundInformation entity)
 *
 * External data (tour-catalog, payment) are merged in via enrichFromDeparture()
 * and enrichFromPayment() — called by service after Feign calls.
 */
@Component
public class BookingConverter {

    private final ModelMapper              modelMapper;
    private final BookingPassengerConverter passengerConverter;

    public BookingConverter(ModelMapper modelMapper,
                            BookingPassengerConverter passengerConverter) {
        this.modelMapper        = modelMapper;
        this.passengerConverter = passengerConverter;
    }

    // ── Entity → DTO (base fields only — no Feign calls here) ────────────────

    public BookingResponse toResponse(Booking booking) {
        // ModelMapper handles all same-name/type fields
        BookingResponse res = modelMapper.map(booking, BookingResponse.class);

        // Manual: enum → String
        res.setBookingStatus(booking.getBookingStatus() != null
                ? booking.getBookingStatus().name()
                : null);

        // Manual: field name mismatch  (departureId → departureID)
        res.setDepartureID(booking.getDepartureId());

        // Manual: passengers list via passenger converter
        if (booking.getPassengers() != null) {
            res.setPassengers(
                booking.getPassengers().stream()
                    .map(passengerConverter::toResponse)
                    .collect(Collectors.toList())
            );
        }

        // Manual: refund info from nested entity
        if (booking.getRefundInformation() != null) {
            var ri = booking.getRefundInformation();
            res.setRefundBank(ri.getBank());
            res.setRefundAccountNumber(ri.getAccountNumber());
            res.setRefundAccountName(ri.getAccountName());
            res.setRefundStatus(ri.getRefundStatus());
        }

        // Manual: coin refund status
        res.setCoinRefundStatus(booking.getCoinRefundStatus());

        return res;
    }

    // ── Enrich with departure/tour info (from tour-catalog-service) ───────────

    public void enrichFromDeparture(BookingResponse res, DepartureInfoResponse dep) {
        if (dep == null) return;
        res.setDepartureDate(dep.getDepartureDate());
        res.setTourID(dep.getTourID());
        res.setTourCode(dep.getTourCode());
        res.setTourName(dep.getTourName());
        res.setImage(dep.getImage());
        res.setDuration(dep.getDuration());
    }

    // ── Enrich with payment info (from payment-service) ───────────────────────

    public void enrichFromPayment(BookingResponse res, PaymentInfoResponse pay) {
        if (pay == null) return;
        res.setPaymentID(pay.getPaymentID());
        res.setAmount(pay.getAmount());
        res.setTimeLimit(pay.getTimeLimit());
        res.setBank(pay.getBank());
        res.setAccountNumber(pay.getAccountNumber());
        res.setAccountName(pay.getAccountName());

        java.time.LocalDateTime createdAt = res.getBookingDate();
        if (createdAt != null) {
            res.setPaymentDeadline(createdAt.plusHours(24).toString());
        } else if (pay.getTimeLimit() != null) {
            res.setPaymentDeadline(pay.getTimeLimit().plusHours(24).minusMinutes(15).toString());
        }
    }
}
