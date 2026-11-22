package com.rodrigo.pedidos.pedido.kafka;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.pedido.config.KafkaTopics;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
public class EstoqueEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EstoqueEventConsumer.class);

    private final PedidoRepository pedidoRepository;
    private final MeterRegistry meterRegistry;

    public EstoqueEventConsumer(PedidoRepository pedidoRepository, MeterRegistry meterRegistry) {
        this.pedidoRepository = pedidoRepository;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(attempts = "${app.kafka.retry.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${app.kafka.retry.backoff-ms:2000}"))
    @KafkaListener(topics = KafkaTopics.ESTOQUE_RESERVADO, groupId = "pedido-service")
    @Transactional
    public void aoReservarEstoque(EstoqueReservadoEvent evento) {
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());
        aplicarResultado(evento.pedidoId(), StatusPedido.CONFIRMADO, null);
    }

    @RetryableTopic(attempts = "${app.kafka.retry.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${app.kafka.retry.backoff-ms:2000}"))
    @KafkaListener(topics = KafkaTopics.ESTOQUE_INDISPONIVEL, groupId = "pedido-service")
    @Transactional
    public void aoFalharEstoque(EstoqueIndisponivelEvent evento) {
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());
        aplicarResultado(evento.pedidoId(), StatusPedido.CANCELADO, evento.motivo());
    }

    @DltHandler
    public void aoChegarNaDlt(ConsumerRecord<String, Object> record) {
        String sourceTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String cause = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        meterRegistry.counter("kafka.dlt.records", "service", "pedido-service",
                "source_topic", sourceTopic == null ? "unknown" : sourceTopic).increment();
        log.error("Evento retido em DLT: sourceTopic={}, dltTopic={}, key={}, partition={}, offset={}, cause={}",
                sourceTopic, record.topic(), record.key(), record.partition(), record.offset(), cause);
    }

    private void aplicarResultado(java.util.UUID pedidoId, StatusPedido destino, String motivo) {
        Pedido pedido = pedidoRepository.findById(pedidoId).orElse(null);
        if (pedido == null) {
            meterRegistry.counter("pedido.evento.pedido_nao_visivel").increment();
            throw new PedidoAindaNaoVisivelException(
                    "Evento de estoque para pedido ainda não visível: " + pedidoId);
        }

        if (pedido.getStatus() != StatusPedido.PENDENTE) {
            observarResultadoTerminal(pedidoId, pedido.getStatus(), destino);
            return;
        }

        Instant criadoEm = pedido.getCriadoEm();
        Instant agora = Instant.now();
        int atualizadas = pedidoRepository.transicionarSePendente(
                pedidoId, StatusPedido.PENDENTE, destino, motivo, agora);
        if (atualizadas == 0) {
            Pedido atual = pedidoRepository.findById(pedidoId)
                    .orElseThrow(() -> new PedidoAindaNaoVisivelException(
                            "Pedido desapareceu durante transição: " + pedidoId));
            observarResultadoTerminal(pedidoId, atual.getStatus(), destino);
            return;
        }

        String resultado = destino == StatusPedido.CONFIRMADO ? "confirmado" : "cancelado";
        meterRegistry.counter("pedido." + resultado).increment();
        meterRegistry.timer("pedido.saga.duracao", "resultado", resultado)
                .record(Duration.between(criadoEm, agora));
        log.info("Pedido {} transitou para {}{}", pedidoId, destino,
                motivo == null ? "" : ": " + motivo);
    }

    private void observarResultadoTerminal(java.util.UUID pedidoId, StatusPedido atual, StatusPedido recebido) {
        if (atual == recebido) {
            log.debug("Evento duplicado ignorado para pedido {} no estado {}", pedidoId, recebido);
            return;
        }
        meterRegistry.counter("pedido.transicao.conflito", "estado_atual", atual.name(),
                "estado_recebido", recebido.name()).increment();
        log.error("Resultado conflitante ignorado para pedido {}: atual={}, recebido={}",
                pedidoId, atual, recebido);
    }

    private String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    static class PedidoAindaNaoVisivelException extends RuntimeException {
        PedidoAindaNaoVisivelException(String message) {
            super(message);
        }
    }
}
