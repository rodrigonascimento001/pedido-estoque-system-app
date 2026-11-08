package com.rodrigo.pedidos.pedido.kafka;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.pedido.config.KafkaTopics;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PedidoEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PedidoEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PedidoEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publicarPedidoCriado(Pedido pedido) {
        var itens = pedido.getItens().stream()
                .map(i -> new PedidoCriadoEvent.ItemPedido(i.getProdutoId(), i.getQuantidade(), i.getPrecoUnitario()))
                .toList();

        var evento = new PedidoCriadoEvent(pedido.getId(), itens, Instant.now());

        // chave = pedidoId garante ordenação por pedido dentro da partição
        kafkaTemplate.send(KafkaTopics.PEDIDO_CRIADO, pedido.getId().toString(), evento)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Falha ao publicar PedidoCriadoEvent para pedido {}", pedido.getId(), ex);
                    } else {
                        log.info("PedidoCriadoEvent publicado para pedido {}", pedido.getId());
                    }
                });
    }
}
