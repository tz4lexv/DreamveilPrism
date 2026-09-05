package dev.dreamveil.prism.api.performance;

/** Creator-facing shader/pipeline reuse counters for the active session. */
public record PrismPipelineCacheSnapshot(
        long compileGenerations,
        long successfulCompiles,
        long reusedPipelines,
        long compiledPipelines,
        long cachedFailuresSkipped) {

    public static final PrismPipelineCacheSnapshot EMPTY = new PrismPipelineCacheSnapshot(0, 0, 0, 0, 0);

    public PrismPipelineCacheSnapshot {
        if (compileGenerations < 0 || successfulCompiles < 0 || reusedPipelines < 0
                || compiledPipelines < 0 || cachedFailuresSkipped < 0) {
            throw new IllegalArgumentException("Pipeline cache counters must be >= 0");
        }
    }

    public long totalPipelineDecisions() {
        return reusedPipelines + compiledPipelines;
    }

    public double reuseRatio() {
        long total = totalPipelineDecisions();
        return total == 0 ? 0.0 : (double) reusedPipelines / total;
    }
}
