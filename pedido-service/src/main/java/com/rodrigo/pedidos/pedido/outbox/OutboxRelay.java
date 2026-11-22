package com.rodrigo.pedidos.pedido.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxClaimService claimService,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       ObjectMapper objectMapper,
                       @Value("${app.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void publishPending() {
        for (var event : claimService.claimBatch()) {
            publish(event);
        }
    }

    private void publish(OutboxClaimService.ClaimedOutboxEvent event) {
        try {
            Object payload = deserialize(event);
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    event.topic(), event.messageKey(), payload);
            record.headers().add(new RecordHeader("event-id",
                    event.eventId().toString().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            claimService.markPublished(event);
            log.info("Outbox event published: eventId={}, topic={}, key={}",
                    event.eventId(), event.topic(), event.messageKey());
        } catch (Exception ex) {
            claimService.releaseAfterFailure(event, ex.toString());
            log.error("Outbox publication failed: eventId={}, topic={}, attempt={}",
                    event.eventId(), event.topic(), event.attempts(), ex);
        }
    }

    private Object deserialize(OutboxClaimService.ClaimedOutboxEvent event) throws Exception {
        if (event.eventType().equals(PedidoCriadoEvent.class.getName())) {
            return objectMapper.readValue(event.payload(), PedidoCriadoEvent.class);
        }
        throw new IllegalArgumentException("Tipo de evento desconhecido no outbox: " + event.eventType());
    }
}
