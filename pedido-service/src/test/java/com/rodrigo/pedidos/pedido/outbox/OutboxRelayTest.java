package com.rodrigo.pedidos.pedido.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rodrigo.pedidos.comum.events.PedidoCriadoEvent;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.header.Header;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {

    @Test
    void liberaEventoERegistraFalhaQuandoBrokerRecusaPublicacao() throws Exception {
        var claimService = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        var kafkaTemplate = (KafkaTemplate<String, Object>) mock(KafkaTemplate.class);
        UUID pedidoId = UUID.randomUUID();
        var event = new PedidoCriadoEvent(pedidoId, List.of(), Instant.now());
        var claimed = claimedEvent(event, pedidoId);
        when(claimService.claimBatch()).thenReturn(List.of(claimed));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker indisponível")));

        new OutboxRelay(claimService, kafkaTemplate, mapper(), 100).publishPending();

        verify(claimService).releaseAfterFailure(eq(claimed), contains("broker indisponível"));
        verify(claimService, never()).markPublished(any());
    }

    @Test
    void marcaEventoPublicadoSomenteAposConfirmacaoDoBroker() throws Exception {
        var claimService = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        var kafkaTemplate = (KafkaTemplate<String, Object>) mock(KafkaTemplate.class);
        UUID pedidoId = UUID.randomUUID();
        var event = new PedidoCriadoEvent(pedidoId, List.of(), Instant.now());
        var claimed = claimedEvent(event, pedidoId);
        when(claimService.claimBatch()).thenReturn(List.of(claimed));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxRelay(claimService, kafkaTemplate, mapper(), 100).publishPending();

        verify(claimService).markPublished(claimed);
        verify(claimService, never()).releaseAfterFailure(any(), any());
    }

    @Test
    void republicaComMesmoEventIdSeRelayCaiDepoisDoAckAntesDeMarcarPublicado() throws Exception {
        var claimService = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        var kafkaTemplate = (KafkaTemplate<String, Object>) mock(KafkaTemplate.class);
        UUID pedidoId = UUID.randomUUID();
        var claimed = claimedEvent(new PedidoCriadoEvent(pedidoId, List.of(), Instant.now()), pedidoId);
        when(claimService.claimBatch()).thenReturn(List.of(claimed), List.of(claimed));
        doThrow(new IllegalStateException("falha simulada após ack"))
                .doNothing().when(claimService).markPublished(claimed);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        var relay = new OutboxRelay(claimService, kafkaTemplate, mapper(), 100);

        relay.publishPending();
        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(records.capture());
        Header firstId = records.getAllValues().get(0).headers().lastHeader("event-id");
        Header secondId = records.getAllValues().get(1).headers().lastHeader("event-id");
        assertThat(new String(firstId.value(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(claimed.eventId().toString());
        assertThat(secondId.value()).isEqualTo(firstId.value());
        verify(claimService).releaseAfterFailure(eq(claimed), contains("falha simulada após ack"));
        verify(claimService, times(2)).markPublished(claimed);
    }

    private OutboxClaimService.ClaimedOutboxEvent claimedEvent(PedidoCriadoEvent event, UUID pedidoId)
            throws Exception {
        return new OutboxClaimService.ClaimedOutboxEvent(UUID.randomUUID(), event.getClass().getName(),
                "pedido-criado", pedidoId.toString(), mapper().writeValueAsString(event), 1,
                UUID.randomUUID());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
