package io.github.amadeu01.chaos.viewersimulator;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.Map;

@Path("/viewers-per-second")
public class ViewerRateResource {

    @Inject
    ViewerSimulator simulator;

    @GET
    public Map<String, Integer> get() {
        return Map.of("viewersPerSecond", simulator.viewersPerSecond());
    }

    @PUT
    public void set(@QueryParam("value") String value) {
        if (value == null) {
            throw new BadRequestException("Query parameter 'value' is required");
        }
        try {
            simulator.setViewersPerSecond(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Query parameter 'value' must be an integer");
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
