package dev.dreamveil.prism.api.performance;

/** Creator-facing logical/runtime resource lifetime counters for hot-reload diagnostics. */
public record PrismResourceLifetimeSnapshot(
        long generationsInstalled,
        long generationsRetired,
        int liveGenerations,
        int pendingGenerations,
        long pipelineBindingsInstalled,
        long pipelineBindingsRetired,
        int livePipelineBindings,
        long samplersCreated,
        long samplersClosed,
        int liveSamplers,
        long transientTexturesCreated,
        long transientTexturesClosed,
        long transientTextureViewsCreated,
        long transientTextureViewsClosed,
        long transientBuffersCreated,
        long transientBuffersClosed,
        long transientReuseHits,
        int transientCachedResources,
        int transientActiveResources,
        int sessionCachedPipelines) {

    public static final PrismResourceLifetimeSnapshot EMPTY = new PrismResourceLifetimeSnapshot(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public PrismResourceLifetimeSnapshot {
        if (generationsInstalled < 0 || generationsRetired < 0 || liveGenerations < 0 || pendingGenerations < 0
                || pipelineBindingsInstalled < 0 || pipelineBindingsRetired < 0 || livePipelineBindings < 0
                || samplersCreated < 0 || samplersClosed < 0 || liveSamplers < 0
                || transientTexturesCreated < 0 || transientTexturesClosed < 0
                || transientTextureViewsCreated < 0 || transientTextureViewsClosed < 0
                || transientBuffersCreated < 0 || transientBuffersClosed < 0 || transientReuseHits < 0
                || transientCachedResources < 0 || transientActiveResources < 0 || sessionCachedPipelines < 0) {
            throw new IllegalArgumentException("Prism resource lifetime counters must be >= 0");
        }
    }

    public boolean atLogicalBaseline() {
        return liveGenerations == 0
                && pendingGenerations == 0
                && livePipelineBindings == 0
                && liveSamplers == 0
                && transientActiveResources == 0;
    }
}
