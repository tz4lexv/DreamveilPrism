package dev.dreamveil.prism.api.performance;

/** Creator profiler. CPU submission and capability-gated asynchronous GPU timestamps are reported separately. */
public interface PrismPerformanceApi {
    PrismPerformanceApi EMPTY = new PrismPerformanceApi() {
        @Override public PrismPerformanceSnapshot snapshot() { return PrismPerformanceSnapshot.EMPTY; }
    };

    PrismPerformanceSnapshot snapshot();

    /** Added in API 1.3. */
    default PrismGpuPerformanceSnapshot gpuSnapshot() {
        return PrismGpuPerformanceSnapshot.EMPTY;
    }

    /** Added in API 1.4. Pipeline compile/reuse counters for creator diagnostics. */
    default PrismPipelineCacheSnapshot pipelineCacheSnapshot() {
        return PrismPipelineCacheSnapshot.EMPTY;
    }

    /** Added in API 1.5. Explicit hot-reload/resource lifetime diagnostics. */
    default PrismResourceLifetimeSnapshot resourceLifetimeSnapshot() {
        return PrismResourceLifetimeSnapshot.EMPTY;
    }

    /** Added in API 1.7. Per-frame shadow visibility counters; EMPTY until a shadow renderer records them. */
    default PrismShadowCullingSnapshot shadowCullingSnapshot() {
        return PrismShadowCullingSnapshot.EMPTY;
    }

    /** Added in API 1.18. Immutable passes/resources/hazards for creator graph tooling. */
    default PrismGraphDiagnosticsSnapshot graphSnapshot() {
        return PrismGraphDiagnosticsSnapshot.EMPTY;
    }
}
