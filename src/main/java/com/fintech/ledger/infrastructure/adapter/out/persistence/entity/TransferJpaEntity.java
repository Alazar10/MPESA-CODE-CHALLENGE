package com.fintech.ledger.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "transfers")
public class TransferJpaEntity {

    @Id
    @Column(name = "transfer_id", length = 64)
    private String transferId;

    @Column(name = "from_wallet", nullable = false, length = 32)
    private String fromWallet;

    @Column(name = "to_wallet", nullable = false, length = 32)
    private String toWallet;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferJpaEntity() {}

    public TransferJpaEntity(String transferId, String fromWallet, String toWallet, Long amount, String reference, String status) {
        this.transferId = transferId;
        this.fromWallet = fromWallet;
        this.toWallet = toWallet;
        this.amount = amount;
        this.reference = reference;
        this.status = status;
    }

    public String getTransferId() { return transferId; }
    public String getFromWallet() { return fromWallet; }
    public String getToWallet() { return toWallet; }
    public Long getAmount() { return amount; }
    public String getReference() { return reference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}