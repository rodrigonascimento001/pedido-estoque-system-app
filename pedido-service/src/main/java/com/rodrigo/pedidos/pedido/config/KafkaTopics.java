package com.rodrigo.pedidos.pedido.config;

public final class KafkaTopics {
    public static final String PEDIDO_CRIADO = "pedido-criado";
    public static final String ESTOQUE_RESERVADO = "estoque-reservado";
    public static final String ESTOQUE_INDISPONIVEL = "estoque-indisponivel";

    private KafkaTopics() {
    }
}
