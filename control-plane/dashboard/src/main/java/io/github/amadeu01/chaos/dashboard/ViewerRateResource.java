package io.github.amadeu01.chaos.dashboard;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Proxy for the Viewers/s slider, so the page only ever talks to the dashboard. */
@Path("/api/viewers-per-second")
@Produces(MediaType.APPLICATION_JSON)
public class ViewerRateResource {

    @Inject
    @RestClient
    ViewerSimulatorClient simulator;

    @GET
    public Response get() {
        return relay(simulator.get());
    }

    @PUT
    public Response set(@QueryParam("value") String value) {
        return relay(simulator.set(value));
    }

    @ServerExceptionMapper
    public Response simulatorUnreachable(ProcessingException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"viewer-simulator unreachable\"}")
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
