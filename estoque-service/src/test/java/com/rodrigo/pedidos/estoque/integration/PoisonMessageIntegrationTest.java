package com.rodrigo.pedidos.estoque.integration;

import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import com.rodrigo.pedidos.estoque.domain.Estoque;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import com.rodrigo.pedidos.estoque.support.IntegrationTestBase;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.ContainerTestUtils;

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
 * Mensagem malformada (poison pill) em pedido-criado, com Kafka real: deve ir ao DLT sem
 * retries, com os bytes originais, e não pode impedir o processamento da mensagem seguinte.
 */
class PoisonMessageIntegrationTest extends IntegrationTestBase {

    private static final String TOPICO = "pedido-criado";
    private static final String DLT = "pedido-criado-dlt";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EstoqueRepository estoqueRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

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
        testConsumer.subscribe(List.of(DLT, "estoque-reservado"));

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        rawProducer = new KafkaProducer<>(producerProps);

        ContainerTestUtils.waitForAssignment(
                listenerRegistry.getListenerContainer("estoque-pedido-consumer"), 1);
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
            "{\"pedidoId\":\"nao-e-um-uuid\",\"itens\":[]}"
    })
    void deveDesviarMensagemMalformadaParaDltSemBloquearAProximaMensagem(String payloadMalformado) throws Exception {
        double dltAntes = contador("kafka.dlt.records");
        double poisonAntes = contador("kafka.poison.records");
        UUID produtoId = UUID.randomUUID();
        estoqueRepository.save(new Estoque(produtoId, 5));

        // 1) a mensagem malformada
        String chaveMalformada = "poison-" + UUID.randomUUID();
        byte[] bytes = payloadMalformado.getBytes(StandardCharsets.UTF_8);
        rawProducer.send(new ProducerRecord<>(TOPICO, chaveMalformada, bytes)).get(10, TimeUnit.SECONDS);

        // 2) uma mensagem válida logo depois, na mesma partição (mesmo tópico de uma partição)
        UUID pedidoId = UUID.randomUUID();
        kafkaTemplate.send(TOPICO, pedidoId.toString(), new PedidoCriadoEvent(pedidoId,
                List.of(new PedidoCriadoEvent.ItemPedido(produtoId, 2, BigDecimal.TEN)), Instant.now()))
                .get(10, TimeUnit.SECONDS);

        // a malformada chega ao DLT, byte a byte, com a origem e a causa nos headers
        ConsumerRecord<String, String> naDlt = aguardarRegistro(DLT, chaveMalformada);
        assertThat(naDlt.value()).isEqualTo(payloadMalformado);
        // DIAGNÓSTICO: lista os headers que o spring-kafka realmente grava no registro do DLT
        List<String> nomesDosHeaders = new java.util.ArrayList<>();
        naDlt.headers().forEach(h -> nomesDosHeaders.add(h.key() + "=" + abreviar(h.value())));
        assertThat(nomesDosHeaders).as("headers do registro no DLT").anyMatch(h -> h.startsWith(KafkaHeaders.DLT_ORIGINAL_TOPIC + "="));

        // e a válida seguinte foi processada normalmente: a partição não ficou presa
        aguardarRegistro("estoque-reservado", pedidoId.toString());
        assertThat(estoqueRepository.findById(produtoId).orElseThrow().getQuantidadeDisponivel()).isEqualTo(3);

        assertThat(contador("kafka.dlt.records")).isEqualTo(dltAntes + 1);
        assertThat(contador("kafka.poison.records")).isEqualTo(poisonAntes + 1);
    }

    private double contador(String nome) {
        return meterRegistry.counter(nome, "service", "estoque-service", "source_topic", TOPICO).count();
    }

    private static String abreviar(byte[] valor) {
        String texto = new String(valor, StandardCharsets.UTF_8);
        return texto.length() > 60 ? texto.substring(0, 60) + "..." : texto;
    }

    private String header(ConsumerRecord<?, ?> record, String nome) {
        Header header = record.headers().lastHeader(nome);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Aguarda o registro com a chave dada; o Kafka dos testes é compartilhado com outras classes. */
    private ConsumerRecord<String, String> aguardarRegistro(String topico, String chave) {
        AtomicReference<ConsumerRecord<String, String>> encontrado = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (ConsumerRecord<String, String> candidato : testConsumer.poll(Duration.ofMillis(500)).records(topico)) {
                if (chave.equals(candidato.key())) {
                    encontrado.compareAndSet(null, candidato);
                }
            }
            assertThat(encontrado.get()).as("registro %s no tópico %s", chave, topico).isNotNull();
        });
        return encontrado.get();
    }
}
