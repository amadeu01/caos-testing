package io.github.amadeu01.chaos.catalog;

import java.util.List;
import java.util.stream.IntStream;

/** Fifty fictional titles, ids t01..t50 (recommendations relies on this id format). */
final class TitleSeed {

    private static final List<String> ADJECTIVES = List.of(
            "Silent", "Broken", "Hidden", "Last", "Crimson", "Frozen", "Electric", "Lost", "Midnight", "Golden");
    private static final List<String> NOUNS = List.of("Harbor", "Signal", "Empire", "Garden", "Circuit");
    private static final List<String> GENRES = List.of("Drama", "Thriller", "Comedy", "Sci-Fi", "Documentary");

    private TitleSeed() {
    }

    static List<Title> titles() {
        return IntStream.range(0, 50)
                .mapToObj(i -> new Title(
                        "t%02d".formatted(i + 1),
                        "The " + ADJECTIVES.get(i % 10) + " " + NOUNS.get(i / 10),
                        GENRES.get(i % GENRES.size()),
                        1990 + (i * 7) % 35))
                .toList();
    }
}
