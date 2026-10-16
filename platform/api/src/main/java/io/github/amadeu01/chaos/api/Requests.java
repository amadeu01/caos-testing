package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.BadRequestException;

/** Input checks done before any Dependency is called, so bad input is a 400 and never a downstream error. */
final class Requests {

    private Requests() {
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }
}
