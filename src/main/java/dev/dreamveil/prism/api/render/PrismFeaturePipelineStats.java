package dev.dreamveil.prism.api.render;

/** Read-only dynamic Feature Rendering variant-cache statistics for diagnostics/profiling. */
public record PrismFeaturePipelineStats(
        long generation,
        int queuedVariants,
        int readyVariants,
        int failedVariants,
        long variantRequests,
        long cacheHits,
        long vanillaFallbacks,
        long budgetRejectedRequests,
        long compileAttempts,
        long compileSuccesses,
        long compileFailures) {
    public static final PrismFeaturePipelineStats EMPTY =
            new PrismFeaturePipelineStats(0L, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    public PrismFeaturePipelineStats {
        if (generation < 0L) throw new IllegalArgumentException("generation must be >= 0");
        if (queuedVariants < 0 || readyVariants < 0 || failedVariants < 0) {
            throw new IllegalArgumentException("variant counts must be >= 0");
        }
        if (variantRequests < 0L || cacheHits < 0L || vanillaFallbacks < 0L || budgetRejectedRequests < 0L
                || compileAttempts < 0L || compileSuccesses < 0L || compileFailures < 0L) {
            throw new IllegalArgumentException("counters must be >= 0");
        }
        if (cacheHits + vanillaFallbacks > variantRequests) {
            throw new IllegalArgumentException("resolved requests exceed total requests");
        }
        if (budgetRejectedRequests > vanillaFallbacks) {
            throw new IllegalArgumentException("budget rejections must be a subset of vanilla fallbacks");
        }
        if (compileSuccesses + compileFailures > compileAttempts) {
            throw new IllegalArgumentException("compile outcomes exceed attempts");
        }
    }
}
