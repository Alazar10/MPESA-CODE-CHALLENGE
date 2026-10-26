package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record WalletStatusUpdateResponse(
    @JsonProperty("wallet_id") String walletId,
    String status,
    @JsonProperty("changed_at") Instant changedAt
) {}