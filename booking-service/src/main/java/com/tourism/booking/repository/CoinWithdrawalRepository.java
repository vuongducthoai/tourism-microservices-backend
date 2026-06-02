package com.tourism.booking.repository;

import com.tourism.booking.entity.CoinWithdrawal;
import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import com.tourism.booking.entity.CoinWithdrawalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CoinWithdrawalRepository extends JpaRepository<CoinWithdrawal, Long> {

    List<CoinWithdrawal> findByUserIdOrderByCreatedAtDesc(Integer userId);

    Optional<CoinWithdrawal> findByOperationKey(String operationKey);

    @Query("""
            SELECT c FROM CoinWithdrawal c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:userId IS NULL OR c.userId = :userId)
              AND (:errorSource IS NULL OR c.errorSource = :errorSource)
            ORDER BY c.createdAt DESC
            """)
    Page<CoinWithdrawal> searchAdmin(
            @Param("status") CoinWithdrawalStatus status,
            @Param("userId") Integer userId,
            @Param("errorSource") CoinWithdrawalErrorSource errorSource,
            Pageable pageable);
}
