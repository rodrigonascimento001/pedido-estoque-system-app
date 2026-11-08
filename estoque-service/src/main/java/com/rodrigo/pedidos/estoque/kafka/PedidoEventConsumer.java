package com.rodrigo.pedidos.estoque.kafka;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.config.KafkaTopics;
import com.rodrigo.pedidos.estoque.service.EstoqueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class PedidoEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PedidoEventConsumer.class);

    private final EstoqueService estoqueService;

    public PedidoEventConsumer(EstoqueService estoqueService) {
        this.estoqueService = estoqueService;
    }

    @KafkaListener(id = "estoque-pedido-consumer", topics = KafkaTopics.PEDIDO_CRIADO, groupId = "estoque-service")
    public void aoCriarPedido(PedidoCriadoEvent evento) {
        log.info("Processando reserva de estoque para pedido {}", evento.pedidoId());
        estoqueService.processarPedido(evento);
    }

    // Reprocessa mensagens com falha (ex: erro transitório de banco) antes de
    // enviar para a dead-letter topic (pedido-criado-dlt), evitando reservas
    // perdidas por instabilidade momentânea.
    @Bean
    public RetryTopicConfiguration retryTopicConfiguration(KafkaTemplate<String, Object> template) {
        return RetryTopicConfigurationBuilder
                .newInstance()
                .maxAttempts(3)
                .fixedBackOff(2000)
                .create(template);
    }
}
