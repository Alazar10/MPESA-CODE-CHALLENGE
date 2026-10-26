package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record P2PTransferRequest(
    @NotBlank(message = "from_wallet is required")
    @JsonProperty("from_wallet")
    String fromWallet,

    @NotBlank(message = "to_wallet is required")
    @JsonProperty("to_wallet")
    String toWallet,

    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be between 1 and 5,000,000")
    @Max(value = 5000000, message = "amount must be between 1 and 5,000,000")
    Long amount,

    @NotBlank(message = "reference is required")
    @Size(min = 1, max = 64, message = "reference length must be between 1 and 64")
    String reference
) {}