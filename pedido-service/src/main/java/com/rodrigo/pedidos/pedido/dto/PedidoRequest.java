package com.rodrigo.pedidos.pedido.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PedidoRequest(
        @NotEmpty(message = "O pedido precisa ter ao menos um item")
        @Valid
        List<ItemRequest> itens
) {
    public record ItemRequest(
            @NotNull UUID produtoId,
            @Positive int quantidade,
            @NotNull @Positive BigDecimal precoUnitario
    ) {
    }
}
