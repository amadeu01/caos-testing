package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.List;

/** SPS history; Experiments (slice 5) read it to judge a Hypothesis. */
@Path("/sps")
public class SpsResource {

    @Inject
    Aggregator aggregator;

    @GET
    public List<SpsPoint> sps(@QueryParam("group") @DefaultValue("total") String group,
                              @QueryParam("from") @DefaultValue("0") long fromMs) {
        try {
            return aggregator.sps(group, fromMs);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
