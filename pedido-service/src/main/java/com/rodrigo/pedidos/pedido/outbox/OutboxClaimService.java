package com.rodrigo.pedidos.pedido.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {

    private static final String CLAIM_SQL = """
            WITH candidatos AS (
                SELECT event_id
                FROM outbox_event
                WHERE published_at IS NULL
                  AND (claim_until IS NULL OR claim_until < CURRENT_TIMESTAMP)
                ORDER BY created_at, event_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_event evento
            SET claim_id = ?,
                claim_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                attempts = attempts + 1
            FROM candidatos
            WHERE evento.event_id = candidatos.event_id
            RETURNING evento.event_id, evento.event_type, evento.topic, evento.message_key,
                      evento.payload, evento.attempts, evento.claim_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;
    private final Duration lease;

    public OutboxClaimService(JdbcTemplate jdbcTemplate,
                              @Value("${app.outbox.batch-size:25}") int batchSize,
                              @Value("${app.outbox.lease-ms:30000}") long leaseMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = batchSize;
        this.lease = Duration.ofMillis(leaseMs);
    }

    @Transactional
    public List<ClaimedOutboxEvent> claimBatch() {
        UUID claimId = UUID.randomUUID();
        return jdbcTemplate.query(CLAIM_SQL, this::mapRow, batchSize, claimId, lease.toMillis());
    }

    @Transactional
    public void markPublished(ClaimedOutboxEvent event) {
        jdbcTemplate.update("""
                        UPDATE outbox_event
                        SET published_at = CURRENT_TIMESTAMP,
                            claim_id = NULL,
                            claim_until = NULL,
                            last_error = NULL
                        WHERE event_id = ? AND claim_id = ?
                        """,
                event.eventId(), event.claimId());
    }

    @Transactional
    public void releaseAfterFailure(ClaimedOutboxEvent event, String message) {
        jdbcTemplate.update("""
                        UPDATE outbox_event
                        SET claim_until = CURRENT_TIMESTAMP +
                                (LEAST(300000::numeric, 1000 * POWER(2, LEAST(attempts, 8))) * INTERVAL '1 millisecond'),
                            last_error = ?
                        WHERE event_id = ? AND claim_id = ?
                        """,
                truncate(message, 2000), event.eventId(), event.claimId());
    }

    private ClaimedOutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedOutboxEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getObject("claim_id", UUID.class));
    }

    private String truncate(String value, int max) {
        if (value == null) return "Falha sem mensagem";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ClaimedOutboxEvent(UUID eventId, String eventType, String topic,
                                     String messageKey, String payload, int attempts,
                                     UUID claimId) {
    }
}
