package com.example.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String event, Long entityId) {
        jdbcTemplate.update(
            "INSERT INTO audit_log (event, entity_id) VALUES (?, ?)",
            event, entityId);
    }
}
