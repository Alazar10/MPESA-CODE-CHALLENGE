package com.fintech.ledger.infrastructure.adapter.in.web;

import com.fintech.ledger.application.service.TransferService;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.*;
import com.fintech.ledger.infrastructure.config.idempotency.IdempotencyManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/p2p/transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyManager idempotencyManager;

    public TransferController(TransferService transferService, IdempotencyManager idempotencyManager) {
        this.transferService = transferService;
        this.idempotencyManager = idempotencyManager;
    }

    @PostMapping
    public ResponseEntity<P2PTransferResponse> transfer(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody P2PTransferRequest request) {

        P2PTransferResponse response = idempotencyManager.execute(
                idempotencyKey,
                "/mpesa/v1/p2p/transfers",
                request,
                P2PTransferResponse.class,
                () -> transferService.executeTransfer(request)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/reversal")
    public ResponseEntity<ReversalResponse> reverse(
            @PathVariable("id") String transferId,
            @Valid @RequestBody ReversalRequest request) {

        ReversalResponse response = transferService.reverseTransfer(transferId, request);
        return ResponseEntity.ok(response);
    }
}