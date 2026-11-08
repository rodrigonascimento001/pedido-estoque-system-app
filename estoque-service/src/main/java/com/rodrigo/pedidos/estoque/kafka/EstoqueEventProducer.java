package com.rodrigo.pedidos.estoque.kafka;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.estoque.config.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class EstoqueEventProducer {

    private static final Logger log = LoggerFactory.getLogger(EstoqueEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EstoqueEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publicarReservado(UUID pedidoId) {
        var evento = new EstoqueReservadoEvent(pedidoId, Instant.now());
        kafkaTemplate.send(KafkaTopics.ESTOQUE_RESERVADO, pedidoId.toString(), evento);
        log.info("EstoqueReservadoEvent publicado para pedido {}", pedidoId);
    }

    public void publicarIndisponivel(UUID pedidoId, List<UUID> produtosIndisponiveis, String motivo) {
        var evento = new EstoqueIndisponivelEvent(pedidoId, produtosIndisponiveis, motivo, Instant.now());
        kafkaTemplate.send(KafkaTopics.ESTOQUE_INDISPONIVEL, pedidoId.toString(), evento);
        log.info("EstoqueIndisponivelEvent publicado para pedido {}: {}", pedidoId, motivo);
    }
}
