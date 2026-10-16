package io.github.amadeu01.chaos.api;

import java.util.List;

public record Home(Profile profile, List<Title> titles, List<String> recommendedTitleIds) {
}
