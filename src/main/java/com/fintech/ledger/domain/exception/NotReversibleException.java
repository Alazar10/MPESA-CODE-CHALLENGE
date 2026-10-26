package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class NotReversibleException extends MpesaException {
    public NotReversibleException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_REVERSIBLE", message);
    }
}