package com.rodrigo.pedidos.pedido.kafka;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.pedido.config.KafkaTopics;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class PedidoEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PedidoEventProducer.class);

    private final OutboxWriter outboxWriter;

    public PedidoEventProducer(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publicarPedidoCriado(Pedido pedido) {
        var itens = pedido.getItens().stream()
                .map(i -> new PedidoCriadoEvent.ItemPedido(i.getProdutoId(), i.getQuantidade(), i.getPrecoUnitario()))
                .toList();

        var evento = new PedidoCriadoEvent(pedido.getId(), itens, Instant.now());
        UUID eventId = outboxWriter.enqueue(KafkaTopics.PEDIDO_CRIADO, pedido.getId().toString(), evento);
        log.info("PedidoCriadoEvent registrado no outbox para pedido {} (eventId={})",
                pedido.getId(), eventId);
    }
}
