package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/windows")
public class WindowsResource {

    @Inject
    Aggregator aggregator;

    @GET
    @Path("/latest")
    public Snapshot latest() {
        return aggregator.latest();
    }
}
