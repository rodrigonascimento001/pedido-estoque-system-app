package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa a segunda ponta da saga: o pedido-service reage aos eventos publicados
 * pelo estoque-service. Aqui simulamos o estoque-service publicando os eventos
 * diretamente no tópico, sem depender do módulo estoque-service estar no ar.
 */
class EstoqueEventConsumerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PedidoRepository pedidoRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID criarPedidoPendente() {
        var item = PedidoItem.novo(UUID.randomUUID(), 3, BigDecimal.valueOf(9.99));
        var pedido = pedidoRepository.save(Pedido.novo(List.of(item)));
        return pedido.getId();
    }

    @Test
    void deveConfirmarPedidoAoReceberEstoqueReservado() {
        UUID pedidoId = criarPedidoPendente();
        double confirmadosAntes = meterRegistry.counter("pedido.confirmado").count();
        long sagasAntes = meterRegistry.timer("pedido.saga.duracao", "resultado", "confirmado").count();

        kafkaTemplate.send("estoque-reservado", pedidoId.toString(),
                new EstoqueReservadoEvent(pedidoId, Instant.now()));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var pedido = pedidoRepository.findById(pedidoId).orElseThrow();
                    assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
                });

        // métricas de negócio que alimentam os dashboards/monitores no Datadog
        assertThat(meterRegistry.counter("pedido.confirmado").count()).isEqualTo(confirmadosAntes + 1);
        assertThat(meterRegistry.timer("pedido.saga.duracao", "resultado", "confirmado").count())
                .isEqualTo(sagasAntes + 1);
    }

    @Test
    void deveCancelarPedidoAoReceberEstoqueIndisponivel() {
        UUID pedidoId = criarPedidoPendente();
        UUID produtoIndisponivel = UUID.randomUUID();
        double canceladosAntes = meterRegistry.counter("pedido.cancelado").count();

        kafkaTemplate.send("estoque-indisponivel", pedidoId.toString(),
                new EstoqueIndisponivelEvent(pedidoId, List.of(produtoIndisponivel),
                        "Saldo insuficiente", Instant.now()));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var pedido = pedidoRepository.findById(pedidoId).orElseThrow();
                    assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CANCELADO);
                    assertThat(pedido.getMotivoCancelamento()).isEqualTo("Saldo insuficiente");
                });

        assertThat(meterRegistry.counter("pedido.cancelado").count()).isEqualTo(canceladosAntes + 1);
    }
}
