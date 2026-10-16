package io.github.amadeu01.chaos.edgegateway;

import io.github.amadeu01.chaos.libtelemetry.DependencyCalls;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** The front door, like Netflix's Zuul: every Viewer request enters the platform here. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class EdgeResource {

    private static final Logger LOG = Logger.getLogger(EdgeResource.class);

    @Inject
    @RestClient
    ApiClient api;

    @Inject
    DependencyCalls calls;

    @GET
    @Path("home")
    public Response home(@QueryParam("viewerId") String viewerId) {
        return relay(calls.callHttp("api", () -> api.home(viewerId)));
    }

    @POST
    @Path("play")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response play(String body) {
        return relay(calls.callHttp("api", () -> api.play(body)));
    }

    @POST
    @Path("ratings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rate(String body) {
        return relay(calls.callHttp("api", () -> api.rate(body)));
    }

    @ServerExceptionMapper
    public Response apiUnreachable(ProcessingException e) {
        LOG.warnf("api unreachable: %s", e.getMessage());
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"api unreachable\"}")
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
