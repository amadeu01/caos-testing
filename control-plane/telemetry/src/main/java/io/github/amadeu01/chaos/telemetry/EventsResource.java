package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/events")
public class EventsResource {

    @Inject
    Aggregator aggregator;

    @POST
    public Response ingest(EventBatch batch) {
        aggregator.ingest(batch);
        return Response.accepted().build();
    }
}
