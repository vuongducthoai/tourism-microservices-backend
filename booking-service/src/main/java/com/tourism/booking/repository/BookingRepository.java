package com.tourism.booking.repository;

import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Integer>, BookingRepositoryCustom {

    List<Booking> findByUserIdOrderByBookingDateDesc(Integer userId);

    List<Booking> findByUserIdAndBookingStatusOrderByBookingDateDesc(Integer userId, BookingStatus status);

    java.util.Optional<Booking> findByBookingCode(String bookingCode);

    // ─── Dashboard stats queries ───

    @Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingStatus = :status AND (b.isDeleted = false OR b.isDeleted IS NULL)")
    BigDecimal sumTotalPriceByStatus(@Param("status") BookingStatus status);

    @Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingDate BETWEEN :start AND :end AND b.bookingStatus = :status AND (b.isDeleted = false OR b.isDeleted IS NULL)")
    BigDecimal sumRevenueByDateAndStatus(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         @Param("status") BookingStatus status);

    @Query("""
            SELECT FUNCTION('DATE', b.bookingDate), SUM(b.totalPrice), COUNT(b)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY FUNCTION('DATE', b.bookingDate)
            ORDER BY FUNCTION('DATE', b.bookingDate)
            """)
    List<Object[]> getDailyRevenueCounts(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("status") BookingStatus status);

    @Query("""
            SELECT CAST(b.bookingStatus AS string), COUNT(b), SUM(b.totalPrice)
            FROM Booking b
            WHERE (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.bookingStatus
            """)
    List<Object[]> getBookingStatusDistribution();

    Long countByBookingStatus(BookingStatus status);

    Long countByBookingDateBetween(LocalDateTime start, LocalDateTime end);

    List<Booking> findTop5ByBookingStatusOrderByCreatedAtDesc(BookingStatus status);

    @Query("""
            SELECT b.departureId, COUNT(b), SUM(b.totalPrice)
            FROM Booking b
            WHERE b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            ORDER BY COUNT(b) DESC, SUM(b.totalPrice) DESC
            """)
    List<Object[]> getTopDeparturesByBookingCount(@Param("status") BookingStatus status, Pageable pageable);

    @Query("""
            SELECT b.departureId, COUNT(b)
            FROM Booking b
            WHERE b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            ORDER BY COUNT(b) DESC
            """)
    List<Object[]> getTopDeparturesByStatus(@Param("status") BookingStatus status, Pageable pageable);
}
