package com.fintech.ledger.infrastructure.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mpesa/internal")
public class InternalResetController {

    private final JdbcTemplate jdbcTemplate;

    public InternalResetController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetState() {
        jdbcTemplate.execute("SELECT reset_mpesa_state();");
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Database reset and seeded"));
    }
}