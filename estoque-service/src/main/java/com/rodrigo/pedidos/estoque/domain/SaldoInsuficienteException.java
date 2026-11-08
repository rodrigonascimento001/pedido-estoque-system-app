package com.rodrigo.pedidos.estoque.domain;

import java.util.UUID;

public class SaldoInsuficienteException extends RuntimeException {
    public SaldoInsuficienteException(UUID produtoId, int solicitado, int disponivel) {
        super("Saldo insuficiente para o produto " + produtoId
                + " (solicitado: " + solicitado + ", disponível: " + disponivel + ")");
    }
}
