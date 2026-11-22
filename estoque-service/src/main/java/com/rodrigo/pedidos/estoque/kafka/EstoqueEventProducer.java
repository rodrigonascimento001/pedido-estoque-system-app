package com.rodrigo.pedidos.estoque.kafka;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.estoque.config.KafkaTopics;
import com.rodrigo.pedidos.estoque.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class EstoqueEventProducer {

    private static final Logger log = LoggerFactory.getLogger(EstoqueEventProducer.class);

    private final OutboxWriter outboxWriter;

    public EstoqueEventProducer(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publicarReservado(UUID pedidoId) {
        var evento = new EstoqueReservadoEvent(pedidoId, Instant.now());
        var eventId = outboxWriter.enqueue(KafkaTopics.ESTOQUE_RESERVADO, pedidoId.toString(), evento);
        log.info("EstoqueReservadoEvent registrado no outbox para pedido {} (eventId={})", pedidoId, eventId);
    }

    public void publicarIndisponivel(UUID pedidoId, List<UUID> produtosIndisponiveis, String motivo) {
        var evento = new EstoqueIndisponivelEvent(pedidoId, produtosIndisponiveis, motivo, Instant.now());
        var eventId = outboxWriter.enqueue(KafkaTopics.ESTOQUE_INDISPONIVEL, pedidoId.toString(), evento);
        log.info("EstoqueIndisponivelEvent registrado no outbox para pedido {} (eventId={}): {}",
                pedidoId, eventId, motivo);
    }
}
