package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends MpesaException {
    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }
}