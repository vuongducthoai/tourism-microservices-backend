package com.tourism.booking.service.impl;

import com.tourism.booking.convert.BookingConverter;
import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import com.tourism.booking.feign.NotificationFeignClient;
import com.tourism.booking.feign.PaymentFeignClient;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.PaymentInfoResponse;
import com.tourism.booking.repository.BookingRepository;
import com.tourism.booking.repository.RefundInformationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for admin booking management features.
 *
 * Tests cover:
 *  1. adminSearchBookings()     — delegation to repository + mapping
 *  2. adminUpdateBookingStatus()— PENDING_CONFIRMATION→PAID transition
 *  3. adminUpdateBookingStatus()— PENDING_PAYMENT→CANCELLED (no refund)
 *  4. adminUpdateBookingStatus()— PAID→CANCELLED (with refund)
 *  5. adminUpdateBookingStatus()— Invalid status transition guard
 *  6. adminUpdateBookingStatus()— Unknown new status guard
 *  7. Notification fire-and-forget (exceptions do NOT roll back the booking)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BookingServiceImpl — Admin Features")
class AdminBookingServiceImplTest {

    @Mock private BookingRepository          bookingRepository;
    @Mock private RefundInformationRepository refundRepository;
    @Mock private TourCatalogFeignClient     tourCatalogClient;
    @Mock private PaymentFeignClient         paymentClient;
    @Mock private IamFeignClient             iamClient;
    @Mock private NotificationFeignClient    notificationClient;
    @Mock private BookingConverter           bookingConverter;

    @InjectMocks
    private BookingServiceImpl service;

    // ── Shared test fixtures ─────────────────────────────────────────────────

    private Booking makeBooking(int id, BookingStatus status) {
        Booking b = new Booking();
        b.setBookingID(id);
        b.setBookingCode("BK00000" + id);
        b.setBookingStatus(status);
        b.setUserId(10);
        b.setContactEmail("customer@test.com");
        b.setContactFullName("Test User");
        b.setTotalPrice(new BigDecimal("5000000"));
        b.setPaidByCoin(new BigDecimal("500000"));
        b.setDepartureId(1);
        return b;
    }

    private BookingResponse makeResponse(Booking b) {
        BookingResponse r = new BookingResponse();
        r.setBookingID(b.getBookingID());
        r.setBookingCode(b.getBookingCode());
        r.setBookingStatus(b.getBookingStatus() != null ? b.getBookingStatus().name() : null);
        r.setTotalPrice(b.getTotalPrice());
        return r;
    }

    @BeforeEach
    void stubFeign() {
        // Suppress Feign calls — not the focus of these tests
        lenient().doThrow(new RuntimeException("No feign")).when(tourCatalogClient).getDepartureInfo(anyInt());
        lenient().doThrow(new RuntimeException("No feign")).when(paymentClient).getPaymentByBooking(anyInt());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. adminSearchBookings
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("adminSearchBookings")
    class AdminSearchBookingsTests {

        @Test
        @DisplayName("Should return mapped page from repository")
        void shouldReturnMappedPage() {
            Booking b1 = makeBooking(1, BookingStatus.PAID);
            Booking b2 = makeBooking(2, BookingStatus.CANCELLED);
            Pageable pageable = PageRequest.of(0, 10, Sort.by("bookingDate").descending());
            Page<Booking> repoPage = new PageImpl<>(List.of(b1, b2), pageable, 2);

            AdminSearchBookingRequest req = new AdminSearchBookingRequest();

            when(bookingRepository.searchBookings(req, pageable)).thenReturn(repoPage);
            when(bookingConverter.toResponse(b1)).thenReturn(makeResponse(b1));
            when(bookingConverter.toResponse(b2)).thenReturn(makeResponse(b2));

            Page<BookingResponse> result = service.adminSearchBookings(req, pageable);

            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getBookingID()).isEqualTo(1);
            assertThat(result.getContent().get(1).getBookingID()).isEqualTo(2);
            verify(bookingRepository).searchBookings(req, pageable);
        }

        @Test
        @DisplayName("Should return empty page when no matching bookings")
        void shouldReturnEmptyPage() {
            Pageable pageable = PageRequest.of(0, 10);
            AdminSearchBookingRequest req = AdminSearchBookingRequest.builder()
                    .bookingCode("NONEXISTENT")
                    .build();
            Page<Booking> emptyPage = Page.empty(pageable);

            when(bookingRepository.searchBookings(req, pageable)).thenReturn(emptyPage);

            Page<BookingResponse> result = service.adminSearchBookings(req, pageable);

            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. adminUpdateBookingStatus — PENDING_CONFIRMATION → PAID
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("adminUpdateBookingStatus — PAID transition")
    class ConfirmPaidTests {

        @Test
        @DisplayName("Should confirm PENDING_CONFIRMATION → PAID and notify")
        void shouldConfirmPendingConfirmationToPaid() {
            Booking booking = makeBooking(1, BookingStatus.PENDING_CONFIRMATION);
            when(bookingRepository.findById(1)).thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingConverter.toResponse(any())).thenAnswer(inv -> makeResponse(inv.getArgument(0)));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(1)
                    .bookingStatus("PAID")
                    .build();

            BookingResponse result = service.adminUpdateBookingStatus(req);

            assertThat(result.getBookingStatus()).isEqualTo("PAID");
            verify(bookingRepository).save(argThat(b -> b.getBookingStatus() == BookingStatus.PAID));
            verify(notificationClient).notifyBookingConfirmed(any());
        }

        @Test
        @DisplayName("Should reject PAID transition from non-PENDING_CONFIRMATION status")
        void shouldRejectPaidIfNotPendingConfirmation() {
            Booking booking = makeBooking(2, BookingStatus.PAID);  // already PAID
            when(bookingRepository.findById(2)).thenReturn(Optional.of(booking));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(2).bookingStatus("PAID").build();

            assertThatThrownBy(() -> service.adminUpdateBookingStatus(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Chỉ có thể xác nhận");

            verify(bookingRepository, never()).save(any());
            verify(notificationClient, never()).notifyBookingConfirmed(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. adminUpdateBookingStatus — PENDING_PAYMENT → CANCELLED (no refund)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("adminUpdateBookingStatus — CANCELLED (no refund path)")
    class CancelNoRefundTests {

        @Test
        @DisplayName("Should cancel PENDING_PAYMENT booking without refund")
        void shouldCancelPendingPaymentNoRefund() {
            Booking booking = makeBooking(3, BookingStatus.PENDING_PAYMENT);
            booking.setRefundAmount(null);
            when(bookingRepository.findById(3)).thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingConverter.toResponse(any())).thenAnswer(inv -> makeResponse(inv.getArgument(0)));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(3).bookingStatus("CANCELLED").cancelReason("Test cancel").build();

            BookingResponse result = service.adminUpdateBookingStatus(req);

            assertThat(result.getBookingStatus()).isEqualTo("CANCELLED");
            // PENDING_PAYMENT → no refund amount set
            verify(bookingRepository).save(argThat(b ->
                    b.getBookingStatus() == BookingStatus.CANCELLED &&
                    b.getRefundAmount() == null));
            verify(notificationClient).notifyStatusUpdated(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. adminUpdateBookingStatus — PAID → CANCELLED (with refund)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("adminUpdateBookingStatus — CANCELLED (with refund path)")
    class CancelWithRefundTests {

        @Test
        @DisplayName("Should cancel PAID booking and set full refund amount")
        void shouldCancelPaidWithFullRefund() {
            Booking booking = makeBooking(4, BookingStatus.PAID);
            // totalPrice=5000000 + paidByCoin=500000 → refund=5500000
            when(bookingRepository.findById(4)).thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingConverter.toResponse(any())).thenAnswer(inv -> makeResponse(inv.getArgument(0)));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(4).bookingStatus("CANCELLED").cancelReason("Admin cancel").build();

            BookingResponse result = service.adminUpdateBookingStatus(req);

            assertThat(result.getBookingStatus()).isEqualTo("CANCELLED");
            verify(bookingRepository).save(argThat(b ->
                    b.getBookingStatus() == BookingStatus.CANCELLED &&
                    new BigDecimal("5500000").compareTo(b.getRefundAmount()) == 0));
            verify(notificationClient).notifyStatusUpdated(any());
        }

        @Test
        @DisplayName("Should also cancel PENDING_CONFIRMATION booking with refund")
        void shouldCancelPendingConfirmationWithRefund() {
            Booking booking = makeBooking(5, BookingStatus.PENDING_CONFIRMATION);
            when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingConverter.toResponse(any())).thenAnswer(inv -> makeResponse(inv.getArgument(0)));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(5).bookingStatus("CANCELLED").cancelReason("Early cancel").build();

            BookingResponse result = service.adminUpdateBookingStatus(req);

            assertThat(result.getBookingStatus()).isEqualTo("CANCELLED");
            verify(bookingRepository).save(argThat(b ->
                    b.getBookingStatus() == BookingStatus.CANCELLED &&
                    b.getRefundAmount() != null &&
                    b.getRefundAmount().compareTo(BigDecimal.ZERO) > 0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. adminUpdateBookingStatus — Guard: non-cancellable status
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("adminUpdateBookingStatus — Guard: non-cancellable statuses")
    class NonCancellableGuardTests {

        @Test
        @DisplayName("Should reject cancel for REVIEWED booking")
        void shouldRejectCancelForReviewedBooking() {
            Booking booking = makeBooking(6, BookingStatus.REVIEWED);
            when(bookingRepository.findById(6)).thenReturn(Optional.of(booking));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(6).bookingStatus("CANCELLED").cancelReason("test").build();

            assertThatThrownBy(() -> service.adminUpdateBookingStatus(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Không thể hủy");

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reject cancel for CANCELLED booking")
        void shouldRejectCancelForAlreadyCancelledBooking() {
            Booking booking = makeBooking(7, BookingStatus.CANCELLED);
            when(bookingRepository.findById(7)).thenReturn(Optional.of(booking));

            AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                    .bookingID(7).bookingStatus("CANCELLED").cancelReason("test").build();

            assertThatThrownBy(() -> service.adminUpdateBookingStatus(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Không thể hủy");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. adminUpdateBookingStatus — Unknown status guard
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should throw for unknown target status")
    void shouldThrowForUnknownStatus() {
        Booking booking = makeBooking(8, BookingStatus.PENDING_CONFIRMATION);
        when(bookingRepository.findById(8)).thenReturn(Optional.of(booking));

        AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                .bookingID(8).bookingStatus("INVALID_STATUS").build();

        assertThatThrownBy(() -> service.adminUpdateBookingStatus(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Trạng thái không hợp lệ");

        verify(bookingRepository, never()).save(any());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Notification fire-and-forget — notification failure should NOT fail booking
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Notification failure should NOT roll back booking update")
    void notificationFailureShouldNotRollBack() {
        Booking booking = makeBooking(9, BookingStatus.PENDING_CONFIRMATION);
        when(bookingRepository.findById(9)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingConverter.toResponse(any())).thenAnswer(inv -> makeResponse(inv.getArgument(0)));

        // Notification service throws
        doThrow(new RuntimeException("Notification service down"))
                .when(notificationClient).notifyBookingConfirmed(any());

        AdminUpdateStatusRequest req = AdminUpdateStatusRequest.builder()
                .bookingID(9).bookingStatus("PAID").build();

        // Should NOT throw — notification failure is caught internally
        assertThatNoException()
                .isThrownBy(() -> service.adminUpdateBookingStatus(req));

        // Booking was still saved
        verify(bookingRepository).save(argThat(b -> b.getBookingStatus() == BookingStatus.PAID));
    }
}
