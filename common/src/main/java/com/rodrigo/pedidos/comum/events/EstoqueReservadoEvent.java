package com.rodrigo.pedidos.comum.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Publicado pelo estoque-service quando TODOS os itens do pedido
 * foram reservados com sucesso. Consumido pelo pedido-service para
 * confirmar o pedido (etapa final da saga - caminho feliz).
 */
public record EstoqueReservadoEvent(
        UUID pedidoId,
        Instant processadoEm
) {
}
