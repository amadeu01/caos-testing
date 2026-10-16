package io.github.amadeu01.chaos.license;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Path("/licenses")
public class LicenseResource {

    static final Duration LICENSE_VALIDITY = Duration.ofHours(4);

    @POST
    public License issue(LicenseRequest request) {
        if (request == null || isBlank(request.viewerId()) || isBlank(request.titleId())) {
            throw new BadRequestException("viewerId and titleId are required");
        }
        return new License(UUID.randomUUID().toString(), request.viewerId(), request.titleId(),
                Instant.now().plus(LICENSE_VALIDITY));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
