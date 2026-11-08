package com.rodrigo.pedidos.estoque.dto;

import com.rodrigo.pedidos.estoque.domain.Estoque;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public class EstoqueDto {

    public record Request(@NotNull UUID produtoId, @PositiveOrZero int quantidade) {
    }

    public record Response(UUID produtoId, int quantidadeDisponivel) {
        public static Response de(Estoque estoque) {
            return new Response(estoque.getProdutoId(), estoque.getQuantidadeDisponivel());
        }
    }
}
