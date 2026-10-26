package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class SameWalletException extends MpesaException {
    public SameWalletException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "SAME_WALLET", message);
    }
}