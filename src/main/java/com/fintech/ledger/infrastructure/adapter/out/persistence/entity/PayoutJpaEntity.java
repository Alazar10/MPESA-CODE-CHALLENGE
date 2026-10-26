package com.fintech.ledger.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity
@Table(name = "payouts")
public class PayoutJpaEntity {

    @Id
    @Column(name = "payout_id", length = 64)
    private String payoutId;

    @Column(name = "wallet_id", nullable = false, length = 32)
    private String walletId;

    @Column(name = "bank_account", nullable = false, length = 64)
    private String bankAccount;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "bank_ref", length = 64)
    private String bankRef;

    @Column(name = "conflict", nullable = false)
    private Boolean conflict = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayoutJpaEntity() {}

    public PayoutJpaEntity(String payoutId, String walletId, String bankAccount, Long amount, String status) {
        this.payoutId = payoutId;
        this.walletId = walletId;
        this.bankAccount = bankAccount;
        this.amount = amount;
        this.status = status;
        this.conflict = false;
    }

    public String getPayoutId() { return payoutId; }
    public String getWalletId() { return walletId; }
    public String getBankAccount() { return bankAccount; }
    public Long getAmount() { return amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBankRef() { return bankRef; }
    public void setBankRef(String bankRef) { this.bankRef = bankRef; }
    public Boolean getConflict() { return conflict; }
    public void setConflict(Boolean conflict) { this.conflict = conflict; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}