package com.fintech.ledger.infrastructure.adapter.out.persistence.repository;

import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.TransferJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<TransferJpaEntity, String> {

    @Modifying
    @Query("UPDATE TransferJpaEntity t SET t.status = 'REVERSED' WHERE t.transferId = :id AND t.status = 'COMPLETED'")
    int markReversedIfCompleted(@Param("id") String id);
}