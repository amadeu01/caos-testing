# Telemetria

Engenharia do caos começa medindo o **Steady State** (estado estável). Todo número do dashboard vem daqui.

## Como uma chamada vira um número

1. Um **Service** chama uma **Dependency** (dependência) por um único ponto, `DependencyCalls`:
   ```java
   Profile profile = calls.call("profile", () -> profiles.get(viewerId));
   ```
   Esse ponto mede o tempo da chamada e registra `OK`, ou `ERROR` quando ela lança exceção. O [ADR 0005](../adr/0005-own-telemetry-with-explicit-dependency-calls.md) (em inglês) explica por que medimos aqui em vez de usar OpenTelemetry.
2. O evento entra em um buffer limitado. A cada 250 ms o buffer é enviado ao `telemetry` como um lote. Um lote vazio também é enviado: é o heartbeat (sinal de vida) do Service.
3. O `telemetry` organiza os eventos em janelas de um segundo e guarda as últimas 600 (dez minutos).

## Observabilidade nunca é uma Critical Dependency

Quando o telemetry está lento ou fora do ar, os Services continuam atendendo. O buffer descarta eventos e conta quantos descartou; nenhuma requisição espera pelo telemetry. Cada Service envia no máximo um lote por vez: enquanto um está em andamento, o próximo envio espera e os eventos ficam no buffer limitado. Do lado que recebe, o `telemetry` ignora lotes sem nome de Service e pula eventos incompletos em vez de falhar. Experimente: `docker compose stop telemetry` e veja `docker compose logs -f viewer-simulator` continuar mostrando o SPS.

## O que uma janela contém

`curl http://127.0.0.1:8082/windows/latest` mostra o último segundo *assentado*, de cerca de 2 s atrás. O `telemetry` espera um segundo por lotes atrasados antes de considerar uma janela completa, então os números não piscam enquanto um segundo ainda está sendo preenchido:

- **SPS**: Playback Starts (inícios de reprodução) naquele segundo.
- **Por Dependency**: chamadas por segundo, percentual de erro, latência p50 e p99, contagem por resultado, saúde.
- **Por Service**: saúde.

`curl 'http://127.0.0.1:8082/sps?from=0'` traz o SPS segundo a segundo dos últimos dez minutos.

## A regra de saúde

Vale a primeira que se aplicar:

| Cor | Quando |
|---|---|
| vermelho | o Service parou de enviar heartbeat por mais de 3 s, ou pelo menos 50 % das chamadas que ele recebe falham |
| âmbar | ele está servindo **Fallbacks** (respostas degradadas), ou pelo menos 5 % das chamadas falham, ou o p99 passa de 1 s |
| verde | caso contrário |

O cinza, para um contêiner parado, chega com o chaos-controller na próxima fatia.
