package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record BankCallbackRequest(
    @NotBlank @JsonProperty("event_id") String eventId,
    @NotBlank @JsonProperty("payout_id") String payoutId,
    @JsonProperty("bank_ref") String bankRef,
    @NotBlank String status
) {}