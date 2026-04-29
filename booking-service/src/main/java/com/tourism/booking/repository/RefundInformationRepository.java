package com.tourism.booking.repository;

import com.tourism.booking.entity.RefundInformation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundInformationRepository extends JpaRepository<RefundInformation, Integer> {

    Optional<RefundInformation> findByBooking_BookingID(Integer bookingId);
}
