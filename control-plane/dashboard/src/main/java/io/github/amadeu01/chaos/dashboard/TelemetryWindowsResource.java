package io.github.amadeu01.chaos.dashboard;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Proxy for the telemetry graphs page: loads its initial history once, then follows /live. */
@Path("/api/telemetry/windows")
@Produces(MediaType.APPLICATION_JSON)
public class TelemetryWindowsResource {

    @Inject
    @RestClient
    TelemetryClient telemetry;

    @GET
    public Response windows(@QueryParam("from") String from) {
        try {
            return relay(telemetry.windows(from));
        } catch (WebApplicationException e) {
            return relay(e.getResponse());
        }
    }

    @ServerExceptionMapper
    public Response telemetryUnreachable(ProcessingException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"telemetry unreachable\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response relay(Response upstream) {
        try (upstream) {
            String body = upstream.hasEntity() ? upstream.readEntity(String.class) : null;
            return Response.status(upstream.getStatus()).entity(body).type(MediaType.APPLICATION_JSON).build();
        }
    }
}
