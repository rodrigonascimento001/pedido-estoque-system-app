## 1. Serialização e desserialização

- [x] 1.1 Adicionar `spring-kafka` ao `common` e criar `BytesPreservingJsonSerializer`.
- [x] 1.2 Em `pedido-service` e `estoque-service`: `ErrorHandlingDeserializer` com delegate `JsonDeserializer` como `value-deserializer` e `BytesPreservingJsonSerializer` como `value-serializer`.

## 2. Telemetria

- [x] 2.1 Em cada serviço, configuração que estende `RetryTopicConfigurationSupport` e registra um `RetryListener` que conta `kafka.dlt.records` e `kafka.poison.records` e loga em ERROR quando a causa é `DeserializationException`.

## 3. Testes de integração (Kafka real)

- [x] 3.1 `estoque-service`: registros malformados em `pedido-criado` vão ao `pedido-criado-dlt` com os bytes originais, incrementam as métricas e não impedem o processamento do pedido válido seguinte.
- [x] 3.2 `pedido-service`: o mesmo para `estoque-reservado`, com o pedido válido seguinte sendo confirmado.

## 4. Verificação e documentação

- [ ] 4.1 `mvn -B verify` verde na esteira.
- [x] 4.2 Atualizar o README (tabela de métricas) e o item 5 do `AGENTS.md`, que passa a cobrir só tópicos e partições.
