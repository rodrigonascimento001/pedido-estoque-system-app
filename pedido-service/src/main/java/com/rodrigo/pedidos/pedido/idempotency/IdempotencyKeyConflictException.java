package com.rodrigo.pedidos.pedido.idempotency;

/** A mesma chave chegou com um corpo diferente do registrado (resulta em 422). */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("A Idempotency-Key já foi usada com um corpo de requisição diferente");
    }
}
