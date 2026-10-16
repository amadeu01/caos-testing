package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthRuleTest {

    private static final long NOW = 100_000;

    private static DependencyView incoming(int ok, int errors, int fallbacks, long p99Ms) {
        int calls = ok + errors + fallbacks;
        return new DependencyView("api", "profile", calls, calls == 0 ? 0 : errors * 100.0 / calls, 1, p99Ms,
                Map.of("OK", ok, "ERROR", errors, "FALLBACK", fallbacks), Health.GREEN);
    }

    @Test
    void reportingServiceThatWentSilentIsRed() {
        assertThat(HealthRule.forService(List.of(), NOW - 3_001, NOW)).isEqualTo(Health.RED);
    }

    @Test
    void reportingServiceWithoutIncomingCallsIsGreen() {
        assertThat(HealthRule.forService(List.of(), NOW - 500, NOW)).isEqualTo(Health.GREEN);
    }

    @Test
    void halfOfCallsFailingIsRed() {
        assertThat(HealthRule.forService(List.of(incoming(5, 5, 0, 10)), null, NOW)).isEqualTo(Health.RED);
    }

    @Test
    void fivePercentErrorsIsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(95, 5, 0, 10)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void servingFallbacksIsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(9, 0, 1, 10)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void slowP99IsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(10, 0, 0, 1_001)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void healthyTrafficIsGreen() {
        assertThat(HealthRule.forService(List.of(incoming(99, 1, 0, 50)), null, NOW)).isEqualTo(Health.GREEN);
    }

    @Test
    void dependencyHealthUsesTheSameThresholds() {
        assertThat(HealthRule.forDependency(5, 5, 0, 10)).isEqualTo(Health.RED);
        assertThat(HealthRule.forDependency(95, 5, 0, 10)).isEqualTo(Health.AMBER);
        assertThat(HealthRule.forDependency(100, 0, 0, 10)).isEqualTo(Health.GREEN);
    }
}
