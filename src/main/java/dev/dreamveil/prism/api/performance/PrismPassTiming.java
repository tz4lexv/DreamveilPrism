package dev.dreamveil.prism.api.performance;

/** CPU submission timing for one Prism render-graph pass. This is not a GPU timestamp. */
public record PrismPassTiming(
        String passName,
        long lastCpuNanos,
        double averageCpuNanos,
        long maxCpuNanos,
        long samples) {
    public PrismPassTiming {
        if (passName == null || passName.isBlank()) {
            throw new IllegalArgumentException("passName must not be blank");
        }
        if (lastCpuNanos < 0 || averageCpuNanos < 0.0 || maxCpuNanos < 0 || samples < 0) {
            throw new IllegalArgumentException("Prism timing values must be >= 0");
        }
    }

    public double lastCpuMilliseconds() {
        return lastCpuNanos / 1_000_000.0;
    }

    public double averageCpuMilliseconds() {
        return averageCpuNanos / 1_000_000.0;
    }

    public double maxCpuMilliseconds() {
        return maxCpuNanos / 1_000_000.0;
    }
}
