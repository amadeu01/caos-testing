package io.github.amadeu01.chaos.playback;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/playbacks")
public class PlaybackResource {

    @Inject
    @RestClient
    LicenseClient licenses;

    @POST
    public Playback start(PlaybackRequest request) {
        if (request == null || isBlank(request.viewerId()) || isBlank(request.titleId())) {
            throw new BadRequestException("viewerId and titleId are required");
        }
        License license = licenses.issue(new LicenseRequest(request.viewerId(), request.titleId()));
        return new Playback("https://stream.example/titles/" + request.titleId() + "?license=" + license.licenseId(),
                license.licenseId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
