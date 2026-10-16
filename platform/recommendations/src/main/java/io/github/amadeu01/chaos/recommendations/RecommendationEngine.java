package io.github.amadeu01.chaos.recommendations;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Stands in for a trained model: a repeatable pseudo-random pick per Viewer.
 * The id format and catalog size match catalog's seeded titles (t01..t50).
 */
@ApplicationScoped
public class RecommendationEngine {

    static final int CATALOG_SIZE = 50;
    static final int PICKS = 10;

    public List<String> recommendFor(String viewerId) {
        List<String> ids = new ArrayList<>(IntStream.rangeClosed(1, CATALOG_SIZE)
                .mapToObj(n -> "t%02d".formatted(n))
                .toList());
        Collections.shuffle(ids, new Random(viewerId.hashCode()));
        return List.copyOf(ids.subList(0, PICKS));
    }
}
