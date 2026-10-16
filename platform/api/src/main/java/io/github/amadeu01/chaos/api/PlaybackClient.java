package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> playback (Critical). */
@RegisterRestClient(configKey = "playback")
@Path("/playbacks")
public interface PlaybackClient {

    @POST
    Playback start(PlayRequest request);
}
