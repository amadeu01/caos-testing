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

`telemetry unavailable` também aparece quando o telemetry responde algo que não é JSON, e não apenas quando ele está inalcançável. Nesse estado, e enquanto o dashboard se reconecta, o grafo escurece e o SPS mostra `–`, para que uma imagem velha nunca seja confundida com uma plataforma saudável.

## Gráficos de telemetria

O link **Telemetry graphs** na barra superior abre `http://127.0.0.1:8080/telemetry.html`, que traça os últimos 1, 5 (padrão) ou 10 minutos. Use-o para ver *quando* cada **Dependency** quebrou e se recuperou, o que o "agora" único da topologia não mostra.

- **SPS** no topo: o **Steady State** (estado estável) ao longo do tempo. As linhas de Control e Experiment só aparecem quando são diferentes de zero.
- **Um cartão por Dependency** (`caller → callee`): chamadas por segundo, percentual de erro e latência p50 e p99 em ms. A faixa fina sob cada cartão tem uma célula por segundo, verde, âmbar ou vermelha conforme a saúde da Dependency, cinza quando não houve dados.

Pare um Service e veja o erro % e o p99 subirem nas Dependencies que o chamam, depois a faixa ficar vermelha; inicie-o de novo e leia quanto tempo a recuperação levou. O histórico carrega uma vez; depois a página segue a mesma conexão ao vivo do dashboard e escurece quando o telemetry está indisponível.

## Experimente

1. Veja o SPS se estabilizar perto de 50.
2. `docker compose stop recommendations`. O recommendations para de responder e nenhuma Dependency tem timeout ainda, então as chamadas do api esperam. As 16 threads de requisição do api se enchem, o api começa a rejeitar requisições, as chamadas do edge-gateway ao api falham e o SPS cai para 0. O recommendations fica vermelho quando o heartbeat dele para (3 s). Uma **Non-critical Dependency** (dependência não crítica) derrubou a plataforma inteira: esse é o **Act** 2 (ato 2), a **Cascading Failure** (falha em cascata).
3. `docker compose start recommendations`. O Steady State volta quando as chamadas presas tiverem falhado.

Fora o controle de Viewers/s, o dashboard só mostra; as **Faults** (falhas) e os controles de **Resilience Policy** chegam nas próximas fatias.
