package com.fintech.ledger.infrastructure.adapter.out.persistence.repository;

import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.ReversalJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReversalRepository extends JpaRepository<ReversalJpaEntity, String> {}