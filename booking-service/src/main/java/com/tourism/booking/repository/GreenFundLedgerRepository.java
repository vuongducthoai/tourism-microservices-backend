package com.tourism.booking.repository;

import com.tourism.booking.entity.GreenFundLedger;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface GreenFundLedgerRepository extends JpaRepository<GreenFundLedger, Long> {

    /** Cộng quỹ — atomic UPDATE, an toàn khi nhiều đóng góp song song. */
    @Modifying
    @Query("UPDATE GreenFundLedger l SET l.totalFundRaised = l.totalFundRaised + :amount, " +
           "l.updatedAt = CURRENT_TIMESTAMP WHERE l.id = 1")
    int addFund(@Param("amount") BigDecimal amount);

    /** Khóa dòng ledger để quy đổi quỹ → cây (scheduler, tránh race). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM GreenFundLedger l WHERE l.id = 1")
    Optional<GreenFundLedger> findSingletonForUpdate();
}
