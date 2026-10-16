package io.github.amadeu01.chaos.playback;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency playback -> license (Critical). */
@RegisterRestClient(configKey = "license")
@Path("/licenses")
public interface LicenseClient {

    @POST
    License issue(LicenseRequest request);
}
