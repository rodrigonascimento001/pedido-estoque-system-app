package com.rodrigo.pedidos.comum.kafka;

import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * {@link JsonSerializer} que publica {@code byte[]} exatamente como recebeu.
 *
 * <p>Quando um registro não pode ser desserializado, o {@code DeadLetterPublishingRecoverer}
 * o republica no DLT com os bytes originais ({@code byte[]}). O {@code JsonSerializer} comum
 * reescreveria esses bytes como uma string JSON em base64, e o payload original se perderia.
 * Valores que não são {@code byte[]} seguem o caminho normal de serialização JSON.
 */
public class BytesPreservingJsonSerializer<T> extends JsonSerializer<T> {

    @Override
    public byte[] serialize(String topic, Headers headers, T data) {
        if (data instanceof byte[] bytes) {
            return bytes;
        }
        return super.serialize(topic, headers, data);
    }

    @Override
    public byte[] serialize(String topic, T data) {
        if (data instanceof byte[] bytes) {
            return bytes;
        }
        return super.serialize(topic, data);
    }
}
