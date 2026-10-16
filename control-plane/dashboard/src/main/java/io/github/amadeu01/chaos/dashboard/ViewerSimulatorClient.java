package io.github.amadeu01.chaos.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "viewer-simulator")
@Path("/viewers-per-second")
public interface ViewerSimulatorClient {

    @GET
    Response get();

    @PUT
    Response set(@QueryParam("value") String value);
}
