package com.rodrigo.pedidos.pedido.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

@Component
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID enqueue(String topic, String key, Object event) {
        try {
            UUID eventId = UUID.randomUUID();
            jdbcTemplate.update("""
                            INSERT INTO outbox_event
                                (event_id, event_type, topic, message_key, payload, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    eventId, event.getClass().getName(), topic, key,
                    objectMapper.writeValueAsString(event), Timestamp.from(Instant.now()));
            return eventId;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Não foi possível serializar evento para o outbox", ex);
        }
    }
}
