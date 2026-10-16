package io.github.amadeu01.chaos.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Second half of the Viewer journey: a successful answer here is a Playback Start. */
@Path("/play")
public class PlayResource {

    @Inject
    @RestClient
    PlaybackClient playback;

    @POST
    public Playback play(PlayRequest request) {
        if (request == null) {
            throw new BadRequestException("body is required");
        }
        Requests.requireNonBlank(request.viewerId(), "viewerId");
        Requests.requireNonBlank(request.titleId(), "titleId");
        return playback.start(request);
    }
}
