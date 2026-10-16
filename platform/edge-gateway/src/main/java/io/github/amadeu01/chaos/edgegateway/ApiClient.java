package io.github.amadeu01.chaos.edgegateway;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency edge-gateway -> api (Critical). Returns raw Responses so the gateway can relay any status. */
@RegisterRestClient(configKey = "api")
public interface ApiClient {

    @GET
    @Path("/home")
    Response home(@QueryParam("viewerId") String viewerId);

    @POST
    @Path("/play")
    @Consumes(MediaType.APPLICATION_JSON)
    Response play(String body);

    @POST
    @Path("/ratings")
    @Consumes(MediaType.APPLICATION_JSON)
    Response rate(String body);
}
