package com.tourism.payment.repository;

import com.tourism.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    Optional<Payment> findByBookingId(Integer bookingId);
}
