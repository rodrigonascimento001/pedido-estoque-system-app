package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.dto.PedidoResponse;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PedidoIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PedidoRepository pedidoRepository;

    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        testConsumer = new KafkaConsumer<>(props);
        testConsumer.subscribe(List.of("pedido-criado"));
    }

    @AfterEach
    void tearDown() {
        testConsumer.close();
    }

    @Test
    void deveCriarPedidoPersistirComoPendenteEPublicarEvento() {
        var produtoId = UUID.randomUUID();
        var request = new PedidoRequest(List.of(
                new PedidoRequest.ItemRequest(produtoId, 2, BigDecimal.valueOf(19.90))
        ));

        ResponseEntity<PedidoResponse> response =
                restTemplate.postForEntity("/api/pedidos", request, PedidoResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(StatusPedido.PENDENTE);

        var pedidoId = response.getBody().id();
        assertThat(pedidoRepository.findById(pedidoId)).isPresent();

        var records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(10))
                .records("pedido-criado");
        ConsumerRecord<String, String> record = StreamSupport.stream(records.spliterator(), false)
                .filter(candidate -> candidate.key().equals(pedidoId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Evento do pedido não foi publicado no Kafka"));

        assertThat(record.key()).isEqualTo(pedidoId.toString());
        assertThat(record.value()).contains(pedidoId.toString());
        assertThat(record.value()).contains(produtoId.toString());
    }

    @Test
    void deveRetornar400QuandoPedidoSemItens() {
        var request = new PedidoRequest(List.of());

        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/pedidos", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deveRetornar404ParaPedidoInexistente() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/pedidos/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
