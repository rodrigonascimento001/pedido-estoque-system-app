package com.rodrigo.pedidos.pedido.kafka;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.pedido.config.KafkaTopics;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @KafkaListener(topics = KafkaTopics.ESTOQUE_RESERVADO, groupId = "pedido-service")
    @Transactional
    public void aoReservarEstoque(EstoqueReservadoEvent evento) {
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());
        pedidoRepository.findById(evento.pedidoId()).ifPresentOrElse(
                pedido -> {
                    pedido.confirmar();
                    registrarDesfechoDaSaga(pedido, "confirmado");
                    log.info("Pedido {} confirmado", pedido.getId());
                },
                () -> log.warn("EstoqueReservadoEvent para pedido inexistente: {}", evento.pedidoId())
        );
    }

    @KafkaListener(topics = KafkaTopics.ESTOQUE_INDISPONIVEL, groupId = "pedido-service")
    @Transactional
    public void aoFalharEstoque(EstoqueIndisponivelEvent evento) {
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());
        pedidoRepository.findById(evento.pedidoId()).ifPresentOrElse(
                pedido -> {
                    pedido.cancelar(evento.motivo());
                    registrarDesfechoDaSaga(pedido, "cancelado");
                    log.info("Pedido {} cancelado: {}", pedido.getId(), evento.motivo());
                },
                () -> log.warn("EstoqueIndisponivelEvent para pedido inexistente: {}", evento.pedidoId())
        );
    }

    /**
     * Contador do desfecho (pedido.confirmado / pedido.cancelado) e timer com o tempo
     * total da saga: do pedido criado até a resposta do estoque. É a métrica mais útil
     * para acompanhar a saúde do fluxo assíncrono (lag do Kafka, estoque lento etc.).
     */
    private void registrarDesfechoDaSaga(Pedido pedido, String resultado) {
        meterRegistry.counter("pedido." + resultado).increment();
        meterRegistry.timer("pedido.saga.duracao", "resultado", resultado)
                .record(Duration.between(pedido.getCriadoEm(), Instant.now()));
    }
}
