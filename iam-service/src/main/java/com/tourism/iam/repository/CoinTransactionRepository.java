package com.tourism.iam.repository;

import com.tourism.iam.entity.CoinTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinTransactionRepository extends JpaRepository<CoinTransaction, Long> {
    boolean existsByOperationKey(String operationKey);
}
