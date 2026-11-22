package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.kafka.EstoqueEventProducer;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReservaPedidoProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReservaPedidoProcessor.class);

    private final EstoqueRepository estoqueRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EstoqueEventProducer eventProducer;
    private final MeterRegistry meterRegistry;

    public ReservaPedidoProcessor(EstoqueRepository estoqueRepository, JdbcTemplate jdbcTemplate,
                                  EstoqueEventProducer eventProducer, MeterRegistry meterRegistry) {
        this.estoqueRepository = estoqueRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventProducer = eventProducer;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ResultadoProcessamento processar(PedidoCriadoEvent evento) {
        Map<UUID, Integer> quantidades = normalizar(evento);
        String payloadHash = fingerprint(quantidades);
        int criada = jdbcTemplate.update("""
                        INSERT INTO reserva_pedido (pedido_id, payload_hash, resultado, criado_em)
                        VALUES (?, ?, 'PROCESSANDO', ?)
                        ON CONFLICT (pedido_id) DO NOTHING
                        """,
                evento.pedidoId(), payloadHash, Timestamp.from(Instant.now()));

        if (criada == 0) {
            return resultadoExistente(evento.pedidoId(), payloadHash);
        }

        List<Estoque> saldos = estoqueRepository.bloquearProdutos(quantidades.keySet());
        Map<UUID, Integer> saldoPorProduto = saldos.stream()
                .collect(Collectors.toMap(Estoque::getProdutoId, Estoque::getQuantidadeDisponivel));
        List<UUID> indisponiveis = quantidades.entrySet().stream()
                .filter(item -> saldoPorProduto.getOrDefault(item.getKey(), -1) < item.getValue())
                .map(Map.Entry::getKey)
                .toList();

        if (!indisponiveis.isEmpty()) {
            registrarItens(evento.pedidoId(), quantidades, false);
            String motivo = descreverIndisponibilidade(indisponiveis, saldoPorProduto, quantidades);
            jdbcTemplate.update("""
                            UPDATE reserva_pedido
                            SET resultado = 'INDISPONIVEL', motivo = ?
                            WHERE pedido_id = ?
                            """,
                    motivo, evento.pedidoId());
            eventProducer.publicarIndisponivel(evento.pedidoId(), indisponiveis, motivo);
            String resultadoMetric = indisponiveis.stream().anyMatch(id -> !saldoPorProduto.containsKey(id))
                    ? "produto_inexistente" : "saldo_insuficiente";
            meterRegistry.counter("estoque.reserva", "resultado", resultadoMetric).increment();
            return new ResultadoProcessamento(EstadoReserva.INDISPONIVEL, false);
        }

        for (var item : quantidades.entrySet()) {
            int atualizadas = estoqueRepository.decrementarSeDisponivel(item.getKey(), item.getValue());
            if (atualizadas != 1) {
                throw new IllegalStateException("Saldo mudou durante reserva bloqueada do produto " + item.getKey());
            }
        }

        registrarItens(evento.pedidoId(), quantidades, true);
        jdbcTemplate.update("UPDATE reserva_pedido SET resultado = 'RESERVADO' WHERE pedido_id = ?",
                evento.pedidoId());
        eventProducer.publicarReservado(evento.pedidoId());
        meterRegistry.counter("estoque.reserva", "resultado", "sucesso").increment();
        log.info("Reserva integral concluída para pedido {} com {} produto(s)",
                evento.pedidoId(), quantidades.size());
        return new ResultadoProcessamento(EstadoReserva.RESERVADO, false);
    }

    private ResultadoProcessamento resultadoExistente(UUID pedidoId, String payloadHash) {
        ReservaExistente existente = jdbcTemplate.queryForObject("""
                        SELECT payload_hash, resultado
                        FROM reserva_pedido
                        WHERE pedido_id = ?
                        """,
                (rs, rowNum) -> new ReservaExistente(rs.getString("payload_hash"), rs.getString("resultado")),
                pedidoId);
        if (!existente.payloadHash().equals(payloadHash)) {
            meterRegistry.counter("estoque.reserva.idempotency.conflict").increment();
            log.error("Evento repetido com conteúdo diferente para pedido {}; reserva persistida preservada", pedidoId);
            return new ResultadoProcessamento(EstadoReserva.CONFLITO, true);
        }
        log.info("Evento duplicado ignorado para pedido {} com resultado {}", pedidoId, existente.resultado());
        return new ResultadoProcessamento(EstadoReserva.valueOf(existente.resultado()), true);
    }

    private Map<UUID, Integer> normalizar(PedidoCriadoEvent evento) {
        if (evento.itens() == null || evento.itens().isEmpty()) {
            throw new IllegalArgumentException("Pedido sem itens não pode reservar estoque");
        }
        Map<UUID, Integer> quantidades = new TreeMap<>();
        for (var item : evento.itens()) {
            if (item.produtoId() == null || item.quantidade() <= 0) {
                throw new IllegalArgumentException("Item do pedido possui produto ou quantidade inválida");
            }
            quantidades.merge(item.produtoId(), item.quantidade(), Math::addExact);
        }
        return quantidades;
    }

    private String fingerprint(Map<UUID, Integer> quantidades) {
        String canonical = quantidades.entrySet().stream()
                .map(item -> item.getKey() + ":" + item.getValue())
                .collect(Collectors.joining("\n"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }

    private void registrarItens(UUID pedidoId, Map<UUID, Integer> quantidades, boolean reservado) {
        for (var item : quantidades.entrySet()) {
            jdbcTemplate.update("""
                            INSERT INTO reserva_pedido_item (pedido_id, produto_id, quantidade, reservado)
                            VALUES (?, ?, ?, ?)
                            """,
                    pedidoId, item.getKey(), item.getValue(), reservado);
        }
    }

    private String descreverIndisponibilidade(List<UUID> ids, Map<UUID, Integer> saldos,
                                               Map<UUID, Integer> quantidades) {
        List<String> detalhes = new ArrayList<>();
        for (UUID id : ids) {
            Integer saldo = saldos.get(id);
            detalhes.add(saldo == null
                    ? "produto " + id + " inexistente"
                    : "produto " + id + " requer " + quantidades.get(id) + " e possui " + saldo);
        }
        return String.join("; ", detalhes);
    }

    private record ReservaExistente(String payloadHash, String resultado) {
    }

    public enum EstadoReserva {
        PROCESSANDO, RESERVADO, INDISPONIVEL, CONFLITO
    }

    public record ResultadoProcessamento(EstadoReserva estado, boolean duplicado) {
    }
}
