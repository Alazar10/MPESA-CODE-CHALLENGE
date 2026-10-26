package com.fintech.ledger.domain.exception;
import org.springframework.http.HttpStatus;

public class WalletNotActiveException extends MpesaException {
    public WalletNotActiveException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "WALLET_NOT_ACTIVE", message);
    }
}