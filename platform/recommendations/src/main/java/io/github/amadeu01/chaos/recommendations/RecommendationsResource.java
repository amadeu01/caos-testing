package io.github.amadeu01.chaos.recommendations;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/recommendations")
public class RecommendationsResource {

    @Inject
    RecommendationEngine engine;

    @GET
    @Path("/{viewerId}")
    public Recommendations get(@PathParam("viewerId") String viewerId) {
        return new Recommendations(viewerId, engine.recommendFor(viewerId));
    }
}
