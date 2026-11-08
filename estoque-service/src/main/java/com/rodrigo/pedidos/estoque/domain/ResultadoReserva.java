package com.rodrigo.pedidos.estoque.domain;

import java.util.Locale;

/**
 * Desfecho da tentativa de reservar um item. Falha de negócio (sem saldo, produto
 * inexistente) é um resultado esperado, não uma exceção: assim não é marcada como
 * erro no span do APM nem polui a taxa de erros do serviço.
 */
public record ResultadoReserva(Tipo tipo, String motivo) {

    public enum Tipo {
        SUCESSO, SALDO_INSUFICIENTE, PRODUTO_INEXISTENTE;

        /** Valor usado como tag de métrica/span: sucesso, saldo_insuficiente, produto_inexistente. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static ResultadoReserva sucesso() {
        return new ResultadoReserva(Tipo.SUCESSO, null);
    }

    public static ResultadoReserva saldoInsuficiente(String motivo) {
        return new ResultadoReserva(Tipo.SALDO_INSUFICIENTE, motivo);
    }

    public static ResultadoReserva produtoInexistente(String motivo) {
        return new ResultadoReserva(Tipo.PRODUTO_INEXISTENTE, motivo);
    }

    public boolean reservou() {
        return tipo == Tipo.SUCESSO;
    }
}
