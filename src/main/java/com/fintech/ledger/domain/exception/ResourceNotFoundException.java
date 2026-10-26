package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends MpesaException {
    public ResourceNotFoundException(String error, String message) {
        super(HttpStatus.NOT_FOUND, error, message);
    }
}