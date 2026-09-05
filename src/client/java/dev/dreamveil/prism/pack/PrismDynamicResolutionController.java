package dev.dreamveil.prism.pack;

import dev.dreamveil.prism.PrismMod;

/** Adaptive pack-effect resolution. It never resizes Minecraft's authoritative scene target. */
final class PrismDynamicResolutionController {
    private PrismDynamicResolutionDefinition policy = PrismDynamicResolutionDefinition.DISABLED;
    private double scale = 1.0;
    private long accumulatedNanos;
    private int samples;
    private boolean changed;

    void configure(PrismDynamicResolutionDefinition next) {
        policy = next == null ? PrismDynamicResolutionDefinition.DISABLED : next;
        scale = policy.enabled() ? policy.maximumScale() : 1.0;
        accumulatedNanos = 0L;
        samples = 0;
        changed = policy.enabled();
    }

    void reset() {
        configure(PrismDynamicResolutionDefinition.DISABLED);
    }

    double scale() {
        return scale;
    }

    boolean consumeChanged() {
        boolean result = changed;
        changed = false;
        return result;
    }

    void recordFrame(long elapsedNanos) {
        if (!policy.enabled() || elapsedNanos < 0L) return;
        accumulatedNanos = Math.addExact(accumulatedNanos, elapsedNanos);
        samples++;
        if (samples < policy.evaluationFrames()) return;

        double averageMs = accumulatedNanos / (samples * 1_000_000.0);
        accumulatedNanos = 0L;
        samples = 0;
        double ratio = policy.targetMilliseconds() / Math.max(0.05, averageMs);
        if (ratio > 0.96 && ratio < 1.06) return;

        // Pixel cost is approximately quadratic in scale. Limit each adjustment so temporal
        // algorithms see a small, deterministic resize instead of oscillating every evaluation.
        double desired = scale * Math.sqrt(ratio);
        double boundedStep = Math.clamp(desired, scale - 0.05, scale + 0.05);
        double next = Math.clamp(boundedStep, policy.minimumScale(), policy.maximumScale());
        next = Math.round(next * 64.0) / 64.0;
        next = Math.clamp(next, policy.minimumScale(), policy.maximumScale());
        if (Math.abs(next - scale) < 1.0 / 128.0) return;
        double previous = scale;
        scale = next;
        changed = true;
        PrismMod.LOGGER.info(
                "Prism dynamic pack resolution changed: {} -> {} (effect frame {} ms, target {} ms)",
                previous, scale, averageMs, policy.targetMilliseconds());
    }
}
