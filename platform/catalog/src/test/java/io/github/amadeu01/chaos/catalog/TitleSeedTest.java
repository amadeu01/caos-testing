package io.github.amadeu01.chaos.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSeedTest {

    @Test
    void seedsFiftyTitlesWithCatalogIds() {
        assertThat(TitleSeed.titles())
                .hasSize(50)
                .extracting(Title::id)
                .doesNotHaveDuplicates()
                .startsWith("t01")
                .endsWith("t50");
    }

    @Test
    void seedIsRepeatable() {
        assertThat(TitleSeed.titles()).isEqualTo(TitleSeed.titles());
    }
}
