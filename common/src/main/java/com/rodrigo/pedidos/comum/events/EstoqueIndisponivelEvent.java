package com.rodrigo.pedidos.comum.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publicado pelo estoque-service quando um ou mais itens do pedido
 * não puderam ser reservados. Consumido pelo pedido-service para
 * cancelar o pedido (compensação da saga).
 */
public record EstoqueIndisponivelEvent(
        UUID pedidoId,
        List<UUID> produtosIndisponiveis,
        String motivo,
        Instant processadoEm
) {
}
