# Primeiros passos

## 1. Instalação

1. Instale o Docker com Docker Compose.
2. Instale o IntelliJ IDEA e o plugin *Quarkus Tools* (*Settings > Plugins*).
3. Abra a pasta do repositório no IntelliJ. Ele importa o projeto Maven sozinho.
4. Defina o SDK do projeto como JDK 25 (*File > Project Structure > SDK*; use *Download JDK* se não tiver um).

Na linha de comando, aponte `JAVA_HOME` para um JDK 25. O build para com uma mensagem clara em qualquer JDK mais antigo.

## 2. Compilar e testar

```bash
./mvnw verify
```

Os testes dos **Services** (serviços) sobem contêineres reais de DynamoDB Local, ElasticMQ e Redis via Testcontainers, então o Docker precisa estar rodando.

## 3. Subir a plataforma

```bash
scripts/platform-up.sh
```

A primeira execução baixa as imagens e leva alguns minutos; as seguintes levam cerca de um minuto. O script termina quando o edge-gateway e o viewer-simulator estão prontos.

## 4. Observar o Steady State

```bash
docker compose logs -f viewer-simulator
```

A cada segundo aparece uma linha como:

```
SPS total=50 viewersPerSecond=50
```

O SPS conta **Playback Starts** (inícios de reprodução): jornadas de **Viewers** (espectadores simulados) que carregaram a página inicial e começaram a assistir. Com 50 Viewers/s, uma plataforma saudável mostra cerca de 50 SPS.

Mude o tráfego:

```bash
curl -X PUT 'http://127.0.0.1:8090/viewers-per-second?value=20'
```

Outras coisas para experimentar:

- `curl 'http://127.0.0.1:8000/home?viewerId=v-1'` mostra o conteúdo da página inicial de um Viewer.
- `http://127.0.0.1:9325` é a interface web do ElasticMQ, onde você acompanha a fila `ratings`.

## 5. Parar

```bash
scripts/platform-down.sh
```
