package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/** Dependency api -> catalog (Critical). */
@RegisterRestClient(configKey = "catalog")
@Path("/titles")
public interface CatalogClient {

    @GET
    List<Title> all();
}
