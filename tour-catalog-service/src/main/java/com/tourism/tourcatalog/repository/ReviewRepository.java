package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Integer> {

    Optional<Review> findByBookingId(Integer bookingId);

    boolean existsByBookingId(Integer bookingId);
}
