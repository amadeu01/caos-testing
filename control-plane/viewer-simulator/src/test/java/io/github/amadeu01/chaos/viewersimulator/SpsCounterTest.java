package io.github.amadeu01.chaos.viewersimulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpsCounterTest {

    private final SpsCounter counter = new SpsCounter();

    @Test
    void countsStartsWithinTheSameSecond() {
        counter.recordStart(10_000);
        counter.recordStart(10_999);
        assertThat(counter.startsIn(10)).isEqualTo(2);
    }

    @Test
    void keepsSecondsApart() {
        counter.recordStart(10_500);
        counter.recordStart(11_500);
        assertThat(counter.startsIn(10)).isEqualTo(1);
        assertThat(counter.startsIn(11)).isEqualTo(1);
    }

    @Test
    void secondWithoutStartsIsZero() {
        assertThat(counter.startsIn(42)).isZero();
    }

    @Test
    void reusedSlotForgetsOlderSecond() {
        counter.recordStart(10_000);
        counter.recordStart((10 + SpsCounter.SECONDS_KEPT) * 1000L);
        assertThat(counter.startsIn(10)).isZero();
        assertThat(counter.startsIn(10 + SpsCounter.SECONDS_KEPT)).isEqualTo(1);
    }
}
