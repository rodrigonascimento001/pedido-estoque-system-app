package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.comum.events.EstoqueReservadoEvent;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.PedidoItem;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mensagem malformada (poison pill) em estoque-reservado, com Kafka real: deve ir ao DLT sem
 * retries, com os bytes originais, e não pode impedir a confirmação do pedido seguinte.
 */
class PoisonMessageIntegrationTest extends IntegrationTestBase {

    private static final String TOPICO = "estoque-reservado";
    private static final String DLT = "estoque-reservado-dlt";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PedidoRepository pedidoRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private KafkaConsumer<String, String> testConsumer;
    private KafkaProducer<String, byte[]> rawProducer;

    @BeforeEach
    void setUp() {
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-poison-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        testConsumer = new KafkaConsumer<>(consumerProps);
        testConsumer.subscribe(List.of(DLT));

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        rawProducer = new KafkaProducer<>(producerProps);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            testConsumer.poll(Duration.ofMillis(100));
            assertThat(testConsumer.assignment()).isNotEmpty();
        });
    }

    @AfterEach
    void tearDown() {
        rawProducer.close();
        testConsumer.close();
    }

    @ParameterizedTest(name = "payload malformado: {0}")
    @ValueSource(strings = {
            "{ isto nao e json",
            "{\"pedidoId\":\"nao-e-um-uuid\"}"
    })
    void deveDesviarMensagemMalformadaParaDltSemBloquearAProximaMensagem(String payloadMalformado) throws Exception {
        double dltAntes = contador("kafka.dlt.records");
        double poisonAntes = contador("kafka.poison.records");
        UUID pedidoId = pedidoPendente();

        String chaveMalformada = "poison-" + UUID.randomUUID();
        rawProducer.send(new ProducerRecord<>(TOPICO, chaveMalformada,
                payloadMalformado.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);

        kafkaTemplate.send(TOPICO, pedidoId.toString(), new EstoqueReservadoEvent(pedidoId, Instant.now()))
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> naDlt = aguardarNaDlt(chaveMalformada);
        assertThat(naDlt.value()).isEqualTo(payloadMalformado);
        // DIAGNÓSTICO: lista os headers que o spring-kafka realmente grava no registro do DLT
        List<String> nomesDosHeaders = new java.util.ArrayList<>();
        naDlt.headers().forEach(h -> nomesDosHeaders.add(h.key() + "=" + abreviar(h.value())));
        assertThat(nomesDosHeaders).as("headers do registro no DLT").anyMatch(h -> h.startsWith(KafkaHeaders.DLT_ORIGINAL_TOPIC + "="));

        // a mensagem válida seguinte confirmou o pedido: a partição não ficou presa
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(pedidoRepository.findById(pedidoId).orElseThrow().getStatus())
                        .isEqualTo(StatusPedido.CONFIRMADO));

        assertThat(contador("kafka.dlt.records")).isEqualTo(dltAntes + 1);
        assertThat(contador("kafka.poison.records")).isEqualTo(poisonAntes + 1);
    }

    private UUID pedidoPendente() {
        var item = PedidoItem.novo(UUID.randomUUID(), 1, BigDecimal.valueOf(9.99));
        return pedidoRepository.save(Pedido.novo(List.of(item))).getId();
    }

    private double contador(String nome) {
        return meterRegistry.counter(nome, "service", "pedido-service", "source_topic", TOPICO).count();
    }

    private static String abreviar(byte[] valor) {
        String texto = new String(valor, StandardCharsets.UTF_8);
        return texto.length() > 60 ? texto.substring(0, 60) + "..." : texto;
    }

    private String header(ConsumerRecord<?, ?> record, String nome) {
        Header header = record.headers().lastHeader(nome);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ConsumerRecord<String, String> aguardarNaDlt(String chave) {
        AtomicReference<ConsumerRecord<String, String>> encontrado = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (ConsumerRecord<String, String> candidato : testConsumer.poll(Duration.ofMillis(500)).records(DLT)) {
                if (chave.equals(candidato.key())) {
                    encontrado.compareAndSet(null, candidato);
                }
            }
            assertThat(encontrado.get()).as("registro %s no tópico %s", chave, DLT).isNotNull();
        });
        return encontrado.get();
    }
}
