package com.fintech.ledger.infrastructure.adapter.in.web;

import com.fintech.ledger.application.service.PayoutService;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.*;
import com.fintech.ledger.infrastructure.config.idempotency.IdempotencyManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1")
public class PayoutController {

    private final PayoutService payoutService;
    private final IdempotencyManager idempotencyManager;

    public PayoutController(PayoutService payoutService, IdempotencyManager idempotencyManager) {
        this.payoutService = payoutService;
        this.idempotencyManager = idempotencyManager;
    }

    @PostMapping("/payouts")
    public ResponseEntity<PayoutAcceptedResponse> createPayout(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutSubmitRequest request) {

        PayoutAcceptedResponse response = idempotencyManager.execute(
                idempotencyKey,
                "/mpesa/v1/payouts",
                request,
                PayoutAcceptedResponse.class,
                () -> payoutService.reserveAndCreatePayout(request)
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/payouts/{id}")
    public ResponseEntity<PayoutDetailsResponse> getPayout(@PathVariable("id") String id) {
        return ResponseEntity.ok(payoutService.getPayout(id));
    }

    @PostMapping("/callbacks/bank")
    public ResponseEntity<Void> receiveBankCallback(@Valid @RequestBody BankCallbackRequest request) {
        payoutService.handleBankCallback(request);
        return ResponseEntity.ok().build();
    }
}