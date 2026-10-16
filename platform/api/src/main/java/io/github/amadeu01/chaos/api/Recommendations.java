package io.github.amadeu01.chaos.api;

import java.util.List;

public record Recommendations(String viewerId, List<String> titleIds) {
}
