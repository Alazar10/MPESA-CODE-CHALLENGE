package com.fintech.ledger.infrastructure.adapter.in.web;

import com.fintech.ledger.application.service.WalletService;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletResponse;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletStatusUpdateRequest;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.WalletStatusUpdateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("id") String id) {
        return ResponseEntity.ok(walletService.getWalletSnapshot(id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<WalletStatusUpdateResponse> updateStatus(
            @PathVariable("id") String id,
            @Valid @RequestBody WalletStatusUpdateRequest request) {
        return ResponseEntity.ok(walletService.updateStatus(id, request));
    }
}