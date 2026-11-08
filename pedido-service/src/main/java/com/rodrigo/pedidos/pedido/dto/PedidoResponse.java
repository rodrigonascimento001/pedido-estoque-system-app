package com.rodrigo.pedidos.pedido.dto;

import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PedidoResponse(
        UUID id,
        StatusPedido status,
        BigDecimal valorTotal,
        List<ItemResponse> itens,
        Instant criadoEm,
        Instant atualizadoEm,
        String motivoCancelamento
) {
    public record ItemResponse(UUID produtoId, int quantidade, BigDecimal precoUnitario) {
    }

    public static PedidoResponse de(Pedido pedido) {
        return new PedidoResponse(
                pedido.getId(),
                pedido.getStatus(),
                pedido.valorTotal(),
                pedido.getItens().stream()
                        .map(i -> new ItemResponse(i.getProdutoId(), i.getQuantidade(), i.getPrecoUnitario()))
                        .toList(),
                pedido.getCriadoEm(),
                pedido.getAtualizadoEm(),
                pedido.getMotivoCancelamento()
        );
    }
}
