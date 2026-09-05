package dev.dreamveil.prism.runtime.api;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.render.PrismFeaturePipelineStats;
import dev.dreamveil.prism.api.render.PrismRenderDomain;
import dev.dreamveil.prism.api.render.PrismWorldPipelineSnapshot;
import dev.dreamveil.prism.api.render.PrismWorldRenderApi;
import dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot;

/** Thread-safe publication point between the Minecraft bridge and the public creator API. */
public final class PrismWorldRenderApiImpl implements PrismWorldRenderApi {
    private final AtomicReference<PrismWorldPipelineSnapshot> snapshot =
            new AtomicReference<>(PrismWorldPipelineSnapshot.VANILLA);
    private final AtomicReference<Set<PrismRenderDomain>> supportedDomains =
            new AtomicReference<>(Set.of());
    private final AtomicReference<PrismFeaturePipelineStats> featurePipelineStats =
            new AtomicReference<>(PrismFeaturePipelineStats.EMPTY);
    private final AtomicReference<PrismShadowExecutionSnapshot> shadowExecution =
            new AtomicReference<>(PrismShadowExecutionSnapshot.UNAVAILABLE);

    @Override
    public PrismWorldPipelineSnapshot snapshot() {
        return snapshot.get();
    }

    @Override
    public Set<PrismRenderDomain> supportedDomains() {
        return supportedDomains.get();
    }

    @Override
    public PrismFeaturePipelineStats featurePipelineStats() {
        return featurePipelineStats.get();
    }

    @Override
    public PrismShadowExecutionSnapshot shadowExecutionSnapshot() {
        return shadowExecution.get();
    }

    public void publish(PrismWorldPipelineSnapshot next) {
        snapshot.set(java.util.Objects.requireNonNull(next, "next"));
    }

    public void publishSupportedDomains(Set<PrismRenderDomain> domains) {
        supportedDomains.set(Set.copyOf(java.util.Objects.requireNonNull(domains, "domains")));
    }

    public void publishFeaturePipelineStats(PrismFeaturePipelineStats stats) {
        featurePipelineStats.set(java.util.Objects.requireNonNull(stats, "stats"));
    }

    public void publishShadowExecution(PrismShadowExecutionSnapshot snapshot) {
        shadowExecution.set(java.util.Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** Clears the current pack/generation while preserving bridge execution capabilities. */
    public void clear() {
        snapshot.set(PrismWorldPipelineSnapshot.VANILLA);
        featurePipelineStats.set(PrismFeaturePipelineStats.EMPTY);
    }

    /** Clears all state when the Minecraft bridge itself is shut down. */
    public void reset() {
        snapshot.set(PrismWorldPipelineSnapshot.VANILLA);
        supportedDomains.set(Set.of());
        featurePipelineStats.set(PrismFeaturePipelineStats.EMPTY);
        shadowExecution.set(PrismShadowExecutionSnapshot.UNAVAILABLE);
    }
}
