package io.github.amadeu01.chaos.recommendations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void recommendsTenDistinctCatalogTitles() {
        assertThat(engine.recommendFor("v-1"))
                .hasSize(RecommendationEngine.PICKS)
                .doesNotHaveDuplicates()
                .allMatch(id -> id.matches("t(0[1-9]|[1-4][0-9]|50)"));
    }

    @Test
    void sameViewerGetsSameRecommendations() {
        assertThat(engine.recommendFor("v-9")).isEqualTo(engine.recommendFor("v-9"));
    }

    @Test
    void differentViewersUsuallyGetDifferentRecommendations() {
        assertThat(engine.recommendFor("v-1")).isNotEqualTo(engine.recommendFor("v-2"));
    }
}
