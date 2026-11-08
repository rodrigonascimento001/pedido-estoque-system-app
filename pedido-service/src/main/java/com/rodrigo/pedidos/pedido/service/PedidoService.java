package com.rodrigo.pedidos.pedido.service;

import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.kafka.PedidoEventProducer;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository pedidoRepository;
    private final PedidoEventProducer pedidoEventProducer;
    private final MeterRegistry meterRegistry;

    public PedidoService(PedidoRepository pedidoRepository, PedidoEventProducer pedidoEventProducer,
                         MeterRegistry meterRegistry) {
        this.pedidoRepository = pedidoRepository;
        this.pedidoEventProducer = pedidoEventProducer;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Pedido criar(PedidoRequest request) {
        var itens = request.itens().stream()
                .map(i -> PedidoItem.novo(i.produtoId(), i.quantidade(), i.precoUnitario()))
                .toList();

        Pedido pedido = pedidoRepository.save(Pedido.novo(itens));

        // tag no span de entrada (requisição HTTP): permite buscar o trace por pedido.id no APM
        SpanTags.tagEntrada("pedido.id", pedido.getId());

        // Publicado após o commit seria ideal (outbox pattern); aqui simplificado
        // e publicado logo após o save para manter o exemplo direto.
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
