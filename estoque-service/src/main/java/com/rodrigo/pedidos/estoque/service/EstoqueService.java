package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.domain.ResultadoReserva;
import com.rodrigo.pedidos.estoque.kafka.EstoqueEventProducer;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import datadog.trace.api.Trace;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EstoqueService {

    private static final Logger log = LoggerFactory.getLogger(EstoqueService.class);

    private static final int MAX_TENTATIVAS_POR_CONFLITO_DE_LOCK = 3;

    private final EstoqueRepository estoqueRepository;
    private final ReservaEstoqueService reservaEstoqueService;
    private final EstoqueEventProducer estoqueEventProducer;
    private final MeterRegistry meterRegistry;

    public EstoqueService(EstoqueRepository estoqueRepository, ReservaEstoqueService reservaEstoqueService,
                          EstoqueEventProducer estoqueEventProducer, MeterRegistry meterRegistry) {
        this.estoqueRepository = estoqueRepository;
        this.reservaEstoqueService = reservaEstoqueService;
        this.estoqueEventProducer = estoqueEventProducer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Tenta reservar todos os itens do pedido. Se qualquer item falhar
     * (produto inexistente ou saldo insuficiente), desfaz as reservas já
     * feitas para este pedido (compensação) e publica evento de falha.
     *
     * <p>Este método NÃO é transacional: cada reserva roda em sua própria transação curta
     * (via {@link ReservaEstoqueService}) para minimizar contenção de lock; a compensação
     * também. Conflitos de lock otimista (dois pedidos disputando o mesmo produto) são
     * repetidos algumas vezes antes de desistir.
     */
    public void processarPedido(PedidoCriadoEvent evento) {
        // tag no span de entrada (consumo Kafka): permite buscar o trace por pedido.id no APM
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());

        List<PedidoCriadoEvent.ItemPedido> reservados = new ArrayList<>();
        UUID produtoComFalha = null;
        ResultadoReserva falha = null;

        for (var item : evento.itens()) {
            ResultadoReserva resultado = comRetryDeLock(
                    () -> reservaEstoqueService.reservar(item.produtoId(), item.quantidade()));
            meterRegistry.counter("estoque.reserva", "resultado", resultado.tipo().tag()).increment();

            if (resultado.reservou()) {
                reservados.add(item);
            } else {
                produtoComFalha = item.produtoId();
                falha = resultado;
                break;
            }
        }

        if (falha != null) {
            log.warn("Reserva falhou para pedido {}: {}. Compensando {} item(ns) já reservado(s)",
                    evento.pedidoId(), falha.motivo(), reservados.size());
            compensar(reservados);
            estoqueEventProducer.publicarIndisponivel(evento.pedidoId(), List.of(produtoComFalha), falha.motivo());
        } else {
            estoqueEventProducer.publicarReservado(evento.pedidoId());
        }
    }

    /**
     * Span próprio porque a compensação é uma etapa da saga com latência e custo que
     * merecem aparecer separados no trace. O @Trace é aplicado pelo agente no bytecode,
     * então funciona mesmo sendo chamado por {@code this} (ao contrário do @Transactional).
     */
    @Trace(operationName = "estoque.compensar", resourceName = "EstoqueService.compensar")
    void compensar(List<PedidoCriadoEvent.ItemPedido> reservados) {
        SpanTags.tag("compensacao.itens", reservados.size());
        for (var item : reservados) {
            comRetryDeLock(() -> {
                reservaEstoqueService.repor(item.produtoId(), item.quantidade());
                return null;
            });
            meterRegistry.counter("estoque.compensacao").increment();
        }
    }

    @Transactional
    public Estoque cadastrarOuAtualizar(UUID produtoId, int quantidade) {
        return estoqueRepository.findById(produtoId)
                .map(e -> {
                    e.repor(quantidade);
                    return e;
                })
                .orElseGet(() -> estoqueRepository.save(new Estoque(produtoId, quantidade)));
    }

    private <T> T comRetryDeLock(Supplier<T> operacao) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                return operacao.get();
            } catch (OptimisticLockingFailureException ex) {
                if (tentativa >= MAX_TENTATIVAS_POR_CONFLITO_DE_LOCK) {
                    throw ex;
                }
                meterRegistry.counter("estoque.lock.conflito").increment();
                log.warn("Conflito de lock otimista (tentativa {}/{}), tentando novamente",
                        tentativa, MAX_TENTATIVAS_POR_CONFLITO_DE_LOCK);
            }
        }
    }
}
