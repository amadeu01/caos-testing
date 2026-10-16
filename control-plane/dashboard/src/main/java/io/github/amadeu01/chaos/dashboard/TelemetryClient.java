package io.github.amadeu01.chaos.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The dashboard relays telemetry's snapshot as-is, so its shape lives in one place (telemetry). */
@RegisterRestClient(configKey = "telemetry")
public interface TelemetryClient {

    @GET
    @Path("/windows/latest")
    @Produces(MediaType.APPLICATION_JSON)
    String latest();

    /** History for the telemetry graphs: a JSON array of snapshots, one per settled second from {@code from}. */
    @GET
    @Path("/windows")
    @Produces(MediaType.APPLICATION_JSON)
    Response windows(@QueryParam("from") String from);
}
