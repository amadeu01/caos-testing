# Demonstração de Engenharia do Caos

[English](README.md) | **Português (Brasil)**

Uma mini-Netflix que você quebra de propósito. É um projeto didático para uma aula de engenharia do caos, seguindo as práticas que a Netflix publicou: Chaos Monkey, FIT, ChAP e os *Principles of Chaos Engineering*.

Você sobe uma pequena plataforma de streaming no seu notebook, observa o **Steady State** (estado estável) medido por **SPS** (Playback Starts por segundo), injeta **Faults** (falhas) e vê se a falha se espalha ou é contida.

> **Status:** fatia 1 de 6. A plataforma roda e o SPS aparece no log. O dashboard, as Faults, as **Resilience Policies** (políticas de resiliência) e os **Experiments** (experimentos) chegam nas próximas fatias.

## Pré-requisitos

- JDK 25 (o IntelliJ baixa um para você: *File > Project Structure > SDK*)
- Docker com Docker Compose
- IntelliJ IDEA (qualquer edição) com o plugin gratuito *Quarkus Tools*, recomendado

## Início rápido

```bash
scripts/platform-up.sh                    # compila, sobe e espera ficar pronto
docker compose logs -f viewer-simulator   # acompanhe o SPS
scripts/platform-down.sh                  # para tudo
```

No IntelliJ, use as configurações de execução **Platform up**, **SPS log** e **Platform down**.

## Saiba mais

- [Primeiros passos](docs/pt-br/getting-started.md)
- [Arquitetura](docs/pt-br/architecture.md)
- [Glossário](docs/pt-br/glossary.md)
- Decisões de design: [`docs/adr/`](docs/adr/) (em inglês)

## Aviso de segurança

Todas as portas escutam apenas em `127.0.0.1`. As próximas fatias adicionam um chaos-controller que para contêineres pelo socket do Docker, o que equivale a acesso root na sua máquina. Nunca exponha esta stack em uma rede compartilhada.
