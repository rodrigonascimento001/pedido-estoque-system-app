## Context

Hoje `value-deserializer` é `JsonDeserializer`. Quando ele falha, a exceção sai do `KafkaConsumer.poll()`, o container não consegue avançar o offset e o mesmo registro é lido para sempre. O `@RetryableTopic` existente só trata exceções lançadas pelo método do listener, então não protege contra esse caso.

## Goals / Non-Goals

**Goals:**
- Um registro malformado nunca bloqueia a partição nem derruba o listener.
- O registro fica disponível no DLT com o payload original e a causa, e o time é avisado pelo alerta existente.

**Non-Goals:**
- Gerenciar tópicos, partições e escala (backlog 5, outra change).
- Ferramenta de inspeção/replay do DLT (backlog 4).
- Mudar o formato dos eventos válidos ou a lógica de retry de falhas de negócio.

## Decisions

- **`ErrorHandlingDeserializer` com delegate `JsonDeserializer`** (`spring.deserializer.value.delegate.class`), mantendo `spring.json.trusted.packages` e o uso do header `__TypeId__`. Só o valor é envolvido: a chave é `String` e `StringDeserializer` não falha por conteúdo.
- **`DeserializationException` é fatal no non-blocking retry** (lista global de exceções fatais do spring-kafka): o registro vai direto ao DLT, sem passar pelos tópicos de retry. Não é preciso configuração extra para isso.
- **Preservar o payload no DLT.** Ao recuperar um registro malformado, o `DeadLetterPublishingRecoverer` publica o `byte[]` original. O template padrão usa `JsonSerializer`, que reescreveria esses bytes como string JSON em base64. `BytesPreservingJsonSerializer` estende `JsonSerializer` e devolve `byte[]` como está; é configurado como `value-serializer` do produtor, o que também vale para o relay do outbox (que nunca publica `byte[]`, então o comportamento dele não muda). Alternativa descartada: um segundo `KafkaTemplate` só para o DLT, porque a auto-configuração do Spring Boot deixaria de criar o `KafkaTemplate` padrão e exigiria declarar os dois à mão.
- **Telemetria via `RetryListener`.** O consumer do DLT (`@DltHandler`) não vê registros malformados: a falha de desserialização acontece antes do listener, também nele. Por isso a contagem acontece no `recovered(...)` do `DefaultErrorHandler`, registrado por uma classe que estende `RetryTopicConfigurationSupport` e usa `configureCustomizers`. Só conta quando a causa é `DeserializationException`; falhas de negócio continuam sendo contadas pelo `@DltHandler`, sem contagem dupla.
- **Métricas:** `kafka.dlt.records{service,source_topic}` (existente, para manter o monitor `dlt-kafka` funcionando) e `kafka.poison.records{service,source_topic}` (nova, distingue payload inválido de falha de processamento).

## Risks / Trade-offs

- [O comportamento exato do spring-kafka 3.1.x (rota direta ao DLT, bytes preservados, consumo do DLT sem laço) é validado só pelos testes de integração] → Os testes verificam o registro no `<topico>-dlt` com os bytes originais, o contador e o processamento da mensagem válida seguinte.
- [Estender `RetryTopicConfigurationSupport` substitui a configuração padrão de retry topics] → A classe não altera nenhuma configuração além do customizer; os testes existentes de DLT e retry continuam cobrindo o restante.
- [Registros malformados ficam no DLT sem ferramenta de replay] → Aceito nesta change; a ferramenta é o item 4 do backlog.

## Migration Plan

Sem migração de dados. Registros malformados que já estejam presos em uma partição em produção passam a ser desviados para o DLT assim que o serviço for atualizado.
