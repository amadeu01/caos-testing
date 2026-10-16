package io.github.amadeu01.chaos.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/ratings")
public class RatingResource {

    static final int MIN_STARS = 1;
    static final int MAX_STARS = 5;

    @Inject
    RatingPublisher publisher;

    @POST
    public Response rate(Rating rating) {
        if (rating == null) {
            throw new BadRequestException("body is required");
        }
        Requests.requireNonBlank(rating.viewerId(), "viewerId");
        Requests.requireNonBlank(rating.titleId(), "titleId");
        if (rating.stars() < MIN_STARS || rating.stars() > MAX_STARS) {
            throw new BadRequestException("stars must be between " + MIN_STARS + " and " + MAX_STARS);
        }
        publisher.publish(rating);
        return Response.accepted().build();
    }
}
