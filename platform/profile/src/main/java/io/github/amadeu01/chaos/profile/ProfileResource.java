package io.github.amadeu01.chaos.profile;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/profiles")
public class ProfileResource {

    @GET
    @Path("/{viewerId}")
    public Profile get(@PathParam("viewerId") String viewerId) {
        return Profile.of(viewerId);
    }
}
