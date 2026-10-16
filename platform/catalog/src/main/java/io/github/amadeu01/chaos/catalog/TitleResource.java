package io.github.amadeu01.chaos.catalog;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.List;

@Path("/titles")
public class TitleResource {

    @Inject
    CatalogService catalog;

    @GET
    public List<Title> all() {
        return catalog.all();
    }

    @GET
    @Path("/{id}")
    public Title byId(@PathParam("id") String id) {
        return catalog.byId(id).orElseThrow(() -> new NotFoundException("No title " + id));
    }
}
