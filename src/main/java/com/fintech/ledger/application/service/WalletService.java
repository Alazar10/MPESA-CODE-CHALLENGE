package com.fintech.ledger.application.service;

import com.fintech.ledger.domain.exception.ResourceNotFoundException;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletResponse;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletStatusUpdateRequest;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletStatusUpdateResponse;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.WalletJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletSnapshot(String walletId) {
        WalletJpaEntity wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + walletId + " not found"));

        return new WalletResponse(
                wallet.getWalletId(),
                wallet.getStatus(),
                wallet.getBalance(),
                wallet.getReserved(),
                wallet.getAvailable()
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletStatusUpdateResponse updateStatus(String walletId, WalletStatusUpdateRequest request) {
        WalletJpaEntity wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + walletId + " not found"));

        if (!wallet.getStatus().equalsIgnoreCase(request.status())) {
            wallet.setStatus(request.status().toUpperCase());
            walletRepository.save(wallet);
        }

        return new WalletStatusUpdateResponse(wallet.getWalletId(), wallet.getStatus(), Instant.now());
    }
}