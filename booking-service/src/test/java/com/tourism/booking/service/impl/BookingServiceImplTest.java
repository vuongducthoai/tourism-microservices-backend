package com.tourism.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.convert.BookingConverter;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.RefundInformation;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.NotificationFeignClient;
import com.tourism.booking.feign.PaymentFeignClient;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.PaymentInfoResponse;
import com.tourism.booking.repository.BookingRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.repository.RefundInformationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingServiceImpl.
 *
 * Covers:
 *  1. cancelBooking  — coin-refund path (always CANCELLED, instant, no admin)
 *  2. submitRefundRequest — bank-refund path (PENDING_REFUND, needs admin)
 *  3. calculateRefundableAmount — fee tiers + paidByCoin included
 *  4. getBookingsByUser — status filter
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    @Mock BookingRepository           bookingRepository;
    @Mock RefundInformationRepository refundRepository;
    @Mock OutboxEventRepository       outboxRepository;
    @Mock TourCatalogFeignClient      tourCatalogClient;
    @Mock PaymentFeignClient          paymentClient;
    @Mock IamFeignClient              iamClient;
    @Mock NotificationFeignClient     notificationClient;
    @Mock BookingConverter            bookingConverter;
    @Mock ObjectMapper                objectMapper;

    @InjectMocks BookingServiceImpl service;

    // ── setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUpConverter() throws Exception {
        // ObjectMapper stub: return valid JSON for any object (used by OutboxEventFactory)
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{\"stub\":true}");
        // OutboxEvent save: return the entity unchanged
        lenient().when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // lenient() prevents UnnecessaryStubbing on tests that throw before toResponse() is called
        lenient().when(bookingConverter.toResponse(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            BookingResponse r = new BookingResponse();
            r.setBookingID(b.getBookingID());
            r.setBookingCode(b.getBookingCode());
            r.setTotalPrice(b.getTotalPrice());
            r.setPaidByCoin(b.getPaidByCoin());
            r.setRefundAmount(b.getRefundAmount());
            r.setBookingStatus(b.getBookingStatus() != null ? b.getBookingStatus().name() : null);
            r.setDepartureID(b.getDepartureId());
            // refund info
            if (b.getRefundInformation() != null) {
                r.setRefundBank(b.getRefundInformation().getBank());
                r.setRefundAccountNumber(b.getRefundInformation().getAccountNumber());
                r.setRefundAccountName(b.getRefundInformation().getAccountName());
                r.setRefundStatus(b.getRefundInformation().getRefundStatus());
            }
            return r;
        });

        // enrichFromPayment — void method, must actually set fields on the response
        lenient().doAnswer(inv -> {
            BookingResponse r   = inv.getArgument(0);
            PaymentInfoResponse p = inv.getArgument(1);
            if (p != null) {
                r.setPaymentID(p.getPaymentID());
                r.setAmount(p.getAmount());
                r.setTimeLimit(p.getTimeLimit());
                r.setBank(p.getBank());
                r.setAccountNumber(p.getAccountNumber());
                r.setAccountName(p.getAccountName());
            }
            return null;
        }).when(bookingConverter).enrichFromPayment(any(BookingResponse.class), any());

        // enrichFromDeparture — void method, must actually set fields on the response
        lenient().doAnswer(inv -> {
            BookingResponse r      = inv.getArgument(0);
            DepartureInfoResponse d = inv.getArgument(1);
            if (d != null) {
                r.setDepartureDate(d.getDepartureDate());
                r.setTourID(d.getTourID());
                r.setTourCode(d.getTourCode());
                r.setTourName(d.getTourName());
                r.setImage(d.getImage());
                r.setDuration(d.getDuration());
            }
            return null;
        }).when(bookingConverter).enrichFromDeparture(any(BookingResponse.class), any());
    }

    private Booking makePaidBooking(int bookingId, int userId, BigDecimal totalPrice, BigDecimal paidByCoin) {
        Booking b = new Booking();
        b.setBookingID(bookingId);
        b.setUserId(userId);
        b.setTotalPrice(totalPrice);
        b.setPaidByCoin(paidByCoin);
        b.setBookingStatus(BookingStatus.PAID);
        b.setDepartureId(10);
        b.setBookingCode("BKtest01");
        return b;
    }

    /** Returns a departure response with a date N days from today */
    private DepartureInfoResponse depInfoDaysFromNow(long days) {
        DepartureInfoResponse dep = new DepartureInfoResponse();
        dep.setDepartureDate(LocalDateTime.now().plusDays(days).toString());
        dep.setTourID(1);
        dep.setTourCode("T001");
        dep.setTourName("Hà Nội - Sapa");
        return dep;
    }

    // ── 1. cancelBooking — coin-refund path ──────────────────────────────────

    @Nested
    @DisplayName("cancelBooking — coin-refund path")
    class CancelBookingTests {

        @Test
        @DisplayName("HAPPY PATH: PAID booking, 20 days to departure → 10% fee → 90% refunded as coins instantly")
        void cancelPaidBooking_farFuture_90percentCoinsInstantly() {
            // totalPrice=1_000_000, paidByCoin=0
            // 20 days away → fee=10% → refundable = 900_000 VND → 900 coins
            Booking booking = makePaidBooking(1, 42, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(1)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(1);
            req.setCancelReason("Kế hoạch thay đổi");

            BookingResponse resp = service.cancelBooking(req);

            // Status must be CANCELLED (not PENDING_REFUND)
            assertThat(resp.getBookingStatus()).isEqualTo("CANCELLED");

            // Refund amount = 900_000
            assertThat(resp.getRefundAmount()).isEqualByComparingTo("900000");

            // IAM is NOT called directly — coin refund is deferred to outbox
            verify(iamClient, never()).addCoins(any(), any(), any());
            // 2 outbox events: COIN_REFUND + STATUS_UPDATED notification
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));

            // Booking saved with CANCELLED status
            ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(captor.capture());
            assertThat(captor.getValue().getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(captor.getValue().getCancelReason()).isEqualTo("Kế hoạch thay đổi");
        }

        @Test
        @DisplayName("HAPPY PATH: includes paidByCoin in refund base — (totalPrice + paidByCoin) * 0.9")
        void cancelBooking_includesPaidByCoinInRefundBase() {
            // totalPrice=500_000, paidByCoin=200_000
            // 20 days → fee=10% → refundable = 700_000 * 0.9 = 630_000 VND → 630 coins
            Booking booking = makePaidBooking(2, 55, new BigDecimal("500000"), new BigDecimal("200000"));
            when(bookingRepository.findById(2)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(2);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            assertThat(resp.getRefundAmount()).isEqualByComparingTo("630000");
            // Coin refund deferred via outbox (COIN_REFUND + STATUS_UPDATED = 2 events)
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
            verify(iamClient, never()).addCoins(any(), any(), any());
        }

        @Test
        @DisplayName("HAPPY PATH: 7 days to departure → 50% fee → 50% refunded")
        void cancelBooking_7daysAway_50percentFee() {
            // totalPrice=1_000_000
            // 7 days → fee=50% → refundable = 500_000 → 500 coins
            Booking booking = makePaidBooking(3, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(3)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(7));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(3);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            assertThat(resp.getRefundAmount()).isEqualByComparingTo("500000");
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("HAPPY PATH: 3 days to departure → 70% fee → 30% refunded")
        void cancelBooking_3daysAway_70percentFee() {
            // totalPrice=1_000_000
            // 3 days → fee=70% → refundable = 300_000 → 300 coins
            Booking booking = makePaidBooking(4, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(4)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(3));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(4);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            assertThat(resp.getRefundAmount()).isEqualByComparingTo("300000");
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("HAPPY PATH: 1 day to departure → 90% fee → 10% refunded")
        void cancelBooking_1dayAway_90percentFee() {
            // totalPrice=1_000_000
            // 1 day → fee=90% → refundable = 100_000 → 100 coins
            Booking booking = makePaidBooking(5, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(5)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(1));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(5);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            assertThat(resp.getRefundAmount()).isEqualByComparingTo("100000");
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("EDGE CASE: past departure → 100% fee → 0 refund → no COIN_REFUND outbox event")
        void cancelBooking_pastDeparture_noRefund_noCoins() {
            // totalPrice=1_000_000
            // -5 days (past) → fee=100% → refundable = 0 → no coins
            Booking booking = makePaidBooking(6, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(6)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(-5));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(6);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            assertThat(resp.getRefundAmount()).isEqualByComparingTo("0");
            // No coin refund when refund is 0 — only 1 notification outbox event
            verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
            verify(iamClient, never()).addCoins(any(), any(), any());
            assertThat(resp.getBookingStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("EDGE CASE: departure service unavailable → defaults to >15 days (10% fee)")
        void cancelBooking_tourCatalogDown_defaultsToMinFee() {
            // If feign fails, getDaysUntilDeparture returns Long.MAX_VALUE → >15 → fee=10%
            Booking booking = makePaidBooking(7, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(7)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenThrow(new RuntimeException("Service down"));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(7);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            // Long.MAX_VALUE > 15 → fee=10% → refundable=900_000
            assertThat(resp.getRefundAmount()).isEqualByComparingTo("900000");
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("FLOOR DIVISION: 1234 VND refund → 1 coin (floor, not round)")
        void cancelBooking_floorDivision_coinsRoundedDown() {
            // totalPrice=1000 (small amount to produce non-divisible result)
            // 20 days → fee=10% → refundable = 900 VND → 0 coins (floor(900/1000)=0)
            // Use bigger number: totalPrice=1999, paidByCoin=0
            // 20 days → fee=10% → refundable=1999*0.9=1799.1 → setScale(0,DOWN)=1799 VND → 1 coin
            Booking booking = makePaidBooking(8, 10, new BigDecimal("1999"), BigDecimal.ZERO);
            when(bookingRepository.findById(8)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(8);
            req.setCancelReason("Test");

            BookingResponse resp = service.cancelBooking(req);

            // 1999 * 0.9 = 1799.1 → floor = 1799 VND → floor(1799/1000) = 1 coin
            assertThat(resp.getRefundAmount()).isEqualByComparingTo("1799");
            verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("ERROR CASE: booking not found → RuntimeException")
        void cancelBooking_notFound_throwsException() {
            when(bookingRepository.findById(999)).thenReturn(Optional.empty());

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(999);
            req.setCancelReason("Test");

            assertThatThrownBy(() -> service.cancelBooking(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Booking not found: 999");
        }

        @Test
        @DisplayName("ERROR CASE: already CANCELLED → RuntimeException, no coins added")
        void cancelBooking_alreadyCancelled_throwsException() {
            Booking booking = makePaidBooking(10, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            booking.setBookingStatus(BookingStatus.CANCELLED);
            when(bookingRepository.findById(10)).thenReturn(Optional.of(booking));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(10);
            req.setCancelReason("Test");

            assertThatThrownBy(() -> service.cancelBooking(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already cancelled");

            verify(iamClient, never()).addCoins(any(), any(), any());
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("OUTBOX: cancelBooking with zero refund saves only 1 outbox event (notification only)")
        void cancelBooking_zeroRefund_onlyOneOutboxEvent() {
            // Past departure → 100% fee → 0 refund → no COIN_REFUND event
            Booking booking = makePaidBooking(11, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(11)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(-5));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(11);
            req.setCancelReason("Past departure");

            service.cancelBooking(req);

            // Only 1 outbox event: STATUS_UPDATED notification (no coin refund)
            verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
            verify(iamClient, never()).addCoins(any(), any(), any());
        }

        @Test
        @DisplayName("CRITICAL: cancelBooking does NOT set PENDING_REFUND (only CANCELLED)")
        void cancelBooking_neverSetsPendingRefund() {
            Booking booking = makePaidBooking(12, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(12)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(12);
            req.setCancelReason("Test");

            service.cancelBooking(req);

            ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(captor.capture());
            assertThat(captor.getValue().getBookingStatus())
                    .isNotEqualTo(BookingStatus.PENDING_REFUND)
                    .isEqualTo(BookingStatus.CANCELLED);
        }
    }

    // ── 2. submitRefundRequest — bank-refund path ─────────────────────────────

    @Nested
    @DisplayName("submitRefundRequest — bank-refund path")
    class SubmitRefundRequestTests {

        @Test
        @DisplayName("HAPPY PATH: PAID booking → saves bank info, sets PENDING_REFUND, no coins added")
        void submitRefund_paidBooking_setsPendingRefund() {
            Booking booking = makePaidBooking(20, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            RefundInformation savedRefund = new RefundInformation();
            savedRefund.setRefundID(1);

            when(bookingRepository.findById(20)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(refundRepository.save(any())).thenReturn(savedRefund);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("Vietcombank");
            req.setAccountNumber("1234567890");
            req.setAccountName("NGUYEN VAN A");

            BookingResponse resp = service.submitRefundRequest(20, req);

            // Status must be PENDING_REFUND
            assertThat(resp.getBookingStatus()).isEqualTo("PENDING_REFUND");

            // IAM is never called directly for bank refund path (needs admin approval)
            verify(iamClient, never()).addCoins(any(), any(), any());
            // One outbox event saved: REFUND_REQUESTED notification
            verify(outboxRepository, times(1)).save(any(OutboxEvent.class));

            // RefundInformation saved with correct data
            ArgumentCaptor<RefundInformation> refundCaptor = ArgumentCaptor.forClass(RefundInformation.class);
            verify(refundRepository).save(refundCaptor.capture());
            RefundInformation saved = refundCaptor.getValue();
            assertThat(saved.getBank()).isEqualTo("Vietcombank");
            assertThat(saved.getAccountNumber()).isEqualTo("1234567890");
            assertThat(saved.getAccountName()).isEqualTo("NGUYEN VAN A");
            assertThat(saved.getRefundStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("HAPPY PATH: PENDING_CONFIRMATION booking → also accepted, sets PENDING_REFUND")
        void submitRefund_pendingConfirmation_accepted() {
            Booking booking = makePaidBooking(21, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            booking.setBookingStatus(BookingStatus.PENDING_CONFIRMATION);
            RefundInformation savedRefund = new RefundInformation();
            savedRefund.setRefundID(2);

            when(bookingRepository.findById(21)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(refundRepository.save(any())).thenReturn(savedRefund);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("Techcombank");
            req.setAccountNumber("9876543210");
            req.setAccountName("TRAN THI B");

            BookingResponse resp = service.submitRefundRequest(21, req);

            assertThat(resp.getBookingStatus()).isEqualTo("PENDING_REFUND");
            verify(iamClient, never()).addCoins(any(), any(), any());
        }

        @Test
        @DisplayName("HAPPY PATH: refund amount calculated correctly (20 days → 10% fee → 900_000)")
        void submitRefund_refundAmountIsCorrect() {
            Booking booking = makePaidBooking(22, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            RefundInformation savedRefund = new RefundInformation();
            savedRefund.setRefundID(3);

            when(bookingRepository.findById(22)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(refundRepository.save(any())).thenReturn(savedRefund);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("MB Bank");
            req.setAccountNumber("1111111111");
            req.setAccountName("LE VAN C");

            service.submitRefundRequest(22, req);

            ArgumentCaptor<RefundInformation> refundCaptor = ArgumentCaptor.forClass(RefundInformation.class);
            verify(refundRepository).save(refundCaptor.capture());
            assertThat(refundCaptor.getValue().getRefundAmount()).isEqualByComparingTo("900000");
        }

        @Test
        @DisplayName("HAPPY PATH: existing RefundInformation updated in-place (same refundID)")
        void submitRefund_existingRefund_updatesInPlace() {
            Booking booking = makePaidBooking(23, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            RefundInformation existing = new RefundInformation();
            existing.setRefundID(99);
            existing.setBank("OldBank");
            booking.setRefundInformation(existing);

            RefundInformation savedRefund = new RefundInformation();
            savedRefund.setRefundID(99);

            when(bookingRepository.findById(23)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(refundRepository.save(any())).thenReturn(savedRefund);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("NewBank");
            req.setAccountNumber("2222222222");
            req.setAccountName("Updated");

            service.submitRefundRequest(23, req);

            ArgumentCaptor<RefundInformation> refundCaptor = ArgumentCaptor.forClass(RefundInformation.class);
            verify(refundRepository).save(refundCaptor.capture());
            // Must reuse existing refundID (update, not insert new)
            assertThat(refundCaptor.getValue().getRefundID()).isEqualTo(99);
            assertThat(refundCaptor.getValue().getBank()).isEqualTo("NewBank");
        }

        @Test
        @DisplayName("ERROR CASE: already CANCELLED → exception")
        void submitRefund_cancelledBooking_throwsException() {
            Booking booking = makePaidBooking(24, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            booking.setBookingStatus(BookingStatus.CANCELLED);
            when(bookingRepository.findById(24)).thenReturn(Optional.of(booking));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("Bank");
            req.setAccountNumber("123");
            req.setAccountName("Name");

            assertThatThrownBy(() -> service.submitRefundRequest(24, req))
                    .isInstanceOf(RuntimeException.class);

            verify(refundRepository, never()).save(any());
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("ERROR CASE: already PENDING_REFUND → exception (prevent duplicate)")
        void submitRefund_alreadyPendingRefund_throwsException() {
            Booking booking = makePaidBooking(25, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            booking.setBookingStatus(BookingStatus.PENDING_REFUND);
            when(bookingRepository.findById(25)).thenReturn(Optional.of(booking));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("Bank");
            req.setAccountNumber("123");
            req.setAccountName("Name");

            assertThatThrownBy(() -> service.submitRefundRequest(25, req))
                    .isInstanceOf(RuntimeException.class);

            verify(refundRepository, never()).save(any());
        }

        @Test
        @DisplayName("CRITICAL: submitRefundRequest does NOT add coins to user")
        void submitRefund_neverAddsCoins() {
            Booking booking = makePaidBooking(26, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            RefundInformation savedRefund = new RefundInformation();
            savedRefund.setRefundID(4);

            when(bookingRepository.findById(26)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(20));
            when(refundRepository.save(any())).thenReturn(savedRefund);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundInformationRequest req = new RefundInformationRequest();
            req.setBank("Bank");
            req.setAccountNumber("123");
            req.setAccountName("Name");

            service.submitRefundRequest(26, req);

            // Bank refund needs admin approval — IAM is never called directly here
            verify(iamClient, never()).addCoins(any(), any(), any());
            // One outbox event for REFUND_REQUESTED notification
            verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
        }
    }

    // ── 3. refund fee tiers — boundary value analysis ────────────────────────

    @Nested
    @DisplayName("calculateRefundableAmount — fee tier boundary tests")
    @MockitoSettings(strictness = Strictness.LENIENT)  // some stubs not used when refund=0
    class FeeTierBoundaryTests {

        private BigDecimal refundForDays(long days) {
            Booking booking = makePaidBooking(100, 10, new BigDecimal("1000000"), BigDecimal.ZERO);
            when(bookingRepository.findById(100)).thenReturn(Optional.of(booking));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(depInfoDaysFromNow(days));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CancelBookingRequest req = new CancelBookingRequest();
            req.setBookingID(100);
            req.setCancelReason("Test");

            return service.cancelBooking(req).getRefundAmount();
        }

        @Test @DisplayName("16 days → >15 → fee=10% → 900_000")
        void days16_fee10() { assertThat(refundForDays(16)).isEqualByComparingTo("900000"); }

        @Test @DisplayName("15 days → ≤15 and >5 → fee=50% → 500_000")
        void days15_fee50() { assertThat(refundForDays(15)).isEqualByComparingTo("500000"); }

        @Test @DisplayName("6 days → >5 → fee=50% → 500_000")
        void days6_fee50() { assertThat(refundForDays(6)).isEqualByComparingTo("500000"); }

        @Test @DisplayName("5 days → ≤5 and >2 → fee=70% → 300_000")
        void days5_fee70() { assertThat(refundForDays(5)).isEqualByComparingTo("300000"); }

        @Test @DisplayName("3 days → >2 → fee=70% → 300_000")
        void days3_fee70() { assertThat(refundForDays(3)).isEqualByComparingTo("300000"); }

        @Test @DisplayName("2 days → ≤2 and ≥0 → fee=90% → 100_000")
        void days2_fee90() { assertThat(refundForDays(2)).isEqualByComparingTo("100000"); }

        @Test @DisplayName("0 days (today) → ≥0 → fee=90% → 100_000")
        void days0_fee90() { assertThat(refundForDays(0)).isEqualByComparingTo("100000"); }

        @Test @DisplayName("-1 days (yesterday) → past → fee=100% → 0")
        void daysMinus1_fee100() { assertThat(refundForDays(-1)).isEqualByComparingTo("0"); }
    }

    // ── 4. getBookingsByUser ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getBookingsByUser")
    class GetBookingsByUserTests {

        @Test
        @DisplayName("HAPPY PATH: no status filter → returns all bookings for user")
        void getBookings_noFilter_returnsAll() {
            Booking b1 = makePaidBooking(30, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            Booking b2 = makePaidBooking(31, 5, new BigDecimal("300000"), BigDecimal.ZERO);
            when(bookingRepository.findByUserIdOrderByBookingDateDesc(5)).thenReturn(List.of(b1, b2));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(new DepartureInfoResponse());
            when(paymentClient.getPaymentByBooking(any())).thenReturn(null);

            List<BookingResponse> result = service.getBookingsByUser(5, null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("HAPPY PATH: with CANCELLED status filter → queries by status")
        void getBookings_withCancelledFilter_queriesByStatus() {
            Booking b1 = makePaidBooking(32, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            b1.setBookingStatus(BookingStatus.CANCELLED);
            when(bookingRepository.findByUserIdAndBookingStatusOrderByBookingDateDesc(5, BookingStatus.CANCELLED))
                    .thenReturn(List.of(b1));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(new DepartureInfoResponse());
            when(paymentClient.getPaymentByBooking(any())).thenReturn(null);

            List<BookingResponse> result = service.getBookingsByUser(5, "CANCELLED");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBookingStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("HAPPY PATH: with PENDING_REFUND status filter")
        void getBookings_withPendingRefundFilter() {
            Booking b1 = makePaidBooking(33, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            b1.setBookingStatus(BookingStatus.PENDING_REFUND);
            when(bookingRepository.findByUserIdAndBookingStatusOrderByBookingDateDesc(5, BookingStatus.PENDING_REFUND))
                    .thenReturn(List.of(b1));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(new DepartureInfoResponse());
            when(paymentClient.getPaymentByBooking(any())).thenReturn(null);

            List<BookingResponse> result = service.getBookingsByUser(5, "PENDING_REFUND");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBookingStatus()).isEqualTo("PENDING_REFUND");
        }

        @Test
        @DisplayName("ERROR CASE: invalid status string → IllegalArgumentException")
        void getBookings_invalidStatus_throwsException() {
            assertThatThrownBy(() -> service.getBookingsByUser(5, "INVALID_STATUS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid booking status");
        }

        @Test
        @DisplayName("HAPPY PATH: payment info mapped to response (amount, bank, accountNumber, accountName)")
        void getBookings_paymentFieldsMappedCorrectly() {
            Booking b = makePaidBooking(34, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            PaymentInfoResponse payInfo = new PaymentInfoResponse();
            payInfo.setPaymentID(77);
            payInfo.setAmount(new BigDecimal("500000"));
            payInfo.setBank("BIDV");
            payInfo.setAccountNumber("9988776655");
            payInfo.setAccountName("TOUR COMPANY");

            when(bookingRepository.findByUserIdOrderByBookingDateDesc(5)).thenReturn(List.of(b));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(new DepartureInfoResponse());
            when(paymentClient.getPaymentByBooking(34)).thenReturn(payInfo);

            List<BookingResponse> result = service.getBookingsByUser(5, null);

            assertThat(result).hasSize(1);
            BookingResponse resp = result.get(0);
            assertThat(resp.getPaymentID()).isEqualTo(77);
            assertThat(resp.getAmount()).isEqualByComparingTo("500000");
            assertThat(resp.getBank()).isEqualTo("BIDV");
            assertThat(resp.getAccountNumber()).isEqualTo("9988776655");
            assertThat(resp.getAccountName()).isEqualTo("TOUR COMPANY");
        }

        @Test
        @DisplayName("HAPPY PATH: refund info mapped correctly (refundBank, refundAccountNumber, etc.)")
        void getBookings_refundInfoMappedCorrectly() {
            Booking b = makePaidBooking(35, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            b.setBookingStatus(BookingStatus.PENDING_REFUND);
            RefundInformation refund = new RefundInformation();
            refund.setRefundID(10);
            refund.setBank("Vietinbank");
            refund.setAccountNumber("1234000000");
            refund.setAccountName("CUSTOMER");
            refund.setRefundStatus("PENDING");
            b.setRefundInformation(refund);

            when(bookingRepository.findByUserIdOrderByBookingDateDesc(5)).thenReturn(List.of(b));
            when(tourCatalogClient.getDepartureInfo(any())).thenReturn(new DepartureInfoResponse());
            when(paymentClient.getPaymentByBooking(any())).thenReturn(null);

            List<BookingResponse> result = service.getBookingsByUser(5, null);

            BookingResponse resp = result.get(0);
            assertThat(resp.getRefundBank()).isEqualTo("Vietinbank");
            assertThat(resp.getRefundAccountNumber()).isEqualTo("1234000000");
            assertThat(resp.getRefundAccountName()).isEqualTo("CUSTOMER");
            assertThat(resp.getRefundStatus()).isEqualTo("PENDING");
        }
    }

    // ── GetBookingById Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getBookingById()")
    class GetBookingByIdTests {

        @Test
        @DisplayName("HAPPY PATH: returns brief response with userId, bookingCode, status")
        void getBookingById_found_returnsBriefResponse() {
            Booking b = makePaidBooking(77, 42, new BigDecimal("1000000"), BigDecimal.ZERO);
            b.setBookingCode("BK-REVIEW01");

            when(bookingRepository.findById(77)).thenReturn(Optional.of(b));

            var result = service.getBookingById(77);

            assertThat(result.getBookingID()).isEqualTo(77);
            assertThat(result.getBookingCode()).isEqualTo("BK-REVIEW01");
            assertThat(result.getBookingStatus()).isEqualTo("PAID");
            assertThat(result.getUserId()).isEqualTo(42);
        }

        @Test
        @DisplayName("BOOKING NOT FOUND: throws RuntimeException")
        void getBookingById_notFound_throws() {
            when(bookingRepository.findById(999)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBookingById(999))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("STATUS NULL: bookingStatus returned as null without NPE")
        void getBookingById_nullStatus_returnsNull() {
            Booking b = makePaidBooking(55, 10, BigDecimal.ZERO, BigDecimal.ZERO);
            b.setBookingStatus(null);

            when(bookingRepository.findById(55)).thenReturn(Optional.of(b));

            var result = service.getBookingById(55);

            assertThat(result.getBookingStatus()).isNull();
        }
    }

    // ── UpdateBookingStatus Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("updateBookingStatus()")
    class UpdateBookingStatusTests {

        @Test
        @DisplayName("HAPPY PATH: PAID → REVIEWED saves booking with new status")
        void updateStatus_paidToReviewed_saves() {
            Booking b = makePaidBooking(10, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            when(bookingRepository.findById(10)).thenReturn(Optional.of(b));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateBookingStatus(10, "REVIEWED");

            ArgumentCaptor<Booking> cap = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(cap.capture());
            assertThat(cap.getValue().getBookingStatus()).isEqualTo(BookingStatus.REVIEWED);
        }

        @Test
        @DisplayName("HAPPY PATH: PENDING_REVIEW → REVIEWED (case-insensitive)")
        void updateStatus_caseInsensitive() {
            Booking b = makePaidBooking(11, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            b.setBookingStatus(BookingStatus.PENDING_REVIEW);
            when(bookingRepository.findById(11)).thenReturn(Optional.of(b));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateBookingStatus(11, "reviewed"); // lowercase

            ArgumentCaptor<Booking> cap = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(cap.capture());
            assertThat(cap.getValue().getBookingStatus()).isEqualTo(BookingStatus.REVIEWED);
        }

        @Test
        @DisplayName("BOOKING NOT FOUND: throws RuntimeException, never saves")
        void updateStatus_notFound_throws() {
            when(bookingRepository.findById(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateBookingStatus(404, "REVIEWED"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("404");
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("INVALID STATUS: throws IllegalArgumentException, never saves")
        void updateStatus_invalidStatus_throws() {
            Booking b = makePaidBooking(20, 5, new BigDecimal("500000"), BigDecimal.ZERO);
            when(bookingRepository.findById(20)).thenReturn(Optional.of(b));

            assertThatThrownBy(() -> service.updateBookingStatus(20, "BOGUS_STATUS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BOGUS_STATUS");
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("ALL VALID STATUSES: each status string is accepted")
        void updateStatus_allValidStatuses_noException() {
            String[] statuses = {
                "PENDING_PAYMENT", "OVERDUE_PAYMENT", "PENDING_CONFIRMATION",
                "PAID", "CANCELLED", "PENDING_REVIEW", "REVIEWED", "PENDING_REFUND"
            };
            for (String s : statuses) {
                Booking b = makePaidBooking(1, 1, BigDecimal.ONE, BigDecimal.ZERO);
                when(bookingRepository.findById(1)).thenReturn(Optional.of(b));
                when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                assertThatNoException().isThrownBy(() -> service.updateBookingStatus(1, s));
            }
        }
    }
}
