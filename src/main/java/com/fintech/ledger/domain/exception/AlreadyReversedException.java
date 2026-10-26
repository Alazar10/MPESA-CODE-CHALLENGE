package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class AlreadyReversedException extends MpesaException {
    public AlreadyReversedException(String message) {
        super(HttpStatus.CONFLICT, "ALREADY_REVERSED", message);
    }
}