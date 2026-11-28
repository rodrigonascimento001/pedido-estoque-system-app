package com.rodrigo.pedidos.gateway;

import java.util.concurrent.atomic.AtomicReference;

import com.rodrigo.pedidos.comum.security.AuthenticatedUserHeader;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserHeaderFilterTest {

    private final AuthenticatedUserHeaderFilter filter = new AuthenticatedUserHeaderFilter();

    @Test
    void replacesForgedHeaderWithTheValidatedSubject() {
        var forwarded = executar("forjado", true);

        assertThat(forwarded.getRequest().getHeaders().get(AuthenticatedUserHeader.NAME))
                .containsExactly("usuario-do-jwt");
    }

    @Test
    void setsHeaderFromSubjectWhenClientSentNone() {
        var forwarded = executar(null, true);

        assertThat(forwarded.getRequest().getHeaders().getFirst(AuthenticatedUserHeader.NAME))
                .isEqualTo("usuario-do-jwt");
    }

    @Test
    void removesForgedHeaderWhenThereIsNoAuthenticatedUser() {
        var forwarded = executar("forjado", false);

        assertThat(forwarded.getRequest().getHeaders().containsKey(AuthenticatedUserHeader.NAME)).isFalse();
    }

    private ServerWebExchange executar(String headerDoCliente, boolean autenticado) {
        var requestBuilder = MockServerHttpRequest.post("/api/pedidos");
        if (headerDoCliente != null) {
            requestBuilder.header(AuthenticatedUserHeader.NAME, headerDoCliente);
        }
        var exchange = MockServerWebExchange.from(requestBuilder);
        var capturado = new AtomicReference<ServerWebExchange>();
        GatewayFilterChain chain = ex -> {
            capturado.set(ex);
            return Mono.empty();
        };

        Mono<Void> resultado = filter.filter(exchange, chain);
        if (autenticado) {
            var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("usuario-do-jwt").build();
            resultado = resultado.contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)));
        }
        resultado.block();
        return capturado.get();
    }
}
