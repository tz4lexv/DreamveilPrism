package dev.dreamveil.prism.api.performance;

import java.util.List;

/** GPU timing data is delayed/asynchronous; unavailable samples are simply omitted. */
public record PrismGpuPerformanceSnapshot(boolean available, String packId, List<PrismGpuPassTiming> passes) {
    public static final PrismGpuPerformanceSnapshot EMPTY = new PrismGpuPerformanceSnapshot(false, "", List.of());
    public PrismGpuPerformanceSnapshot {
        packId = packId == null ? "" : packId;
        passes = List.copyOf(passes);
    }
    public double averageTotalMilliseconds() {
        return passes.stream().mapToDouble(PrismGpuPassTiming::averageGpuMilliseconds).sum();
    }
}
