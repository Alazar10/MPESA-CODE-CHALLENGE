package com.fintech.ledger.infrastructure.adapter.out.persistence.repository;

import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.PayoutJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends JpaRepository<PayoutJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PayoutJpaEntity p WHERE p.payoutId = :id")
    Optional<PayoutJpaEntity> findByIdForUpdate(@Param("id") String id);

    @Query(value = "SELECT * FROM payouts WHERE status IN ('PENDING', 'SUBMITTED') AND created_at < :cutoff ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<PayoutJpaEntity> findStalePayoutsForReconciliation(@Param("cutoff") Instant cutoff);
}