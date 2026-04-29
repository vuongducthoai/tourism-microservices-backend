package com.tourism.booking.repository;

import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Integer> {

    List<Booking> findByUserIdOrderByBookingDateDesc(Integer userId);

    List<Booking> findByUserIdAndBookingStatusOrderByBookingDateDesc(Integer userId, BookingStatus status);
}
