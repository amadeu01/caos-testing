package io.github.amadeu01.chaos.license;

import java.time.Instant;

public record License(String licenseId, String viewerId, String titleId, Instant expiresAt) {
}
