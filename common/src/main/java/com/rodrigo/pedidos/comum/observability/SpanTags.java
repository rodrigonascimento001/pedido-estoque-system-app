package com.rodrigo.pedidos.comum.observability;

import datadog.trace.api.interceptor.MutableSpan;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;

/**
 * Atalhos null-safe para adicionar tags de negócio aos spans do Datadog.
 *
 * <p>Sem o dd-java-agent (dev local, testes) o tracer global é um no-op e nenhum span
 * está ativo, então estas chamadas não fazem nada. Nunca lançam exceção: observabilidade
 * não pode quebrar o fluxo de negócio.
 *
 * <p>Regra de ouro: IDs (pedido.id, produto.id) vão em <b>tags de span</b>, nunca no nome
 * da operação nem em tags de <b>métrica</b> (cardinalidade ilimitada).
 */
public final class SpanTags {

    private SpanTags() {
    }

    /** Tag no span ativo no momento (por exemplo, o criado por {@code @Trace}). */
    public static void tag(String chave, Object valor) {
        try {
            Span span = GlobalTracer.get().activeSpan();
            if (span != null && valor != null) {
                span.setTag(chave, String.valueOf(valor));
            }
        } catch (RuntimeException ignorada) {
            // observabilidade nunca derruba o negócio
        }
    }

    /**
     * Tag no span de entrada deste serviço (a requisição HTTP ou o consumo Kafka), que é a
     * raiz local do trace. É nele que você filtra no APM ("quero o trace do pedido X"),
     * mesmo que a chamada esteja dentro de vários spans filhos.
     */
    public static void tagEntrada(String chave, Object valor) {
        try {
            Span span = GlobalTracer.get().activeSpan();
            if (span == null || valor == null) {
                return;
            }
            String texto = String.valueOf(valor);
            if (span instanceof MutableSpan mutavel) {
                MutableSpan raiz = mutavel.getLocalRootSpan();
                if (raiz != null) {
                    raiz.setTag(chave, texto);
                    return;
                }
            }
            span.setTag(chave, texto);
        } catch (RuntimeException ignorada) {
            // observabilidade nunca derruba o negócio
        }
    }
}
