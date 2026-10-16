# Relatório: rodada de Chaos Monkey de 16/10/2026

Resultado dos testes de caos feitos ao vivo na plataforma (fatias 1 e 2: plataforma, telemetria e dashboard, **sem nenhuma Resilience Policy** (política de resiliência) ainda). O objetivo é registrar o que quebrou, como quebrou e o que precisamos melhorar. Os termos em inglês seguem o [glossário](../glossary.md).

## Resumo

- A plataforma aguenta carga: 500 **Viewers** (espectadores simulados) por segundo sem degradar.
- Ela **não aguenta falhas**. Qualquer **Dependency** (dependência) que falha, mesmo uma **Non-critical Dependency** (dependência não crítica), derruba o **SPS** (Playback Starts por segundo) para zero: uma **Cascading Failure** (falha em cascata).
- Matar quatro Services ao mesmo tempo deixou a plataforma em uma **falha metaestável**: todos os contêineres voltaram, mas o sistema continuou quebrado. Nem tirar todo o tráfego resolveu. Só um reinício completo (cerca de 30 s) trouxe o **Steady State** (estado estável) de volta.
- A causa comum de quase tudo é a mesma: **nenhuma chamada entre Services tem timeout**. Isso é proposital nesta fase (é a lição dos Atos 2 e 4), e a fatia 4 corrige com Resilience Policies.

## Como o teste foi feito

- Plataforma em Docker Compose, commit `c1f303d`, 50 Viewers/s, dashboard em `http://127.0.0.1:8080`, telemetria em `http://127.0.0.1:8082`.
- **Faults** (falhas) usadas, todas reversíveis e locais:
  - `docker compose stop` e `docker compose kill` (parada limpa e queda abrupta de um contêiner);
  - `docker update --cpus` (falta de CPU, como um vizinho barulhento ou uma máquina pequena demais);
  - aumento de tráfego pelo viewer-simulator (até o limite de 500 Viewers/s).
- Critério de recuperação: SPS de volta a 45 ou mais em até 60 s depois de religar o que foi derrubado.
- O roteiro da rodada escalonada está no [apêndice](#apêndice-roteiro-da-rodada-escalonada).

## Resultados

| # | Fault | SPS durante | Saúde durante | Recuperação |
|---|---|---|---|---|
| 1 | parar `recommendations` | 0 | recommendations, api, edge-gateway vermelhos | sozinha, 10 a 30 s depois de religar |
| 2 | parar `ratings-worker` | 50 (sem mudança) | ratings-worker vermelho, fila crescendo | sozinha, a fila esvazia |
| 3 | parar `telemetry` | 50 (sem mudança, pelo log) | dashboard escurece e mostra "telemetry unavailable" | sozinha |
| 4 | parar `store` (DynamoDB) | 50 por 10 a 20 s, depois 0 | catalog, api, edge-gateway vermelhos | **manual**: recriar tabelas e reiniciar catalog |
| 5 | tráfego de 50 a 500 Viewers/s | 50 a 480 | tudo verde, 1 % de erro no edge a 500/s | não precisou |
| 6 | 500/s com api limitado a 0,25 CPU | ~315 | edge-gateway âmbar, 16 % de erro | sozinha ao tirar o limite |
| 7 | 500/s com api limitado a 0,1 CPU | ~86 | edge-gateway vermelho, 62 % de erro | sozinha ao tirar o limite |
| 8 | matar `recommendations` | 0 | 3 vermelhos | sozinha, 15 s |
| 9 | matar `profile` e `playback` | 0 | 3 vermelhos | sozinha, 5 s |
| 10 | matar `api`, `catalog`, `license`, `ratings-worker` | 0 | 5 vermelhos | **não recuperou**: só com reinício completo |

## Achados e melhorias

Os achados estão em ordem de gravidade. Cada um diz o que vimos, por que acontece e o que fazer.

### 1. Falha metaestável depois de várias quedas ao mesmo tempo (crítico)

**O que vimos.** Na rodada 10, os quatro Services voltaram e ficaram saudáveis no Docker, mas o SPS ficou entre 2 e 10 por mais de dois minutos. O edge-gateway registrou 1.804 avisos de "Worker thread pool exhaustion" em 40 s. Pausar todo o tráfego por 15 s não resolveu. Só `scripts/platform-down.sh` seguido de `scripts/platform-up.sh` trouxe o SPS de volta a 50.

**Onde estava o tempo.** As chamadas do api levavam de 200 a 400 ms, em degraus de 100 ms, enquanto os Services chamados respondiam em 1 a 2 ms (catalog→cache 0 ms, playback→license 1 ms). O tempo estava sendo perdido dentro do cliente HTTP do api, não nos Services chamados.

**Hipótese (ainda não provada).** Quando os Services morreram de forma abrupta, o pool de conexões do REST Client do api manteve conexões mortas ("meio abertas"). Novas chamadas esperam atrás delas. Como nenhuma chamada tem timeout, os 16 threads do edge-gateway e do api ficam presos, e o sistema se mantém quebrado mesmo depois que a causa foi embora. Esse é o padrão descrito como *metastable failure*: o gatilho passa, mas o sistema fica preso no estado ruim.

**O que melhorar.**
- Timeouts de conexão e de leitura em todas as Dependencies (fatia 4, **Resilience Policy**).
- **Circuit breaker** (disjuntor) para parar de chamar quem está falhando e dar tempo de recuperar (fatia 4).
- Validar ou renovar conexões do pool (por exemplo, um tempo máximo de vida para cada conexão) para não reusar conexões mortas. Verificar a propriedade certa no Quarkus 3.40.
- **Load shedding** (descarte de carga) no edge-gateway: recusar rápido quando a fila está cheia em vez de enfileirar mais.
- Um health check que reflita "estou travado" (fila de threads cheia), para que um orquestrador reinicie só o Service doente, e não tudo.
- **Experiment** (experimento) automático para este caso na fatia 5: "a plataforma volta ao Steady State em até 60 s depois de perder quatro Services", rodando no CI.

### 2. Um Service parado faz quem chama esperar, em vez de falhar rápido (alto)

**O que vimos.** Ao parar o recommendations, as chamadas do api não falharam na hora: ficaram esperando. Os 16 threads do api encheram e o api começou a recusar requisições. O recommendations é uma **Non-critical Dependency**, mas derrubou tudo.

**Por quê.** Um contêiner parado no Docker não responde com "conexão recusada": o endereço antigo simplesmente não responde, e o cliente espera pelos timeouts padrão (15 s para conectar e 30 s para ler, no REST Client do Quarkus).

**O que melhorar.** Timeout e **Fallback** (resposta degradada, aqui: títulos populares) na Dependency api→recommendations, além de um **bulkhead** (limite de chamadas simultâneas) para que uma Dependency doente não ocupe todos os threads (fatia 4, Ato 3).

### 3. Reiniciar o DynamoDB perde os dados e exige ação manual (alto)

**O que vimos.** Depois de religar o `store`, o catálogo continuou vazio e o SPS em 0. Foi preciso recriar as tabelas (`docker compose run --rm store-init`) e reiniciar o catalog para ele inserir os 50 títulos de novo.

**Por quê.** O DynamoDB Local roda em memória (`-inMemory`), e o catalog só insere os títulos quando inicia.

**O que melhorar.**
- Para a demonstração: guardar os dados do DynamoDB Local em um volume, ou fazer o catalog verificar e reinserir os títulos se a tabela estiver vazia.
- Para a aula: este é um bom exemplo de por que um banco em produção precisa de durabilidade, backup e um **runbook** (roteiro de recuperação) testado. Uma recuperação que ninguém ensaiou não é uma recuperação.

### 4. O cache esconde a queda do banco e depois cai de uma vez (médio)

**O que vimos.** Com o store parado, nada mudou por 10 a 20 s: o Redis ainda servia os títulos. Quando a entrada expirou (TTL de 30 s), catalog, api e edge-gateway ficaram vermelhos ao mesmo tempo.

**O que melhorar.**
- Servir o último valor do cache quando o store falha (*stale-while-error*): um Fallback natural para o catalog (fatia 4).
- Alertar sobre erros no store mesmo quando o SPS ainda está bom. O problema já existia durante os 20 s em que tudo parecia verde.

### 5. O sintoma aparece antes da causa: quem fica vermelho não é quem está doente (médio)

**O que vimos.** Com o api limitado a 0,1 CPU, o **api ficou verde** e o **edge-gateway ficou vermelho**. As chamadas ao api ainda funcionavam, só mais devagar (p99 perto de 200 ms, abaixo do limite de 1 s para âmbar). Quem falhava era o edge-gateway, porque os threads dele ficavam esperando o api lento.

**O que melhorar.**
- Medir saturação por Service: threads ocupados e tamanho da fila de trabalho, não só erros e latência.
- Contar chamadas em andamento por Dependency, para ver o api travado mesmo antes de uma chamada terminar (planejado para a fatia 4, junto com o bulkhead).
- Revisar se o limite de 1 s para âmbar é alto demais para uma plataforma cujas chamadas normais levam poucos milissegundos.

### 6. Durante a falha, o tráfego aumenta (médio)

**O que vimos.** No meio da queda do store, a taxa de viewer-simulator→edge-gateway subiu de 150 para 234 chamadas por segundo, embora o número de Viewers não tenha mudado.

**Por quê.** Jornadas que esperam e expiram se sobrepõem às novas. É um pequeno exemplo de amplificação de carga, primo da **retry storm** (tempestade de novas tentativas).

**O que melhorar.** Retry com backoff exponencial e jitter (fatia 4) e um limite de taxa no edge-gateway.

### 7. Telemetria e dashboard (baixo, parte já corrigida)

- Corrigido nesta rodada: o dashboard piscava porque mostrava um segundo ainda incompleto, e o envio da telemetria disputava os mesmos 16 threads das requisições (um Service travado ficava mudo).
- Ainda aberto:
  - os arquivos do dashboard eram servidos com `cache-control: immutable` por um dia, então quem atualizava o projeto via a página antiga até forçar a recarga (Ctrl+Shift+R). A correção está em andamento.
  - o controle de Viewers/s mostra o valor de quando a página abriu, não o valor real. A correção está em andamento.
  - um Service travado, sem chamadas terminando, pode aparecer verde por alguns segundos (veja o achado 5).
  - não havia gráficos de histórico por Dependency. A página de gráficos de telemetria está sendo construída.

### 8. O teste de carga bate no limite do simulador (baixo)

**O que vimos.** A 500 Viewers/s (o máximo do viewer-simulator), a plataforma ainda estava saudável, com cerca de 4.500 chamadas por segundo entre Services. Só conseguimos degradar tirando CPU do api.

**O que melhorar.** Permitir uma taxa maior no simulador, ou vários simuladores, para achar o limite real; e definir limites de CPU por contêiner que se pareçam com um ambiente real.

## Próximos passos

| Melhoria | Achados | Onde entra |
|---|---|---|
| Timeouts, retry com jitter, circuit breaker, bulkhead e Fallbacks por Dependency, ligáveis no dashboard | 1, 2, 4, 6 | fatia 4 |
| Renovar conexões do pool e fazer load shedding no edge-gateway | 1 | fatia 4 |
| Chamadas em andamento e saturação por Service na telemetria | 5, 7 | fatia 4 |
| Faults de erro e de latência com TTL, sem precisar parar contêineres (por exemplo, "store devolve 50 % de erro") | 3, 4 | fatia 3 |
| Estado "parado" (cinza) vindo do chaos-controller | 2, 7 | fatia 3 |
| Experiments automáticos no CI, incluindo "recupera em 60 s depois de perder quatro Services" | 1 | fatias 5 e 6 |
| Dados do DynamoDB Local em volume, ou catalog que reinsere títulos | 3 | tarefa pequena antes da fatia 3 |
| Cache dos arquivos estáticos, controle de Viewers/s e página de gráficos | 7 | em andamento |

## Para a aula

- O Chaos Monkey achou em minutos um modo de falha que nenhum teste automatizado tinha visto: a falha metaestável. Esse é o argumento da Netflix para fazer caos de propósito, em vez de esperar o incidente real.
- Medir o SPS, e não a CPU ou a contagem de erros, mostrou a degradação antes de qualquer Service ficar vermelho.
- "Reiniciar tudo" funcionou, mas levou 30 s com a plataforma inteira fora do ar. O objetivo das próximas fatias é que nenhuma dessas Faults precise disso.

## Apêndice: roteiro da rodada escalonada

```bash
#!/usr/bin/env bash
# Chaos Monkey escalonado: cada rodada mata mais Services, religa e verifica se o Steady State volta.
set -uo pipefail
cd "$(dirname "$0")/.."

sps() { curl -s --max-time 2 http://127.0.0.1:8082/windows/latest | python3 -c "import json,sys; print(json.load(sys.stdin)['sps']['total'])" 2>/dev/null || echo "?"; }
red() { curl -s --max-time 2 http://127.0.0.1:8082/windows/latest | python3 -c "import json,sys; print(','.join(x['name'] for x in json.load(sys.stdin)['services'] if x['health']=='RED') or '-')" 2>/dev/null || echo "?"; }

recovered() {
  for i in $(seq 1 12); do
    sleep 5
    local s; s=$(sps)
    if [[ "$s" =~ ^[0-9]+$ ]] && (( s >= 45 )); then echo "RECOVERED after $((i*5))s (SPS $s)"; return 0; fi
  done
  echo "NOT RECOVERED after 60s (SPS $(sps), red: $(red))"; return 1
}

round() {
  local name=$1; shift
  echo "=== $name: kill $* ==="
  echo "before: SPS $(sps)"
  docker compose kill "$@" >/dev/null 2>&1
  sleep 10
  echo "during (10s): SPS $(sps), red: $(red)"
  docker compose start "$@" >/dev/null 2>&1
  recovered
}

round "Round 1" recommendations || exit 0
round "Round 2" profile playback || exit 0
round "Round 3" api catalog license ratings-worker || exit 0
round "Round 4" edge-gateway api profile catalog recommendations playback license ratings-worker || exit 0
round "Round 5" cache queue || exit 0
round "Round 6" store || exit 0
echo "Survived every round."
```

Saída da rodada de 16/10/2026:

```
=== Round 1: kill recommendations ===
before: SPS 55
during (10s): SPS 0, red: api,edge-gateway,recommendations
RECOVERED after 15s (SPS 50)
=== Round 2: kill profile playback ===
before: SPS 50
during (10s): SPS 0, red: edge-gateway,playback,profile
RECOVERED after 5s (SPS 50)
=== Round 3: kill api catalog license ratings-worker ===
before: SPS 50
during (10s): SPS 0, red: api,catalog,edge-gateway,license,ratings-worker
NOT RECOVERED after 60s (SPS 4, red: edge-gateway)
```
