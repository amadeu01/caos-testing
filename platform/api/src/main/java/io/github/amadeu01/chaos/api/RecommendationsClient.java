package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> recommendations (Non-critical). */
@RegisterRestClient(configKey = "recommendations")
@Path("/recommendations")
public interface RecommendationsClient {

    @GET
    @Path("/{viewerId}")
    Recommendations get(@PathParam("viewerId") String viewerId);
}
