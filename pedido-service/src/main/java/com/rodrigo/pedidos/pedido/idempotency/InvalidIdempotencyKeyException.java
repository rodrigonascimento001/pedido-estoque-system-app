package com.rodrigo.pedidos.pedido.idempotency;

/** Chave de idempotência ausente de escopo ou fora do formato aceito (resulta em 400). */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
