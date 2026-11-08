## Why

O projeto ainda não valida automaticamente alterações nem publica imagens consumíveis para implantação manual. Além disso, o Compose atual mistura aplicações e dependências de infraestrutura, dificultando a evolução independente dos dois conjuntos.

## What Changes

- Adicionar GitHub Actions para executar `mvn verify` nos fluxos de desenvolvimento e principal.
- Publicar no GHCR privado imagens dos três serviços somente após alterações integradas em `main`, com tag imutável do commit e uma tag móvel.
- Separar o Compose de aplicações no repositório `pedido-estoque-system-app` do Compose de infraestrutura no repositório `pedido-estoque-system-infra`.
- Manter o fluxo completo local possível coordenando os dois Compose e documentando a rede, os endereços de serviço e a ordem de inicialização.
- Manter a implantação manual; preparar e publicar imagens não executa deploy.

## Capabilities

### New Capabilities
- `automated-ci-image-publishing`: validação Maven automatizada e publicação controlada de imagens privadas no GHCR.
- `separated-compose-stacks`: execução independente dos serviços de aplicação e recursos de infraestrutura, com integração documentada para o fluxo local.

### Modified Capabilities

## Impact

- Repositório da aplicação `rodrigonascimento001/pedido-estoque-system-app`: workflows, Compose de aplicações, documentação e configuração de build/publicação das imagens.
- Repositório de infraestrutura `rodrigonascimento001/pedido-estoque-system-infra`: Compose de Kafka, bancos de dados, Redis e demais recursos que forem confirmados como parte da infraestrutura.
- GHCR privado: autenticação do workflow e permissões para publicar os pacotes.
- O Compose local completo dependerá dos dois repositórios disponíveis, de uma rede Docker compartilhada e de configuração coerente dos nomes DNS e portas.
- A mudança não configura deploy automático nem afirma prontidão para produção; endurecimento e operação do ambiente produtivo permanecem trabalho futuro.
