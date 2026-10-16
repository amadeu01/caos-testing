package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.List;

@Path("/windows")
public class WindowsResource {

    @Inject
    Aggregator aggregator;

    @GET
    @Path("/latest")
    public Snapshot latest() {
        return aggregator.latest();
    }

    /** History for the telemetry graphs: one Snapshot per settled second from {@code from} (epoch ms). */
    @GET
    public List<Snapshot> windows(@QueryParam("from") long from) {
        return aggregator.windows(from);
    }
}
