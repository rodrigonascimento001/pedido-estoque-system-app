package com.rodrigo.pedidos.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filtro global de autenticação. Valida a presença/formato do token Bearer
 * antes de encaminhar a requisição aos microsserviços.
 * TODO: trocar a validação simples por verificação real de JWT (assinatura, expiração, escopos).
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final String[] ROTAS_PUBLICAS = {"/actuator"};

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        boolean isPublica = false;
        for (String rota : ROTAS_PUBLICAS) {
            if (path.startsWith(rota)) {
                isPublica = true;
                break;
            }
        }
        if (isPublica) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
