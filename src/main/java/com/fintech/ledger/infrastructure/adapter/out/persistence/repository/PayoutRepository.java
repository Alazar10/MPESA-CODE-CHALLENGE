package com.fintech.ledger.infrastructure.adapter.out.persistence.repository;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.PayoutJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<PayoutJpaEntity, String> {}