package com.rodrigo.pedidos.pedido.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base para testes de integração: sobe Postgres e Kafka reais via Testcontainers.
 * @ServiceConnection injeta automaticamente as propriedades de datasource/kafka
 * na aplicação de teste — sem precisar de application-test.yml.
 * Containers singleton mantêm as portas estáveis enquanto o Spring reutiliza
 * o contexto entre classes de teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
