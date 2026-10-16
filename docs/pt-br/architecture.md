# Arquitetura

A plataforma imita uma Netflix bem pequena. Cada caixa abaixo é um contêiner próprio, então cada uma pode falhar sozinha.

```
viewer-simulator ─▶ edge-gateway ─▶ api ─┬─▶ profile
                                         ├─▶ catalog ─┬─▶ cache (Redis)
                                         │            └─▶ store (DynamoDB Local)
                                         ├─▶ recommendations
                                         ├─▶ playback ─▶ license
                                         └─▶ queue (ElasticMQ) ─▶ ratings-worker ─▶ store
```

## A jornada do Viewer

1. `GET /home`: o api pede a página inicial para profile, catalog e recommendations.
2. O **Viewer** (espectador simulado) escolhe o primeiro título recomendado.
3. `POST /play`: o api chama o playback, que chama o license. Sucesso aqui é um **Playback Start** (início de reprodução).
4. `POST /ratings`: o api coloca uma avaliação na fila; o ratings-worker grava depois.

## Dependencies

Uma **Dependency** (dependência) é uma chamada de um Service para outro.

| Dependency | Tipo | O que a perda significa |
|---|---|---|
| edge-gateway → api | **Critical** (crítica) | nenhum Viewer entra |
| api → profile | **Non-critical** (não crítica) | poderia mostrar um perfil anônimo |
| api → catalog | Critical | não há títulos para mostrar |
| catalog → cache | Non-critical | o catalog pode ler direto do store |
| catalog → store | Critical | nenhum título |
| api → recommendations | Non-critical | poderia mostrar títulos populares |
| api → playback, playback → license | Critical | nenhum Playback Start |
| api → queue | Non-critical | a avaliação pode ser descartada |

O "poderia" é proposital: nesta fatia nenhuma Dependency tem **Fallback** (resposta degradada) ainda, então toda falha se espalha. As próximas fatias adicionam **Resilience Policies** (políticas de resiliência) que você liga e desliga.

## AWS sem conta na AWS

Os Services usam o AWS SDK v2 de verdade, apontado para emuladores locais: DynamoDB Local no lugar do DynamoDB, ElasticMQ no lugar do SQS e Redis no lugar do ElastiCache. Cada emulador é um contêiner próprio. O [ADR 0001](../adr/0001-aws-sdk-against-per-service-emulators.md) (em inglês) explica por que não usamos LocalStack.

## Threads, de propósito

Cada Service da plataforma atende requisições em um pool pequeno e limitado de 16 threads. É proposital: assim uma Dependency lenta esgota visivelmente um Service no Ato 4. Veja o [ADR 0002](../adr/0002-fixed-platform-thread-pools.md).

## Organização do repositório

| Caminho | Conteúdo |
|---|---|
| `platform/` | os 8 Services da plataforma (alvos de Faults) |
| `control-plane/` | viewer-simulator; depois chaos-controller, telemetry e dashboard |
| `libs/` | código compartilhado; hoje só recursos de teste |
| `infra/` | configuração dos emuladores e criação de tabelas |
| `scripts/`, `.run/` | subir e parar, pelo terminal ou pelo IntelliJ |
