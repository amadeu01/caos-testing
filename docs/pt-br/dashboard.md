# Dashboard

Abra `http://127.0.0.1:8080` depois de `scripts/platform-up.sh` (ou do **Platform up** no IntelliJ).

| Área | O que mostra |
|---|---|
| Barra superior | o controle de Viewers/s e o estado da conexão (`live`, `reconnecting…`, `telemetry unavailable`) |
| Faixa de SPS | o SPS agora e os últimos 60 segundos em linha: esse é o **Steady State** (estado estável) |
| Topologia | todos os **Services** e **Dependencies**. A cor do nó é a saúde ([telemetria](telemetry.md)). O rótulo da aresta mostra chamadas por segundo e erros; os tracejados andam mais rápido com mais tráfego. Arestas grossas são **Critical Dependencies** (dependências críticas); pontilhadas são assíncronas |
| Painel lateral | detalhes do que você clicou: saúde de um Service; taxa, erros, p50, p99 e resultados de uma Dependency |
| Painel de Experiment | vazio por enquanto; os **Experiments** (experimentos) chegam na fatia 5 |

A visão roda cerca de 2 s atrás do tempo real: ela mostra o último segundo assentado ([telemetria](telemetry.md)).

`telemetry unavailable` também aparece quando o telemetry responde algo que não é JSON, e não apenas quando ele está inalcançável.

## Experimente

1. Veja o SPS se estabilizar perto de 50.
2. `docker compose stop recommendations`. Em segundos o recommendations fica vermelho, depois o api e o edge-gateway, e o SPS cai para 0: uma **Non-critical Dependency** (dependência não crítica) derrubou a plataforma inteira. Esse é o **Act** 2 (ato 2), a **Cascading Failure** (falha em cascata).
3. `docker compose start recommendations` e veja o Steady State voltar.

O dashboard só mostra; as **Faults** (falhas) e os controles de **Resilience Policy** chegam nas próximas fatias.
