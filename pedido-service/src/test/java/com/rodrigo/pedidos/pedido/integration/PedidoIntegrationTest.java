package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.dto.PedidoResponse;
import com.rodrigo.pedidos.pedido.kafka.PedidoEventProducer;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PedidoEventProducer pedidoEventProducer;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM outbox_event WHERE message_key = ?",
                        java.sql.Timestamp.class, pedidoId.toString())).isNotNull());
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

    @Test
    void flywayDeveBaselinarBancoExistenteSemApagarDados() {
        String schema = "baseline_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".pedidos (id UUID PRIMARY KEY, status VARCHAR(255) NOT NULL, "
                + "criado_em TIMESTAMP WITH TIME ZONE NOT NULL, atualizado_em TIMESTAMP WITH TIME ZONE, "
                + "motivo_cancelamento VARCHAR(255))");
        UUID pedidoExistente = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".pedidos (id, status, criado_em) VALUES (?, 'PENDENTE', CURRENT_TIMESTAMP)",
                pedidoExistente);

        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".pedidos WHERE id = ?",
                Integer.class, pedidoExistente)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = 'outbox_event'", Integer.class, schema))
                .isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
    }

    @Test
    void rollbackDoPedidoTambemRemoveEventoDoOutbox() {
        var pedido = Pedido.novo(List.of(PedidoItem.novo(UUID.randomUUID(), 1, BigDecimal.TEN)));

        try {
            new TransactionTemplate(transactionManager).execute(status -> {
                pedidoRepository.save(pedido);
                pedidoEventProducer.publicarPedidoCriado(pedido);
                throw new IllegalStateException("simular falha antes do commit");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("simular falha antes do commit");
        }

        assertThat(pedidoRepository.findById(pedido.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE message_key = ?",
                Integer.class, pedido.getId().toString())).isZero();
    }
}
