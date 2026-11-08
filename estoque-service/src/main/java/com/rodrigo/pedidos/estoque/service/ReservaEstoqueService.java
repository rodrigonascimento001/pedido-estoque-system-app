package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.domain.ResultadoReserva;
import com.rodrigo.pedidos.estoque.domain.SaldoInsuficienteException;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import datadog.trace.api.Trace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Operações de estoque que precisam de transação curta por item.
 *
 * <p>Vive em um bean separado do {@link EstoqueService} de propósito: o {@code @Transactional}
 * só é aplicado quando a chamada passa pelo proxy do Spring. Chamar estes métodos com
 * {@code this.reservar(...)} de dentro do EstoqueService ignoraria a transação (self-invocation)
 * e o lock otimista não teria efeito.
 *
 * <p>Os spans {@code estoque.reservar_item} e {@code estoque.repor_item} cobrem o corpo do
 * método; o commit (e um eventual conflito de lock otimista) acontece depois, no proxy
 * transacional, e aparece como erro do chamador, onde é tratado com retry.
 */
@Service
public class ReservaEstoqueService {

    private final EstoqueRepository estoqueRepository;

    public ReservaEstoqueService(EstoqueRepository estoqueRepository) {
        this.estoqueRepository = estoqueRepository;
    }

    @Trace(operationName = "estoque.reservar_item", resourceName = "ReservaEstoqueService.reservar")
    @Transactional
    public ResultadoReserva reservar(UUID produtoId, int quantidade) {
        SpanTags.tag("produto.id", produtoId);
        SpanTags.tag("reserva.quantidade", quantidade);

        ResultadoReserva resultado = estoqueRepository.buscarParaAtualizar(produtoId)
                .map(estoque -> tentarReservar(estoque, quantidade))
                .orElseGet(() -> ResultadoReserva.produtoInexistente(
                        "Produto não encontrado no estoque: " + produtoId));

        SpanTags.tag("reserva.resultado", resultado.tipo().tag());
        return resultado;
    }

    @Trace(operationName = "estoque.repor_item", resourceName = "ReservaEstoqueService.repor")
    @Transactional
    public void repor(UUID produtoId, int quantidade) {
        SpanTags.tag("produto.id", produtoId);
        SpanTags.tag("reposicao.quantidade", quantidade);

        estoqueRepository.buscarParaAtualizar(produtoId)
                .ifPresent(estoque -> estoque.repor(quantidade));
    }

    private ResultadoReserva tentarReservar(Estoque estoque, int quantidade) {
        try {
            estoque.reservar(quantidade);
            return ResultadoReserva.sucesso();
        } catch (SaldoInsuficienteException ex) {
            // nada foi alterado na entidade, então não há o que reverter
            return ResultadoReserva.saldoInsuficiente(ex.getMessage());
        }
    }
}
