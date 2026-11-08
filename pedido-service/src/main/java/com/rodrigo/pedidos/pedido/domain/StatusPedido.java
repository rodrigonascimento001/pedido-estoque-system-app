package com.rodrigo.pedidos.pedido.domain;

public enum StatusPedido {
    PENDENTE,      // criado, aguardando confirmação de estoque
    CONFIRMADO,    // estoque reservado com sucesso
    CANCELADO      // estoque indisponível (compensação da saga)
}
