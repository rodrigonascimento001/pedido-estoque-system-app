package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.domain.ResultadoReserva;
import com.rodrigo.pedidos.estoque.kafka.EstoqueEventProducer;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import com.rodrigo.pedidos.estoque.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Exercita a regra de negócio central (reserva + compensação) contra um
 * Postgres real via Testcontainers. O producer Kafka é mockado aqui porque
 * o foco é validar o estado do estoque, não a integração com o broker
 * (isso é coberto por PedidoEventConsumerIntegrationTest).
 */
class EstoqueServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private EstoqueService estoqueService;

    @Autowired
    private ReservaEstoqueService reservaEstoqueService;

    @Autowired
    private EstoqueRepository estoqueRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private EstoqueEventProducer estoqueEventProducer;

    @Test
    void deveReservarQuandoHaSaldoSuficiente() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 10));

        ResultadoReserva resultado = reservaEstoqueService.reservar(produtoId, 4);

        assertThat(resultado.reservou()).isTrue();
        // lido em nova consulta: prova que a transação foi realmente commitada
        Estoque atualizado = estoqueRepository.findById(produtoId).orElseThrow();
        assertThat(atualizado.getQuantidadeDisponivel()).isEqualTo(6);
    }

    @Test
    void deveRetornarSaldoInsuficienteSemAlterarEstoque() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 2));

        ResultadoReserva resultado = reservaEstoqueService.reservar(produtoId, 5);

        // falha de negócio é resultado, não exceção (não vira "erro" no span do APM)
        assertThat(resultado.tipo()).isEqualTo(ResultadoReserva.Tipo.SALDO_INSUFICIENTE);
        assertThat(resultado.motivo()).contains(produtoId.toString());
        // saldo não deve ter sido alterado
        assertThat(estoqueRepository.findById(produtoId).orElseThrow().getQuantidadeDisponivel()).isEqualTo(2);
    }

    @Test
    void deveRetornarProdutoInexistenteQuandoNaoHaEstoqueCadastrado() {
        UUID produtoId = UUID.randomUUID();

        ResultadoReserva resultado = reservaEstoqueService.reservar(produtoId, 1);

        assertThat(resultado.tipo()).isEqualTo(ResultadoReserva.Tipo.PRODUTO_INEXISTENTE);
    }

    @Test
    void deveConfirmarReservaQuandoTodosOsItensTemSaldo() {
        UUID produtoA = UUID.randomUUID();
        UUID produtoB = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoA, 10));
        estoqueRepository.save(new Estoque(produtoB, 10));

        var pedidoId = UUID.randomUUID();
        var evento = new PedidoCriadoEvent(pedidoId, List.of(
                new PedidoCriadoEvent.ItemPedido(produtoA, 3, BigDecimal.TEN),
                new PedidoCriadoEvent.ItemPedido(produtoB, 2, BigDecimal.TEN)
        ), Instant.now());

        estoqueService.processarPedido(evento);

        assertThat(estoqueRepository.findById(produtoA).orElseThrow().getQuantidadeDisponivel()).isEqualTo(7);
        assertThat(estoqueRepository.findById(produtoB).orElseThrow().getQuantidadeDisponivel()).isEqualTo(8);
        verify(estoqueEventProducer).publicarReservado(pedidoId);
        verify(estoqueEventProducer, never()).publicarIndisponivel(any(), any(), any());
    }

    @Test
    void deveCompensarReservasJaFeitasQuandoUmItemFalha() {
        UUID produtoComSaldo = UUID.randomUUID();
        UUID produtoSemSaldo = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoComSaldo, 10));
        estoqueRepository.save(new Estoque(produtoSemSaldo, 1));

        double compensacoesAntes = meterRegistry.counter("estoque.compensacao").count();
        double falhasAntes = meterRegistry.counter("estoque.reserva", "resultado", "saldo_insuficiente").count();

        var pedidoId = UUID.randomUUID();
        // primeiro item reserva com sucesso, segundo falha -> deve reverter o primeiro
        var evento = new PedidoCriadoEvent(pedidoId, List.of(
                new PedidoCriadoEvent.ItemPedido(produtoComSaldo, 3, BigDecimal.TEN),
                new PedidoCriadoEvent.ItemPedido(produtoSemSaldo, 5, BigDecimal.TEN)
        ), Instant.now());

        estoqueService.processarPedido(evento);

        // saldo do primeiro produto deve ter voltado ao original (compensação)
        assertThat(estoqueRepository.findById(produtoComSaldo).orElseThrow().getQuantidadeDisponivel()).isEqualTo(10);
        assertThat(estoqueRepository.findById(produtoSemSaldo).orElseThrow().getQuantidadeDisponivel()).isEqualTo(1);
        verify(estoqueEventProducer).publicarIndisponivel(eq(pedidoId), eq(List.of(produtoSemSaldo)), any());
        verify(estoqueEventProducer, never()).publicarReservado(any());

        // métricas de negócio: 1 item compensado e 1 falha por saldo insuficiente
        assertThat(meterRegistry.counter("estoque.compensacao").count()).isEqualTo(compensacoesAntes + 1);
        assertThat(meterRegistry.counter("estoque.reserva", "resultado", "saldo_insuficiente").count())
                .isEqualTo(falhasAntes + 1);
    }
}
