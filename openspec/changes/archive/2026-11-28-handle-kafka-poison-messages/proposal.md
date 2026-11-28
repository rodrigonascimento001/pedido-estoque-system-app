## Why

Os consumers de `pedido-service` e `estoque-service` usam `JsonDeserializer` diretamente. Uma mensagem malformada (JSON inválido, UUID inválido, header de tipo inesperado) lança a exceção dentro do `poll()` do consumer, antes de chegar ao `@RetryableTopic`: o offset não avança e a partição fica presa, com o consumer repetindo o mesmo erro em loop (*poison pill*). Um único registro ruim para toda a saga daquele tópico.

## What Changes

- Os consumers passam a usar `ErrorHandlingDeserializer` (delegando ao `JsonDeserializer` atual), de modo que a falha vira um `DeserializationException` tratado pelo error handler do container em vez de travar o `poll()`.
- Registros malformados vão direto para o DLT (`<topico>-dlt`), sem retries, preservando os bytes originais de chave e valor e os headers de exceção, para inspeção e futuro replay.
- Um serializador de produtor que preserva `byte[]` (`BytesPreservingJsonSerializer`, no módulo `common`) evita que o `JsonSerializer` reescreva o payload original ao publicá-lo no DLT.
- Observabilidade: cada registro malformado incrementa `kafka.dlt.records` (mesma métrica e alerta já existentes, para o monitor `dlt-kafka` disparar) e uma métrica própria `kafka.poison.records`, com log em nível ERROR.
- Testes de integração com Kafka real nos dois serviços: mensagem malformada vai ao DLT e as mensagens seguintes continuam sendo processadas.

## Capabilities

### New Capabilities
- `poison-message-handling`: tolerância a mensagens malformadas nos consumers Kafka, com desvio para o DLT, preservação do payload e telemetria.

### Modified Capabilities

None.

## Impact

- `common`: nova classe `BytesPreservingJsonSerializer` e dependência `spring-kafka`.
- `pedido-service` e `estoque-service`: `application.yml` (serializer/deserializer) e uma configuração que estende `RetryTopicConfigurationSupport` para contar os registros malformados.
- Fora de escopo (continua no backlog, item 5): criação explícita de tópicos, partições e política de escala; ferramenta de replay do DLT (item 4).
