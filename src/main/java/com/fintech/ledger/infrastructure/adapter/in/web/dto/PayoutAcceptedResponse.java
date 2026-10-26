package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PayoutAcceptedResponse(
    @JsonProperty("payout_id") String payoutId,
    String status
) {}