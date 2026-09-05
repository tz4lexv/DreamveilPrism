package dev.dreamveil.prism.api.performance;

/** Asynchronous GPU timestamp timing for one Prism pass. */
public record PrismGpuPassTiming(String passName, long lastGpuNanos, double averageGpuNanos, long maxGpuNanos, long samples) {
    public PrismGpuPassTiming {
        if (passName == null || passName.isBlank()) throw new IllegalArgumentException("passName must not be blank");
        if (lastGpuNanos < 0 || averageGpuNanos < 0.0 || maxGpuNanos < 0 || samples < 0) throw new IllegalArgumentException("GPU timing values must be >= 0");
    }
    public double lastGpuMilliseconds() { return lastGpuNanos / 1_000_000.0; }
    public double averageGpuMilliseconds() { return averageGpuNanos / 1_000_000.0; }
    public double maxGpuMilliseconds() { return maxGpuNanos / 1_000_000.0; }
}
