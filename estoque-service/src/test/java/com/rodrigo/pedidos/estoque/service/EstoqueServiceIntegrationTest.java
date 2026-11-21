package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import com.rodrigo.pedidos.estoque.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "app.outbox.enabled=false"})
class EstoqueServiceIntegrationTest extends IntegrationTestBase {

    @Autowired private EstoqueService estoqueService;
    @Autowired private EstoqueRepository estoqueRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void deveReservarTodosOsItensEmUmaUnicaOperacao() {
        UUID produtoA = UUID.randomUUID();
        UUID produtoB = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoA, 10));
        estoqueRepository.save(new Estoque(produtoB, 10));
        UUID pedidoId = UUID.randomUUID();

        estoqueService.processarPedido(evento(pedidoId,
                item(produtoA, 3), item(produtoB, 2)));

        assertThat(saldo(produtoA)).isEqualTo(7);
        assertThat(saldo(produtoB)).isEqualTo(8);
        assertThat(resultado(pedidoId)).isEqualTo("RESERVADO");
        assertThat(outboxCount(pedidoId)).isEqualTo(1);
    }

    @Test
    void deveDeixarTodosOsSaldosIntactosSeQualquerItemNaoTiverSaldo() {
        UUID produtoA = UUID.randomUUID();
        UUID produtoB = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoA, 10));
        estoqueRepository.save(new Estoque(produtoB, 1));
        UUID pedidoId = UUID.randomUUID();
        double falhasAntes = meterRegistry.counter("estoque.reserva", "resultado", "saldo_insuficiente").count();

        estoqueService.processarPedido(evento(pedidoId,
                item(produtoA, 3), item(produtoB, 5)));

        assertThat(saldo(produtoA)).isEqualTo(10);
        assertThat(saldo(produtoB)).isEqualTo(1);
        assertThat(resultado(pedidoId)).isEqualTo("INDISPONIVEL");
        assertThat(outboxCount(pedidoId)).isEqualTo(1);
        assertThat(meterRegistry.counter("estoque.reserva", "resultado", "saldo_insuficiente").count())
                .isEqualTo(falhasAntes + 1);
    }

    @Test
    void deveSomarItensRepetidosDoMesmoProdutoAntesDeReservar() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 10));
        UUID pedidoId = UUID.randomUUID();

        estoqueService.processarPedido(evento(pedidoId, item(produtoId, 2), item(produtoId, 3)));

        assertThat(saldo(produtoId)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT quantidade FROM reserva_pedido_item WHERE pedido_id = ? AND produto_id = ?",
                Integer.class, pedidoId, produtoId)).isEqualTo(5);
    }

    @Test
    void entregaDuplicadaNaoDeveReservarNemPublicarNovamente() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 10));
        UUID pedidoId = UUID.randomUUID();
        var evento = evento(pedidoId, item(produtoId, 4));

        estoqueService.processarPedido(evento);
        estoqueService.processarPedido(evento);

        assertThat(saldo(produtoId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reserva_pedido WHERE pedido_id = ?", Integer.class, pedidoId))
                .isEqualTo(1);
        assertThat(outboxCount(pedidoId)).isEqualTo(1);
    }

    @Test
    void entregaDuplicadaDeRejeicaoNaoDevePublicarNovamente() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 1));
        UUID pedidoId = UUID.randomUUID();
        var evento = evento(pedidoId, item(produtoId, 3));

        estoqueService.processarPedido(evento);
        estoqueService.processarPedido(evento);

        assertThat(saldo(produtoId)).isEqualTo(1);
        assertThat(resultado(pedidoId)).isEqualTo("INDISPONIVEL");
        assertThat(outboxCount(pedidoId)).isEqualTo(1);
    }

    @Test
    void eventoDuplicadoComConteudoDiferenteNaoDeveAlterarReserva() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 10));
        UUID pedidoId = UUID.randomUUID();

        estoqueService.processarPedido(evento(pedidoId, item(produtoId, 2)));
        estoqueService.processarPedido(evento(pedidoId, item(produtoId, 5)));

        assertThat(saldo(produtoId)).isEqualTo(8);
        assertThat(outboxCount(pedidoId)).isEqualTo(1);
        assertThat(meterRegistry.counter("estoque.reserva.idempotency.conflict").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reservasConcorrentesNaoDeixamSaldoNegativoNemParcial() throws Exception {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 5));
        UUID pedidoA = UUID.randomUUID();
        UUID pedidoB = UUID.randomUUID();
        var eventoA = evento(pedidoA, item(produtoId, 4));
        var eventoB = evento(pedidoB, item(produtoId, 4));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> estoqueService.processarPedido(eventoA));
            var b = executor.submit(() -> estoqueService.processarPedido(eventoB));
            a.get();
            b.get();
        }

        assertThat(saldo(produtoId)).isEqualTo(1);
        var resultados = jdbcTemplate.queryForList(
                "SELECT resultado FROM reserva_pedido WHERE pedido_id IN (?, ?)", String.class, pedidoA, pedidoB);
        assertThat(resultados).containsExactlyInAnyOrder("RESERVADO", "INDISPONIVEL");
        assertThat(outboxCount(pedidoA) + outboxCount(pedidoB)).isEqualTo(2);
    }

    private int saldo(UUID produtoId) {
        return estoqueRepository.findById(produtoId).orElseThrow().getQuantidadeDisponivel();
    }

    private String resultado(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT resultado FROM reserva_pedido WHERE pedido_id = ?", String.class, pedidoId);
    }

    private int outboxCount(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE message_key = ?",
                Integer.class, pedidoId.toString());
    }

    private PedidoCriadoEvent evento(UUID pedidoId, PedidoCriadoEvent.ItemPedido... itens) {
        return new PedidoCriadoEvent(pedidoId, List.of(itens), Instant.now());
    }

    private PedidoCriadoEvent.ItemPedido item(UUID produtoId, int quantidade) {
        return new PedidoCriadoEvent.ItemPedido(produtoId, quantidade, BigDecimal.TEN);
    }
}
