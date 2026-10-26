package com.fintech.ledger.infrastructure.adapter.out.persistence.repository;

import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.WalletJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<WalletJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletJpaEntity w WHERE w.walletId = :id")
    Optional<WalletJpaEntity> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletJpaEntity w WHERE w.walletId IN :ids ORDER BY w.walletId ASC")
    List<WalletJpaEntity> findAllByIdInForUpdateOrderById(@Param("ids") List<String> ids);
}