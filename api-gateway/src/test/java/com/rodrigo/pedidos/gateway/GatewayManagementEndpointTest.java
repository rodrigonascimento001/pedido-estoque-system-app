package com.rodrigo.pedidos.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "management.health.redis.enabled=false"})
class GatewayManagementEndpointTest {

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthIsServedOnlyOnTheManagementListener() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build()
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + applicationPort)
                .build()
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void gatewayDetailsAreNotExposedOnTheManagementListener() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build()
                .get()
                .uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
