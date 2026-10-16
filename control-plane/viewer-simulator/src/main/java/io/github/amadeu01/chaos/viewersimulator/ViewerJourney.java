package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.amadeu01.chaos.libtelemetry.CallRecorder;
import io.github.amadeu01.chaos.libtelemetry.Outcome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Viewer: load the home page, pick a title, press play, then rate it.
 * The journey is a Playback Start only if both the home page and play succeed.
 */
public class ViewerJourney {

    static final String EDGE = "edge-gateway";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI edge;
    private final CallRecorder recorder;

    public ViewerJourney(HttpClient http, ObjectMapper mapper, URI edge, CallRecorder recorder) {
        this.http = http;
        this.mapper = mapper;
        this.edge = edge;
        this.recorder = recorder;
    }

    public CompletableFuture<Boolean> run(String viewerId) {
        return send(HttpRequest.newBuilder(edge.resolve("/home?viewerId=" + viewerId)).GET())
                .thenCompose(home -> {
                    Optional<String> title = isSuccess(home) ? pickTitle(home.body()) : Optional.empty();
                    if (title.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return send(postJson("/play", "{\"viewerId\":\"%s\",\"titleId\":\"%s\"}".formatted(viewerId, title.get())))
                            .thenApply(play -> {
                                if (!isSuccess(play)) {
                                    return false;
                                }
                                int stars = ThreadLocalRandom.current().nextInt(1, 6);
                                send(postJson("/ratings", "{\"viewerId\":\"%s\",\"titleId\":\"%s\",\"stars\":%d}"
                                        .formatted(viewerId, title.get(), stars)));
                                return true;
                            });
                })
                .exceptionally(failure -> false);
    }

    Optional<String> pickTitle(String homeJson) {
        try {
            JsonNode home = mapper.readTree(homeJson);
            JsonNode recommended = home.path("recommendedTitleIds");
            if (recommended.isArray() && !recommended.isEmpty()) {
                return Optional.of(recommended.get(0).asText());
            }
            JsonNode titles = home.path("titles");
            if (titles.isArray() && !titles.isEmpty()) {
                return Optional.of(titles.get(0).path("id").asText());
            }
            return Optional.empty();
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request) {
        long start = System.nanoTime();
        return http.sendAsync(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> recorder.recordCall(EDGE,
                        failure != null || response.statusCode() >= 500 ? Outcome.ERROR : Outcome.OK,
                        (System.nanoTime() - start) / 1_000_000));
    }

    private HttpRequest.Builder postJson(String path, String json) {
        return HttpRequest.newBuilder(edge.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private static boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }
}
