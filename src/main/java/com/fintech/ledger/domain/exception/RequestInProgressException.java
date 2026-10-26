package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class RequestInProgressException extends MpesaException {
    public RequestInProgressException(String message) {
        super(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", message);
    }
}