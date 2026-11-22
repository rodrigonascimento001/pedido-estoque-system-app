package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.comum.events.EstoqueIndisponivelEvent;
import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.kafka.EstoqueEventConsumer;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

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

    @Autowired
    private EstoqueEventConsumer estoqueEventConsumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void eventosDuplicadosEConflitantesNaoDevemRecontarOuSobrescreverEstado() {
        UUID pedidoId = criarPedidoPendente();
        double confirmadosAntes = meterRegistry.counter("pedido.confirmado").count();
        double conflitosAntes = meterRegistry.counter("pedido.transicao.conflito",
                "estado_atual", "CONFIRMADO", "estado_recebido", "CANCELADO").count();
        var confirmado = new EstoqueReservadoEvent(pedidoId, Instant.now());

        estoqueEventConsumer.aoReservarEstoque(confirmado);
        estoqueEventConsumer.aoReservarEstoque(confirmado);
        estoqueEventConsumer.aoFalharEstoque(new EstoqueIndisponivelEvent(pedidoId,
                List.of(UUID.randomUUID()), "conflito", Instant.now()));

        assertThat(pedidoRepository.findById(pedidoId).orElseThrow().getStatus())
                .isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(meterRegistry.counter("pedido.transicao.conflito", "estado_atual", "CONFIRMADO",
                "estado_recebido", "CANCELADO").count()).isEqualTo(conflitosAntes + 1);
        assertThat(meterRegistry.counter("pedido.confirmado").count()).isEqualTo(confirmadosAntes + 1);
    }

    @Test
    void handlerDaDltRegistraMetricaEContextoSemPayload() {
        double antes = meterRegistry.counter("kafka.dlt.records", "service", "pedido-service",
                "source_topic", "estoque-reservado").count();
        var record = new ConsumerRecord<String, Object>("estoque-reservado-dlt", 0, 42L, "pedido-123", null);
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC,
                "estoque-reservado".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "falha simulada".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        estoqueEventConsumer.aoChegarNaDlt(record);

        assertThat(meterRegistry.counter("kafka.dlt.records", "service", "pedido-service",
                "source_topic", "estoque-reservado").count()).isEqualTo(antes + 1);
    }

    @Test
    void resultadoRecebidoAntesDoPedidoEhTentadoNovamenteAposPedidoFicarVisivel() throws Exception {
        UUID pedidoId = UUID.randomUUID();
        double ausentesAntes = meterRegistry.counter("pedido.evento.pedido_nao_visivel").count();
        kafkaTemplate.send("estoque-reservado", pedidoId.toString(),
                new EstoqueReservadoEvent(pedidoId, Instant.now())).get(10, java.util.concurrent.TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(meterRegistry.counter("pedido.evento.pedido_nao_visivel").count())
                        .isGreaterThan(ausentesAntes));
        jdbcTemplate.update("INSERT INTO pedidos (id, status, criado_em) VALUES (?, 'PENDENTE', CURRENT_TIMESTAMP)",
                pedidoId);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pedidoRepository.findById(pedidoId).orElseThrow().getStatus())
                        .isEqualTo(StatusPedido.CONFIRMADO));
    }
}
