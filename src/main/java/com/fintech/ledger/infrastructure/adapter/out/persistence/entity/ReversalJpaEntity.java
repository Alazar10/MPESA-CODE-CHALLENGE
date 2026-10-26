package com.fintech.ledger.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "reversals")
public class ReversalJpaEntity {

    @Id
    @Column(name = "reversal_id", length = 64)
    private String reversalId;

    @Column(name = "transfer_id", nullable = false, unique = true, length = 64)
    private String transferId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "reason", nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReversalJpaEntity() {}

    public ReversalJpaEntity(String reversalId, String transferId, Long amount, String reason) {
        this.reversalId = reversalId;
        this.transferId = transferId;
        this.amount = amount;
        this.reason = reason;
    }

    public String getReversalId() { return reversalId; }
    public String getTransferId() { return transferId; }
    public Long getAmount() { return amount; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}