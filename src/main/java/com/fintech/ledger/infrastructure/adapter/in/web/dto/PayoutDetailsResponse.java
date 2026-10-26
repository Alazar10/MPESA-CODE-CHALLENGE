package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record PayoutDetailsResponse(
    @JsonProperty("payout_id") String payoutId,
    @JsonProperty("wallet_id") String walletId,
    Long amount,
    String status,
    @JsonProperty("bank_ref") String bankRef,
    Boolean conflict,
    @JsonProperty("updated_at") Instant updatedAt
) {}