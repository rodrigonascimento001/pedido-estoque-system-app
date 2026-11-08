package com.rodrigo.pedidos.estoque.integration;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import com.rodrigo.pedidos.estoque.support.IntegrationTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ponta a ponta via Kafka real: publica PedidoCriadoEvent no tópico e verifica
 * que o estoque-service reage corretamente (reserva + evento de sucesso,
 * ou compensação + evento de falha), sem mockar nada do pipeline de mensageria.
 */
class PedidoEventConsumerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EstoqueRepository estoqueRepository;

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
        testConsumer.subscribe(List.of("estoque-reservado", "estoque-indisponivel"));
    }

    @AfterEach
    void tearDown() {
        testConsumer.close();
    }

    @Test
    void deveReservarEPublicarEstoqueReservadoQuandoHaSaldo() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 5));

        UUID pedidoId = UUID.randomUUID();
        var evento = new PedidoCriadoEvent(pedidoId,
                List.of(new PedidoCriadoEvent.ItemPedido(produtoId, 2, BigDecimal.valueOf(15))),
                Instant.now());

        kafkaTemplate.send("pedido-criado", pedidoId.toString(), evento);

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(testConsumer, "estoque-reservado", Duration.ofSeconds(15));

        assertThat(record.key()).isEqualTo(pedidoId.toString());
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(estoqueRepository.findById(produtoId).orElseThrow().getQuantidadeDisponivel()).isEqualTo(3)
        );
    }

    @Test
    void devePublicarEstoqueIndisponivelQuandoSaldoInsuficiente() {
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 1));

        UUID pedidoId = UUID.randomUUID();
        var evento = new PedidoCriadoEvent(pedidoId,
                List.of(new PedidoCriadoEvent.ItemPedido(produtoId, 5, BigDecimal.valueOf(15))),
                Instant.now());

        kafkaTemplate.send("pedido-criado", pedidoId.toString(), evento);

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(testConsumer, "estoque-indisponivel", Duration.ofSeconds(15));

        assertThat(record.key()).isEqualTo(pedidoId.toString());
        assertThat(record.value()).contains(produtoId.toString());
        // saldo permanece inalterado (compensação não se aplica pois nada foi reservado)
        assertThat(estoqueRepository.findById(produtoId).orElseThrow().getQuantidadeDisponivel()).isEqualTo(1);
    }
}
