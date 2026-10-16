package io.github.amadeu01.chaos.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The dashboard relays telemetry's snapshot as-is, so its shape lives in one place (telemetry). */
@RegisterRestClient(configKey = "telemetry")
public interface TelemetryClient {

    @GET
    @Path("/windows/latest")
    @Produces(MediaType.APPLICATION_JSON)
    String latest();
}
