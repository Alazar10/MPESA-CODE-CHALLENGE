package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WalletResponse(
    @JsonProperty("wallet_id") String walletId,
    String status,
    Long balance,
    Long reserved,
    Long available
) {}