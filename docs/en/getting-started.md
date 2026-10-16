# Getting started

## 1. Install

1. Install Docker with Docker Compose.
2. Install IntelliJ IDEA and the *Quarkus Tools* plugin (*Settings > Plugins*).
3. Open the repository folder in IntelliJ. It imports the Maven project on its own.
4. Set the project SDK to JDK 25 (*File > Project Structure > SDK*; *Download JDK* if you have none).

In IntelliJ, the **Platform up** run configuration builds with the project SDK. Command-line scripts need `JAVA_HOME` pointing at a JDK 25. The build stops with a clear message on any older JDK.

## 2. Build and test

```bash
./mvnw verify
```

Service tests start real DynamoDB Local, ElasticMQ and Redis containers through Testcontainers, so Docker must be running.

## 3. Start the platform

```bash
scripts/platform-up.sh
```

The first run downloads images and takes a few minutes; later runs take about a minute. The script returns when edge-gateway and viewer-simulator are ready.

## 4. Watch Steady State

```bash
docker compose logs -f viewer-simulator
```

Every second you see a line like:

```
SPS total=50 viewersPerSecond=50
```

SPS counts Playback Starts: Viewer journeys that loaded the home page and started playback. At 50 Viewers/s a healthy platform shows about 50 SPS.

Change the traffic:

```bash
curl -X PUT 'http://127.0.0.1:8090/viewers-per-second?value=20'
```

Other things to try:

- `curl 'http://127.0.0.1:8000/home?viewerId=v-1'` shows what a Viewer's home page contains.
- `http://127.0.0.1:9325` is the ElasticMQ web UI, where you can watch the `ratings` queue. It stays near empty; if it grows, ratings-worker is not keeping up.

## 5. Stop

```bash
scripts/platform-down.sh
```
