package com.rodrigo.pedidos.gateway;

import java.util.Optional;

import com.rodrigo.pedidos.comum.security.AuthenticatedUserHeader;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Repassa aos serviços o {@code sub} do JWT já validado no header interno
 * {@value AuthenticatedUserHeader#NAME}. Qualquer valor desse header vindo do cliente é descartado,
 * mesmo quando não há usuário autenticado.
 */
@Component
public class AuthenticatedUserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(contexto -> Mono.justOrEmpty(contexto.getAuthentication()))
                .flatMap(authentication -> Mono.justOrEmpty(authentication.getPrincipal()))
                .ofType(Jwt.class)
                .flatMap(jwt -> Mono.justOrEmpty(jwt.getSubject()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(subject -> chain.filter(comIdentidade(exchange, subject.orElse(null))));
    }

    private ServerWebExchange comIdentidade(ServerWebExchange exchange, String subject) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(AuthenticatedUserHeader.NAME);
                    if (subject != null) {
                        headers.set(AuthenticatedUserHeader.NAME, subject);
                    }
                }))
                .build();
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
