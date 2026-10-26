package com.fintech.ledger.application.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fintech.ledger.domain.exception.InsufficientFundsException;
import com.fintech.ledger.domain.exception.ResourceNotFoundException;
import com.fintech.ledger.domain.exception.WalletNotActiveException;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.*;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.PayoutJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.entity.WalletJpaEntity;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.PayoutRepository;
import com.fintech.ledger.infrastructure.adapter.out.persistence.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final WalletRepository walletRepository;
    private final PayoutRepository payoutRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient bankClient;
    private final ExecutorService bankSubmitExecutor = Executors.newFixedThreadPool(20);

    public PayoutService(WalletRepository walletRepository,
                         PayoutRepository payoutRepository,
                         JdbcTemplate jdbcTemplate) {
        this.walletRepository = walletRepository;
        this.payoutRepository = payoutRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.bankClient = RestClient.builder()
                .baseUrl("http://localhost:9090")
                .build();
    }

    // Step 1: Synchronous Reservation in DB (Runs in <5ms, answers 202 under 500ms p95)
    @Transactional(rollbackFor = Exception.class)
    public PayoutAcceptedResponse reserveAndCreatePayout(PayoutSubmitRequest req) {
        WalletJpaEntity wallet = walletRepository.findByIdForUpdate(req.walletId())
                .orElseThrow(() -> new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + req.walletId() + " not found"));

        if (!"ACTIVE".equalsIgnoreCase(wallet.getStatus())) {
            throw new WalletNotActiveException("Wallet " + req.walletId() + " is not ACTIVE");
        }

        if (wallet.getAvailable() < req.amount()) {
            throw new InsufficientFundsException(
                wallet.getWalletId() + " available " + wallet.getAvailable() + ", requested " + req.amount()
            );
        }

        // Reserve funds (available drops, balance does not)
        wallet.setReserved(wallet.getReserved() + req.amount());
        walletRepository.save(wallet);

        String payoutId = "P-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        PayoutJpaEntity payout = new PayoutJpaEntity(payoutId, req.walletId(), req.bankAccount(), req.amount(), "PENDING");
        payoutRepository.save(payout);

        // Async submit to fake bank completely detached from DB connection
        CompletableFuture.runAsync(() -> submitToBankAsync(payoutId, req.bankAccount(), req.amount()), bankSubmitExecutor);

        return new PayoutAcceptedResponse(payoutId, "PENDING");
    }

    // Step 2: Bank Submission over HTTP (Out-of-transaction)
    public void submitToBankAsync(String payoutId, String bankAccount, long amount) {
        try {
            Map<String, Object> body = Map.of("payout_id", payoutId, "bank_account", bankAccount, "amount", amount);

            ResponseEntity<Map<String, String>> response = bankClient.post()
                    .uri("/bank/payouts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String result = response.getBody().get("result");
                String bankRef = response.getBody().get("bank_ref");

                if ("ACCEPTED".equalsIgnoreCase(result)) {
                    updatePayoutStatus(payoutId, "SUBMITTED", bankRef);
                } else if ("REJECTED".equalsIgnoreCase(result)) {
                    finalizePayoutInternal(payoutId, "FAILED", null, false);
                }
            }
        } catch (Exception ex) {
            log.warn("Bank call failed or timed out for {}: {}. Outcome unknown, will reconcile.", payoutId, ex.getMessage());
            // Section 4.1: Unknown is not failure. Wait for callback or reconciliation.
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePayoutStatus(String payoutId, String status, String bankRef) {
        payoutRepository.findById(payoutId).ifPresent(p -> {
            if ("PENDING".equals(p.getStatus())) {
                p.setStatus(status);
                p.setBankRef(bankRef);
                payoutRepository.save(p);
            }
        });
    }

    // Step 3: Handle Callback from Bank (POST /mpesa/v1/callbacks/bank)
    @Transactional(rollbackFor = Exception.class)
    public void handleBankCallback(BankCallbackRequest req) {
        // Dedup event_id (Section 4.3: About 30% arrive 2 or 3 times concurrently)
        try {
            jdbcTemplate.update("INSERT INTO bank_callback_events (event_id, payout_id) VALUES (?, ?)", req.eventId(), req.payoutId());
        } catch (DataIntegrityViolationException dup) {
            // Duplicate event_id received: ignore cleanly with 200
            return;
        }

        finalizePayoutInternal(req.payoutId(), req.status(), req.bankRef(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizePayoutInternal(String payoutId, String newStatus, String bankRef, boolean fromReconciler) {
        PayoutJpaEntity payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("PAYOUT_NOT_FOUND", "Payout " + payoutId + " not found"));

        // Final states: SUCCEEDED, FAILED (Section 5.5)
        if ("SUCCEEDED".equalsIgnoreCase(payout.getStatus()) || "FAILED".equalsIgnoreCase(payout.getStatus())) {
            if (!payout.getStatus().equalsIgnoreCase(newStatus)) {
                payout.setConflict(true);
                payoutRepository.save(payout);
            }
            return;
        }

        WalletJpaEntity wallet = walletRepository.findByIdForUpdate(payout.getWalletId())
                .orElseThrow(() -> new ResourceNotFoundException("WALLET_NOT_FOUND", "Wallet " + payout.getWalletId() + " not found"));

        if ("SUCCEEDED".equalsIgnoreCase(newStatus)) {
            wallet.setBalance(wallet.getBalance() - payout.getAmount());
            wallet.setReserved(wallet.getReserved() - payout.getAmount());
            payout.setStatus("SUCCEEDED");
        } else {
            // FAILED or REJECTED: release reservation only
            wallet.setReserved(wallet.getReserved() - payout.getAmount());
            payout.setStatus("FAILED");
        }

        if (bankRef != null) {
            payout.setBankRef(bankRef);
        }
        walletRepository.save(wallet);
        payoutRepository.save(payout);
    }

    @Transactional(readOnly = true)
    public PayoutDetailsResponse getPayout(String payoutId) {
        PayoutJpaEntity p = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("PAYOUT_NOT_FOUND", "Payout " + payoutId + " not found"));

        return new PayoutDetailsResponse(
                p.getPayoutId(),
                p.getWalletId(),
                p.getAmount(),
                p.getStatus(),
                p.getBankRef(),
                p.getConflict(),
                p.getUpdatedAt()
        );
    }

    // Step 4: Background Reconciler (Ensures finality within 2 minutes under instance crash or 60s timeout)
    @Scheduled(fixedDelay = 5000)
    public void reconcileStalePayouts() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(10));
        List<PayoutJpaEntity> stale = payoutRepository.findStalePayoutsForReconciliation(cutoff);

        for (PayoutJpaEntity p : stale) {
            try {
                ResponseEntity<Map<String, String>> res = bankClient.get()
                        .uri("/bank/payouts/" + p.getPayoutId())
                        .retrieve()
                        .toEntity(new ParameterizedTypeReference<>() {});

                if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                    String status = res.getBody().get("status");
                    if ("SUCCEEDED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
                        finalizePayoutInternal(p.getPayoutId(), "REJECTED".equalsIgnoreCase(status) ? "FAILED" : status, null, true);
                    }
                }
            } catch (Exception ex) {
                log.warn("Reconcile error on {}: {}", p.getPayoutId(), ex.getMessage());
            }
        }
    }
}