package io.github.amadeu01.chaos.telemetry;

import java.util.List;

final class Percentiles {

    private Percentiles() {
    }

    /** Nearest-rank percentile of already sorted values. */
    static long of(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(quantile * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
