package io.github.amadeu01.chaos.api;

import io.github.amadeu01.chaos.libtelemetry.DependencyCalls;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

/** First half of the Viewer journey: everything a Viewer needs before pressing play. */
@Path("/home")
public class HomeResource {

    static final int TITLES_ON_HOME = 20;

    @Inject
    @RestClient
    ProfileClient profiles;

    @Inject
    @RestClient
    CatalogClient catalog;

    @Inject
    @RestClient
    RecommendationsClient recommendations;

    @Inject
    DependencyCalls calls;

    @GET
    public Home home(@QueryParam("viewerId") String viewerId) {
        Requests.requireNonBlank(viewerId, "viewerId");
        Profile profile = calls.call("profile", () -> profiles.get(viewerId));
        List<Title> titles = calls.call("catalog", () -> catalog.all()).stream().limit(TITLES_ON_HOME).toList();
        List<String> recommended = calls.call("recommendations", () -> recommendations.get(viewerId)).titleIds();
        return new Home(profile, titles, recommended);
    }
}
