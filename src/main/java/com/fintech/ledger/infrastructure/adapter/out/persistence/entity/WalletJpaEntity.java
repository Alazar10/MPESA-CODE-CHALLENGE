package com.fintech.ledger.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "wallets")
public class WalletJpaEntity {

    @Id
    @Column(name = "wallet_id", length = 32)
    private String walletId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "balance", nullable = false)
    private Long balance;

    @Column(name = "reserved", nullable = false)
    private Long reserved;

    protected WalletJpaEntity() {}

    public WalletJpaEntity(String walletId, String status, Long balance, Long reserved) {
        this.walletId = walletId;
        this.status = status;
        this.balance = balance;
        this.reserved = reserved;
    }

    public String getWalletId() { return walletId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }
    public Long getReserved() { return reserved; }
    public void setReserved(Long reserved) { this.reserved = reserved; }
    public Long getAvailable() { return balance - reserved; }
}