# poison-message-handling Specification

## Purpose
TBD - created by archiving change handle-kafka-poison-messages. Update Purpose after archive.
## Requirements
### Requirement: Malformed records do not block consumption
Os consumers de `pedido-service` (`estoque-reservado`, `estoque-indisponivel`) e de `estoque-service` (`pedido-criado`) SHALL tolerar registros cujo valor não possa ser desserializado, sem interromper o consumo da partição.

#### Scenario: Valor não é JSON válido
- **WHEN** um registro com valor que não é JSON válido é publicado em um tópico consumido
- **THEN** o registro é desviado para o DLT do tópico sem retries
- **AND** os registros válidos publicados depois dele na mesma partição continuam sendo processados

#### Scenario: JSON bem formado com conteúdo inválido
- **WHEN** um registro com JSON bem formado, mas com campos de tipo inválido (por exemplo, `pedidoId` que não é UUID), é publicado
- **THEN** o registro é tratado como mensagem malformada e desviado para o DLT sem retries

#### Scenario: Mensagem malformada não altera o estado de negócio
- **WHEN** um registro malformado é consumido
- **THEN** nenhum estoque é baixado e nenhum pedido muda de estado por causa dele

### Requirement: Malformed records are preserved in the DLT
O DLT SHALL conter a chave e o valor originais do registro malformado, byte a byte, e os headers padrão do spring-kafka com a topic de origem e a causa da falha.

#### Scenario: Payload original disponível para inspeção
- **WHEN** um registro malformado é desviado para `<topico>-dlt`
- **THEN** o valor do registro no DLT é idêntico aos bytes publicados originalmente
- **AND** o registro carrega os headers com o tópico original e a mensagem de exceção

### Requirement: Malformed records are observable
Cada registro malformado desviado para o DLT SHALL gerar telemetria suficiente para alerta e diagnóstico.

#### Scenario: Métricas e log
- **WHEN** um registro malformado é desviado para o DLT
- **THEN** o contador `kafka.dlt.records` é incrementado com as tags `service` e `source_topic`
- **AND** o contador `kafka.poison.records` é incrementado com as mesmas tags
- **AND** um log em nível ERROR registra tópico, partição, offset, chave e causa

