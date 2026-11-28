package com.rodrigo.pedidos.comum.security;

/**
 * Header interno com o {@code sub} do JWT validado. Quem o define é apenas o gateway, que descarta
 * qualquer valor enviado pelo cliente; os serviços só o confiam por ficarem inalcançáveis fora da
 * rede Docker.
 */
public final class AuthenticatedUserHeader {

    public static final String NAME = "X-Authenticated-User";

    private AuthenticatedUserHeader() {
    }
}
