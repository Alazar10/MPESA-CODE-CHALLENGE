package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends MpesaException {
    public InsufficientFundsException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", message);
    }
}