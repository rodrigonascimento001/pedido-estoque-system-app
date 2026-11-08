package com.rodrigo.pedidos.comum.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evento publicado pelo pedido-service quando um novo pedido é criado.
 * Consumido pelo estoque-service para tentar reservar os itens (saga).
 */
public record PedidoCriadoEvent(
        UUID pedidoId,
        List<ItemPedido> itens,
        Instant criadoEm
) {
    public record ItemPedido(UUID produtoId, int quantidade, BigDecimal precoUnitario) {
    }
}
