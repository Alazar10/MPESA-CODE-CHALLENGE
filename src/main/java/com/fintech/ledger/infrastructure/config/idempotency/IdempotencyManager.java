package com.fintech.ledger.infrastructure.config.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.ledger.domain.exception.IdempotencyConflictException;
import com.fintech.ledger.domain.exception.RequestInProgressException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class IdempotencyManager {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyManager(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public static class CachedResult {
        public final int statusCode;
        public final String responseBody;

        public CachedResult(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }

    public <T> T execute(String key, String endpoint, Object requestBody, Supplier<T> operation) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (key.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key exceeds 64 characters");
        }

        String bodyHash = computeHash(requestBody);

        try {
            
            jdbcTemplate.update(
                "INSERT INTO idempotency_records (idempotency_key, endpoint, request_hash, status) VALUES (?, ?, ?, 'IN_PROGRESS')",
                key, endpoint, bodyHash
            );
        } catch (DataIntegrityViolationException ex) {
            
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT request_hash, status, response_code, response_body FROM idempotency_records WHERE idempotency_key = ?",
                key
            );

            String existingHash = (String) row.get("request_hash");
            String status = (String) row.get("status");

            if (!existingHash.equals(bodyHash)) {
                throw new IdempotencyConflictException("Idempotency key reused with different request payload");
            }

            if ("IN_PROGRESS".equalsIgnoreCase(status)) {
                throw new RequestInProgressException("Concurrent request with same idempotency key is already in progress");
            }

            try {
                @SuppressWarnings("unchecked")
                Class<T> returnType = (Class<T>) operation.get().getClass();
                return objectMapper.readValue((String) row.get("response_body"), returnType);
            } catch (Exception parseEx) {
                throw new RuntimeException("Failed to deserialize cached idempotent response", parseEx);
            }
        }

        T result;
        try {
            result = operation.get();
        } catch (RuntimeException e) {
            
            jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key = ?", key);
            throw e;
        }

        try {
            String jsonResult = objectMapper.writeValueAsString(result);
            jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', response_code = 201, response_body = ? WHERE idempotency_key = ?",
                jsonResult, key
            );
        } catch (Exception serializeEx) {
            throw new RuntimeException("Failed to serialize idempotent response", serializeEx);
        }

        return result;
    }

    private String computeHash(Object body) {
        try {
            String rawJson = objectMapper.writeValueAsString(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute request body hash", e);
        }
    }
}