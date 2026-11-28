package com.rodrigo.pedidos.pedido.idempotency;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Registro das chaves. As operações de escrita devem rodar na transação que cria o pedido, para que
 * não exista pedido sem chave nem chave sem pedido.
 */
@Repository
public class IdempotencyStore {

    /**
     * Reivindica a chave. Retorna 1 quando inseriu (ou assumiu uma linha expirada) e 0 quando há uma
     * linha ainda válida. Um insert concorrente espera a outra transação terminar antes de decidir.
     */
    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_key (scope, idem_key, request_hash, pedido_id, created_at, expires_at)
            VALUES (?, ?, ?, NULL, ?, ?)
            ON CONFLICT (scope, idem_key) DO UPDATE
               SET request_hash = EXCLUDED.request_hash,
                   pedido_id = NULL,
                   created_at = EXCLUDED.created_at,
                   expires_at = EXCLUDED.expires_at
             WHERE idempotency_key.expires_at <= EXCLUDED.created_at
            """;

    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM idempotency_key
            WHERE (scope, idem_key) IN (
                SELECT scope, idem_key FROM idempotency_key
                WHERE expires_at < ?
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            """;

    public record Registro(String requestHash, UUID pedidoId) {
    }

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean reivindicar(String escopo, String chave, String requestHash, Instant agora, Instant expiraEm) {
        return jdbcTemplate.update(CLAIM_SQL, escopo, chave, requestHash, utc(agora), utc(expiraEm)) == 1;
    }

    public Optional<Registro> buscar(String escopo, String chave) {
        return jdbcTemplate.query(
                "SELECT request_hash, pedido_id FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                (rs, n) -> new Registro(rs.getString("request_hash"), rs.getObject("pedido_id", UUID.class)),
                escopo, chave).stream().findFirst();
    }

    public void vincular(String escopo, String chave, UUID pedidoId) {
        jdbcTemplate.update("UPDATE idempotency_key SET pedido_id = ? WHERE scope = ? AND idem_key = ?",
                pedidoId, escopo, chave);
    }

    public int removerExpiradas(Instant agora, int limite) {
        return jdbcTemplate.update(DELETE_EXPIRED_SQL, utc(agora), limite);
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante.atOffset(ZoneOffset.UTC);
    }
}
