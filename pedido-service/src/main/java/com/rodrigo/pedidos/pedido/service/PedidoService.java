package com.rodrigo.pedidos.pedido.service;

import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyKeyConflictException;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyKeys;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyStore;
import com.rodrigo.pedidos.pedido.idempotency.InvalidIdempotencyKeyException;
import com.rodrigo.pedidos.pedido.kafka.PedidoEventProducer;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository pedidoRepository;
    private final PedidoEventProducer pedidoEventProducer;
    private final MeterRegistry meterRegistry;
    private final IdempotencyStore idempotencyStore;
    private final Duration idempotencyTtl;

    public PedidoService(PedidoRepository pedidoRepository, PedidoEventProducer pedidoEventProducer,
                         MeterRegistry meterRegistry, IdempotencyStore idempotencyStore,
                         @Value("${app.idempotency.ttl:PT24H}") Duration idempotencyTtl) {
        this.pedidoRepository = pedidoRepository;
        this.pedidoEventProducer = pedidoEventProducer;
        this.meterRegistry = meterRegistry;
        this.idempotencyStore = idempotencyStore;
        this.idempotencyTtl = idempotencyTtl;
    }

    /** Resultado de uma criação; {@code repeticao} é true quando devolve o pedido de uma chave já usada. */
    public record CriacaoPedido(Pedido pedido, boolean repeticao) {
    }

    @Transactional
    public Pedido criar(PedidoRequest request) {
        return criarPedido(request);
    }

    /**
     * Cria o pedido uma única vez por (usuário, chave). A chave, o pedido e o evento do outbox são
     * gravados na mesma transação: se qualquer parte falhar, nada fica registrado e o cliente pode
     * repetir com a mesma chave.
     */
    @Transactional
    public CriacaoPedido criar(PedidoRequest request, String chave, String usuario) {
        IdempotencyKeys.validarChave(chave);
        if (usuario == null || usuario.isBlank()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key exige usuário autenticado (header interno ausente)");
        }

        String hash = IdempotencyKeys.impressaoDigital(request);
        Instant agora = Instant.now();
        boolean reivindicada = idempotencyStore.reivindicar(usuario, chave, hash, agora, agora.plus(idempotencyTtl));

        if (reivindicada) {
            Pedido pedido = criarPedido(request);
            pedidoRepository.flush();
            idempotencyStore.vincular(usuario, chave, pedido.getId());
            return new CriacaoPedido(pedido, false);
        }

        var registro = idempotencyStore.buscar(usuario, chave)
                .orElseThrow(() -> new IllegalStateException("Chave de idempotência reivindicada e não encontrada"));
        if (!registro.requestHash().equals(hash)) {
            meterRegistry.counter("pedido.idempotency.conflict").increment();
            throw new IdempotencyKeyConflictException();
        }
        Pedido existente = pedidoRepository.findById(registro.pedidoId())
                .orElseThrow(() -> new IllegalStateException(
                        "Chave de idempotência aponta para pedido inexistente: " + registro.pedidoId()));
        meterRegistry.counter("pedido.idempotency.replay").increment();
        log.info("Requisição repetida devolveu o pedido {}", existente.getId());
        return new CriacaoPedido(existente, true);
    }

    private Pedido criarPedido(PedidoRequest request) {
        var itens = request.itens().stream()
                .map(i -> PedidoItem.novo(i.produtoId(), i.quantidade(), i.precoUnitario()))
                .toList();

        Pedido pedido = pedidoRepository.save(Pedido.novo(itens));

        // tag no span de entrada (requisição HTTP): permite buscar o trace por pedido.id no APM
        SpanTags.tagEntrada("pedido.id", pedido.getId());

        // O producer grava o evento no outbox na mesma transação do pedido;
        // o relay só o envia ao Kafka depois que o commit estiver concluído.
        pedidoEventProducer.publicarPedidoCriado(pedido);

        // Métrica de negócio: nunca usar pedidoId como tag (cardinalidade ilimitada
        // = custo alto no Datadog); o id vai no log, que já carrega o trace_id.
        meterRegistry.counter("pedido.criado").increment();
        log.info("Pedido {} criado com {} item(ns), valor total {}",
                pedido.getId(), pedido.getItens().size(), pedido.valorTotal());

        return pedido;
    }

    @Transactional(readOnly = true)
    public Pedido buscarPorId(UUID id) {
        return pedidoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Pedido não encontrado: " + id));
    }
}
