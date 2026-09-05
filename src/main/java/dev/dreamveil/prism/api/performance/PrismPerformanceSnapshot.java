package dev.dreamveil.prism.api.performance;

import java.util.List;

/** Low-overhead runtime metrics. CPU timings measure submission/runtime cost, never claimed GPU time. */
public record PrismPerformanceSnapshot(
        boolean packActive,
        String packId,
        long lastPackCpuNanos,
        double averagePackCpuNanos,
        long maxPackCpuNanos,
        long samples,
        int pipelineCount,
        int logicalTransientResources,
        int physicalTransientSlots,
        List<PrismPassTiming> passes) {
    public static final PrismPerformanceSnapshot EMPTY = new PrismPerformanceSnapshot(
            false, "", 0L, 0.0, 0L, 0L, 0, 0, 0, List.of());

    public PrismPerformanceSnapshot {
        packId = packId == null ? "" : packId;
        if (lastPackCpuNanos < 0 || averagePackCpuNanos < 0.0 || maxPackCpuNanos < 0 || samples < 0
                || pipelineCount < 0 || logicalTransientResources < 0 || physicalTransientSlots < 0) {
            throw new IllegalArgumentException("Prism performance values must be >= 0");
        }
        passes = List.copyOf(passes);
    }

    public double lastPackCpuMilliseconds() {
        return lastPackCpuNanos / 1_000_000.0;
    }

    public double averagePackCpuMilliseconds() {
        return averagePackCpuNanos / 1_000_000.0;
    }

    public double maxPackCpuMilliseconds() {
        return maxPackCpuNanos / 1_000_000.0;
    }
}
