# Glossário

As definições ficam em um só lugar, [`CONTEXT.md`](../../CONTEXT.md) (em inglês), compartilhado por pessoas e agentes de código. Código, rótulos do dashboard e documentação usam as mesmas palavras, em inglês, para que um termo no nome de uma classe signifique exatamente o mesmo que na aula.

| Termo | Em português |
|---|---|
| Viewer | espectador simulado |
| Playback Start | início de reprodução: jornada completa até o stream licenciado |
| SPS | Playback Starts por segundo |
| Steady State | estado estável, medido por SPS |
| Service | serviço da plataforma |
| Dependency | dependência: chamada de um Service para outro |
| Critical / Non-critical Dependency | dependência crítica / não crítica |
| Fallback | resposta degradada, mas aceitável |
| Resilience Policy | política de resiliência: timeout, retry, circuit breaker, bulkhead e Fallback |
| Cascading Failure | falha em cascata |
| Fault | falha introduzida de propósito |
| Ad-hoc Fault | falha introduzida à mão, fora de um Experiment |
| Experiment | experimento: teste escrito com Hypothesis, Blast Radius e Verdict |
| Hypothesis | hipótese de que o Steady State se mantém |
| Blast Radius | raio de impacto: parte do tráfego que o Experiment pode afetar |
| Control Group / Experiment Group | grupo de controle / grupo do experimento |
| Abort Condition | condição de aborto |
| Verdict | veredito: hipótese mantida, refutada ou experimento abortado |
| Act | ato: um passo roteirizado da aula |
