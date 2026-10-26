package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record WalletStatusUpdateRequest(
    @NotBlank(message = "status is required")
    @Pattern(regexp = "ACTIVE|FROZEN", message = "status must be ACTIVE or FROZEN")
    String status,

    String reason
) {}