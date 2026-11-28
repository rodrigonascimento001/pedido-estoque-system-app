package com.rodrigo.pedidos.gateway;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = GatewaySecurityProbeController.class)
@Import(GatewaySecurityConfiguration.class)
class GatewaySecurityWebTest {

    private static final String VALID_TOKEN = "valid-token";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void rejectsRequestWithoutBearerToken() {
        webTestClient.get()
                .uri("/api/pedidos/probe")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void allowsAnonymousUserRegistrationOnlyForPost() {
        webTestClient.post()
                .uri("/api/usuarios")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("created");

        webTestClient.get()
                .uri("/api/usuarios")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void forwardsRequestWithDecodedJwt() {
        when(jwtDecoder.decode(VALID_TOKEN)).thenReturn(Mono.just(validJwt()));

        webTestClient.get()
                .uri("/api/pedidos/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("forwarded");
    }

    @Test
    void rejectsTokenWhenDecoderReportsInvalidJwt() {
        when(jwtDecoder.decode("invalid-token"))
                .thenReturn(Mono.error(new BadJwtException("Invalid test token")));

        webTestClient.get()
                .uri("/api/pedidos/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deniesActuatorPathsOnApplicationListener() {
        webTestClient.get()
                .uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    private Jwt validJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(VALID_TOKEN)
                .header("alg", "RS256")
                .subject("developer")
                .issuer("http://localhost:8180/realms/pedido-estoque")
                .audience(List.of("pedido-estoque-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
