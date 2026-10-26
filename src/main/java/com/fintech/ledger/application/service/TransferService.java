package com.fintech.ledger.application.service;

import com.fintech.ledger.domain.exception.*;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.*;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.ReversalJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.TransferJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.WalletJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.ReversalRepository;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.TransferRepository;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final ReversalRepository reversalRepository;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           ReversalRepository reversalRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.reversalRepository = reversalRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public P2PTransferResponse executeTransfer(P2PTransferRequest request) {
        if (request.fromWallet().equals(request.toWallet())) {
            throw new SameWalletException("Source and destination wallets must be different");
        }

        // Ordered locking by wallet_id prevents deadlocks (S1.3)
        List<String> sortedIds = List.of(request.fromWallet(), request.toWallet()).stream()
                .sorted()
                .toList();

        List<WalletJpaEntity> lockedWallets = walletRepository.findAllByIdInForUpdateOrderById(sortedIds);
        Map<String, WalletJpaEntity> walletMap = lockedWallets.stream()
                .collect(Collectors.toMap(WalletJpaEntity::getWalletId, Function.identity()));

        WalletJpaEntity from = walletMap.get(request.fromWallet());
        WalletJpaEntity to = walletMap.get(request.toWallet());

        if (from == null) {
            throw new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + request.fromWallet() + " not found");
        }
        if (to == null) {
            throw new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + request.toWallet() + " not found");
        }

        if (!"ACTIVE".equalsIgnoreCase(from.getStatus())) {
            throw new WalletNotActiveException("Sender wallet " + request.fromWallet() + " is not ACTIVE");
        }
        if (!"ACTIVE".equalsIgnoreCase(to.getStatus())) {
            throw new WalletNotActiveException("Receiver wallet " + request.toWallet() + " is not ACTIVE");
        }

        if (from.getAvailable() < request.amount()) {
            throw new InsufficientFundsException(
                request.fromWallet() + " available " + from.getAvailable() + ", requested " + request.amount()
            );
        }

        // Atomic balance shift
        from.setBalance(from.getBalance() - request.amount());
        to.setBalance(to.getBalance() + request.amount());

        walletRepository.save(from);
        walletRepository.save(to);

        String transferId = "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        TransferJpaEntity transfer = new TransferJpaEntity(
                transferId,
                from.getWalletId(),
                to.getWalletId(),
                request.amount(),
                request.reference(),
                "COMPLETED"
        );
        TransferJpaEntity saved = transferRepository.save(transfer);

        return new P2PTransferResponse(
                saved.getTransferId(),
                saved.getStatus(),
                from.getAvailable(),
                saved.getCreatedAt() != null ? saved.getCreatedAt() : Instant.now()
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public ReversalResponse reverseTransfer(String transferId, ReversalRequest request) {
        TransferJpaEntity transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("TRANSFER_NOT_FOUND", "Transfer " + transferId + " not found"));

        if ("REVERSED".equalsIgnoreCase(transfer.getStatus())) {
            throw new AlreadyReversedException("Transfer " + transferId + " is already reversed");
        }
        if (!"COMPLETED".equalsIgnoreCase(transfer.getStatus())) {
            throw new NotReversibleException("Transfer " + transferId + " is not in COMPLETED status");
        }

        // Lock in alphabetical order
        String originalFrom = transfer.getFromWallet(); // Receiver in reversal
        String originalTo = transfer.getToWallet();     // Debited in reversal

        List<String> sortedIds = List.of(originalFrom, originalTo).stream().sorted().toList();
        List<WalletJpaEntity> locked = walletRepository.findAllByIdInForUpdateOrderById(sortedIds);
        Map<String, WalletJpaEntity> map = locked.stream()
                .collect(Collectors.toMap(WalletJpaEntity::getWalletId, Function.identity()));

        WalletJpaEntity debitedWallet = map.get(originalTo);
        WalletJpaEntity creditedWallet = map.get(originalFrom);

        if (debitedWallet == null || creditedWallet == null) {
            throw new ResourceNotFoundException("WALLET_NOT_FOUND", "One or both wallets not found");
        }

        // Debited wallet must be ACTIVE
        if (!"ACTIVE".equalsIgnoreCase(debitedWallet.getStatus())) {
            throw new WalletNotActiveException("Debited wallet " + debitedWallet.getWalletId() + " is not ACTIVE");
        }

        // Debited wallet must have sufficient available funds
        if (debitedWallet.getAvailable() < transfer.getAmount()) {
            throw new InsufficientFundsException(
                debitedWallet.getWalletId() + " available " + debitedWallet.getAvailable() + ", requested " + transfer.getAmount()
            );
        }

        // Credited wallet can be FROZEN (Section 5.2)

        // Atomic conditional update to guard concurrent race (S2.1)
        int updated = transferRepository.markReversedIfCompleted(transferId);
        if (updated == 0) {
            throw new AlreadyReversedException("Transfer " + transferId + " was concurrently reversed");
        }

        debitedWallet.setBalance(debitedWallet.getBalance() - transfer.getAmount());
        creditedWallet.setBalance(creditedWallet.getBalance() + transfer.getAmount());

        walletRepository.save(debitedWallet);
        walletRepository.save(creditedWallet);

        String reversalId = "R-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ReversalJpaEntity reversal = new ReversalJpaEntity(reversalId, transferId, transfer.getAmount(), request.reason());
        reversalRepository.save(reversal);

        return new ReversalResponse(transferId, reversalId, "REVERSED", transfer.getAmount());
    }
}