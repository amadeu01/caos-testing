package io.github.amadeu01.chaos.profile;

public record Profile(String viewerId, String displayName, String maturityRating) {

    /** Profiles are derived from the viewer id, so no storage is needed and answers are repeatable. */
    static Profile of(String viewerId) {
        String maturity = Math.floorMod(viewerId.hashCode(), 2) == 0 ? "ADULT" : "KIDS";
        return new Profile(viewerId, "Viewer " + viewerId, maturity);
    }
}
