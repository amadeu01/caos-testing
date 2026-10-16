package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void nearestRankOnSortedValues() {
        List<Long> oneToHundred = LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(Percentiles.of(oneToHundred, 0.50)).isEqualTo(50);
        assertThat(Percentiles.of(oneToHundred, 0.99)).isEqualTo(99);
    }

    @Test
    void emptyIsZero() {
        assertThat(Percentiles.of(List.of(), 0.99)).isZero();
    }

    @Test
    void singleValue() {
        assertThat(Percentiles.of(List.of(7L), 0.50)).isEqualTo(7);
    }
}
