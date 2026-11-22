package com.rodrigo.pedidos.estoque.kafka;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.config.KafkaTopics;
import com.rodrigo.pedidos.estoque.service.EstoqueService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PedidoEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PedidoEventConsumer.class);

    private final EstoqueService estoqueService;
    private final MeterRegistry meterRegistry;

    public PedidoEventConsumer(EstoqueService estoqueService, MeterRegistry meterRegistry) {
        this.estoqueService = estoqueService;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(attempts = "${app.kafka.retry.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${app.kafka.retry.backoff-ms:2000}"))
    @KafkaListener(id = "estoque-pedido-consumer", topics = KafkaTopics.PEDIDO_CRIADO,
            groupId = "estoque-service")
    public void aoCriarPedido(PedidoCriadoEvent evento) {
        log.info("Processando reserva integral de estoque para pedido {}", evento.pedidoId());
        estoqueService.processarPedido(evento);
    }

    @DltHandler
    public void aoChegarNaDlt(ConsumerRecord<String, Object> record) {
        String sourceTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String cause = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        meterRegistry.counter("kafka.dlt.records", "service", "estoque-service",
                "source_topic", sourceTopic == null ? "unknown" : sourceTopic).increment();
        log.error("Evento retido em DLT: sourceTopic={}, dltTopic={}, key={}, partition={}, offset={}, cause={}",
                sourceTopic, record.topic(), record.key(), record.partition(), record.offset(), cause);
    }

    private String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
