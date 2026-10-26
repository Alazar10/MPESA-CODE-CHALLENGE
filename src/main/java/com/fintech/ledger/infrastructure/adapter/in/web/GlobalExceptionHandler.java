package com.fintech.ledger.infrastructure.adapter.in.web;

import com.fintech.ledger.domain.exception.MpesaException;
import com.fintech.ledger.infrastructure.adapter.in.web.dto.MpesaErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MpesaException.class)
    public ResponseEntity<MpesaErrorResponse> handleMpesaException(MpesaException ex) {
        log.warn("Business error [{}]: {}", ex.getError(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(new MpesaErrorResponse(ex.getError(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MpesaErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MpesaErrorResponse("VALIDATION_FAILED", msg));
    }

    @ExceptionHandler({CannotAcquireLockException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<MpesaErrorResponse> handleDatabaseUnavailable(Exception ex) {
        log.error("Database connection/lock error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new MpesaErrorResponse("SERVICE_UNAVAILABLE", "Database temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MpesaErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled internal server error: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new MpesaErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred"));
    }
}