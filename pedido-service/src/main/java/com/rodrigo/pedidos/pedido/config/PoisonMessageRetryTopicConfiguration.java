package com.rodrigo.pedidos.pedido.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Telemetria das mensagens malformadas (poison messages).
 *
 * <p>Um registro que não desserializa não chega ao listener nem ao {@code @DltHandler}: o
 * container entrega o {@link DeserializationException} ao error handler, que (por ser exceção
 * fatal) o publica direto no DLT. Por isso a contagem é feita aqui, quando o registro é
 * recuperado. Falhas de negócio que esgotam os retries continuam sendo contadas pelo
 * {@code @DltHandler} do consumer, sem contagem dupla.
 */
@Configuration
public class PoisonMessageRetryTopicConfiguration extends RetryTopicConfigurationSupport {

    private static final Logger log = LoggerFactory.getLogger(PoisonMessageRetryTopicConfiguration.class);
    private static final String SERVICO = "pedido-service";

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public PoisonMessageRetryTopicConfiguration(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void configureCustomizers(CustomizersConfigurer customizersConfigurer) {
        customizersConfigurer.customizeErrorHandler(errorHandler -> {
            if (errorHandler instanceof DefaultErrorHandler defaultErrorHandler) {
                defaultErrorHandler.setRetryListeners(new RetryListener() {
                    @Override
                    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
                        // nada a fazer: só interessa o momento em que o registro é recuperado (enviado ao DLT)
                    }

                    @Override
                    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
                        registrarSeMalformado(record, exception);
                    }
                });
            }
        });
    }

    private void registrarSeMalformado(ConsumerRecord<?, ?> record, Throwable falha) {
        Throwable causa = falha;
        while (causa != null && !(causa instanceof DeserializationException)) {
            causa = causa.getCause() == causa ? null : causa.getCause();
        }
        if (causa == null) {
            return;
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter("kafka.dlt.records", "service", SERVICO, "source_topic", record.topic()).increment();
            registry.counter("kafka.poison.records", "service", SERVICO, "source_topic", record.topic()).increment();
        }
        log.error("Mensagem malformada desviada para o DLT: topic={}, partition={}, offset={}, key={}, causa={}",
                record.topic(), record.partition(), record.offset(), record.key(), causa.getMessage());
    }
}
