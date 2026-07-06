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
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Integer>, BookingRepositoryCustom {

    @Query("""
            SELECT b FROM Booking b
            LEFT JOIN FETCH b.passengers
            WHERE UPPER(b.bookingCode) = UPPER(:code)
              AND (b.isDeleted IS NULL OR b.isDeleted = false)
            """)
    Optional<Booking> findByBookingCodeWithPassengers(@Param("code") String code);

    List<Booking> findByUserIdOrderByBookingDateDesc(Integer userId);

    List<Booking> findByUserIdAndBookingStatusOrderByBookingDateDesc(Integer userId, BookingStatus status);

    java.util.Optional<Booking> findByBookingCode(String bookingCode);

    // ─── Dashboard stats queries ───

    @Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingStatus = :status AND (b.isDeleted = false OR b.isDeleted IS NULL)")
    BigDecimal sumTotalPriceByStatus(@Param("status") BookingStatus status);

    @Query("""
            SELECT SUM(b.totalPrice)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            """)
    BigDecimal sumTotalPriceByStatusBetween(@Param("status") BookingStatus status,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

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

    @Query("""
            SELECT CAST(b.bookingStatus AS string), COUNT(b), SUM(b.totalPrice)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.bookingStatus
            """)
    List<Object[]> getBookingStatusDistributionBetween(@Param("start") LocalDateTime start,
                                                       @Param("end") LocalDateTime end);

    Long countByBookingStatus(BookingStatus status);

    Long countByBookingDateBetween(LocalDateTime start, LocalDateTime end);

    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            """)
    Long countActiveBookingsBetween(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            """)
    Long countByBookingStatusBetween(@Param("status") BookingStatus status,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

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
            SELECT b.departureId, COUNT(b), SUM(b.totalPrice)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            ORDER BY COUNT(b) DESC, SUM(b.totalPrice) DESC
            """)
    List<Object[]> getTopDeparturesByBookingCountBetween(@Param("status") BookingStatus status,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end,
                                                         Pageable pageable);

    @Query("""
            SELECT b.departureId, COUNT(b)
            FROM Booking b
            WHERE b.bookingStatus = :status
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            ORDER BY COUNT(b) DESC
            """)
    List<Object[]> getTopDeparturesByStatus(@Param("status") BookingStatus status, Pageable pageable);

    // ─── Doanh thu theo NHÓM trạng thái đã thu tiền (PAID + PENDING_REVIEW + REVIEWED) ───
    // Đơn đã thanh toán, sau khi đi tour xong sẽ chuyển sang PENDING_REVIEW rồi REVIEWED.
    // Tiền vẫn đã thu, nên phải tính vào doanh thu.

    @Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingStatus IN :statuses AND (b.isDeleted = false OR b.isDeleted IS NULL)")
    BigDecimal sumTotalPriceByStatuses(@Param("statuses") java.util.Collection<BookingStatus> statuses);

    @Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingDate >= :start AND b.bookingDate < :end AND b.bookingStatus IN :statuses AND (b.isDeleted = false OR b.isDeleted IS NULL)")
    BigDecimal sumRevenueByDateAndStatuses(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("statuses") java.util.Collection<BookingStatus> statuses);

    @Query("""
            SELECT FUNCTION('DATE', b.bookingDate), SUM(b.totalPrice), COUNT(b)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus IN :statuses
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY FUNCTION('DATE', b.bookingDate)
            ORDER BY FUNCTION('DATE', b.bookingDate)
            """)
    List<Object[]> getDailyRevenueCountsByStatuses(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("statuses") java.util.Collection<BookingStatus> statuses);

    @Query("""
            SELECT b.departureId, COUNT(b), SUM(b.totalPrice)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus IN :statuses
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            ORDER BY COUNT(b) DESC, SUM(b.totalPrice) DESC
            """)
    List<Object[]> getTopDeparturesByBookingCountBetweenStatuses(@Param("statuses") java.util.Collection<BookingStatus> statuses,
                                                                 @Param("start") LocalDateTime start,
                                                                 @Param("end") LocalDateTime end,
                                                                 Pageable pageable);

    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.bookingDate >= :start AND b.bookingDate < :end
              AND b.bookingStatus IN :statuses
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            """)
    Long countByBookingStatusesBetween(@Param("statuses") java.util.Collection<BookingStatus> statuses,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    // ─── Số khách ĐÃ ĐẶT theo từng lịch khởi hành (loại trừ booking đã hủy) ───
    // Dùng cho trang Quản lý Lịch khởi hành: cột "đã đặt" = tổng số khách đang giữ chỗ.
    @Query("""
            SELECT b.departureId, COALESCE(SUM(b.totalPassengers), 0)
            FROM Booking b
            WHERE b.departureId IN :ids
              AND b.bookingStatus <> :excluded
              AND (b.isDeleted = false OR b.isDeleted IS NULL)
            GROUP BY b.departureId
            """)
    List<Object[]> sumPassengersByDepartureIds(@Param("ids") java.util.Collection<Integer> ids,
                                               @Param("excluded") BookingStatus excluded);
}
