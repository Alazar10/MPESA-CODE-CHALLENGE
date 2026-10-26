package com.fintech.ledger.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ReversalRequest(
    @NotBlank(message = "reason is required")
    String reason
) {}