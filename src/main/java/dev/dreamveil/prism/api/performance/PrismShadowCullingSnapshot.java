package dev.dreamveil.prism.api.performance;

/** Per-frame visibility counters for a future/active Prism shadow renderer. */
public record PrismShadowCullingSnapshot(
        boolean available,
        long frameIndex,
        int candidates,
        int accepted,
        int culled,
        int drawCalls,
        boolean gpuTimeAvailable,
        long gpuTimeNanos) {
    public static final PrismShadowCullingSnapshot EMPTY = new PrismShadowCullingSnapshot(
            false, -1L, 0, 0, 0, 0, false, 0L);

    public PrismShadowCullingSnapshot {
        if (available && frameIndex < 0) {
            throw new IllegalArgumentException("available shadow metrics require frameIndex >= 0");
        }
        if (candidates < 0 || accepted < 0 || culled < 0 || drawCalls < 0 || gpuTimeNanos < 0) {
            throw new IllegalArgumentException("shadow culling metrics must be >= 0");
        }
        if (available && accepted + culled != candidates) {
            throw new IllegalArgumentException("accepted + culled must equal candidates");
        }
        if (!gpuTimeAvailable && gpuTimeNanos != 0L) {
            throw new IllegalArgumentException("gpuTimeNanos must be zero when GPU time is unavailable");
        }
    }

    public double acceptanceRatio() {
        return candidates == 0 ? 0.0 : (double) accepted / candidates;
    }

    public double gpuMilliseconds() {
        return gpuTimeNanos / 1_000_000.0;
    }
}
