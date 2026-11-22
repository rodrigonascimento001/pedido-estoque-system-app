package com.rodrigo.pedidos.estoque.service;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.comum.observability.SpanTags;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import datadog.trace.api.Trace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EstoqueService {

    private final EstoqueRepository estoqueRepository;
    private final ReservaPedidoProcessor reservaPedidoProcessor;

    public EstoqueService(EstoqueRepository estoqueRepository, ReservaPedidoProcessor reservaPedidoProcessor) {
        this.estoqueRepository = estoqueRepository;
        this.reservaPedidoProcessor = reservaPedidoProcessor;
    }

    @Trace(operationName = "estoque.processar_pedido", resourceName = "EstoqueService.processarPedido")
    public void processarPedido(PedidoCriadoEvent evento) {
        SpanTags.tagEntrada("pedido.id", evento.pedidoId());
        reservaPedidoProcessor.processar(evento);
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
}
