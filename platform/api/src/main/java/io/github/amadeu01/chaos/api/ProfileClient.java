package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> profile (Non-critical). */
@RegisterRestClient(configKey = "profile")
@Path("/profiles")
public interface ProfileClient {

    @GET
    @Path("/{viewerId}")
    Profile get(@PathParam("viewerId") String viewerId);
}
