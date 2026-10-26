package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record P2PTransferResponse(
    @JsonProperty("transfer_id") String transferId,
    String status,
    @JsonProperty("from_available_after") Long fromAvailableAfter,
    @JsonProperty("created_at") Instant createdAt
) {}