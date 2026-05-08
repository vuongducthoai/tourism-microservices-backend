package com.tourism.booking.repository.impl;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import com.tourism.booking.repository.BookingRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Criteria API implementation — mirrors monolith's BookingRepositoryCustomImpl.
 *
 * Filters:
 *  bookingCode   → LIKE '%...%' (case-insensitive, upper)
 *  bookingStatus → EQUAL (BookingStatus enum)
 *  bookingDate   → BETWEEN start-of-day 00:00:00 and end-of-day 23:59:59
 */
@Repository
public class BookingRepositoryCustomImpl implements BookingRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Booking> searchBookings(AdminSearchBookingRequest request, Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // ── Content query ────────────────────────────────────────────────────────
        CriteriaQuery<Booking> contentQuery = cb.createQuery(Booking.class);
        Root<Booking> root = contentQuery.from(Booking.class);

        List<Predicate> predicates = buildPredicates(request, cb, root);
        contentQuery.where(predicates.toArray(new Predicate[0]));

        // Apply sorting from Pageable
        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                if (order.isAscending()) {
                    orders.add(cb.asc(root.get(order.getProperty())));
                } else {
                    orders.add(cb.desc(root.get(order.getProperty())));
                }
            });
            contentQuery.orderBy(orders);
        }

        TypedQuery<Booking> typedQuery = entityManager.createQuery(contentQuery);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<Booking> bookings = typedQuery.getResultList();

        // ── Count query ──────────────────────────────────────────────────────────
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Booking> countRoot = countQuery.from(Booking.class);
        List<Predicate> countPredicates = buildPredicates(request, cb, countRoot);

        countQuery.select(cb.count(countRoot));
        countQuery.where(countPredicates.toArray(new Predicate[0]));

        Long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(bookings, pageable, total);
    }

    // ── Predicate builder — reused for content + count queries ──────────────────

    private List<Predicate> buildPredicates(AdminSearchBookingRequest request,
                                             CriteriaBuilder cb,
                                             Root<Booking> root) {
        List<Predicate> predicates = new ArrayList<>();

        // 1. bookingCode — partial match, case-insensitive
        if (request.getBookingCode() != null
                && !request.getBookingCode().trim().isEmpty()) {
            String pattern = "%" + request.getBookingCode().trim().toUpperCase() + "%";
            predicates.add(cb.like(cb.upper(root.get("bookingCode")), pattern));
        }

        // 2. bookingStatus — exact enum match
        if (request.getBookingStatus() != null
                && !request.getBookingStatus().trim().isEmpty()) {
            try {
                BookingStatus status = BookingStatus.valueOf(
                        request.getBookingStatus().trim().toUpperCase());
                predicates.add(cb.equal(root.get("bookingStatus"), status));
            } catch (IllegalArgumentException e) {
                // Unknown status → ignore predicate (no results will be blocked)
            }
        }

        // 3. bookingDate — filter the entire day
        if (request.getBookingDate() != null) {
            LocalDateTime startOfDay = request.getBookingDate()
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime endOfDay = request.getBookingDate()
                    .withHour(23).withMinute(59).withSecond(59).withNano(999_999_999);
            predicates.add(cb.between(root.get("bookingDate"), startOfDay, endOfDay));
        }

        return predicates;
    }
}
