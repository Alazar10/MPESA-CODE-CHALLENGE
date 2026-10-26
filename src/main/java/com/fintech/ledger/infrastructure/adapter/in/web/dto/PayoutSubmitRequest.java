package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PayoutSubmitRequest(
    @NotBlank(message = "wallet_id is required")
    @JsonProperty("wallet_id")
    String walletId,

    @NotBlank(message = "bank_account is required")
    @JsonProperty("bank_account")
    String bankAccount,

    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be between 1 and 5,000,000")
    @Max(value = 5000000, message = "amount must be between 1 and 5,000,000")
    Long amount
) {}