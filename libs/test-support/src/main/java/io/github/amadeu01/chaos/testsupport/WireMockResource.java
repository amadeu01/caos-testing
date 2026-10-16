package io.github.amadeu01.chaos.testsupport;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts one WireMock server that stands in for every downstream Service of the Service under test.
 * Downstream Services are told apart by path, so one server is enough.
 */
public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    /** Init arg: comma-separated config properties (for example {@code quarkus.rest-client.profile.url}) to point at WireMock. */
    public static final String URL_PROPERTIES = "urlProperties";

    private WireMockServer server;
    private List<String> urlProperties = List.of();

    @Override
    public void init(Map<String, String> initArgs) {
        urlProperties = Arrays.stream(initArgs.getOrDefault(URL_PROPERTIES, "").split(","))
                .map(String::trim)
                .filter(property -> !property.isEmpty())
                .toList();
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Map<String, String> config = new HashMap<>();
        urlProperties.forEach(property -> config.put(property, server.baseUrl()));
        return config;
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(server,
                new TestInjector.AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
