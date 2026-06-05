package com.tourism.iam.repository;

import com.tourism.iam.entity.CoinTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinTransactionRepository extends JpaRepository<CoinTransaction, Long> {
    boolean existsByOperationKey(String operationKey);

    /** Đối soát coin forum: tổng amount theo direction + prefix operationKey. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(t.amount), 0) FROM CoinTransaction t " +
            "WHERE t.direction = :direction AND t.operationKey LIKE :prefix")
    java.math.BigDecimal sumByDirectionAndKeyPrefix(
            @org.springframework.data.repository.query.Param("direction") String direction,
            @org.springframework.data.repository.query.Param("prefix") String prefix);
}
