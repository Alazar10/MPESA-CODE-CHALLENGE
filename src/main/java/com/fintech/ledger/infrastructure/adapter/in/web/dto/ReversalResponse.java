package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReversalResponse(
    @JsonProperty("transfer_id") String transferId,
    @JsonProperty("reversal_id") String reversalId,
    String status,
    Long amount
) {}